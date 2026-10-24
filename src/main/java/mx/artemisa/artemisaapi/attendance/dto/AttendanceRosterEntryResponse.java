package mx.artemisa.artemisaapi.attendance.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AttendanceRosterEntryResponse(
        UUID studentId,
        String studentNumber,
        String firstName,
        String lastName,
        UUID groupId,
        LocalDate attendanceDate,
        Instant checkInAt,
        Instant checkOutAt,
        String state) {
}
