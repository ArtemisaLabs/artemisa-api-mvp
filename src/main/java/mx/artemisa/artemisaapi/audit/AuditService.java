package mx.artemisa.artemisaapi.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuditService {

    private final JdbcTemplate jdbcTemplate;

    public AuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(
            UUID tenantId,
            UUID branchId,
            UUID actorMembershipId,
            String action,
            String entityType,
            UUID entityId) {
        jdbcTemplate.update("""
                INSERT INTO audit_events (
                    id, tenant_id, branch_id, actor_membership_id,
                    action, entity_type, entity_id, details
                ) VALUES (?, ?, ?, ?, ?, ?, ?, '{}'::jsonb)
                """,
                UUID.randomUUID(), tenantId, branchId, actorMembershipId,
                action, entityType, entityId);
    }
}
