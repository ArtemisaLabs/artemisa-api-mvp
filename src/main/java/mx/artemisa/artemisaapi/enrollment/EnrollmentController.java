package mx.artemisa.artemisaapi.enrollment;

import jakarta.validation.Valid;
import mx.artemisa.artemisaapi.enrollment.dto.ChangeEnrollmentStatusRequest;
import mx.artemisa.artemisaapi.enrollment.dto.CreateEnrollmentRequest;
import mx.artemisa.artemisaapi.enrollment.dto.EnrollmentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/branches/{branchId}/groups/{groupId}/enrollments")
public class EnrollmentController {

    private final EnrollmentService enrollmentService;

    public EnrollmentController(EnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EnrollmentResponse create(
            @PathVariable UUID branchId,
            @PathVariable UUID groupId,
            @Valid @RequestBody CreateEnrollmentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return enrollmentService.create(branchId, groupId, request, jwt);
    }

    @GetMapping
    public List<EnrollmentResponse> list(
            @PathVariable UUID branchId,
            @PathVariable UUID groupId,
            @AuthenticationPrincipal Jwt jwt) {
        return enrollmentService.list(branchId, groupId, jwt);
    }

    @PatchMapping("/{enrollmentId}/status")
    public EnrollmentResponse changeStatus(
            @PathVariable UUID branchId,
            @PathVariable UUID enrollmentId,
            @Valid @RequestBody ChangeEnrollmentStatusRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return enrollmentService.changeStatus(branchId, enrollmentId, request, jwt);
    }
}
