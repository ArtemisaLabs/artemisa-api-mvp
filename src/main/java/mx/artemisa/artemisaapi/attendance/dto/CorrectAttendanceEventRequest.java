package mx.artemisa.artemisaapi.attendance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record CorrectAttendanceEventRequest(
        @NotNull Instant correctedAt,
        @NotBlank @Size(max = 500) String reason) {
}
