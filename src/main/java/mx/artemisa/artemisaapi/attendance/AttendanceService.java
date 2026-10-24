package mx.artemisa.artemisaapi.attendance;

import mx.artemisa.artemisaapi.audit.AuditService;
import mx.artemisa.artemisaapi.attendance.dto.AttendanceEventResponse;
import mx.artemisa.artemisaapi.attendance.dto.AttendanceRosterEntryResponse;
import mx.artemisa.artemisaapi.attendance.dto.CorrectAttendanceEventRequest;
import mx.artemisa.artemisaapi.attendance.dto.RecordAttendanceEventRequest;
import mx.artemisa.artemisaapi.security.BranchAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class AttendanceService {

    private final JdbcTemplate jdbcTemplate;
    private final BranchAuthorizationService authorizationService;
    private final AuditService auditService;

    public AttendanceService(
            JdbcTemplate jdbcTemplate,
            BranchAuthorizationService authorizationService,
            AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    @Transactional
    public AttendanceEventResponse record(
            UUID branchId,
            AttendanceEventType type,
            String idempotencyKey,
            RecordAttendanceEventRequest request,
            Jwt jwt) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Idempotency-Key es obligatorio y debe tener entre 8 y 128 caracteres.");
        }

        UUID tenantId = authorizationService.requireGroupAccess(
                branchId, request.groupId(), jwt, false);
        UUID actorId = authorizationService.requireMembershipId(jwt);
        AttendanceEventResponse replay = findByIdempotencyKey(
                tenantId, branchId, idempotencyKey);
        if (replay != null) {
            if (!replay.studentId().equals(request.studentId())
                    || !replay.groupId().equals(request.groupId())
                    || replay.eventType() != type) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Idempotency-Key ya se usó para otra operación.");
            }
            return replay;
        }

        String timezone = branchTimezone(tenantId, branchId);
        Instant eventAt = Instant.now();
        LocalDate attendanceDate = eventAt.atZone(ZoneId.of(timezone)).toLocalDate();

        jdbcTemplate.query("""
                SELECT id FROM students
                WHERE tenant_id = ? AND branch_id = ? AND id = ? AND status = 'ACTIVE'
                FOR UPDATE
                """,
                rs -> {
                    if (!rs.next()) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "No se encontró un alumno activo en esta sucursal.");
                    }
                    return rs.getObject("id", UUID.class);
                }, tenantId, branchId, request.studentId());

        Boolean hasActiveEnrollment = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM enrollments
                    WHERE tenant_id = ? AND branch_id = ? AND student_id = ?
                      AND class_group_id = ? AND status = 'ACTIVE'
                      AND start_date <= ? AND (end_date IS NULL OR end_date >= ?)
                )
                """, Boolean.class, tenantId, branchId, request.studentId(),
                request.groupId(), attendanceDate, attendanceDate);
        if (!Boolean.TRUE.equals(hasActiveEnrollment)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "El alumno no tiene una matrícula activa en este grupo para la fecha de asistencia.");
        }

        if (type == AttendanceEventType.CHECK_OUT) {
            Instant effectiveCheckIn = effectiveEventAt(
                    tenantId, branchId, request.studentId(), attendanceDate,
                    AttendanceEventType.CHECK_IN);
            if (effectiveCheckIn == null) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "No se puede registrar salida sin una entrada previa.");
            }
            if (!eventAt.isAfter(effectiveCheckIn)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "La hora de salida debe ser posterior a la entrada.");
            }
        }

        UUID eventId = UUID.randomUUID();
        int inserted = jdbcTemplate.update("""
                    INSERT INTO attendance_events (
                        id, tenant_id, branch_id, class_group_id, student_id,
                        attendance_date, event_type, event_at, client_reported_at,
                        device_id, idempotency_key, recorded_by_membership_id, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    ON CONFLICT DO NOTHING
                    """,
                    eventId, tenantId, branchId, request.groupId(), request.studentId(),
                    attendanceDate, type.name(), toOffsetDateTime(eventAt),
                    toOffsetDateTime(request.clientReportedAt()),
                    normalizeOptional(request.deviceId()), idempotencyKey, actorId);
        if (inserted == 0) {
            AttendanceEventResponse winner = findByIdempotencyKey(
                    tenantId, branchId, idempotencyKey);
            if (winner != null && winner.studentId().equals(request.studentId())
                    && winner.groupId().equals(request.groupId()) && winner.eventType() == type) {
                return winner;
            }
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ya existe un evento de ese tipo para el alumno y la fecha.");
        }

        auditService.record(tenantId, branchId, actorId,
                "ATTENDANCE_" + type.name(), "ATTENDANCE_EVENT", eventId);
        return new AttendanceEventResponse(eventId, request.studentId(), request.groupId(),
                attendanceDate, type, eventAt, request.clientReportedAt(), idempotencyKey);
    }

    @Transactional(readOnly = true)
    public List<AttendanceRosterEntryResponse> list(
            UUID branchId, UUID groupId, LocalDate date, Jwt jwt) {
        UUID tenantId = authorizationService.requireGroupAccess(
                branchId, groupId, jwt, false);
        return jdbcTemplate.query("""
                SELECT s.id AS student_id, s.student_number, s.first_name, s.last_name,
                       e.class_group_id, ?::date AS attendance_date,
                       ci_effective.event_at AS check_in_at,
                       co_effective.event_at AS check_out_at
                FROM enrollments e
                JOIN students s
                  ON s.tenant_id = e.tenant_id
                 AND s.branch_id = e.branch_id
                 AND s.id = e.student_id
                LEFT JOIN LATERAL (
                    SELECT COALESCE(
                        (SELECT c.corrected_event_at
                         FROM attendance_corrections c
                         WHERE c.tenant_id = ci.tenant_id
                           AND c.branch_id = ci.branch_id
                           AND c.attendance_event_id = ci.id
                         ORDER BY c.created_at DESC, c.id DESC LIMIT 1), ci.event_at
                    ) AS event_at
                    FROM attendance_events ci
                    WHERE ci.tenant_id = e.tenant_id AND ci.branch_id = e.branch_id
                      AND ci.student_id = e.student_id AND ci.class_group_id = e.class_group_id
                      AND ci.attendance_date = ? AND ci.event_type = 'CHECK_IN'
                    LIMIT 1
                ) ci_effective ON TRUE
                LEFT JOIN LATERAL (
                    SELECT COALESCE(
                        (SELECT c.corrected_event_at
                         FROM attendance_corrections c
                         WHERE c.tenant_id = co.tenant_id
                           AND c.branch_id = co.branch_id
                           AND c.attendance_event_id = co.id
                         ORDER BY c.created_at DESC, c.id DESC LIMIT 1), co.event_at
                    ) AS event_at
                    FROM attendance_events co
                    WHERE co.tenant_id = e.tenant_id AND co.branch_id = e.branch_id
                      AND co.student_id = e.student_id AND co.class_group_id = e.class_group_id
                      AND co.attendance_date = ? AND co.event_type = 'CHECK_OUT'
                    LIMIT 1
                ) co_effective ON TRUE
                WHERE e.tenant_id = ? AND e.branch_id = ? AND e.class_group_id = ?
                  AND e.status = 'ACTIVE' AND e.start_date <= ?
                  AND (e.end_date IS NULL OR e.end_date >= ?)
                  AND s.status = 'ACTIVE'
                ORDER BY s.last_name, s.first_name, s.id
                """,
                (rs, row) -> mapRoster(rs),
                date, date, date, tenantId, branchId, groupId, date, date);
    }

    @Transactional
    public AttendanceEventResponse correct(
            UUID branchId,
            UUID eventId,
            CorrectAttendanceEventRequest request,
            Jwt jwt) {
        EventRow event = findEvent(branchId, eventId, jwt);
        UUID tenantId = event.tenantId();
        authorizationService.requireGroupAccess(
                branchId, event.groupId(), jwt, true);
        lockAttendanceEventsForDay(tenantId, branchId, event.studentId(), event.attendanceDate());
        String timezone = branchTimezone(tenantId, branchId);
        LocalDate correctedDate = request.correctedAt()
                .atZone(ZoneId.of(timezone)).toLocalDate();
        if (!event.attendanceDate().equals(correctedDate)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "La corrección debe permanecer dentro del día local original.");
        }
        if (request.correctedAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "La hora corregida no puede estar en el futuro.");
        }

        Instant prior = effectiveEventAt(tenantId, branchId, event.studentId(),
                event.attendanceDate(), event.type(), eventId);
        if (event.type() == AttendanceEventType.CHECK_IN) {
            Instant checkout = effectiveEventAt(tenantId, branchId, event.studentId(),
                    event.attendanceDate(), AttendanceEventType.CHECK_OUT);
            if (checkout != null && !checkout.isAfter(request.correctedAt())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "La entrada corregida debe ser anterior a la salida.");
            }
        } else {
            Instant checkin = effectiveEventAt(tenantId, branchId, event.studentId(),
                    event.attendanceDate(), AttendanceEventType.CHECK_IN);
            if (checkin == null || !request.correctedAt().isAfter(checkin)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "La salida corregida debe ser posterior a la entrada.");
            }
        }

        UUID actorId = authorizationService.requireMembershipId(jwt);
        UUID correctionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO attendance_corrections (
                    id, tenant_id, branch_id, attendance_event_id,
                    previous_event_at, corrected_event_at, reason,
                    corrected_by_membership_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now())
                """,
                correctionId, tenantId, branchId, eventId, toOffsetDateTime(prior),
                toOffsetDateTime(request.correctedAt()), request.reason().strip(), actorId);
        auditService.record(tenantId, branchId, actorId,
                "ATTENDANCE_EVENT_CORRECTED", "ATTENDANCE_EVENT", eventId);
        return new AttendanceEventResponse(event.id(), event.studentId(), event.groupId(),
                event.attendanceDate(), event.type(), request.correctedAt(),
                event.clientReportedAt(), event.idempotencyKey());
    }

    private AttendanceEventResponse findByIdempotencyKey(
            UUID tenantId, UUID branchId, String key) {
        return jdbcTemplate.query("""
                SELECT id, student_id, class_group_id, attendance_date, event_type,
                       event_at, client_reported_at, idempotency_key
                FROM attendance_events
                WHERE tenant_id = ? AND branch_id = ? AND idempotency_key = ?
                """,
                rs -> rs.next() ? new AttendanceEventResponse(
                        rs.getObject("id", UUID.class),
                        rs.getObject("student_id", UUID.class),
                        rs.getObject("class_group_id", UUID.class),
                        rs.getObject("attendance_date", LocalDate.class),
                        AttendanceEventType.valueOf(rs.getString("event_type")),
                        rs.getObject("event_at", java.time.OffsetDateTime.class).toInstant(),
                        toInstant(rs.getObject("client_reported_at", java.time.OffsetDateTime.class)),
                        rs.getString("idempotency_key")) : null,
                tenantId, branchId, key);
    }

    private EventRow findEvent(UUID branchId, UUID eventId, Jwt jwt) {
        UUID tenantId = authorizationService.requireTenantId(jwt);
        EventRow event = jdbcTemplate.query("""
                SELECT id, tenant_id, branch_id, class_group_id, student_id,
                       attendance_date, event_type, event_at, client_reported_at,
                       idempotency_key
                FROM attendance_events
                WHERE tenant_id = ? AND branch_id = ? AND id = ?
                """,
                rs -> rs.next() ? new EventRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("branch_id", UUID.class),
                        rs.getObject("class_group_id", UUID.class),
                        rs.getObject("student_id", UUID.class),
                        rs.getObject("attendance_date", LocalDate.class),
                        AttendanceEventType.valueOf(rs.getString("event_type")),
                        rs.getObject("event_at", java.time.OffsetDateTime.class).toInstant(),
                        toInstant(rs.getObject("client_reported_at", java.time.OffsetDateTime.class)),
                        rs.getString("idempotency_key")) : null,
                tenantId, branchId, eventId);
        if (event == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró el evento.");
        }
        return event;
    }

    private void lockAttendanceEventsForDay(
            UUID tenantId, UUID branchId, UUID studentId, LocalDate date) {
        jdbcTemplate.query("""
                SELECT id FROM attendance_events
                WHERE tenant_id = ? AND branch_id = ? AND student_id = ?
                  AND attendance_date = ?
                ORDER BY id
                FOR UPDATE
                """,
                rs -> { while (rs.next()) { rs.getObject("id", UUID.class); } return null; },
                tenantId, branchId, studentId, date);
    }

    private Instant effectiveEventAt(
            UUID tenantId, UUID branchId, UUID studentId, LocalDate date,
            AttendanceEventType type) {
        return effectiveEventAt(tenantId, branchId, studentId, date, type, null);
    }

    private Instant effectiveEventAt(
            UUID tenantId, UUID branchId, UUID studentId, LocalDate date,
            AttendanceEventType type, UUID eventId) {
        List<Instant> values = jdbcTemplate.query("""
                SELECT COALESCE(
                    (SELECT c.corrected_event_at FROM attendance_corrections c
                     WHERE c.tenant_id = e.tenant_id AND c.branch_id = e.branch_id
                       AND c.attendance_event_id = e.id
                     ORDER BY c.created_at DESC, c.id DESC LIMIT 1), e.event_at
                ) AS effective_at
                FROM attendance_events e
                WHERE e.tenant_id = ? AND e.branch_id = ? AND e.student_id = ?
                  AND e.attendance_date = ? AND e.event_type = ?
                  AND (?::uuid IS NULL OR e.id = ?)
                """,
                (rs, row) -> rs.getObject("effective_at", java.time.OffsetDateTime.class).toInstant(),
                tenantId, branchId, studentId, date, type.name(), eventId, eventId);
        return values.isEmpty() ? null : values.getFirst();
    }

    private String branchTimezone(UUID tenantId, UUID branchId) {
        String timezone = jdbcTemplate.query("""
                SELECT timezone FROM branches WHERE tenant_id = ? AND id = ?
                """,
                rs -> rs.next() ? rs.getString("timezone") : null,
                tenantId, branchId);
        if (timezone == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró la sucursal.");
        }
        return timezone;
    }

    private static AttendanceRosterEntryResponse mapRoster(ResultSet rs) throws SQLException {
        Instant checkIn = toInstant(rs.getObject("check_in_at", java.time.OffsetDateTime.class));
        Instant checkOut = toInstant(rs.getObject("check_out_at", java.time.OffsetDateTime.class));
        String state = checkIn == null ? "NOT_CHECKED_IN"
                : checkOut == null ? "CHECKED_IN" : "COMPLETED";
        return new AttendanceRosterEntryResponse(
                rs.getObject("student_id", UUID.class), rs.getString("student_number"),
                rs.getString("first_name"), rs.getString("last_name"),
                rs.getObject("class_group_id", UUID.class),
                rs.getObject("attendance_date", LocalDate.class),
                checkIn, checkOut, state);
    }

    private static Instant toInstant(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static java.time.OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private record EventRow(
            UUID id, UUID tenantId, UUID branchId, UUID groupId, UUID studentId,
            LocalDate attendanceDate, AttendanceEventType type, Instant eventAt,
            Instant clientReportedAt, String idempotencyKey) { }
}
