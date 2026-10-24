package mx.artemisa.artemisaapi.student;

import jakarta.validation.Valid;
import mx.artemisa.artemisaapi.student.dto.CreateStudentRequest;
import mx.artemisa.artemisaapi.student.dto.StudentResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

import mx.artemisa.artemisaapi.student.dto.StudentPageResponse;

@RestController
@RequestMapping("/api/v1/branches/{branchId}/students")
public class StudentController {

    private final StudentService studentService;

    public StudentController(StudentService studentService) {
        this.studentService = studentService;
    }

    @PostMapping
    public ResponseEntity<StudentResponse> createStudent(
            @PathVariable UUID branchId,
            @Valid @RequestBody CreateStudentRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        StudentResponse response = studentService.createStudent(branchId, request, jwt);

        URI location = ServletUriComponentsBuilder
                .fromCurrentRequestUri()
                .path("/{studentId}")
                .buildAndExpand(response.id())
                .toUri();

        return ResponseEntity.created(location).body(response);
    }

    @GetMapping
    public ResponseEntity<StudentPageResponse> listStudents(
            @PathVariable UUID branchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal Jwt jwt
    ) {
        return ResponseEntity.ok(
                studentService.listStudents(branchId, page, size, jwt)
        );
    }
}
