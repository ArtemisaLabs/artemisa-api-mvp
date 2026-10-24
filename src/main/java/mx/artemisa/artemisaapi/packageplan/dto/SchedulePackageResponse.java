package mx.artemisa.artemisaapi.packageplan.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record SchedulePackageResponse(
        UUID id,
        UUID branchId,
        String packageCode,
        int version,
        String name,
        int contractedMinutesPerDay,
        int overtimeToleranceMinutes,
        BigDecimal overtimeHourlyRate,
        String currency,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String status) {
}
