package mx.artemisa.artemisaapi.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(max = 80) String tenantSlug,
        @NotBlank @Size(max = 72) String password
) {
}
