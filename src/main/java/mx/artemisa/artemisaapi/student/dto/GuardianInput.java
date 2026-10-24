package mx.artemisa.artemisaapi.student.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GuardianInput(
        @NotBlank @Size(max = 120) String firstName,
        @NotBlank @Size(max = 120) String lastName,
        @Email @Size(max = 320) String email,
        @Size(max = 30) String phone,
        @NotBlank @Size(max = 40) String relationship,
        boolean primaryContact,
        boolean canPickUp
) {
}
