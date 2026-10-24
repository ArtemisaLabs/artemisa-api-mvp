package mx.artemisa.artemisaapi.identity;

import mx.artemisa.artemisaapi.identity.api.TenantOnboardingRequest;
import mx.artemisa.artemisaapi.identity.api.TenantOnboardingResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class TenantOnboardingServiceTest {

    @Autowired
    private TenantOnboardingService onboardingService;

    @Autowired
    private TenantMembershipRepository membershipRepository;

    @Autowired
    private MembershipRoleAssignmentRepository roleAssignmentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void createsTenantBranchActiveAdminAndTenantAdminRole() {
        String rawPassword = "UnaClaveLocalSegura123";

        TenantOnboardingResponse response = onboardingService.onboard(
                request(
                        "Estancia Nube",
                        "estancia-nube",
                        "Sucursal Centro",
                        "admin@estancia-nube.test",
                        rawPassword
                )
        );

        TenantMembership membership = membershipRepository
                .findById(response.membershipId())
                .orElseThrow();

        assertEquals(TenantMembership.Status.ACTIVE, membership.getStatus());

        assertTrue(roleAssignmentRepository
                .existsByTenantIdAndTenantMembershipIdAndRoleCodeAndBranchIdIsNull(
                        response.tenantId(),
                        response.membershipId(),
                        "TENANT_ADMIN"
                ));

        String storedPasswordHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM app_users WHERE id = ?",
                String.class,
                response.userId()
        );

        assertNotEquals(rawPassword, storedPasswordHash);
        assertTrue(passwordEncoder.matches(rawPassword, storedPasswordHash));
    }

    @Test
    void rejectsDuplicateTenantSlug() {
        onboardingService.onboard(
                request(
                        "Estancia Nube",
                        "estancia-nube",
                        "Sucursal Centro",
                        "admin@estancia-nube.test",
                        "UnaClaveLocalSegura123"
                )
        );

        assertThrows(
                TenantOnboardingConflictException.class,
                () -> onboardingService.onboard(
                        request(
                                "Otra estancia",
                                "estancia-nube",
                                "Sucursal Norte",
                                "otro-admin@estancia-nube.test",
                                "OtraClaveLocalSegura123"
                        )
                )
        );
    }

    @Test
    void rejectsDuplicateEmailIgnoringCase() {
        onboardingService.onboard(
                request(
                        "Estancia Nube",
                        "estancia-nube",
                        "Sucursal Centro",
                        "admin@estancia-nube.test",
                        "UnaClaveLocalSegura123"
                )
        );

        assertThrows(
                TenantOnboardingConflictException.class,
                () -> onboardingService.onboard(
                        request(
                                "Estancia Sol",
                                "estancia-sol",
                                "Sucursal Norte",
                                "ADMIN@ESTANCIA-NUBE.TEST",
                                "OtraClaveLocalSegura123"
                        )
                )
        );
    }

    private TenantOnboardingRequest request(
            String tenantName,
            String tenantSlug,
            String branchName,
            String adminEmail,
            String password
    ) {
        return new TenantOnboardingRequest(
                tenantName,
                tenantSlug,
                branchName,
                "America/Monterrey",
                "Administrador Artemisa",
                adminEmail,
                password
        );
    }
}