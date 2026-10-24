package mx.artemisa.artemisaapi.packageplan;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class OvertimeCalculator {

    private OvertimeCalculator() { }

    public static Result calculate(
            long minutesPresent,
            int contractedMinutes,
            int toleranceMinutes,
            BigDecimal hourlyRate) {
        if (minutesPresent < 0 || contractedMinutes < 0 || toleranceMinutes < 0) {
            throw new IllegalArgumentException("Los minutos no pueden ser negativos.");
        }
        if (hourlyRate == null || hourlyRate.signum() < 0) {
            throw new IllegalArgumentException("La tarifa debe ser cero o positiva.");
        }
        long overtimeMinutes = Math.max(
                0, minutesPresent - contractedMinutes - toleranceMinutes);
        long billableHours = overtimeMinutes == 0 ? 0 : (overtimeMinutes + 59) / 60;
        BigDecimal extraCharge = hourlyRate.multiply(BigDecimal.valueOf(billableHours))
                .setScale(2, RoundingMode.HALF_UP);
        return new Result(overtimeMinutes, billableHours, extraCharge);
    }

    public record Result(long overtimeMinutes, long billableHours, BigDecimal extraCharge) { }
}
