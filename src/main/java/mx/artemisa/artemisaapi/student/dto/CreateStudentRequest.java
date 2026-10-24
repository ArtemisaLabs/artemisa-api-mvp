package mx.artemisa.artemisaapi.student.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record CreateStudentRequest(
        @NotBlank @Size(max = 40) String studentNumber,
        @NotBlank @Size(max = 120) String firstName,
        @NotBlank @Size(max = 120) String lastName,
        @Past LocalDate dateOfBirth,
        @NotEmpty @Valid List<GuardianInput> guardians
) {
}
