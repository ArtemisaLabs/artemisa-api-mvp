package mx.artemisa.artemisaapi.group;

import jakarta.validation.Valid;
import mx.artemisa.artemisaapi.group.dto.AssignGroupStaffRequest;
import mx.artemisa.artemisaapi.group.dto.ClassGroupResponse;
import mx.artemisa.artemisaapi.group.dto.CreateClassGroupRequest;
import mx.artemisa.artemisaapi.group.dto.GroupStudentResponse;
import mx.artemisa.artemisaapi.group.dto.UpdateClassGroupRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/branches/{branchId}/groups")
public class ClassGroupController {

    private final ClassGroupService groupService;

    public ClassGroupController(ClassGroupService groupService) {
        this.groupService = groupService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClassGroupResponse create(
            @PathVariable UUID branchId,
            @Valid @RequestBody CreateClassGroupRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return groupService.create(branchId, request, jwt);
    }

    @GetMapping
    public List<ClassGroupResponse> list(
            @PathVariable UUID branchId,
            @AuthenticationPrincipal Jwt jwt) {
        return groupService.list(branchId, jwt);
    }

    @PutMapping("/{groupId}")
    public ClassGroupResponse update(
            @PathVariable UUID branchId,
            @PathVariable UUID groupId,
            @Valid @RequestBody UpdateClassGroupRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return groupService.update(branchId, groupId, request, jwt);
    }

    @PostMapping("/{groupId}/staff")
    @ResponseStatus(HttpStatus.CREATED)
    public void assignStaff(
            @PathVariable UUID branchId,
            @PathVariable UUID groupId,
            @Valid @RequestBody AssignGroupStaffRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        groupService.assignStaff(branchId, groupId, request, jwt);
    }

    @DeleteMapping("/{groupId}/staff/{membershipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeStaff(
            @PathVariable UUID branchId,
            @PathVariable UUID groupId,
            @PathVariable UUID membershipId,
            @AuthenticationPrincipal Jwt jwt) {
        groupService.removeStaff(branchId, groupId, membershipId, jwt);
    }

    @GetMapping("/{groupId}/students")
    public List<GroupStudentResponse> listStudents(
            @PathVariable UUID branchId,
            @PathVariable UUID groupId,
            @AuthenticationPrincipal Jwt jwt) {
        return groupService.listStudents(branchId, groupId, jwt);
    }
}
