package mx.artemisa.artemisaapi.reporting.dto;

import mx.artemisa.artemisaapi.reporting.ExportJobStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ExportJobResponse(
        UUID id,
        UUID branchId,
        LocalDate month,
        ExportJobStatus status,
        int templateVersion,
        Instant createdAt,
        Instant finishedAt,
        String downloadPath,
        String sha256,
        Long fileSizeBytes,
        Integer studentCount,
        String failureCode) {
}
