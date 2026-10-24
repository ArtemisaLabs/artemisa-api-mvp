package mx.artemisa.artemisaapi;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BranchControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void shouldRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/branches"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tenantAdminShouldOnlySeeBranchesFromTheirTenant() throws Exception {
        String suffix = randomSuffix();
        TestAccount firstTenant = createAccount("first-" + suffix);
        TestAccount secondTenant = createAccount("second-" + suffix);

        String token = loginAndGetToken(firstTenant);

        mockMvc.perform(get("/api/v1/branches")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id")
                        .value(firstTenant.branchId().toString()))
                .andExpect(jsonPath("$[0].tenantId")
                        .value(firstTenant.tenantId().toString()));

        // La sucursal del segundo tenant no debe aparecer en la respuesta.
        MvcResult result = mockMvc.perform(get("/api/v1/branches")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertFalse(
                responseBody.contains(secondTenant.branchId().toString()));
    }

    @Test
    void branchScopedUserShouldOnlySeeAssignedBranch() throws Exception {
        String suffix = randomSuffix();
        TestAccount account = createAccount("branch-user-" + suffix);

        UUID assignedBranchId = account.branchId();
        UUID otherBranchId = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO branches
                    (id, tenant_id, name, timezone, status, created_at, updated_at)
                VALUES
                    (?, ?, ?, ?, 'ACTIVE', now(), now())
                """,
                otherBranchId,
                account.tenantId(),
                "Sucursal no asignada",
                "America/Mexico_City");

        int updatedRows = jdbcTemplate.update(
                """
                UPDATE membership_role_assignments
                SET role_code = 'TEACHER',
                    branch_id = ?
                WHERE tenant_membership_id = ?
                  AND role_code = 'TENANT_ADMIN'
                """,
                assignedBranchId,
                account.membershipId());

        assertEquals(1, updatedRows);

        String token = loginAndGetToken(account);

        mockMvc.perform(get("/api/v1/branches")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id")
                        .value(assignedBranchId.toString()));
    }

    private TestAccount createAccount(String slugPrefix) throws Exception {
        String suffix = randomSuffix();
        String slug = slugPrefix;
        String email = "admin-" + suffix + "@example.test";
        String branchName = "Sucursal-" + suffix;

        String onboardingBody = """
                {
                  "tenantName": "Tenant de prueba",
                  "tenantSlug": "%s",
                  "branchName": "%s",
                  "timezone": "America/Mexico_City",
                  "adminFullName": "Administrador de prueba",
                  "adminEmail": "%s",
                  "password": "PruebaLocal123!"
                }
                """.formatted(slug, branchName, email);

        mockMvc.perform(post("/api/v1/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(onboardingBody))
                .andExpect(status().isCreated());

        // El test usa @Transactional y el login/consulta usa JDBC.
        entityManager.flush();

        UUID tenantId = jdbcTemplate.queryForObject(
                "SELECT id FROM tenants WHERE slug = ?",
                UUID.class,
                slug);

        UUID branchId = jdbcTemplate.queryForObject(
                "SELECT id FROM branches WHERE tenant_id = ? AND name = ?",
                UUID.class,
                tenantId,
                branchName);

        UUID membershipId = jdbcTemplate.queryForObject(
                """
                SELECT tm.id
                FROM tenant_memberships tm
                JOIN app_users u ON u.id = tm.app_user_id
                WHERE tm.tenant_id = ?
                  AND u.email = ?
                """,
                UUID.class,
                tenantId,
                email);

        return new TestAccount(
                slug,
                email,
                "PruebaLocal123!",
                tenantId,
                branchId,
                membershipId);
    }

    private String loginAndGetToken(TestAccount account) throws Exception {
        String loginBody = """
                {
                  "tenantSlug": "%s",
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(
                account.slug(),
                account.email(),
                account.password());

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn();

        return JsonPath.read(
                result.getResponse().getContentAsString(),
                "$.accessToken");
    }

    private static String randomSuffix() {
        return UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 12);
    }

    private record TestAccount(
            String slug,
            String email,
            String password,
            UUID tenantId,
            UUID branchId,
            UUID membershipId) {
    }
}
