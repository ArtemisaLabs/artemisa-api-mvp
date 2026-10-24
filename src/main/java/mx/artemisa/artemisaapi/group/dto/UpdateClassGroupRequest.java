package mx.artemisa.artemisaapi.group.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record UpdateClassGroupRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Positive @Max(500) Integer capacity,
        @Min(0) Integer minAgeMonths,
        @Min(0) Integer maxAgeMonths,
        @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE") String status) {
}
