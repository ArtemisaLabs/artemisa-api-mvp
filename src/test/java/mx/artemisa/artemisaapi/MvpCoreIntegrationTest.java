package mx.artemisa.artemisaapi;

import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MvpCoreIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @PersistenceContext private EntityManager entityManager;

    private String tenantSlug;
    private String adminEmail;
    private UUID tenantId;
    private UUID branchId;
    private UUID adminMembershipId;

    @BeforeEach
    void createInstitution() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        tenantSlug = "mvp-" + suffix;
        adminEmail = "admin-" + suffix + "@example.test";
        Map<String, Object> request = Map.of(
                "tenantName", "Estancia MVP " + suffix,
                "tenantSlug", tenantSlug,
                "branchName", "Sucursal principal",
                "timezone", "America/Mexico_City",
                "adminFullName", "Administradora MVP",
                "adminEmail", adminEmail,
                "password", "PruebaLocal123!");

        MvcResult result = mockMvc.perform(post("/api/v1/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        entityManager.flush();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        tenantId = UUID.fromString(body.get("tenantId").asText());
        branchId = UUID.fromString(body.get("branchId").asText());
        adminMembershipId = UUID.fromString(body.get("membershipId").asText());
    }

    @Test
    void shouldInviteStaffEnrollStudentAndRecordAuditableAttendance() throws Exception {
        UUID groupId = createGroup("Maternal", 2);
        UUID studentId = createStudent("ALU-" + UUID.randomUUID().toString().substring(0, 8));
        UUID enrollmentId = createEnrollment(groupId, studentId, "ACTIVE");
        assertNotEquals(studentId, enrollmentId);

        String invitedEmail = "teacher-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
        String invitationBody = objectMapper.writeValueAsString(Map.of(
                "email", invitedEmail,
                "fullName", "Maestra de prueba",
                "roleCode", "TEACHER"));
        MvcResult invitationResult = mockMvc.perform(post(
                        "/api/v1/branches/{branchId}/staff-invitations", branchId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invitationBody))
                .andExpect(status().isCreated())
                .andReturn();
        String invitationToken = JsonPath.read(
                invitationResult.getResponse().getContentAsString(), "$.invitationToken");

        MvcResult acceptResult = mockMvc.perform(post("/api/v1/staff-invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", invitationToken,
                                "password", "MaestraLocal123!"))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID teacherMembershipId = UUID.fromString(JsonPath.read(
                acceptResult.getResponse().getContentAsString(), "$.membershipId"));

        mockMvc.perform(post("/api/v1/branches/{branchId}/groups/{groupId}/staff",
                        branchId, groupId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("membershipId", teacherMembershipId))))
                .andExpect(status().isCreated());

        String teacherToken = login(invitedEmail, "MaestraLocal123!");
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groupIds[0]").value(groupId.toString()));

        Map<String, Object> attendanceBody = Map.of(
                "studentId", studentId,
                "groupId", groupId,
                "deviceId", "tablet-sala-1");
        String checkInKey = "check-in-" + UUID.randomUUID();
        MvcResult checkInResult = mockMvc.perform(post(
                        "/api/v1/branches/{branchId}/attendance/check-ins", branchId)
                        .header("Authorization", "Bearer " + teacherToken)
                        .header("Idempotency-Key", checkInKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(attendanceBody)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode checkIn = objectMapper.readTree(checkInResult.getResponse().getContentAsString());
        LocalDate attendanceDate = LocalDate.parse(checkIn.get("attendanceDate").asText());
        mockMvc.perform(post("/api/v1/branches/{branchId}/attendance/check-ins", branchId)
                        .header("Authorization", "Bearer " + teacherToken)
                        .header("Idempotency-Key", checkInKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(attendanceBody)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(checkIn.get("id").asText()));

        MvcResult checkOutResult = mockMvc.perform(post(
                        "/api/v1/branches/{branchId}/attendance/check-outs", branchId)
                        .header("Authorization", "Bearer " + teacherToken)
                        .header("Idempotency-Key", "check-out-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(attendanceBody)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode checkOut = objectMapper.readTree(checkOutResult.getResponse().getContentAsString());

        mockMvc.perform(get("/api/v1/branches/{branchId}/attendance", branchId)
                        .param("groupId", groupId.toString())
                        .param("date", attendanceDate.toString())
                        .header("Authorization", "Bearer " + teacherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].studentId").value(studentId.toString()))
                .andExpect(jsonPath("$[0].state").value("COMPLETED"));

        Instant checkInAt = Instant.parse(checkIn.get("eventAt").asText());
        Instant checkOutAt = Instant.parse(checkOut.get("eventAt").asText());
        Instant correctedCheckIn = checkInAt.plusNanos(
                Math.max(1, Duration.between(checkInAt, checkOutAt).toNanos() / 2));
        mockMvc.perform(post(
                        "/api/v1/branches/{branchId}/attendance/events/{eventId}/corrections",
                        branchId, checkIn.get("id").asText())
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "correctedAt", correctedCheckIn.toString(),
                                "reason", "Corrección autorizada por revisión de registro"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventAt").value(correctedCheckIn.toString()));

        Integer correctionCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM attendance_corrections
                WHERE tenant_id = ? AND attendance_event_id = ?
                """, Integer.class, tenantId, UUID.fromString(checkIn.get("id").asText()));
        assertEquals(1, correctionCount);
    }

    @Test
    void shouldRejectEnrollmentWhenGroupCapacityIsFull() throws Exception {
        UUID groupId = createGroup("Lactantes", 1);
        UUID firstStudent = createStudent("ALU-" + UUID.randomUUID().toString().substring(0, 8));
        createEnrollment(groupId, firstStudent, "ACTIVE");
        UUID secondStudent = createStudent("ALU-" + UUID.randomUUID().toString().substring(0, 8));

        mockMvc.perform(post(
                        "/api/v1/branches/{branchId}/groups/{groupId}/enrollments",
                        branchId, groupId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "studentId", secondStudent,
                                "startDate", todayAtBranch().toString(),
                                "status", "PENDING"))))
                .andExpect(status().isConflict());
    }

    @Test
    void shouldAssignVersionedPackageAndCalendarOverride() throws Exception {
        UUID studentId = createStudent("ALU-" + UUID.randomUUID().toString().substring(0, 8));
        LocalDate today = todayAtBranch();
        MvcResult packageResult = mockMvc.perform(post(
                        "/api/v1/branches/{branchId}/packages", branchId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "packageCode", "ESTANDAR",
                                "version", 1,
                                "name", "Jornada de ocho horas",
                                "contractedMinutesPerDay", 480,
                                "overtimeToleranceMinutes", 15,
                                "overtimeHourlyRate", new BigDecimal("125.50"),
                                "currency", "MXN",
                                "effectiveFrom", today.toString()))))
                .andExpect(status().isCreated())
                .andReturn();
        UUID packageId = UUID.fromString(JsonPath.read(
                packageResult.getResponse().getContentAsString(), "$.id"));

        mockMvc.perform(post(
                        "/api/v1/branches/{branchId}/students/{studentId}/packages",
                        branchId, studentId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "schedulePackageId", packageId,
                                "effectiveFrom", today.toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.packageCode").value("ESTANDAR"));

        mockMvc.perform(put("/api/v1/branches/{branchId}/calendar/settings", branchId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("businessDaysMask", 31))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDaysMask").value(31));

        mockMvc.perform(put(
                        "/api/v1/branches/{branchId}/calendar/{date}", branchId, today)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "dayType", "CLOSURE",
                                "businessDay", false,
                                "reportable", false,
                                "name", "Cierre de prueba",
                                "source", "Acuerdo de sucursal"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDay").value(false));

        mockMvc.perform(get("/api/v1/branches/{branchId}/calendar", branchId)
                        .param("from", today.toString())
                        .param("to", today.toString())
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dayType").value("CLOSURE"))
                .andExpect(jsonPath("$[0].reportable").value(false));
    }

    @Test
    void groupAccessMustBeLimitedToTheTenantAndAssignedGroup() throws Exception {
        UUID groupId = createGroup("Preescolar", 3);
        UUID anotherGroupId = createGroup("Lactantes", 3);
        String teacherEmail = "unassigned-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.test";
        MvcResult invitation = mockMvc.perform(post(
                        "/api/v1/branches/{branchId}/staff-invitations", branchId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", teacherEmail,
                                "fullName", "Maestra sin grupo",
                                "roleCode", "TEACHER"))))
                .andExpect(status().isCreated())
                .andReturn();
        String invitationToken = JsonPath.read(
                invitation.getResponse().getContentAsString(), "$.invitationToken");
        MvcResult accepted = mockMvc.perform(post("/api/v1/staff-invitations/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", invitationToken,
                                "password", "MaestraLocal123!"))))
                .andExpect(status().isCreated())
                .andReturn();
        String teacherUserId = JsonPath.read(
                accepted.getResponse().getContentAsString(), "$.userId");
        String teacherMembershipId = JsonPath.read(
                accepted.getResponse().getContentAsString(), "$.membershipId");
        mockMvc.perform(post("/api/v1/branches/{branchId}/groups/{groupId}/staff",
                        branchId, groupId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "membershipId", teacherMembershipId))))
                .andExpect(status().isCreated());
        JwtRequestPostProcessor teacherWithoutGroup = jwt().jwt(token -> token
                .subject(teacherUserId)
                .claim("tenant_id", tenantId.toString())
                .claim("membership_id", teacherMembershipId)
                .claim("roles", List.of("TEACHER", "BRANCH_DIRECTOR"))
                .claim("branch_ids", List.of(branchId.toString()))
                .claim("group_ids", List.of(groupId.toString())));

        mockMvc.perform(get(
                        "/api/v1/branches/{branchId}/groups/{groupId}/students",
                        branchId, anotherGroupId)
                        .with(teacherWithoutGroup))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/branches/{branchId}/groups", branchId)
                        .with(teacherWithoutGroup))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(groupId.toString()));
    }

    private UUID createGroup(String name, int capacity) throws Exception {
        MvcResult result = mockMvc.perform(post(
                        "/api/v1/branches/{branchId}/groups", branchId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "capacity", capacity,
                                "minAgeMonths", 0,
                                "maxAgeMonths", 120))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(
                result.getResponse().getContentAsString(), "$.id"));
    }

    private UUID createStudent(String studentNumber) throws Exception {
        Map<String, Object> guardian = Map.of(
                "firstName", "María",
                "lastName", "López",
                "email", "guardian-" + UUID.randomUUID().toString().substring(0, 8)
                        + "@example.test",
                "phone", "3312345678",
                "relationship", "MOTHER",
                "primaryContact", true,
                "canPickUp", true);
        MvcResult result = mockMvc.perform(post(
                        "/api/v1/branches/{branchId}/students", branchId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "studentNumber", studentNumber,
                                "firstName", "Sofía",
                                "lastName", "Ramírez",
                                "dateOfBirth", "2021-04-15",
                                "guardians", List.of(guardian)))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private UUID createEnrollment(UUID groupId, UUID studentId, String state) throws Exception {
        MvcResult result = mockMvc.perform(post(
                        "/api/v1/branches/{branchId}/groups/{groupId}/enrollments",
                        branchId, groupId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "studentId", studentId,
                                "startDate", todayAtBranch().toString(),
                                "status", state))))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantSlug", tenantSlug,
                                "email", email,
                                "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
    }

    private JwtRequestPostProcessor adminJwt() {
        return jwt().jwt(token -> token
                .subject(UUID.randomUUID().toString())
                .claim("tenant_id", tenantId.toString())
                .claim("membership_id", adminMembershipId.toString())
                .claim("roles", List.of("TENANT_ADMIN"))
                .claim("branch_ids", List.of())
                .claim("group_ids", List.of()));
    }

    private LocalDate todayAtBranch() {
        return LocalDate.now(ZoneId.of("America/Mexico_City"));
    }
}
