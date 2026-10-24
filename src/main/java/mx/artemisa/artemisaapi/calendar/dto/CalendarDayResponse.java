package mx.artemisa.artemisaapi.calendar.dto;

import java.time.LocalDate;

public record CalendarDayResponse(
        LocalDate date,
        String dayType,
        boolean businessDay,
        boolean reportable,
        String name,
        String source) {
}
