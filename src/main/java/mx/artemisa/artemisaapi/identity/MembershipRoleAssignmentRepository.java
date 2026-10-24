package mx.artemisa.artemisaapi.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MembershipRoleAssignmentRepository
        extends JpaRepository<MembershipRoleAssignment, UUID> {

    boolean existsByTenantIdAndTenantMembershipIdAndRoleCodeAndBranchId(
            UUID tenantId,
            UUID tenantMembershipId,
            String roleCode,
            UUID branchId
    );

    boolean existsByTenantIdAndTenantMembershipIdAndRoleCodeAndBranchIdIsNull(
            UUID tenantId,
            UUID tenantMembershipId,
            String roleCode
    );
}