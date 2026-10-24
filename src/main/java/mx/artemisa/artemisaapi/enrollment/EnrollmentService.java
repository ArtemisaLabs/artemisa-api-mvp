package mx.artemisa.artemisaapi.enrollment;

import mx.artemisa.artemisaapi.audit.AuditService;
import mx.artemisa.artemisaapi.enrollment.dto.ChangeEnrollmentStatusRequest;
import mx.artemisa.artemisaapi.enrollment.dto.CreateEnrollmentRequest;
import mx.artemisa.artemisaapi.enrollment.dto.EnrollmentResponse;
import mx.artemisa.artemisaapi.security.BranchAuthorizationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
public class EnrollmentService {

    private final JdbcTemplate jdbcTemplate;
    private final BranchAuthorizationService authorizationService;
    private final AuditService auditService;

    public EnrollmentService(
            JdbcTemplate jdbcTemplate,
            BranchAuthorizationService authorizationService,
            AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    @Transactional
    public EnrollmentResponse create(
            UUID branchId, UUID groupId, CreateEnrollmentRequest request, Jwt jwt) {
        UUID tenantId = authorizationService.requireGroupAccess(
                branchId, groupId, jwt, true);
        UUID actorId = authorizationService.requireMembershipId(jwt);

        StudentRow student = lockStudent(tenantId, branchId, request.studentId());
        GroupRow group = lockGroup(tenantId, branchId, groupId);
        if (!"ACTIVE".equals(group.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "No se pueden inscribir alumnos en un grupo inactivo.");
        }
        if (!"ACTIVE".equals(student.status())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "El alumno no está activo.");
        }

        long ageMonths = Period.between(student.dateOfBirth(), request.startDate()).toTotalMonths();
        if (ageMonths < 0
                || (group.minAgeMonths() != null && ageMonths < group.minAgeMonths())
                || (group.maxAgeMonths() != null && ageMonths > group.maxAgeMonths())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "La edad del alumno no cumple el rango definido para el grupo.");
        }
        if (request.status() != EnrollmentStatus.ACTIVE
                && request.status() != EnrollmentStatus.PENDING) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Una matrícula nueva solo puede quedar PENDING o ACTIVE.");
        }

        Boolean alreadyEnrolled = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM enrollments
                    WHERE tenant_id = ? AND student_id = ?
                      AND status IN ('ACTIVE', 'PENDING')
                )
                """, Boolean.class, tenantId, request.studentId());
        if (Boolean.TRUE.equals(alreadyEnrolled)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "El alumno ya tiene una matrícula activa o pendiente.");
        }

        long occupied = countReservedSeats(tenantId, branchId, groupId);
        if (occupied >= group.capacity()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "El grupo alcanzó su capacidad.");
        }

        UUID enrollmentId = UUID.randomUUID();
        try {
            jdbcTemplate.update("""
                    INSERT INTO enrollments (
                        id, tenant_id, branch_id, student_id, class_group_id,
                        start_date, end_date, status, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, now(), now())
                    """,
                    enrollmentId, tenantId, branchId, request.studentId(), groupId,
                    request.startDate(), request.status().name());
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "El alumno ya tiene una matrícula activa en la institución.", exception);
        }
        jdbcTemplate.update("""
                INSERT INTO enrollment_status_history (
                    id, tenant_id, branch_id, enrollment_id, previous_status,
                    new_status, previous_end_date, new_end_date,
                    changed_by_membership_id, changed_at
                ) VALUES (?, ?, ?, ?, NULL, ?, NULL, NULL, ?, now())
                """,
                UUID.randomUUID(), tenantId, branchId, enrollmentId,
                request.status().name(), actorId);
        auditService.record(tenantId, branchId, actorId,
                "ENROLLMENT_CREATED", "ENROLLMENT", enrollmentId);
        return new EnrollmentResponse(enrollmentId, request.studentId(), groupId,
                request.startDate(), null, request.status());
    }

    @Transactional(readOnly = true)
    public List<EnrollmentResponse> list(
            UUID branchId, UUID groupId, Jwt jwt) {
        UUID tenantId = authorizationService.requireGroupAccess(
                branchId, groupId, jwt, false);
        return jdbcTemplate.query("""
                SELECT id, student_id, class_group_id, start_date, end_date, status
                FROM enrollments
                WHERE tenant_id = ? AND branch_id = ? AND class_group_id = ?
                ORDER BY start_date DESC, created_at DESC, id
                """,
                (rs, row) -> new EnrollmentResponse(
                        rs.getObject("id", UUID.class),
                        rs.getObject("student_id", UUID.class),
                        rs.getObject("class_group_id", UUID.class),
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class),
                        EnrollmentStatus.valueOf(rs.getString("status"))),
                tenantId, branchId, groupId);
    }

    @Transactional
    public EnrollmentResponse changeStatus(
            UUID branchId,
            UUID enrollmentId,
            ChangeEnrollmentStatusRequest request,
            Jwt jwt) {
        UUID tenantId = authorizationService.requireManagementAccess(branchId, jwt);
        UUID actorId = authorizationService.requireMembershipId(jwt);
        EnrollmentRow candidate = findEnrollment(tenantId, branchId, enrollmentId);
        if (candidate == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No se encontró la matrícula.");
        }
        authorizationService.requireGroupAccess(
                branchId, candidate.groupId(), jwt, true);

        lockStudent(tenantId, branchId, candidate.studentId());
        GroupRow group = lockGroup(tenantId, branchId, candidate.groupId());
        EnrollmentRow enrollment = findEnrollmentForUpdate(
                tenantId, branchId, enrollmentId);
        EnrollmentStatus target = request.status();
        validateTransition(enrollment.status(), target);

        LocalDate endDate = null;
        if (target == EnrollmentStatus.COMPLETED || target == EnrollmentStatus.CANCELLED) {
            String timezone = jdbcTemplate.queryForObject(
                    "SELECT timezone FROM branches WHERE tenant_id = ? AND id = ?",
                    String.class, tenantId, branchId);
            endDate = LocalDate.now(ZoneId.of(timezone));
            if (endDate.isBefore(enrollment.startDate())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "No se puede finalizar una matrícula antes de su fecha de inicio.");
            }
        }
        if (target == EnrollmentStatus.ACTIVE) {
            if (!"ACTIVE".equals(group.status())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "El grupo está inactivo.");
            }
            Boolean otherActive = jdbcTemplate.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM enrollments
                        WHERE tenant_id = ? AND student_id = ? AND id <> ?
                          AND status IN ('ACTIVE', 'PENDING')
                    )
                    """, Boolean.class, tenantId, enrollment.studentId(), enrollmentId);
            if (Boolean.TRUE.equals(otherActive)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "El alumno ya tiene otra matrícula activa o pendiente.");
            }
            long reservedSeats = countReservedSeats(tenantId, branchId, group.id());
            long seatsUsedByOtherEnrollments = reservedSeats
                    - (enrollment.status() == EnrollmentStatus.PENDING ? 1 : 0);
            if (seatsUsedByOtherEnrollments >= group.capacity()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "El grupo alcanzó su capacidad.");
            }
        }

        jdbcTemplate.update("""
                INSERT INTO enrollment_status_history (
                    id, tenant_id, branch_id, enrollment_id, previous_status,
                    new_status, previous_end_date, new_end_date, reason,
                    changed_by_membership_id, changed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """,
                UUID.randomUUID(), tenantId, branchId, enrollmentId,
                enrollment.status().name(), target.name(), enrollment.endDate(), endDate,
                normalizeReason(request.reason()), actorId);
        jdbcTemplate.update("""
                UPDATE enrollments
                SET status = ?, end_date = ?, updated_at = now()
                WHERE tenant_id = ? AND branch_id = ? AND id = ?
                """,
                target.name(), endDate, tenantId, branchId, enrollmentId);
        auditService.record(tenantId, branchId, actorId,
                "ENROLLMENT_STATUS_CHANGED", "ENROLLMENT", enrollmentId);
        return new EnrollmentResponse(enrollmentId, enrollment.studentId(),
                enrollment.groupId(), enrollment.startDate(), endDate, target);
    }

    private StudentRow lockStudent(UUID tenantId, UUID branchId, UUID studentId) {
        StudentRow student = jdbcTemplate.query("""
                SELECT date_of_birth, status FROM students
                WHERE tenant_id = ? AND branch_id = ? AND id = ?
                FOR UPDATE
                """,
                rs -> rs.next() ? new StudentRow(
                        rs.getObject("date_of_birth", LocalDate.class),
                        rs.getString("status")) : null,
                tenantId, branchId, studentId);
        if (student == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No se encontró el alumno en esta sucursal.");
        }
        return student;
    }

    private GroupRow lockGroup(UUID tenantId, UUID branchId, UUID groupId) {
        GroupRow group = jdbcTemplate.query("""
                SELECT id, capacity, min_age_months, max_age_months, status
                FROM class_groups
                WHERE tenant_id = ? AND branch_id = ? AND id = ?
                FOR UPDATE
                """,
                rs -> rs.next() ? new GroupRow(
                        rs.getObject("id", UUID.class), rs.getInt("capacity"),
                        (Integer) rs.getObject("min_age_months"),
                        (Integer) rs.getObject("max_age_months"), rs.getString("status")) : null,
                tenantId, branchId, groupId);
        if (group == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró el grupo.");
        }
        return group;
    }

    private EnrollmentRow findEnrollment(UUID tenantId, UUID branchId, UUID id) {
        return jdbcTemplate.query("""
                SELECT id, student_id, class_group_id, start_date, end_date, status
                FROM enrollments WHERE tenant_id = ? AND branch_id = ? AND id = ?
                """,
                rs -> rs.next() ? mapEnrollment(rs) : null,
                tenantId, branchId, id);
    }

    private EnrollmentRow findEnrollmentForUpdate(UUID tenantId, UUID branchId, UUID id) {
        return jdbcTemplate.query("""
                SELECT id, student_id, class_group_id, start_date, end_date, status
                FROM enrollments WHERE tenant_id = ? AND branch_id = ? AND id = ?
                FOR UPDATE
                """,
                rs -> rs.next() ? mapEnrollment(rs) : null,
                tenantId, branchId, id);
    }

    private static EnrollmentRow mapEnrollment(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new EnrollmentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("student_id", UUID.class),
                rs.getObject("class_group_id", UUID.class),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                EnrollmentStatus.valueOf(rs.getString("status")));
    }

    private static void validateTransition(EnrollmentStatus current, EnrollmentStatus target) {
        boolean allowed = switch (current) {
            case PENDING -> target == EnrollmentStatus.ACTIVE
                    || target == EnrollmentStatus.CANCELLED;
            case ACTIVE -> target == EnrollmentStatus.COMPLETED
                    || target == EnrollmentStatus.CANCELLED;
            case COMPLETED, CANCELLED -> false;
        };
        if (!allowed) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "La transición de " + current + " a " + target + " no está permitida.");
        }
    }

    private static String normalizeReason(String reason) {
        return reason == null || reason.isBlank() ? null : reason.strip();
    }

    private long countReservedSeats(UUID tenantId, UUID branchId, UUID groupId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM enrollments
                WHERE tenant_id = ? AND branch_id = ? AND class_group_id = ?
                  AND status IN ('ACTIVE', 'PENDING')
                """, Long.class, tenantId, branchId, groupId);
        return count == null ? 0 : count;
    }

    private record StudentRow(LocalDate dateOfBirth, String status) { }
    private record GroupRow(
            UUID id, int capacity, Integer minAgeMonths,
            Integer maxAgeMonths, String status) { }
    private record EnrollmentRow(
            UUID id, UUID studentId, UUID groupId, LocalDate startDate,
            LocalDate endDate, EnrollmentStatus status) { }
}
