package mx.artemisa.artemisaapi.reporting.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record CreateAttendanceExportRequest(@NotNull LocalDate month) {
}
