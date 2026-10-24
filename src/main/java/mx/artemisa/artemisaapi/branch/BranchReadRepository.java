package mx.artemisa.artemisaapi.branch;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class BranchReadRepository {

    private static final RowMapper<BranchResponse> ROW_MAPPER =
            (resultSet, rowNumber) -> new BranchResponse(
                    resultSet.getObject("id", UUID.class),
                    resultSet.getObject("tenant_id", UUID.class),
                    resultSet.getString("name"),
                    resultSet.getString("timezone"),
                    resultSet.getString("status"));

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public BranchReadRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<BranchResponse> findVisibleForMembership(UUID tenantId, UUID membershipId) {
        var parameters = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("membershipId", membershipId);

        return jdbcTemplate.query(
                """
                SELECT b.id, b.tenant_id, b.name, b.timezone, b.status
                FROM branches b
                JOIN tenants t ON t.id = b.tenant_id
                JOIN tenant_memberships tm
                  ON tm.tenant_id = b.tenant_id AND tm.id = :membershipId
                JOIN app_users u ON u.id = tm.app_user_id
                WHERE b.tenant_id = :tenantId AND b.status = 'ACTIVE'
                  AND t.status = 'ACTIVE' AND tm.status = 'ACTIVE' AND u.status = 'ACTIVE'
                  AND (
                    EXISTS (
                        SELECT 1 FROM membership_role_assignments mra
                        WHERE mra.tenant_id = tm.tenant_id
                          AND mra.tenant_membership_id = tm.id
                          AND mra.role_code = 'TENANT_ADMIN'
                          AND mra.branch_id IS NULL
                    )
                    OR EXISTS (
                        SELECT 1 FROM membership_role_assignments mra
                        WHERE mra.tenant_id = tm.tenant_id
                          AND mra.tenant_membership_id = tm.id
                          AND mra.branch_id = b.id
                          AND mra.role_code IN ('BRANCH_DIRECTOR', 'TEACHER', 'ASSISTANT')
                    )
                  )
                ORDER BY b.name, b.id
                """,
                parameters,
                ROW_MAPPER);
    }
}
