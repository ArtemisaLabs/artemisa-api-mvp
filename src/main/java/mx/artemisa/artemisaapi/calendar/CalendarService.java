package mx.artemisa.artemisaapi.calendar;

import mx.artemisa.artemisaapi.audit.AuditService;
import mx.artemisa.artemisaapi.calendar.dto.BranchCalendarSettingsResponse;
import mx.artemisa.artemisaapi.calendar.dto.CalendarDayResponse;
import mx.artemisa.artemisaapi.calendar.dto.SetBranchCalendarSettingsRequest;
import mx.artemisa.artemisaapi.calendar.dto.SetCalendarDayOverrideRequest;
import mx.artemisa.artemisaapi.security.BranchAuthorizationService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class CalendarService {

    private static final List<String> DAY_NAMES = List.of(
            "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY");

    private final JdbcTemplate jdbcTemplate;
    private final BranchAuthorizationService authorizationService;
    private final AuditService auditService;

    public CalendarService(
            JdbcTemplate jdbcTemplate,
            BranchAuthorizationService authorizationService,
            AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizationService = authorizationService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public BranchCalendarSettingsResponse getSettings(UUID branchId, Jwt jwt) {
        UUID tenantId = authorizationService.requireOperationalAccess(branchId, jwt);
        Integer mask = jdbcTemplate.queryForObject("""
                SELECT calendar_business_days_mask FROM branches
                WHERE tenant_id = ? AND id = ?
                """, Integer.class, tenantId, branchId);
        return new BranchCalendarSettingsResponse(mask, weekdays(mask));
    }

    @Transactional
    public BranchCalendarSettingsResponse setSettings(
            UUID branchId, SetBranchCalendarSettingsRequest request, Jwt jwt) {
        UUID tenantId = authorizationService.requireManagementAccess(branchId, jwt);
        Integer previousMask = jdbcTemplate.queryForObject("""
                SELECT calendar_business_days_mask FROM branches
                WHERE tenant_id = ? AND id = ? FOR UPDATE
                """, Integer.class, tenantId, branchId);
        UUID actorId = authorizationService.requireMembershipId(jwt);
        jdbcTemplate.update("""
                INSERT INTO branch_calendar_settings_history (
                    id, tenant_id, branch_id, previous_business_days_mask,
                    new_business_days_mask, changed_by_membership_id, changed_at
                ) VALUES (?, ?, ?, ?, ?, ?, now())
                """,
                UUID.randomUUID(), tenantId, branchId, previousMask,
                request.businessDaysMask(), actorId);
        jdbcTemplate.update("""
                UPDATE branches SET calendar_business_days_mask = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """, request.businessDaysMask(), tenantId, branchId);
        auditService.record(tenantId, branchId,
                actorId,
                "BRANCH_CALENDAR_SETTINGS_CHANGED", "BRANCH", branchId);
        return new BranchCalendarSettingsResponse(
                request.businessDaysMask(), weekdays(request.businessDaysMask()));
    }

    @Transactional(readOnly = true)
    public List<CalendarDayResponse> list(
            UUID branchId, LocalDate from, LocalDate to, Jwt jwt) {
        UUID tenantId = authorizationService.requireOperationalAccess(branchId, jwt);
        validateRange(from, to);
        int mask = jdbcTemplate.queryForObject("""
                SELECT calendar_business_days_mask FROM branches
                WHERE tenant_id = ? AND id = ?
                """, Integer.class, tenantId, branchId);

        Map<LocalDate, CalendarDayResponse> overrides = jdbcTemplate.query("""
                SELECT calendar_date, day_type, is_business_day, is_reportable, name, source
                FROM calendar_day_overrides
                WHERE tenant_id = ? AND branch_id = ?
                  AND calendar_date BETWEEN ? AND ?
                ORDER BY calendar_date
                """,
                (rs, row) -> new CalendarDayResponse(
                        rs.getObject("calendar_date", LocalDate.class),
                        rs.getString("day_type"), rs.getBoolean("is_business_day"),
                        rs.getBoolean("is_reportable"), rs.getString("name"),
                        rs.getString("source")),
                tenantId, branchId, from, to).stream()
                .collect(Collectors.toMap(CalendarDayResponse::date, Function.identity()));

        List<CalendarDayResponse> result = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            CalendarDayResponse override = overrides.get(date);
            if (override != null) {
                result.add(override);
            } else {
                boolean businessDay = isBusinessDay(mask, date.getDayOfWeek());
                result.add(new CalendarDayResponse(date,
                        businessDay ? "WEEKLY_SCHEDULE" : "NON_BUSINESS_DAY",
                        businessDay, businessDay, null, null));
            }
        }
        return List.copyOf(result);
    }

    @Transactional
    public CalendarDayResponse setOverride(
            UUID branchId,
            LocalDate date,
            SetCalendarDayOverrideRequest request,
            Jwt jwt) {
        UUID tenantId = authorizationService.requireManagementAccess(branchId, jwt);
        String dayType = request.dayType().strip().toUpperCase(Locale.ROOT);
        if (!List.of("HOLIDAY", "CLOSURE", "SPECIAL_OPENING").contains(dayType)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "dayType debe ser HOLIDAY, CLOSURE o SPECIAL_OPENING.");
        }
        if ("SPECIAL_OPENING".equals(dayType) && !request.businessDay()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "SPECIAL_OPENING debe indicar businessDay=true.");
        }
        UUID actorId = authorizationService.requireMembershipId(jwt);
        jdbcTemplate.queryForObject("""
                SELECT id FROM branches WHERE tenant_id = ? AND id = ? FOR UPDATE
                """, UUID.class, tenantId, branchId);
        CalendarDayResponse previous = jdbcTemplate.query("""
                SELECT calendar_date, day_type, is_business_day, is_reportable, name, source
                FROM calendar_day_overrides
                WHERE tenant_id = ? AND branch_id = ? AND calendar_date = ?
                FOR UPDATE
                """,
                rs -> rs.next() ? new CalendarDayResponse(
                        rs.getObject("calendar_date", LocalDate.class),
                        rs.getString("day_type"), rs.getBoolean("is_business_day"),
                        rs.getBoolean("is_reportable"), rs.getString("name"),
                        rs.getString("source")) : null,
                tenantId, branchId, date);
        if (previous != null) {
            jdbcTemplate.update("""
                    INSERT INTO calendar_day_override_history (
                        id, tenant_id, branch_id, calendar_date, previous_day_type,
                        previous_business_day, previous_reportable, previous_name,
                        previous_source, changed_by_membership_id, changed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                    """,
                    UUID.randomUUID(), tenantId, branchId, date, previous.dayType(),
                    previous.businessDay(), previous.reportable(), previous.name(),
                    previous.source(), actorId);
        }
        UUID overrideId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO calendar_day_overrides (
                    id, tenant_id, branch_id, calendar_date, day_type,
                    is_business_day, is_reportable, name, source,
                    updated_by_membership_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                ON CONFLICT (tenant_id, branch_id, calendar_date)
                DO UPDATE SET day_type = EXCLUDED.day_type,
                    is_business_day = EXCLUDED.is_business_day,
                    is_reportable = EXCLUDED.is_reportable,
                    name = EXCLUDED.name, source = EXCLUDED.source,
                    updated_by_membership_id = EXCLUDED.updated_by_membership_id,
                    updated_at = now()
                """,
                overrideId, tenantId, branchId, date, dayType,
                request.businessDay(), request.reportable(), request.name().strip(),
                request.source().strip(), actorId);
        auditService.record(tenantId, branchId, actorId,
                "CALENDAR_DAY_OVERRIDE_SET", "CALENDAR_DAY", UUID.nameUUIDFromBytes(
                        (branchId + ":" + date).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return new CalendarDayResponse(date, dayType, request.businessDay(),
                request.reportable(), request.name().strip(), request.source().strip());
    }

    private static void validateRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "from/to son obligatorios y to debe ser igual o posterior a from.");
        }
        if (ChronoUnit.DAYS.between(from, to) > 366) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "El rango máximo de consulta es de 367 días.");
        }
    }

    private static boolean isBusinessDay(int mask, DayOfWeek dayOfWeek) {
        int bit = 1 << (dayOfWeek.getValue() - 1);
        return (mask & bit) != 0;
    }

    private static List<String> weekdays(int mask) {
        List<String> result = new ArrayList<>();
        for (int day = 0; day < DAY_NAMES.size(); day++) {
            if ((mask & (1 << day)) != 0) result.add(DAY_NAMES.get(day));
        }
        return List.copyOf(result);
    }
}
