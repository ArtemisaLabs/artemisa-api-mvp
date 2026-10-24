package mx.artemisa.artemisaapi.reporting;

import mx.artemisa.artemisaapi.attendance.AttendanceEventType;
import mx.artemisa.artemisaapi.packageplan.OvertimeCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Component
public class ReportExportWorker {

    private static final Logger log = LoggerFactory.getLogger(ReportExportWorker.class);
    private static final int MAX_STUDENTS_PER_EXPORT = 2_000;

    private final JdbcTemplate jdbcTemplate;
    private final ReportStorage storage;

    public ReportExportWorker(
            JdbcTemplate jdbcTemplate,
            ReportStorage storage) {
        this.jdbcTemplate = jdbcTemplate;
        this.storage = storage;
    }

    public void process(UUID jobId) {
        int claimed = jdbcTemplate.update("""
                UPDATE export_jobs
                SET status = 'RUNNING', started_at = now(), failure_code = NULL
                WHERE id = ? AND status = 'PENDING'
                """, jobId);
        if (claimed == 0) return;

        String storedKey = null;
        try {
            Job job = loadJob(jobId);
            List<Student> students = loadStudents(job);
            if (students.size() > MAX_STUDENTS_PER_EXPORT) {
                throw new ExportGenerationException("EXPORT_STUDENT_LIMIT_EXCEEDED");
            }
            List<CalendarDay> calendar = loadCalendar(job);
            Map<StudentDate, AttendanceTimes> attendance = loadAttendance(job);
            Map<UUID, List<PackageAssignment>> packages = loadPackages(job);
            String timezone = branchTimezone(job);
            ReportStorage.StoredFile stored = storage.writeZip(
                    job.tenantId(), job.id(), zip -> writeZip(
                            zip, job, students, calendar, attendance, packages, timezone));
            storedKey = stored.storageKey();
            jdbcTemplate.update("""
                    UPDATE export_jobs
                    SET status = 'SUCCEEDED', storage_key = ?, sha256 = ?,
                        file_size_bytes = ?, student_count = ?, finished_at = now()
                    WHERE id = ? AND status = 'RUNNING'
                    """,
                    stored.storageKey(), stored.sha256(), stored.sizeBytes(),
                    students.size(), job.id());
        } catch (Exception exception) {
            if (storedKey != null) {
                try { storage.delete(storedKey); }
                catch (IOException ignored) { }
            }
            String code = exception instanceof ExportGenerationException exportException
                    ? exportException.code() : "REPORT_GENERATION_FAILED";
            jdbcTemplate.update("""
                    UPDATE export_jobs
                    SET status = 'FAILED', failure_code = ?, finished_at = now()
                    WHERE id = ? AND status = 'RUNNING'
                    """, code, jobId);
            log.error("Export job {} failed with {}", jobId, exception.getClass().getSimpleName());
        }
    }

    private Job loadJob(UUID jobId) {
        return jdbcTemplate.query("""
                SELECT j.id, j.tenant_id, j.branch_id, j.report_month, t.version
                FROM export_jobs j
                JOIN report_template_versions t
                  ON t.tenant_id = j.tenant_id AND t.id = j.template_version_id
                WHERE j.id = ? AND j.status = 'RUNNING'
                """,
                rs -> rs.next() ? new Job(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("branch_id", UUID.class),
                        rs.getObject("report_month", LocalDate.class),
                        rs.getInt("version")) : null,
                jobId);
    }

    private List<Student> loadStudents(Job job) {
        LocalDate monthEnd = job.month().plusMonths(1).minusDays(1);
        return jdbcTemplate.query("""
                SELECT DISTINCT s.id, s.student_number, s.first_name, s.last_name
                FROM enrollments e
                JOIN students s
                  ON s.tenant_id = e.tenant_id AND s.branch_id = e.branch_id
                 AND s.id = e.student_id
                WHERE e.tenant_id = ? AND e.branch_id = ?
                  AND e.status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')
                  AND e.start_date <= ?
                  AND (e.end_date IS NULL OR e.end_date >= ?)
                ORDER BY s.last_name, s.first_name, s.id
                """,
                (rs, row) -> new Student(
                        rs.getObject("id", UUID.class), rs.getString("student_number"),
                        rs.getString("first_name"), rs.getString("last_name")),
                job.tenantId(), job.branchId(), monthEnd, job.month());
    }

    private List<CalendarDay> loadCalendar(Job job) {
        int mask = jdbcTemplate.queryForObject("""
                SELECT calendar_business_days_mask FROM branches
                WHERE tenant_id = ? AND id = ?
                """, Integer.class, job.tenantId(), job.branchId());
        LocalDate monthEnd = job.month().plusMonths(1).minusDays(1);
        Map<LocalDate, CalendarDay> overrides = jdbcTemplate.query("""
                SELECT calendar_date, day_type, is_business_day, is_reportable
                FROM calendar_day_overrides
                WHERE tenant_id = ? AND branch_id = ?
                  AND calendar_date BETWEEN ? AND ?
                """,
                (rs, row) -> new CalendarDay(
                        rs.getObject("calendar_date", LocalDate.class),
                        rs.getString("day_type"), rs.getBoolean("is_business_day"),
                        rs.getBoolean("is_reportable")),
                job.tenantId(), job.branchId(), job.month(), monthEnd)
                .stream().collect(java.util.stream.Collectors.toMap(
                        CalendarDay::date, value -> value));

        List<CalendarDay> days = new ArrayList<>();
        for (LocalDate date = job.month(); !date.isAfter(monthEnd); date = date.plusDays(1)) {
            CalendarDay override = overrides.get(date);
            if (override != null) {
                days.add(override);
            } else {
                boolean businessDay = (mask & (1 << (date.getDayOfWeek().getValue() - 1))) != 0;
                days.add(new CalendarDay(date,
                        businessDay ? "WEEKLY_SCHEDULE" : "NON_BUSINESS_DAY",
                        businessDay, businessDay));
            }
        }
        return days.stream().filter(CalendarDay::reportable).toList();
    }

    private Map<StudentDate, AttendanceTimes> loadAttendance(Job job) {
        LocalDate monthEnd = job.month().plusMonths(1).minusDays(1);
        List<AttendanceEvent> events = jdbcTemplate.query("""
                SELECT e.student_id, e.attendance_date, e.event_type,
                       COALESCE(
                           (SELECT c.corrected_event_at
                            FROM attendance_corrections c
                            WHERE c.tenant_id = e.tenant_id
                              AND c.branch_id = e.branch_id
                              AND c.attendance_event_id = e.id
                            ORDER BY c.created_at DESC, c.id DESC LIMIT 1),
                           e.event_at) AS effective_event_at
                FROM attendance_events e
                WHERE e.tenant_id = ? AND e.branch_id = ?
                  AND e.attendance_date BETWEEN ? AND ?
                ORDER BY e.attendance_date, e.student_id, e.event_type
                """,
                (rs, row) -> new AttendanceEvent(
                        rs.getObject("student_id", UUID.class),
                        rs.getObject("attendance_date", LocalDate.class),
                        AttendanceEventType.valueOf(rs.getString("event_type")),
                        rs.getObject("effective_event_at", java.time.OffsetDateTime.class).toInstant()),
                job.tenantId(), job.branchId(), job.month(), monthEnd);
        Map<StudentDate, MutableAttendance> mutable = new HashMap<>();
        for (AttendanceEvent event : events) {
            MutableAttendance times = mutable.computeIfAbsent(
                    new StudentDate(event.studentId(), event.date()), ignored -> new MutableAttendance());
            if (event.type() == AttendanceEventType.CHECK_IN) times.checkIn = event.eventAt();
            else times.checkOut = event.eventAt();
        }
        Map<StudentDate, AttendanceTimes> result = new HashMap<>();
        mutable.forEach((key, value) -> result.put(
                key, new AttendanceTimes(value.checkIn, value.checkOut)));
        return result;
    }

    private Map<UUID, List<PackageAssignment>> loadPackages(Job job) {
        LocalDate monthEnd = job.month().plusMonths(1).minusDays(1);
        List<PackageAssignment> assignments = jdbcTemplate.query("""
                SELECT spa.student_id, spa.effective_from, spa.effective_to,
                       sp.contracted_minutes_per_day, sp.overtime_tolerance_minutes,
                       sp.overtime_hourly_rate, sp.currency
                FROM student_package_assignments spa
                JOIN schedule_packages sp
                  ON sp.tenant_id = spa.tenant_id AND sp.branch_id = spa.branch_id
                 AND sp.id = spa.schedule_package_id
                WHERE spa.tenant_id = ? AND spa.branch_id = ?
                  AND spa.effective_from <= ?
                  AND (spa.effective_to IS NULL OR spa.effective_to >= ?)
                ORDER BY spa.student_id, spa.effective_from
                """,
                (rs, row) -> new PackageAssignment(
                        rs.getObject("student_id", UUID.class),
                        rs.getObject("effective_from", LocalDate.class),
                        rs.getObject("effective_to", LocalDate.class),
                        rs.getInt("contracted_minutes_per_day"),
                        rs.getInt("overtime_tolerance_minutes"),
                        rs.getBigDecimal("overtime_hourly_rate"), rs.getString("currency")),
                job.tenantId(), job.branchId(), monthEnd, job.month());
        Map<UUID, List<PackageAssignment>> result = new HashMap<>();
        for (PackageAssignment assignment : assignments) {
            result.computeIfAbsent(assignment.studentId(), ignored -> new ArrayList<>())
                    .add(assignment);
        }
        return result;
    }

    private String branchTimezone(Job job) {
        return jdbcTemplate.queryForObject("""
                SELECT timezone FROM branches WHERE tenant_id = ? AND id = ?
                """, String.class, job.tenantId(), job.branchId());
    }

    private void writeZip(
            ZipOutputStream zip,
            Job job,
            List<Student> students,
            List<CalendarDay> calendar,
            Map<StudentDate, AttendanceTimes> attendance,
            Map<UUID, List<PackageAssignment>> packages,
            String timezone) throws IOException {
        StringBuilder manifest = new StringBuilder(
                "student_id,file_name,reportable_days,missing_check_outs,billable_overtime_hours,file_sha256\n");
        int count = 0;
        for (Student student : students) {
            List<DailyLine> lines = new ArrayList<>();
            int missingCheckouts = 0;
            long billedHours = 0;
            for (CalendarDay day : calendar) {
                AttendanceTimes times = attendance.get(
                        new StudentDate(student.id(), day.date()));
                PackageAssignment schedule = packageFor(
                        packages.getOrDefault(student.id(), List.of()), day.date());
                if (times != null && times.checkIn() != null && times.checkOut() == null) {
                    missingCheckouts++;
                }
                DailyLine line = lineFor(day, times, schedule, timezone);
                lines.add(line);
                billedHours += line.billableOvertimeHours();
            }
            String fileName = fileName(student);
            byte[] workbook = AttendanceWorkbookWriter.write(
                    student.firstName() + " " + student.lastName(),
                    student.studentNumber() == null ? "" : student.studentNumber(),
                    job.month(), job.templateVersion(), lines);
            String hash = sha256(workbook);
            zip.putNextEntry(new ZipEntry(fileName));
            zip.write(workbook);
            zip.closeEntry();
            manifest.append(csv(student.id().toString())).append(',')
                    .append(csv(fileName)).append(',')
                    .append(lines.size()).append(',')
                    .append(missingCheckouts).append(',')
                    .append(billedHours).append(',')
                    .append(hash).append('\n');
            count++;
        }
        zip.putNextEntry(new ZipEntry("manifest.csv"));
        zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry("README.txt"));
        String note = "Reporte generado con formato interno ARTEMISA_ATTENDANCE_V"
                + job.templateVersion()
                + ". No sustituye la plantilla oficial aprobada por la autoridad.\n"
                + "Los horarios se muestran en la zona de la sucursal: " + timezone + ".\n";
        zip.write(note.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static DailyLine lineFor(
            CalendarDay day, AttendanceTimes attendance,
            PackageAssignment schedule, String timezone) {
        Instant checkIn = attendance == null ? null : attendance.checkIn();
        Instant checkOut = attendance == null ? null : attendance.checkOut();
        long presentMinutes = checkIn != null && checkOut != null
                ? Math.max(0, Duration.between(checkIn, checkOut).toMinutes()) : 0;
        int contracted = schedule == null ? 0 : schedule.contractedMinutes();
        int tolerance = schedule == null ? 0 : schedule.toleranceMinutes();
        BigDecimal hourlyRate = schedule == null ? null : schedule.hourlyRate();
        OvertimeCalculator.Result overtime = schedule == null || checkIn == null || checkOut == null
                ? OvertimeCalculator.calculate(0, 0, 0, BigDecimal.ZERO)
                : OvertimeCalculator.calculate(presentMinutes, contracted, tolerance, hourlyRate);
        ZoneId zone = ZoneId.of(timezone);
        return new DailyLine(day.date(), day.type(), day.reportable(),
                checkIn == null ? null : checkIn.atZone(zone).toLocalTime()
                        .format(DateTimeFormatter.ofPattern("HH:mm")),
                checkOut == null ? null : checkOut.atZone(zone).toLocalTime()
                        .format(DateTimeFormatter.ofPattern("HH:mm")),
                presentMinutes, contracted, tolerance, overtime.overtimeMinutes(),
                overtime.billableHours(), hourlyRate, overtime.extraCharge(),
                schedule == null ? null : schedule.currency());
    }

    private static PackageAssignment packageFor(List<PackageAssignment> assignments, LocalDate date) {
        return assignments.stream()
                .filter(item -> !date.isBefore(item.effectiveFrom())
                        && (item.effectiveTo() == null || !date.isAfter(item.effectiveTo())))
                .findFirst().orElse(null);
    }

    private static String fileName(Student student) {
        String identifier = student.studentNumber() == null || student.studentNumber().isBlank()
                ? student.id().toString() : student.studentNumber();
        identifier = identifier.replaceAll("[^A-Za-z0-9._-]", "_");
        return "alumno-" + identifier + ".xlsx";
    }

    private static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible.", exception);
        }
    }

    private record Job(
            UUID id, UUID tenantId, UUID branchId, LocalDate month, int templateVersion) { }
    private record Student(UUID id, String studentNumber, String firstName, String lastName) { }
    private record CalendarDay(LocalDate date, String type, boolean businessDay, boolean reportable) { }
    private record StudentDate(UUID studentId, LocalDate date) { }
    private record AttendanceEvent(
            UUID studentId, LocalDate date, AttendanceEventType type, Instant eventAt) { }
    private record AttendanceTimes(Instant checkIn, Instant checkOut) { }
    private static final class MutableAttendance { private Instant checkIn; private Instant checkOut; }
    private record PackageAssignment(
            UUID studentId, LocalDate effectiveFrom, LocalDate effectiveTo,
            int contractedMinutes, int toleranceMinutes, BigDecimal hourlyRate, String currency) { }
    static record DailyLine(
            LocalDate date, String dayType, boolean reportable,
            String checkIn, String checkOut, long minutesPresent,
            int contractedMinutes, int toleranceMinutes, long overtimeMinutes,
            long billableOvertimeHours, BigDecimal hourlyRate,
            BigDecimal extraCharge, String currency) { }

    private static final class ExportGenerationException extends RuntimeException {
        private final String code;
        private ExportGenerationException(String code) { super(code); this.code = code; }
        private String code() { return code; }
    }
}
