package mx.artemisa.artemisaapi.calendar.dto;

import java.util.List;

public record BranchCalendarSettingsResponse(
        int businessDaysMask,
        List<String> businessWeekdays) {
}
