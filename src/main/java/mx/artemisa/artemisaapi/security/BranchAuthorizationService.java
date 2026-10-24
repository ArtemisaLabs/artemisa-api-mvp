package mx.artemisa.artemisaapi.security;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Service
public class BranchAuthorizationService {

    private final JdbcTemplate jdbcTemplate;

    public BranchAuthorizationService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public UUID requireManagementAccess(UUID branchId, Jwt jwt) {
        UUID tenantId = requiredUuidClaim(jwt, "tenant_id");
        UUID membershipId = requiredUuidClaim(jwt, "membership_id");
        List<String> roles = stringClaimValues(jwt, "roles");
        List<UUID> assignedBranchIds = uuidClaimValues(jwt, "branch_ids");

        boolean tenantAdmin = roles.contains("TENANT_ADMIN")
                && hasActiveTenantRole(tenantId, membershipId, "TENANT_ADMIN");
        boolean assignedBranchDirector =
                roles.contains("BRANCH_DIRECTOR")
                        && assignedBranchIds.contains(branchId)
                        && hasActiveBranchRole(
                                tenantId, membershipId, "BRANCH_DIRECTOR", branchId);

        if (!tenantAdmin && !assignedBranchDirector) {
            throw new AccessDeniedException(
                    "No tienes permiso para administrar esta sucursal."
            );
        }

        Boolean belongsToTenant = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM branches
                    WHERE id = ?
                      AND tenant_id = ?
                      AND status = 'ACTIVE'
                )
                """,
                Boolean.class,
                branchId,
                tenantId
        );

        if (!Boolean.TRUE.equals(belongsToTenant)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No se encontró la sucursal."
            );
        }

        return tenantId;
    }

    public UUID requireOperationalAccess(UUID branchId, Jwt jwt) {
        UUID tenantId = requiredUuidClaim(jwt, "tenant_id");
        UUID membershipId = requiredUuidClaim(jwt, "membership_id");
        List<String> roles = stringClaimValues(jwt, "roles");
        List<UUID> assignedBranchIds = uuidClaimValues(jwt, "branch_ids");

        boolean tenantAdmin = roles.contains("TENANT_ADMIN")
                && hasActiveTenantRole(tenantId, membershipId, "TENANT_ADMIN");
        boolean assignedBranchRole = assignedBranchIds.contains(branchId)
                && roles.stream().anyMatch(role -> List.of(
                        "BRANCH_DIRECTOR", "TEACHER", "ASSISTANT").contains(role)
                        && hasActiveBranchRole(tenantId, membershipId, role, branchId));

        if (!tenantAdmin && !assignedBranchRole) {
            throw new AccessDeniedException(
                    "No tienes acceso operativo a esta sucursal.");
        }

        ensureBranchBelongsToTenant(branchId, tenantId);
        return tenantId;
    }

    public UUID requireGroupAccess(
            UUID branchId,
            UUID groupId,
            Jwt jwt,
            boolean managersOnly) {
        UUID tenantId = requiredUuidClaim(jwt, "tenant_id");
        UUID membershipId = requiredUuidClaim(jwt, "membership_id");
        ensureGroupBelongsToBranch(groupId, branchId, tenantId);

        List<String> roles = stringClaimValues(jwt, "roles");
        List<UUID> assignedBranchIds = uuidClaimValues(jwt, "branch_ids");
        boolean tenantAdmin = roles.contains("TENANT_ADMIN")
                && hasActiveTenantRole(tenantId, membershipId, "TENANT_ADMIN");
        boolean assignedManager = roles.contains("BRANCH_DIRECTOR")
                && assignedBranchIds.contains(branchId)
                && hasActiveBranchRole(
                        tenantId, membershipId, "BRANCH_DIRECTOR", branchId);

        if (tenantAdmin || assignedManager) {
            return tenantId;
        }

        boolean assignedStaff = !managersOnly
                && assignedBranchIds.contains(branchId)
                && roles.stream().anyMatch(role -> List.of("TEACHER", "ASSISTANT")
                        .contains(role))
                && uuidClaimValues(jwt, "group_ids").contains(groupId)
                && hasActiveGroupAssignment(
                        tenantId, branchId, groupId, membershipId);

        if (!assignedStaff) {
            throw new AccessDeniedException(
                    "No tienes permiso para operar este grupo.");
        }

        return tenantId;
    }

    public UUID requireTenantId(Jwt jwt) {
        return requiredUuidClaim(jwt, "tenant_id");
    }

    public UUID requireMembershipId(Jwt jwt) {
        return requiredUuidClaim(jwt, "membership_id");
    }

    public boolean isTenantAdmin(Jwt jwt) {
        return stringClaimValues(jwt, "roles").contains("TENANT_ADMIN")
                && hasActiveTenantRole(
                        requiredUuidClaim(jwt, "tenant_id"),
                        requiredUuidClaim(jwt, "membership_id"),
                        "TENANT_ADMIN");
    }

    public boolean isBranchDirectorInBranch(UUID branchId, Jwt jwt) {
        UUID tenantId = requiredUuidClaim(jwt, "tenant_id");
        UUID membershipId = requiredUuidClaim(jwt, "membership_id");
        return stringClaimValues(jwt, "roles").contains("BRANCH_DIRECTOR")
                && uuidClaimValues(jwt, "branch_ids").contains(branchId)
                && hasActiveBranchRole(
                        tenantId, membershipId, "BRANCH_DIRECTOR", branchId);
    }

    private boolean hasActiveTenantRole(
            UUID tenantId, UUID membershipId, String roleCode) {
        Boolean allowed = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM tenant_memberships tm
                    JOIN app_users u ON u.id = tm.app_user_id
                    JOIN tenants t ON t.id = tm.tenant_id
                    JOIN membership_role_assignments mra
                      ON mra.tenant_id = tm.tenant_id
                     AND mra.tenant_membership_id = tm.id
                    WHERE tm.tenant_id = ? AND tm.id = ?
                      AND tm.status = 'ACTIVE' AND u.status = 'ACTIVE'
                      AND t.status = 'ACTIVE' AND mra.role_code = ?
                      AND mra.branch_id IS NULL
                )
                """, Boolean.class, tenantId, membershipId, roleCode);
        return Boolean.TRUE.equals(allowed);
    }

    private boolean hasActiveBranchRole(
            UUID tenantId, UUID membershipId, String roleCode, UUID branchId) {
        Boolean allowed = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM tenant_memberships tm
                    JOIN app_users u ON u.id = tm.app_user_id
                    JOIN tenants t ON t.id = tm.tenant_id
                    JOIN membership_role_assignments mra
                      ON mra.tenant_id = tm.tenant_id
                     AND mra.tenant_membership_id = tm.id
                    WHERE tm.tenant_id = ? AND tm.id = ?
                      AND tm.status = 'ACTIVE' AND u.status = 'ACTIVE'
                      AND t.status = 'ACTIVE' AND mra.role_code = ?
                      AND mra.branch_id = ?
                )
                """, Boolean.class, tenantId, membershipId, roleCode, branchId);
        return Boolean.TRUE.equals(allowed);
    }

    private boolean hasActiveGroupAssignment(
            UUID tenantId, UUID branchId, UUID groupId, UUID membershipId) {
        Boolean allowed = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM class_group_staff_assignments a
                    JOIN tenant_memberships tm
                      ON tm.tenant_id = a.tenant_id
                     AND tm.id = a.tenant_membership_id
                    JOIN app_users u ON u.id = tm.app_user_id
                    JOIN tenants t ON t.id = tm.tenant_id
                    JOIN membership_role_assignments mra
                      ON mra.tenant_id = a.tenant_id
                     AND mra.tenant_membership_id = a.tenant_membership_id
                     AND mra.branch_id = a.branch_id
                    WHERE a.tenant_id = ? AND a.branch_id = ?
                      AND a.class_group_id = ? AND a.tenant_membership_id = ?
                      AND tm.status = 'ACTIVE' AND u.status = 'ACTIVE'
                      AND t.status = 'ACTIVE'
                      AND mra.role_code IN ('TEACHER', 'ASSISTANT')
                )
                """, Boolean.class, tenantId, branchId, groupId, membershipId);
        return Boolean.TRUE.equals(allowed);
    }

    private void ensureBranchBelongsToTenant(UUID branchId, UUID tenantId) {
        Boolean belongsToTenant = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM branches
                    WHERE id = ? AND tenant_id = ? AND status = 'ACTIVE'
                )
                """, Boolean.class, branchId, tenantId);

        if (!Boolean.TRUE.equals(belongsToTenant)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No se encontró la sucursal.");
        }
    }

    private void ensureGroupBelongsToBranch(
            UUID groupId, UUID branchId, UUID tenantId) {
        ensureBranchBelongsToTenant(branchId, tenantId);
        Boolean belongsToBranch = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM class_groups
                    WHERE id = ? AND branch_id = ? AND tenant_id = ?
                )
                """, Boolean.class, groupId, branchId, tenantId);

        if (!Boolean.TRUE.equals(belongsToBranch)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No se encontró el grupo.");
        }
    }

    private UUID requiredUuidClaim(Jwt jwt, String claimName) {
        String value = jwt.getClaimAsString(claimName);

        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "El token no contiene el tenant requerido."
            );
        }

        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "El tenant del token no es válido."
            );
        }
    }

    private List<String> stringClaimValues(Jwt jwt, String claimName) {
        Object claim = jwt.getClaims().get(claimName);

        if (!(claim instanceof Collection<?> values)) {
            return List.of();
        }

        return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    private List<UUID> uuidClaimValues(Jwt jwt, String claimName) {
        Object claim = jwt.getClaims().get(claimName);

        if (!(claim instanceof Collection<?> values)) {
            return List.of();
        }

        return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(this::parseUuidOrNull)
                .filter(value -> value != null)
                .toList();
    }

    private UUID parseUuidOrNull(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
