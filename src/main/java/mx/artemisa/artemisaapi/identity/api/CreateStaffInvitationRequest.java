package mx.artemisa.artemisaapi.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateStaffInvitationRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(max = 160) String fullName,
        @NotBlank @Pattern(regexp = "BRANCH_DIRECTOR|TEACHER|ASSISTANT") String roleCode) {
}
