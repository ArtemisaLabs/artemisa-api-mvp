package mx.artemisa.artemisaapi.packageplan.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record AssignStudentPackageRequest(
        @NotNull UUID schedulePackageId,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo) {
}
