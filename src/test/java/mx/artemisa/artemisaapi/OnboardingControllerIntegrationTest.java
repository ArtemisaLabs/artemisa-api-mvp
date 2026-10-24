package mx.artemisa.artemisaapi;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OnboardingControllerIntegrationTest {

    private static final String ONBOARDING_URL = "/api/v1/onboarding";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void shouldCreateTenantBranchAdminMembershipAndRole() throws Exception {
        String suffix = randomSuffix();
        String slug = "test-" + suffix;
        String email = "admin-" + suffix + "@example.test";

        String requestBody = validRequest(slug, email);

        mockMvc.perform(post(ONBOARDING_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated());

        entityManager.flush();

        assertEquals(1, count(
                "SELECT COUNT(*) FROM tenants WHERE slug = ?",
                slug));

        assertEquals(1, count(
                """
                SELECT COUNT(*)
                FROM branches b
                JOIN tenants t ON t.id = b.tenant_id
                WHERE t.slug = ? AND b.name = ?
                """,
                slug,
                "Sucursal de prueba"));

        assertEquals(1, count(
                "SELECT COUNT(*) FROM app_users WHERE email = ?",
                email));

        assertEquals(1, count(
                """
                SELECT COUNT(*)
                FROM tenant_memberships tm
                JOIN tenants t ON t.id = tm.tenant_id
                JOIN app_users u ON u.id = tm.app_user_id
                WHERE t.slug = ?
                  AND u.email = ?
                  AND tm.status = 'ACTIVE'
                """,
                slug,
                email));

        assertEquals(1, count(
                """
                SELECT COUNT(*)
                FROM membership_role_assignments mra
                JOIN tenants t ON t.id = mra.tenant_id
                WHERE t.slug = ?
                  AND mra.role_code = 'TENANT_ADMIN'
                  AND mra.branch_id IS NULL
                """,
                slug));

        String storedPasswordHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM app_users WHERE email = ?",
                String.class,
                email);

        assertNotEquals("PruebaLocal123!", storedPasswordHash);
    }

    @Test
    void shouldReturnBadRequestWhenRequestHasInvalidFields() throws Exception {
        String requestBody = """
                {
                  "tenantName": "",
                  "tenantSlug": "",
                  "branchName": "",
                  "timezone": "",
                  "adminFullName": "",
                  "adminEmail": "correo-invalido",
                  "password": ""
                }
                """;

        mockMvc.perform(post(ONBOARDING_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldReturnConflictWhenTenantSlugAlreadyExists() throws Exception {
        String suffix = randomSuffix();
        String slug = "test-" + suffix;

        mockMvc.perform(post(ONBOARDING_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest(slug, "admin-one-" + suffix + "@example.test")))
                .andExpect(status().isCreated());

        mockMvc.perform(post(ONBOARDING_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest(slug, "admin-two-" + suffix + "@example.test")))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldReturnConflictWhenAdminEmailAlreadyExists() throws Exception {
        String suffix = randomSuffix();
        String email = "admin-" + suffix + "@example.test";

        mockMvc.perform(post(ONBOARDING_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("tenant-one-" + suffix, email)))
                .andExpect(status().isCreated());

        mockMvc.perform(post(ONBOARDING_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest("tenant-two-" + suffix, email)))
                .andExpect(status().isConflict());
    }

    private long count(String sql, Object... parameters) {
        Long result = jdbcTemplate.queryForObject(sql, Long.class, parameters);
        return result == null ? 0 : result;
    }

    private static String randomSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static String validRequest(String tenantSlug, String adminEmail) {
        return """
                {
                  "tenantName": "Instituto de prueba",
                  "tenantSlug": "%s",
                  "branchName": "Sucursal de prueba",
                  "timezone": "America/Mexico_City",
                  "adminFullName": "Administrador de prueba",
                  "adminEmail": "%s",
                  "password": "PruebaLocal123!"
                }
                """.formatted(tenantSlug, adminEmail);
    }
}
