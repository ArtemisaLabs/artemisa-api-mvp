package mx.artemisa.artemisaapi.branch;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BranchRepository extends JpaRepository<Branch, UUID> {

    boolean existsByTenant_IdAndNameIgnoreCase(UUID tenantId, String name);
}