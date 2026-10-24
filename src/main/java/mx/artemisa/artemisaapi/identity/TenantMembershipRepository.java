package mx.artemisa.artemisaapi.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TenantMembershipRepository
        extends JpaRepository<TenantMembership, UUID> {

    Optional<TenantMembership> findByTenantIdAndAppUserId(
            UUID tenantId,
            UUID appUserId
    );

    boolean existsByTenantIdAndAppUserId(
            UUID tenantId,
            UUID appUserId
    );
}