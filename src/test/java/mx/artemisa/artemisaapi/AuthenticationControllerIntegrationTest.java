package mx.artemisa.artemisaapi;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthenticationControllerIntegrationTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldReturnCurrentUserWhenAccessTokenIsValid() throws Exception {
        String suffix = UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 12);

        createAccount(suffix);

        String token = loginAndGetToken(
                "login-" + suffix,
                "admin-" + suffix + "@example.test",
                "PruebaLocal123!");

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.tenantId").isNotEmpty())
                .andExpect(jsonPath("$.membershipId").isNotEmpty())
                .andExpect(jsonPath("$.roles[0]").value("TENANT_ADMIN"));
    }

    @Test
    void shouldReturnUnauthorizedWhenTokenIsMissing() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturnUnauthorizedWhenTokenIsInvalid() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer token-invalido"))
                .andExpect(status().isUnauthorized());
    }

    private void createAccount(String suffix) throws Exception {
        String onboardingBody = """
                {
                  "tenantName": "Tenant de prueba",
                  "tenantSlug": "login-%s",
                  "branchName": "Sucursal de prueba",
                  "timezone": "America/Mexico_City",
                  "adminFullName": "Administrador de prueba",
                  "adminEmail": "admin-%s@example.test",
                  "password": "PruebaLocal123!"
                }
                """.formatted(suffix, suffix);

        mockMvc.perform(post("/api/v1/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(onboardingBody))
                .andExpect(status().isCreated());

        entityManager.flush();
    }

    private String loginAndGetToken(
            String tenantSlug,
            String email,
            String password) throws Exception {

        String loginBody = """
                {
                  "tenantSlug": "%s",
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(tenantSlug, email, password);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn();

        return JsonPath.read(
                result.getResponse().getContentAsString(),
                "$.accessToken");
    }
}
