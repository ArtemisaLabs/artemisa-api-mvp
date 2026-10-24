package mx.artemisa.artemisaapi.student.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record StudentResponse(
        UUID id,
        UUID branchId,
        String studentNumber,
        String firstName,
        String lastName,
        LocalDate dateOfBirth,
        List<GuardianResponse> guardians
) {
}
