package mx.artemisa.artemisaapi.enrollment.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import mx.artemisa.artemisaapi.enrollment.EnrollmentStatus;

public record ChangeEnrollmentStatusRequest(
        @NotNull EnrollmentStatus status,
        @Size(max = 500) String reason) {
}
