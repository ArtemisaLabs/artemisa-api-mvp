package mx.artemisa.artemisaapi.calendar.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SetBranchCalendarSettingsRequest(
        @NotNull @Min(0) @Max(127) Integer businessDaysMask) {
}
