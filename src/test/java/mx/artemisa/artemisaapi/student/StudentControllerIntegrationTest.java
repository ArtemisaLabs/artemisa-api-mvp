package mx.artemisa.artemisaapi.student;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.hasSize;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StudentControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    private UUID tenantId;
    private UUID branchId;
    private UUID adminMembershipId;

    @BeforeEach
    void createTenantAndBranch() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        String adminEmail = "admin-" + suffix + "@example.com";
        Map<String, Object> onboardingRequest = Map.of(
                "tenantName", "Instituto de prueba " + suffix,
                "tenantSlug", "instituto-" + suffix,
                "branchName", "Sucursal principal",
                "timezone", "America/Mexico_City",
                "adminFullName", "Administrador de prueba",
                "adminEmail", adminEmail,
                "password", "PruebaLocal123!"
        );

        mockMvc.perform(post("/api/v1/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(onboardingRequest)))
                .andExpect(status().isCreated());

        // El onboarding persiste algunas entidades con JPA; hacemos flush antes
        // de consultarlas con JDBC dentro de esta prueba transaccional.
        entityManager.flush();

        String tenantSlug = "instituto-" + suffix;

        tenantId = jdbcTemplate.queryForObject(
                "SELECT id FROM tenants WHERE slug = ?",
                UUID.class,
                tenantSlug
        );

        branchId = jdbcTemplate.queryForObject(
                """
                SELECT b.id
                FROM branches b
                JOIN tenants t ON t.id = b.tenant_id
                WHERE t.id = ?
                LIMIT 1
                """,
                UUID.class,
                tenantId
        );

        adminMembershipId = jdbcTemplate.queryForObject(
                """
                SELECT tm.id
                FROM tenant_memberships tm
                JOIN app_users u ON u.id = tm.app_user_id
                WHERE tm.tenant_id = ? AND u.email = ?
                """,
                UUID.class,
                tenantId,
                adminEmail
        );

        assertNotNull(tenantId);
        assertNotNull(branchId);
    }

    @Test
    void shouldCreateStudentAndGuardianForTenantAdmin() throws Exception {
        String studentNumber = uniqueStudentNumber();

        var result = mockMvc.perform(studentRequest(studentNumber)
                        .with(jwtFor("TENANT_ADMIN", List.of())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.studentNumber").value(studentNumber))
                .andExpect(jsonPath("$.guardians", hasSize(1)))
                .andReturn();

        JsonNode response = objectMapper.readTree(
                result.getResponse().getContentAsString()
        );
        UUID studentId = UUID.fromString(response.get("id").asText());

        Integer studentCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM students
                WHERE id = ?
                  AND tenant_id = ?
                  AND branch_id = ?
                """,
                Integer.class,
                studentId,
                tenantId,
                branchId
        );

        Integer guardianLinkCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM student_guardians
                WHERE tenant_id = ?
                  AND student_id = ?
                """,
                Integer.class,
                tenantId,
                studentId
        );

        assertEquals(1, studentCount);
        assertEquals(1, guardianLinkCount);
    }

    @Test
    void shouldAllowBranchDirectorAssignedToBranch() throws Exception {
        jdbcTemplate.update("""
                UPDATE membership_role_assignments
                SET role_code = 'BRANCH_DIRECTOR', branch_id = ?
                WHERE tenant_id = ? AND tenant_membership_id = ?
                  AND role_code = 'TENANT_ADMIN'
                """, branchId, tenantId, adminMembershipId);
        mockMvc.perform(studentRequest(uniqueStudentNumber())
                        .with(jwtFor("BRANCH_DIRECTOR", List.of(branchId))))
                .andExpect(status().isCreated());
    }

    @Test
    void shouldRejectRequestWithoutToken() throws Exception {
        mockMvc.perform(studentRequest(uniqueStudentNumber()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectUserWithoutRequiredRole() throws Exception {
        mockMvc.perform(studentRequest(uniqueStudentNumber())
                        .with(jwtFor("TEACHER", List.of(branchId))))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldNotAllowTenantAdminToUseBranchFromAnotherTenant() throws Exception {
        UUID branchFromAnotherTenant = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/branches/{branchId}/students",
                        branchFromAnotherTenant)
                        .with(jwtFor("TENANT_ADMIN", List.of()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(studentBody(
                                uniqueStudentNumber()
                        ))))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnConflictWhenStudentNumberAlreadyExists() throws Exception {
        String studentNumber = uniqueStudentNumber();

        mockMvc.perform(studentRequest(studentNumber)
                        .with(jwtFor("TENANT_ADMIN", List.of())))
                .andExpect(status().isCreated());

        mockMvc.perform(studentRequest(studentNumber)
                        .with(jwtFor("TENANT_ADMIN", List.of())))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldListStudentsWithGuardiansAndPagination() throws Exception {
        mockMvc.perform(studentRequest(uniqueStudentNumber())
                        .with(jwtFor("TENANT_ADMIN", List.of())))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/branches/{branchId}/students", branchId)
                        .param("page", "0")
                        .param("size", "1")
                        .with(jwtFor("TENANT_ADMIN", List.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].guardians", hasSize(1)));
    }

    private MockHttpServletRequestBuilder studentRequest(String studentNumber)
            throws Exception {
        return post("/api/v1/branches/{branchId}/students", branchId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(studentBody(studentNumber)));
    }

    private Map<String, Object> studentBody(String studentNumber) {
        return Map.of(
                "studentNumber", studentNumber,
                "firstName", "Sofía",
                "lastName", "Ramírez López",
                "dateOfBirth", "2020-04-15",
                "guardians", List.of(Map.of(
                        "firstName", "Laura",
                        "lastName", "López García",
                        "email", "laura-" + studentNumber + "@example.com",
                        "phone", "3312345678",
                        "relationship", "MOTHER",
                        "primaryContact", true,
                        "canPickUp", true
                ))
        );
    }

    private JwtRequestPostProcessor jwtFor(String role, List<UUID> branchIds) {
        return jwt().jwt(token -> token
                .subject(UUID.randomUUID().toString())
                .claim("tenant_id", tenantId.toString())
                .claim("membership_id", adminMembershipId.toString())
                .claim("roles", List.of(role))
                .claim("branch_ids", branchIds.stream()
                        .map(UUID::toString)
                        .toList()));
    }

    private String uniqueStudentNumber() {
        return "ALU-" + UUID.randomUUID().toString().replace("-", "");
    }
}
