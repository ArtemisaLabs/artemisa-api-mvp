package mx.artemisa.artemisaapi.student;

import mx.artemisa.artemisaapi.student.dto.CreateStudentRequest;
import mx.artemisa.artemisaapi.student.dto.GuardianInput;
import mx.artemisa.artemisaapi.student.dto.GuardianResponse;
import mx.artemisa.artemisaapi.student.dto.StudentResponse;
import mx.artemisa.artemisaapi.security.BranchAuthorizationService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import mx.artemisa.artemisaapi.student.dto.StudentPageResponse;

@Service
public class StudentService {

    private final JdbcTemplate jdbcTemplate;
    private final BranchAuthorizationService authorizationService;

    public StudentService(
            JdbcTemplate jdbcTemplate,
            BranchAuthorizationService authorizationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizationService = authorizationService;
    }

    @Transactional
    public StudentResponse createStudent(
            UUID branchId,
            CreateStudentRequest request,
            Jwt jwt
    ) {
        UUID tenantId = authorizeBranchAccess(branchId, jwt);
        ensureExactlyOnePrimaryGuardian(request.guardians());

        UUID studentId = UUID.randomUUID();
        Instant now = Instant.now();

        try {
            jdbcTemplate.update("""
                    INSERT INTO students (
                        id, tenant_id, branch_id, student_number,
                        first_name, last_name, date_of_birth, status,
                        created_at, updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                    """,
                    studentId,
                    tenantId,
                    branchId,
                    request.studentNumber().trim(),
                    request.firstName().trim(),
                    request.lastName().trim(),
                    request.dateOfBirth(),
                    Timestamp.from(now),
                    Timestamp.from(now)
            );

            List<GuardianResponse> guardianResponses = new ArrayList<>();

            for (GuardianInput guardian : request.guardians()) {
                UUID guardianId = UUID.randomUUID();

                jdbcTemplate.update("""
                        INSERT INTO guardians (
                            id, tenant_id, first_name, last_name,
                            email, phone, status, created_at, updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                        """,
                        guardianId,
                        tenantId,
                        guardian.firstName().trim(),
                        guardian.lastName().trim(),
                        normalizeOptional(guardian.email()),
                        normalizeOptional(guardian.phone()),
                        Timestamp.from(now),
                        Timestamp.from(now)
                );

                jdbcTemplate.update("""
                                INSERT INTO student_guardians (
                                    tenant_id,
                                    student_id,
                                    guardian_id,
                                    relationship,
                                    is_primary_contact,
                                    can_pick_up
                                )
                                VALUES (?, ?, ?, ?, ?, ?)
                                """,
                        tenantId,
                        studentId,
                        guardianId,
                        guardian.relationship().trim(),
                        guardian.primaryContact(),
                        guardian.canPickUp()
                );

                guardianResponses.add(new GuardianResponse(
                        guardianId,
                        guardian.firstName().trim(),
                        guardian.lastName().trim(),
                        guardian.relationship().trim(),
                        guardian.primaryContact(),
                        guardian.canPickUp()
                ));
            }

            return new StudentResponse(
                    studentId,
                    branchId,
                    request.studentNumber().trim(),
                    request.firstName().trim(),
                    request.lastName().trim(),
                    request.dateOfBirth(),
                    List.copyOf(guardianResponses)
            );
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "El número de alumno ya está registrado en esta sucursal.",
                    exception
            );
        }
    }

    private void ensureExactlyOnePrimaryGuardian(List<GuardianInput> guardians) {
        long primaryCount = guardians.stream()
                .filter(GuardianInput::primaryContact)
                .count();

        if (primaryCount != 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Debes indicar exactamente un tutor como contacto principal."
            );
        }
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    @Transactional(readOnly = true)
    public StudentPageResponse listStudents(
            UUID branchId,
            int page,
            int size,
            Jwt jwt
    ) {
        if (page < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "page debe ser cero o mayor."
            );
        }

        if (size < 1 || size > 100) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "size debe estar entre 1 y 100."
            );
        }

        UUID tenantId = authorizeBranchAccess(branchId, jwt);

        Long totalResult = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM students
            WHERE tenant_id = ?
              AND branch_id = ?
            """,
                Long.class,
                tenantId,
                branchId
        );

        long total = totalResult == null ? 0 : totalResult;
        long offset = (long) page * size;

        List<StudentResponse> students = jdbcTemplate.query("""
            SELECT id, branch_id, student_number,
                   first_name, last_name, date_of_birth
            FROM students
            WHERE tenant_id = ?
              AND branch_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT ?
            OFFSET ?
            """,
                (resultSet, rowNumber) -> new StudentResponse(
                        resultSet.getObject("id", UUID.class),
                        resultSet.getObject("branch_id", UUID.class),
                        resultSet.getString("student_number"),
                        resultSet.getString("first_name"),
                        resultSet.getString("last_name"),
                        resultSet.getObject("date_of_birth", LocalDate.class),
                        List.of()
                ),
                tenantId,
                branchId,
                size,
                offset
        );

        Map<UUID, List<GuardianResponse>> guardiansByStudent =
                loadGuardiansByStudent(tenantId, students);

        List<StudentResponse> content = students.stream()
                .map(student -> new StudentResponse(
                        student.id(),
                        student.branchId(),
                        student.studentNumber(),
                        student.firstName(),
                        student.lastName(),
                        student.dateOfBirth(),
                        guardiansByStudent.getOrDefault(student.id(), List.of())
                ))
                .toList();

        int totalPages = total == 0
                ? 0
                : Math.toIntExact((total + size - 1) / size);

        boolean hasNext = ((long) page + 1) * size < total;

        return new StudentPageResponse(
                content,
                page,
                size,
                total,
                totalPages,
                hasNext
        );
    }

    private Map<UUID, List<GuardianResponse>> loadGuardiansByStudent(
            UUID tenantId,
            List<StudentResponse> students
    ) {
        Map<UUID, List<GuardianResponse>> guardiansByStudent = new HashMap<>();

        if (students.isEmpty()) {
            return guardiansByStudent;
        }

        String placeholders = String.join(
                ", ",
                java.util.Collections.nCopies(students.size(), "?")
        );

        String sql = """
            SELECT sg.student_id,
                   g.id AS guardian_id,
                   g.first_name,
                   g.last_name,
                   sg.relationship,
                   sg.is_primary_contact,
                   sg.can_pick_up
            FROM student_guardians sg
            JOIN guardians g
              ON g.tenant_id = sg.tenant_id
             AND g.id = sg.guardian_id
            WHERE sg.tenant_id = ?
              AND sg.student_id IN (%s)
            ORDER BY sg.student_id, sg.is_primary_contact DESC, g.last_name
            """.formatted(placeholders);

        Object[] parameters = new Object[students.size() + 1];
        parameters[0] = tenantId;

        for (int i = 0; i < students.size(); i++) {
            parameters[i + 1] = students.get(i).id();
        }

        jdbcTemplate.query(sql, resultSet -> {
            UUID studentId = resultSet.getObject("student_id", UUID.class);

            GuardianResponse guardian = new GuardianResponse(
                    resultSet.getObject("guardian_id", UUID.class),
                    resultSet.getString("first_name"),
                    resultSet.getString("last_name"),
                    resultSet.getString("relationship"),
                    resultSet.getBoolean("is_primary_contact"),
                    resultSet.getBoolean("can_pick_up")
            );

            guardiansByStudent
                    .computeIfAbsent(studentId, ignored -> new ArrayList<>())
                    .add(guardian);
        }, parameters);

        return guardiansByStudent;
    }

    private UUID authorizeBranchAccess(UUID branchId, Jwt jwt) {
        return authorizationService.requireManagementAccess(branchId, jwt);
    }
}
