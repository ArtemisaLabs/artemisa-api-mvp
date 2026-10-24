package mx.artemisa.artemisaapi.attendance;

import jakarta.validation.Valid;
import mx.artemisa.artemisaapi.attendance.dto.AttendanceEventResponse;
import mx.artemisa.artemisaapi.attendance.dto.AttendanceRosterEntryResponse;
import mx.artemisa.artemisaapi.attendance.dto.CorrectAttendanceEventRequest;
import mx.artemisa.artemisaapi.attendance.dto.RecordAttendanceEventRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/branches/{branchId}/attendance")
public class AttendanceController {

    private final AttendanceService attendanceService;

    public AttendanceController(AttendanceService attendanceService) {
        this.attendanceService = attendanceService;
    }

    @PostMapping("/check-ins")
    @ResponseStatus(HttpStatus.CREATED)
    public AttendanceEventResponse checkIn(
            @PathVariable UUID branchId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RecordAttendanceEventRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return attendanceService.record(
                branchId, AttendanceEventType.CHECK_IN, idempotencyKey, request, jwt);
    }

    @PostMapping("/check-outs")
    @ResponseStatus(HttpStatus.CREATED)
    public AttendanceEventResponse checkOut(
            @PathVariable UUID branchId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RecordAttendanceEventRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return attendanceService.record(
                branchId, AttendanceEventType.CHECK_OUT, idempotencyKey, request, jwt);
    }

    @GetMapping
    public List<AttendanceRosterEntryResponse> list(
            @PathVariable UUID branchId,
            @RequestParam UUID groupId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal Jwt jwt) {
        return attendanceService.list(branchId, groupId, date, jwt);
    }

    @PostMapping("/events/{eventId}/corrections")
    public AttendanceEventResponse correct(
            @PathVariable UUID branchId,
            @PathVariable UUID eventId,
            @Valid @RequestBody CorrectAttendanceEventRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        return attendanceService.correct(branchId, eventId, request, jwt);
    }
}
