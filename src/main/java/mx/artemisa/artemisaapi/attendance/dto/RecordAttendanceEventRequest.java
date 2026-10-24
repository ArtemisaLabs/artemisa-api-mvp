package mx.artemisa.artemisaapi.attendance.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record RecordAttendanceEventRequest(
        @NotNull UUID studentId,
        @NotNull UUID groupId,
        Instant clientReportedAt,
        @Size(max = 120) String deviceId) {
}
