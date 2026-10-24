package mx.artemisa.artemisaapi.group;

import mx.artemisa.artemisaapi.audit.AuditService;
import mx.artemisa.artemisaapi.group.dto.AssignGroupStaffRequest;
import mx.artemisa.artemisaapi.group.dto.ClassGroupResponse;
import mx.artemisa.artemisaapi.group.dto.CreateClassGroupRequest;
import mx.artemisa.artemisaapi.group.dto.GroupStudentResponse;
import mx.artemisa.artemisaapi.group.dto.UpdateClassGroupRequest;
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
import java.util.UUID;

@Service
public class ClassGroupService {

    private final JdbcTemplate jdbcTemplate;
    private final BranchAuthorizationService authorizationService;
    private final AuditService auditService;

    public ClassGroupService(
            JdbcTemplate jdbcTemplate,
            BranchAuthorizationService authorizationService,
            AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    @Transactional
    public ClassGroupResponse create(
            UUID branchId, CreateClassGroupRequest request, Jwt jwt) {
        UUID tenantId = authorizationService.requireManagementAccess(branchId, jwt);
        validateAgeRange(request.minAgeMonths(), request.maxAgeMonths());
        UUID groupId = UUID.randomUUID();
        try {
            jdbcTemplate.update("""
                    INSERT INTO class_groups (
                        id, tenant_id, branch_id, name, capacity,
                        min_age_months, max_age_months, status, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', now(), now())
                    """,
                    groupId, tenantId, branchId, request.name().strip(),
                    request.capacity(), request.minAgeMonths(), request.maxAgeMonths());
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ya existe un grupo con ese nombre en la sucursal.", exception);
        }
        auditService.record(tenantId, branchId,
                authorizationService.requireMembershipId(jwt),
                "CLASS_GROUP_CREATED", "CLASS_GROUP", groupId);
        return new ClassGroupResponse(
                groupId, branchId, request.name().strip(), request.capacity(),
                request.minAgeMonths(), request.maxAgeMonths(), "ACTIVE", 0);
    }

    @Transactional(readOnly = true)
    public List<ClassGroupResponse> list(UUID branchId, Jwt jwt) {
        UUID tenantId = authorizationService.requireOperationalAccess(branchId, jwt);
        boolean manager = authorizationService.isTenantAdmin(jwt)
                || authorizationService.isBranchDirectorInBranch(branchId, jwt);

        if (manager) {
            return jdbcTemplate.query("""
                    SELECT g.id, g.branch_id, g.name, g.capacity,
                           g.min_age_months, g.max_age_months, g.status,
                           (SELECT COUNT(*) FROM enrollments e
                            WHERE e.tenant_id = g.tenant_id
                              AND e.branch_id = g.branch_id
                              AND e.class_group_id = g.id
                              AND e.status IN ('ACTIVE', 'PENDING')) AS occupied_seats
                    FROM class_groups g
                    WHERE g.tenant_id = ? AND g.branch_id = ?
                    ORDER BY g.name, g.id
                    """,
                    (rs, row) -> mapGroup(rs.getObject("id", UUID.class),
                            rs.getObject("branch_id", UUID.class), rs.getString("name"),
                            rs.getInt("capacity"), (Integer) rs.getObject("min_age_months"),
                            (Integer) rs.getObject("max_age_months"), rs.getString("status"),
                            rs.getLong("occupied_seats")),
                    tenantId, branchId);
        }

        return jdbcTemplate.query("""
                SELECT g.id, g.branch_id, g.name, g.capacity,
                       g.min_age_months, g.max_age_months, g.status,
                       (SELECT COUNT(*) FROM enrollments e
                        WHERE e.tenant_id = g.tenant_id
                          AND e.branch_id = g.branch_id
                          AND e.class_group_id = g.id
                          AND e.status IN ('ACTIVE', 'PENDING')) AS occupied_seats
                FROM class_groups g
                JOIN class_group_staff_assignments a
                  ON a.tenant_id = g.tenant_id AND a.branch_id = g.branch_id
                 AND a.class_group_id = g.id
                JOIN tenant_memberships tm
                  ON tm.tenant_id = a.tenant_id AND tm.id = a.tenant_membership_id
                JOIN membership_role_assignments mra
                  ON mra.tenant_id = a.tenant_id
                 AND mra.tenant_membership_id = a.tenant_membership_id
                 AND mra.branch_id = a.branch_id
                WHERE g.tenant_id = ? AND g.branch_id = ?
                  AND a.tenant_membership_id = ? AND tm.status = 'ACTIVE'
                  AND mra.role_code IN ('TEACHER', 'ASSISTANT')
                  AND g.status = 'ACTIVE'
                ORDER BY g.name, g.id
                """,
                (rs, row) -> mapGroup(rs.getObject("id", UUID.class),
                        rs.getObject("branch_id", UUID.class), rs.getString("name"),
                        rs.getInt("capacity"), (Integer) rs.getObject("min_age_months"),
                        (Integer) rs.getObject("max_age_months"), rs.getString("status"),
                        rs.getLong("occupied_seats")),
                tenantId, branchId, authorizationService.requireMembershipId(jwt));
    }

    @Transactional
    public ClassGroupResponse update(
            UUID branchId, UUID groupId, UpdateClassGroupRequest request, Jwt jwt) {
        UUID tenantId = authorizationService.requireManagementAccess(branchId, jwt);
        validateAgeRange(request.minAgeMonths(), request.maxAgeMonths());
        GroupRow group = jdbcTemplate.query("""
                SELECT id, name, capacity, min_age_months, max_age_months, status
                FROM class_groups
                WHERE tenant_id = ? AND branch_id = ? AND id = ?
                FOR UPDATE
                """,
                rs -> rs.next() ? new GroupRow(
                        rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getInt("capacity"), (Integer) rs.getObject("min_age_months"),
                        (Integer) rs.getObject("max_age_months"), rs.getString("status")) : null,
                tenantId, branchId, groupId);
        if (group == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró el grupo.");
        }

        long occupied = countReservedSeats(tenantId, branchId, groupId);
        if (request.capacity() < occupied) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "La capacidad no puede ser menor al número de matrículas activas o pendientes.");
        }
        if ("INACTIVE".equals(request.status()) && occupied > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "No se puede desactivar un grupo con matrículas activas o pendientes.");
        }

        UUID actorId = authorizationService.requireMembershipId(jwt);
        jdbcTemplate.update("""
                INSERT INTO class_group_change_history (
                    id, tenant_id, branch_id, class_group_id,
                    previous_name, new_name, previous_capacity, new_capacity,
                    previous_min_age_months, new_min_age_months,
                    previous_max_age_months, new_max_age_months,
                    previous_status, new_status, changed_by_membership_id, changed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """,
                UUID.randomUUID(), tenantId, branchId, groupId,
                group.name(), request.name().strip(), group.capacity(), request.capacity(),
                group.minAgeMonths(), request.minAgeMonths(),
                group.maxAgeMonths(), request.maxAgeMonths(),
                group.status(), request.status(), actorId);

        try {
            jdbcTemplate.update("""
                    UPDATE class_groups
                    SET name = ?, capacity = ?, min_age_months = ?,
                        max_age_months = ?, status = ?, updated_at = now()
                    WHERE tenant_id = ? AND branch_id = ? AND id = ?
                    """,
                    request.name().strip(), request.capacity(), request.minAgeMonths(),
                    request.maxAgeMonths(), request.status(), tenantId, branchId, groupId);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ya existe un grupo con ese nombre en la sucursal.", exception);
        }

        auditService.record(tenantId, branchId, actorId,
                "CLASS_GROUP_UPDATED", "CLASS_GROUP", groupId);
        return new ClassGroupResponse(groupId, branchId, request.name().strip(),
                request.capacity(), request.minAgeMonths(), request.maxAgeMonths(),
                request.status(), occupied);
    }

    @Transactional
    public void assignStaff(
            UUID branchId, UUID groupId, AssignGroupStaffRequest request, Jwt jwt) {
        UUID tenantId = authorizationService.requireGroupAccess(
                branchId, groupId, jwt, true);
        UUID assignedBy = authorizationService.requireMembershipId(jwt);

        Boolean eligibleStaff = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM tenant_memberships tm
                    JOIN membership_role_assignments mra
                      ON mra.tenant_id = tm.tenant_id
                     AND mra.tenant_membership_id = tm.id
                    WHERE tm.tenant_id = ? AND tm.id = ? AND tm.status = 'ACTIVE'
                      AND mra.branch_id = ?
                      AND mra.role_code IN ('TEACHER', 'ASSISTANT')
                )
                """, Boolean.class, tenantId, request.membershipId(), branchId);
        if (!Boolean.TRUE.equals(eligibleStaff)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "La membresía debe estar activa y tener rol de maestra o asistente en esta sucursal.");
        }

        try {
            jdbcTemplate.update("""
                    INSERT INTO class_group_staff_assignments (
                        tenant_id, branch_id, class_group_id,
                        tenant_membership_id, assigned_by_membership_id
                    ) VALUES (?, ?, ?, ?, ?)
                    """,
                    tenantId, branchId, groupId, request.membershipId(), assignedBy);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "La persona ya está asignada a este grupo.", exception);
        }
        auditService.record(tenantId, branchId, assignedBy,
                "GROUP_STAFF_ASSIGNED", "CLASS_GROUP", groupId);
    }

    @Transactional
    public void removeStaff(
            UUID branchId, UUID groupId, UUID membershipId, Jwt jwt) {
        UUID tenantId = authorizationService.requireGroupAccess(
                branchId, groupId, jwt, true);
        int rows = jdbcTemplate.update("""
                DELETE FROM class_group_staff_assignments
                WHERE tenant_id = ? AND branch_id = ? AND class_group_id = ?
                  AND tenant_membership_id = ?
                """, tenantId, branchId, groupId, membershipId);
        if (rows == 0) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "La persona no estaba asignada a este grupo.");
        }
        auditService.record(tenantId, branchId,
                authorizationService.requireMembershipId(jwt),
                "GROUP_STAFF_REMOVED", "CLASS_GROUP", groupId);
    }

    @Transactional(readOnly = true)
    public List<GroupStudentResponse> listStudents(
            UUID branchId, UUID groupId, Jwt jwt) {
        UUID tenantId = authorizationService.requireGroupAccess(
                branchId, groupId, jwt, false);
        return jdbcTemplate.query("""
                SELECT s.id AS student_id, s.student_number, s.first_name,
                       s.last_name, s.date_of_birth, e.id AS enrollment_id,
                       e.start_date
                FROM enrollments e
                JOIN students s
                  ON s.tenant_id = e.tenant_id
                 AND s.branch_id = e.branch_id
                 AND s.id = e.student_id
                WHERE e.tenant_id = ? AND e.branch_id = ? AND e.class_group_id = ?
                  AND e.status = 'ACTIVE'
                  AND s.status = 'ACTIVE'
                ORDER BY s.last_name, s.first_name, s.id
                """,
                (rs, row) -> new GroupStudentResponse(
                        rs.getObject("student_id", UUID.class),
                        rs.getString("student_number"), rs.getString("first_name"),
                        rs.getString("last_name"), rs.getObject("date_of_birth", LocalDate.class),
                        rs.getObject("enrollment_id", UUID.class),
                        rs.getObject("start_date", LocalDate.class)),
                tenantId, branchId, groupId);
    }

    private long countReservedSeats(UUID tenantId, UUID branchId, UUID groupId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM enrollments
                WHERE tenant_id = ? AND branch_id = ? AND class_group_id = ?
                  AND status IN ('ACTIVE', 'PENDING')
                """, Long.class, tenantId, branchId, groupId);
        return count == null ? 0 : count;
    }

    private static void validateAgeRange(Integer minAge, Integer maxAge) {
        if (minAge != null && maxAge != null && minAge > maxAge) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "minAgeMonths no puede ser mayor que maxAgeMonths.");
        }
    }

    private static ClassGroupResponse mapGroup(
            UUID id, UUID branchId, String name, int capacity,
            Integer minAge, Integer maxAge, String status, long occupied) {
        return new ClassGroupResponse(
                id, branchId, name, capacity, minAge, maxAge, status, occupied);
    }

    private record GroupRow(
            UUID id, String name, int capacity,
            Integer minAgeMonths, Integer maxAgeMonths, String status) { }
}
