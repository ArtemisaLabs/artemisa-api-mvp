package mx.artemisa.artemisaapi.attendance.dto;

import mx.artemisa.artemisaapi.attendance.AttendanceEventType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AttendanceEventResponse(
        UUID id,
        UUID studentId,
        UUID groupId,
        LocalDate attendanceDate,
        AttendanceEventType eventType,
        Instant eventAt,
        Instant clientReportedAt,
        String idempotencyKey) {
}
