package mx.artemisa.artemisaapi.packageplan;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OvertimeCalculatorTest {

    private static final BigDecimal RATE = new BigDecimal("125.50");

    @Test
    void appliesContractedMinutesAndToleranceBeforeRoundingUpToAnHour() {
        var noExtra = OvertimeCalculator.calculate(495, 480, 15, RATE);
        var oneMinute = OvertimeCalculator.calculate(496, 480, 15, RATE);
        var oneHour = OvertimeCalculator.calculate(555, 480, 15, RATE);
        var moreThanOneHour = OvertimeCalculator.calculate(556, 480, 15, RATE);

        assertEquals(0, noExtra.overtimeMinutes());
        assertEquals(0, noExtra.billableHours());
        assertEquals(1, oneMinute.overtimeMinutes());
        assertEquals(1, oneMinute.billableHours());
        assertEquals(new BigDecimal("125.50"), oneMinute.extraCharge());
        assertEquals(60, oneHour.overtimeMinutes());
        assertEquals(1, oneHour.billableHours());
        assertEquals(61, moreThanOneHour.overtimeMinutes());
        assertEquals(2, moreThanOneHour.billableHours());
        assertEquals(new BigDecimal("251.00"), moreThanOneHour.extraCharge());
    }

    @Test
    void rejectsNegativeMinutesAndRates() {
        assertThrows(IllegalArgumentException.class,
                () -> OvertimeCalculator.calculate(-1, 480, 15, RATE));
        assertThrows(IllegalArgumentException.class,
                () -> OvertimeCalculator.calculate(500, 480, 15, new BigDecimal("-1")));
    }
}
