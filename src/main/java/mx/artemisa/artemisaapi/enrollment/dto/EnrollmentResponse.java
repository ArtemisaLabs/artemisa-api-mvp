package mx.artemisa.artemisaapi.enrollment.dto;

import mx.artemisa.artemisaapi.enrollment.EnrollmentStatus;

import java.time.LocalDate;
import java.util.UUID;

public record EnrollmentResponse(
        UUID id,
        UUID studentId,
        UUID groupId,
        LocalDate startDate,
        LocalDate endDate,
        EnrollmentStatus status) {
}
