package mx.artemisa.artemisaapi.enrollment.dto;

import jakarta.validation.constraints.NotNull;
import mx.artemisa.artemisaapi.enrollment.EnrollmentStatus;

import java.time.LocalDate;
import java.util.UUID;

public record CreateEnrollmentRequest(
        @NotNull UUID studentId,
        @NotNull LocalDate startDate,
        @NotNull EnrollmentStatus status) {
}
