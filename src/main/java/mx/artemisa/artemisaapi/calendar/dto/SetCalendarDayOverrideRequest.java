package mx.artemisa.artemisaapi.calendar.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SetCalendarDayOverrideRequest(
        @NotBlank @Size(max = 20) String dayType,
        boolean businessDay,
        boolean reportable,
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 200) String source) {
}
