package mx.artemisa.artemisaapi.group.dto;

import java.time.LocalDate;
import java.util.UUID;

public record GroupStudentResponse(
        UUID studentId,
        String studentNumber,
        String firstName,
        String lastName,
        LocalDate dateOfBirth,
        UUID enrollmentId,
        LocalDate startDate) {
}
