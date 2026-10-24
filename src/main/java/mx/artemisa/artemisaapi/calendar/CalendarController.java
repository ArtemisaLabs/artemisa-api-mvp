package mx.artemisa.artemisaapi.calendar;

import jakarta.validation.Valid;
import mx.artemisa.artemisaapi.calendar.dto.BranchCalendarSettingsResponse;
import mx.artemisa.artemisaapi.calendar.dto.CalendarDayResponse;
import mx.artemisa.artemisaapi.calendar.dto.SetBranchCalendarSettingsRequest;
import mx.artemisa.artemisaapi.calendar.dto.SetCalendarDayOverrideRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/branches/{branchId}/calendar")
public class CalendarController {

    private final CalendarService calendarService;

    public CalendarController(CalendarService calendarService) {
        this.calendarService = calendarService;
    }

    @GetMapping("/settings")
    public BranchCalendarSettingsResponse getSettings(
            @PathVariable UUID branchId,
            @AuthenticationPrincipal Jwt jwt) {
        return calendarService.getSettings(branchId, jwt);
    }

    @PutMapping("/settings")
    public BranchCalendarSettingsResponse setSettings(
            @PathVariable UUID branchId,
            @Valid @RequestBody SetBranchCalendarSettingsRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return calendarService.setSettings(branchId, request, jwt);
    }

    @GetMapping
    public List<CalendarDayResponse> list(
            @PathVariable UUID branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal Jwt jwt) {
        return calendarService.list(branchId, from, to, jwt);
    }

    @PutMapping("/{date}")
    public CalendarDayResponse setOverride(
            @PathVariable UUID branchId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody SetCalendarDayOverrideRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return calendarService.setOverride(branchId, date, request, jwt);
    }
}
