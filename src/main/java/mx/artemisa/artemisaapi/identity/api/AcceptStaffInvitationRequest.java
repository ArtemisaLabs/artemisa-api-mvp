package mx.artemisa.artemisaapi.identity.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AcceptStaffInvitationRequest(
        @NotBlank @Size(max = 100) String token,
        @Size(max = 72) String password) {
}
