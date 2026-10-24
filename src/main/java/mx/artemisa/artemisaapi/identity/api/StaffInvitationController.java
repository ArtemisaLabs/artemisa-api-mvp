package mx.artemisa.artemisaapi.identity.api;

import jakarta.validation.Valid;
import mx.artemisa.artemisaapi.identity.StaffInvitationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class StaffInvitationController {

    private final StaffInvitationService invitationService;

    public StaffInvitationController(StaffInvitationService invitationService) {
        this.invitationService = invitationService;
    }

    @PostMapping("/api/v1/branches/{branchId}/staff-invitations")
    public ResponseEntity<StaffInvitationResponse> invite(
            @PathVariable UUID branchId,
            @Valid @RequestBody CreateStaffInvitationRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(invitationService.create(branchId, request, jwt));
    }

    @PostMapping("/api/v1/staff-invitations/accept")
    @ResponseStatus(HttpStatus.CREATED)
    public AcceptStaffInvitationResponse accept(
            @Valid @RequestBody AcceptStaffInvitationRequest request) {
        return invitationService.accept(request);
    }
}
