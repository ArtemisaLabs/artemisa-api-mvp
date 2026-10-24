package mx.artemisa.artemisaapi.packageplan.dto;

import java.time.LocalDate;
import java.util.UUID;

public record StudentPackageResponse(
        UUID id,
        UUID schedulePackageId,
        String packageCode,
        int packageVersion,
        String packageName,
        LocalDate effectiveFrom,
        LocalDate effectiveTo) {
}
