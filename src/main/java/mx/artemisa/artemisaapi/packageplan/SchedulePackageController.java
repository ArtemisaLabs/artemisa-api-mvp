package mx.artemisa.artemisaapi.packageplan;

import jakarta.validation.Valid;
import mx.artemisa.artemisaapi.packageplan.dto.AssignStudentPackageRequest;
import mx.artemisa.artemisaapi.packageplan.dto.CreateSchedulePackageRequest;
import mx.artemisa.artemisaapi.packageplan.dto.SchedulePackageResponse;
import mx.artemisa.artemisaapi.packageplan.dto.StudentPackageResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/branches/{branchId}")
public class SchedulePackageController {

    private final SchedulePackageService packageService;

    public SchedulePackageController(SchedulePackageService packageService) {
        this.packageService = packageService;
    }

    @PostMapping("/packages")
    @ResponseStatus(HttpStatus.CREATED)
    public SchedulePackageResponse create(
            @PathVariable UUID branchId,
            @Valid @RequestBody CreateSchedulePackageRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return packageService.create(branchId, request, jwt);
    }

    @GetMapping("/packages")
    public List<SchedulePackageResponse> list(
            @PathVariable UUID branchId,
            @AuthenticationPrincipal Jwt jwt) {
        return packageService.list(branchId, jwt);
    }

    @PostMapping("/students/{studentId}/packages")
    @ResponseStatus(HttpStatus.CREATED)
    public StudentPackageResponse assign(
            @PathVariable UUID branchId,
            @PathVariable UUID studentId,
            @Valid @RequestBody AssignStudentPackageRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return packageService.assign(branchId, studentId, request, jwt);
    }

    @GetMapping("/students/{studentId}/packages")
    public List<StudentPackageResponse> listAssignments(
            @PathVariable UUID branchId,
            @PathVariable UUID studentId,
            @AuthenticationPrincipal Jwt jwt) {
        return packageService.listStudentAssignments(branchId, studentId, jwt);
    }
}
