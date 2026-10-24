package mx.artemisa.artemisaapi.packageplan;

import mx.artemisa.artemisaapi.audit.AuditService;
import mx.artemisa.artemisaapi.packageplan.dto.AssignStudentPackageRequest;
import mx.artemisa.artemisaapi.packageplan.dto.CreateSchedulePackageRequest;
import mx.artemisa.artemisaapi.packageplan.dto.SchedulePackageResponse;
import mx.artemisa.artemisaapi.packageplan.dto.StudentPackageResponse;
import mx.artemisa.artemisaapi.security.BranchAuthorizationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class SchedulePackageService {

    private final JdbcTemplate jdbcTemplate;
    private final BranchAuthorizationService authorizationService;
    private final AuditService auditService;

    public SchedulePackageService(
            JdbcTemplate jdbcTemplate,
            BranchAuthorizationService authorizationService,
            AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    @Transactional
    public SchedulePackageResponse create(
            UUID branchId, CreateSchedulePackageRequest request, Jwt jwt) {
        UUID tenantId = authorizationService.requireManagementAccess(branchId, jwt);
        UUID actorId = authorizationService.requireMembershipId(jwt);
        validateDates(request.effectiveFrom(), request.effectiveTo());
        String code = request.packageCode().strip().toUpperCase(Locale.ROOT);

        Integer latestVersion = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(version), 0)
                FROM schedule_packages
                WHERE tenant_id = ? AND branch_id = ? AND package_code = ?
                """, Integer.class, tenantId, branchId, code);
        if (request.version() != latestVersion + 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "La versión debe ser consecutiva; la siguiente es " + (latestVersion + 1) + ".");
        }

        Boolean overlaps = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM schedule_packages
                    WHERE tenant_id = ? AND branch_id = ? AND package_code = ?
                      AND effective_from <= COALESCE(?, DATE '9999-12-31')
                      AND COALESCE(effective_to, DATE '9999-12-31') >= ?
                )
                """, Boolean.class, tenantId, branchId, code,
                request.effectiveTo(), request.effectiveFrom());
        if (Boolean.TRUE.equals(overlaps)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "La vigencia se traslapa con otra versión de este paquete.");
        }

        UUID packageId = UUID.randomUUID();
        try {
            jdbcTemplate.update("""
                INSERT INTO schedule_packages (
                    id, tenant_id, branch_id, package_code, version, name,
                    contracted_minutes_per_day, overtime_tolerance_minutes,
                    overtime_hourly_rate, currency, effective_from, effective_to,
                    status, created_by_membership_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                """,
                packageId, tenantId, branchId, code, request.version(),
                request.name().strip(), request.contractedMinutesPerDay(),
                request.overtimeToleranceMinutes(), request.overtimeHourlyRate(),
                    request.currency(), request.effectiveFrom(), request.effectiveTo(), actorId);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "La versión del paquete ya fue creada por otra solicitud.", exception);
        }
        auditService.record(tenantId, branchId, actorId,
                "SCHEDULE_PACKAGE_CREATED", "SCHEDULE_PACKAGE", packageId);
        return new SchedulePackageResponse(packageId, branchId, code,
                request.version(), request.name().strip(), request.contractedMinutesPerDay(),
                request.overtimeToleranceMinutes(), request.overtimeHourlyRate(),
                request.currency(), request.effectiveFrom(), request.effectiveTo(), "ACTIVE");
    }

    @Transactional(readOnly = true)
    public List<SchedulePackageResponse> list(UUID branchId, Jwt jwt) {
        UUID tenantId = authorizationService.requireManagementAccess(branchId, jwt);
        return jdbcTemplate.query("""
                SELECT id, branch_id, package_code, version, name,
                       contracted_minutes_per_day, overtime_tolerance_minutes,
                       overtime_hourly_rate, currency, effective_from,
                       effective_to, status
                FROM schedule_packages
                WHERE tenant_id = ? AND branch_id = ?
                ORDER BY package_code, version DESC
                """,
                (rs, row) -> new SchedulePackageResponse(
                        rs.getObject("id", UUID.class), rs.getObject("branch_id", UUID.class),
                        rs.getString("package_code"), rs.getInt("version"), rs.getString("name"),
                        rs.getInt("contracted_minutes_per_day"),
                        rs.getInt("overtime_tolerance_minutes"),
                        rs.getBigDecimal("overtime_hourly_rate"), rs.getString("currency"),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class), rs.getString("status")),
                tenantId, branchId);
    }

    @Transactional
    public StudentPackageResponse assign(
            UUID branchId,
            UUID studentId,
            AssignStudentPackageRequest request,
            Jwt jwt) {
        UUID tenantId = authorizationService.requireManagementAccess(branchId, jwt);
        UUID actorId = authorizationService.requireMembershipId(jwt);
        validateDates(request.effectiveFrom(), request.effectiveTo());

        String studentStatus = jdbcTemplate.query("""
                SELECT status FROM students
                WHERE tenant_id = ? AND branch_id = ? AND id = ?
                FOR UPDATE
                """,
                rs -> rs.next() ? rs.getString("status") : null,
                tenantId, branchId, studentId);
        if (!"ACTIVE".equals(studentStatus)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No se encontró un alumno activo en esta sucursal.");
        }

        PackageRow schedulePackage = jdbcTemplate.query("""
                SELECT id, package_code, version, name, effective_from, effective_to, status
                FROM schedule_packages
                WHERE tenant_id = ? AND branch_id = ? AND id = ?
                FOR SHARE
                """,
                rs -> rs.next() ? new PackageRow(
                        rs.getObject("id", UUID.class), rs.getString("package_code"),
                        rs.getInt("version"), rs.getString("name"),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class), rs.getString("status")) : null,
                tenantId, branchId, request.schedulePackageId());
        if (schedulePackage == null || !"ACTIVE".equals(schedulePackage.status())) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No se encontró un paquete activo.");
        }
        if (request.effectiveFrom().isBefore(schedulePackage.effectiveFrom())
                || (schedulePackage.effectiveTo() != null
                    && (request.effectiveTo() == null
                        || request.effectiveTo().isAfter(schedulePackage.effectiveTo())))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "La asignación debe quedar dentro de la vigencia del paquete.");
        }

        Boolean overlaps = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM student_package_assignments
                    WHERE tenant_id = ? AND branch_id = ? AND student_id = ?
                      AND effective_from <= COALESCE(?, DATE '9999-12-31')
                      AND COALESCE(effective_to, DATE '9999-12-31') >= ?
                )
                """, Boolean.class, tenantId, branchId, studentId,
                request.effectiveTo(), request.effectiveFrom());
        if (Boolean.TRUE.equals(overlaps)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "El alumno ya tiene un paquete asignado durante esas fechas.");
        }

        UUID assignmentId = UUID.randomUUID();
        try {
            jdbcTemplate.update("""
                    INSERT INTO student_package_assignments (
                        id, tenant_id, branch_id, student_id, schedule_package_id,
                        effective_from, effective_to, assigned_by_membership_id
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    assignmentId, tenantId, branchId, studentId,
                    request.schedulePackageId(), request.effectiveFrom(),
                    request.effectiveTo(), actorId);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "No se pudo asignar el paquete.", exception);
        }
        auditService.record(tenantId, branchId, actorId,
                "STUDENT_PACKAGE_ASSIGNED", "STUDENT_PACKAGE_ASSIGNMENT", assignmentId);
        return new StudentPackageResponse(assignmentId, schedulePackage.id(),
                schedulePackage.code(), schedulePackage.version(), schedulePackage.name(),
                request.effectiveFrom(), request.effectiveTo());
    }

    @Transactional(readOnly = true)
    public List<StudentPackageResponse> listStudentAssignments(
            UUID branchId, UUID studentId, Jwt jwt) {
        UUID tenantId = authorizationService.requireManagementAccess(branchId, jwt);
        return jdbcTemplate.query("""
                SELECT spa.id, spa.schedule_package_id, sp.package_code, sp.version,
                       sp.name, spa.effective_from, spa.effective_to
                FROM student_package_assignments spa
                JOIN schedule_packages sp
                  ON sp.tenant_id = spa.tenant_id
                 AND sp.branch_id = spa.branch_id
                 AND sp.id = spa.schedule_package_id
                WHERE spa.tenant_id = ? AND spa.branch_id = ? AND spa.student_id = ?
                ORDER BY spa.effective_from DESC, spa.created_at DESC
                """,
                (rs, row) -> new StudentPackageResponse(
                        rs.getObject("id", UUID.class),
                        rs.getObject("schedule_package_id", UUID.class),
                        rs.getString("package_code"), rs.getInt("version"),
                        rs.getString("name"),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class)),
                tenantId, branchId, studentId);
    }

    private static void validateDates(LocalDate from, LocalDate to) {
        if (to != null && to.isBefore(from)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "effectiveTo no puede ser anterior a effectiveFrom.");
        }
    }

    private record PackageRow(
            UUID id, String code, int version, String name,
            LocalDate effectiveFrom, LocalDate effectiveTo, String status) { }
}
