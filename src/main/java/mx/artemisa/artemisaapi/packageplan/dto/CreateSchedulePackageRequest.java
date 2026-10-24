package mx.artemisa.artemisaapi.packageplan.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateSchedulePackageRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{2,40}") String packageCode,
        @NotNull @Positive Integer version,
        @NotBlank @Size(max = 120) String name,
        @NotNull @Positive @Min(1) Integer contractedMinutesPerDay,
        @NotNull @Min(0) Integer overtimeToleranceMinutes,
        @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2)
        BigDecimal overtimeHourlyRate,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo) {
}
