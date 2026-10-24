package mx.artemisa.artemisaapi;

import mx.artemisa.artemisaapi.branch.Branch;
import mx.artemisa.artemisaapi.branch.BranchRepository;
import mx.artemisa.artemisaapi.branch.BranchStatus;
import mx.artemisa.artemisaapi.tenant.Tenant;
import mx.artemisa.artemisaapi.tenant.TenantRepository;
import mx.artemisa.artemisaapi.tenant.TenantStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
class TenantBranchRepositoryTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private BranchRepository branchRepository;

    @Test
    void savesTenantWithActiveStatus() {
        Tenant tenant = tenantRepository.saveAndFlush(
                Tenant.create("Estancia Nube", "estancia-nube")
        );

        assertNotNull(tenant.getId());
        assertEquals(TenantStatus.ACTIVE, tenant.getStatus());
        assertTrue(tenantRepository.existsBySlug("estancia-nube"));
    }

    @Test
    void savesBranchAndFindsItByTenantAndNameIgnoringCase() {
        Tenant tenant = tenantRepository.saveAndFlush(
                Tenant.create("Estancia Nube", "estancia-nube")
        );

        Branch branch = branchRepository.saveAndFlush(
                Branch.create(
                        tenant,
                        "Sucursal Centro",
                        "America/Monterrey"
                )
        );

        assertNotNull(branch.getId());
        assertEquals(BranchStatus.ACTIVE, branch.getStatus());
        assertEquals(tenant.getId(), branch.getTenant().getId());

        assertTrue(branchRepository.existsByTenant_IdAndNameIgnoreCase(
                tenant.getId(),
                "sucursal centro"
        ));
    }

    @Test
    void doesNotFindBranchWhenTenantDoesNotMatch() {
        Tenant firstTenant = tenantRepository.saveAndFlush(
                Tenant.create("Estancia Nube", "estancia-nube")
        );

        Tenant secondTenant = tenantRepository.saveAndFlush(
                Tenant.create("Estancia Sol", "estancia-sol")
        );

        branchRepository.saveAndFlush(
                Branch.create(
                        firstTenant,
                        "Sucursal Centro",
                        "America/Monterrey"
                )
        );

        assertFalse(branchRepository.existsByTenant_IdAndNameIgnoreCase(
                secondTenant.getId(),
                "Sucursal Centro"
        ));
    }

    @Test
    void rejectsDuplicateTenantSlug() {
        tenantRepository.saveAndFlush(
                Tenant.create("Estancia Nube", "estancia-nube")
        );

        assertThrows(DataIntegrityViolationException.class, () ->
                tenantRepository.saveAndFlush(
                        Tenant.create("Otra estancia", "estancia-nube")
                )
        );
    }
}
