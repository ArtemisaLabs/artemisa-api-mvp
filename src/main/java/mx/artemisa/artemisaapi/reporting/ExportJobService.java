package mx.artemisa.artemisaapi.reporting;

import mx.artemisa.artemisaapi.reporting.dto.CreateAttendanceExportRequest;
import mx.artemisa.artemisaapi.reporting.dto.ExportJobResponse;
import mx.artemisa.artemisaapi.security.BranchAuthorizationService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ExportJobService {

    private static final String INTERNAL_TEMPLATE_CODE = "ARTEMISA_ATTENDANCE_XLSX";
    private static final int INTERNAL_TEMPLATE_VERSION = 1;
    private static final String INTERNAL_TEMPLATE_DEFINITION =
            "ARTEMISA_ATTENDANCE_XLSX_V1:date,type,checkin,checkout,minutes,package,overtime";

    private final JdbcTemplate jdbcTemplate;
    private final BranchAuthorizationService authorizationService;
    private final ApplicationEventPublisher eventPublisher;
    private final ReportStorage reportStorage;

    public ExportJobService(
            JdbcTemplate jdbcTemplate,
            BranchAuthorizationService authorizationService,
            ApplicationEventPublisher eventPublisher,
            ReportStorage reportStorage) {
        this.jdbcTemplate = jdbcTemplate;
        this.authorizationService = authorizationService;
        this.eventPublisher = eventPublisher;
        this.reportStorage = reportStorage;
    }

    @Transactional
    public ExportJobResponse create(
            UUID branchId,
            String idempotencyKey,
            CreateAttendanceExportRequest request,
            Jwt jwt) {
        UUID tenantId = authorizationService.requireManagementAccess(branchId, jwt);
        UUID actorId = authorizationService.requireMembershipId(jwt);
        validateIdempotencyKey(idempotencyKey);
        if (request.month().getDayOfMonth() != 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "month debe ser el primer día del mes solicitado (YYYY-MM-01).");
        }

        String timezone = jdbcTemplate.queryForObject("""
                SELECT timezone FROM branches WHERE tenant_id = ? AND id = ?
                """, String.class, tenantId, branchId);
        LocalDate currentMonth = LocalDate.now(ZoneId.of(timezone))
                .withDayOfMonth(1);
        if (request.month().isAfter(currentMonth)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "No se puede exportar un mes futuro.");
        }

        ExportJobResponse existing = findByIdempotencyKey(tenantId, branchId, idempotencyKey);
        if (existing != null) {
            if (!existing.month().equals(request.month())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Idempotency-Key ya se usó para otro mes.");
            }
            return existing;
        }

        UUID templateId = ensureInternalTemplate(tenantId, actorId);
        UUID jobId = UUID.randomUUID();
        int inserted = jdbcTemplate.update("""
                INSERT INTO export_jobs (
                    id, tenant_id, branch_id, report_month, template_version_id,
                    status, requested_by_membership_id, idempotency_key, created_at
                ) VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, now())
                ON CONFLICT (tenant_id, branch_id, idempotency_key) DO NOTHING
                """,
                jobId, tenantId, branchId, request.month(), templateId,
                actorId, idempotencyKey);
        if (inserted == 0) {
            ExportJobResponse winner = findByIdempotencyKey(
                    tenantId, branchId, idempotencyKey);
            if (winner == null || !winner.month().equals(request.month())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Idempotency-Key ya se usó para otra solicitud.");
            }
            return winner;
        }

        eventPublisher.publishEvent(new ExportJobRequested(jobId));
        return findById(tenantId, jobId);
    }

    @Transactional(readOnly = true)
    public ExportJobResponse get(UUID jobId, Jwt jwt) {
        UUID tenantId = authorizationService.requireTenantId(jwt);
        ExportJobResponse response = findById(tenantId, jobId);
        authorizationService.requireManagementAccess(response.branchId(), jwt);
        return response;
    }

    @Transactional(readOnly = true)
    public Download getDownload(UUID jobId, Jwt jwt) {
        UUID tenantId = authorizationService.requireTenantId(jwt);
        ExportJobResponse response = findById(tenantId, jobId);
        authorizationService.requireManagementAccess(response.branchId(), jwt);
        if (response.status() != ExportJobStatus.SUCCEEDED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "El archivo todavía no está disponible.");
        }
        String storageKey = jdbcTemplate.queryForObject("""
                SELECT storage_key FROM export_jobs WHERE tenant_id = ? AND id = ?
                """, String.class, tenantId, jobId);
        try {
            return new Download(reportStorage.load(storageKey), response.fileSizeBytes(),
                    "asistencia-" + response.month().toString().substring(0, 7) + ".zip");
        } catch (java.io.IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.GONE,
                    "El archivo de exportación ya no está disponible.", exception);
        }
    }

    private UUID ensureInternalTemplate(UUID tenantId, UUID actorId) {
        UUID templateId = UUID.nameUUIDFromBytes(
                (tenantId + ":" + INTERNAL_TEMPLATE_CODE + ":"
                        + INTERNAL_TEMPLATE_VERSION).getBytes(StandardCharsets.UTF_8));
        jdbcTemplate.update("""
                INSERT INTO report_template_versions (
                    id, tenant_id, template_code, version, source_type,
                    file_name, sha256, active, created_by_membership_id, created_at
                ) VALUES (?, ?, ?, ?, 'INTERNAL', ?, ?, TRUE, ?, now())
                ON CONFLICT (tenant_id, template_code, version) DO NOTHING
                """,
                templateId, tenantId, INTERNAL_TEMPLATE_CODE, INTERNAL_TEMPLATE_VERSION,
                "artemisa-attendance-v1.xlsx", sha256(INTERNAL_TEMPLATE_DEFINITION), actorId);
        return jdbcTemplate.queryForObject("""
                SELECT id FROM report_template_versions
                WHERE tenant_id = ? AND template_code = ? AND version = ?
                """, UUID.class, tenantId, INTERNAL_TEMPLATE_CODE, INTERNAL_TEMPLATE_VERSION);
    }

    private ExportJobResponse findById(UUID tenantId, UUID jobId) {
        ExportJobResponse result = jdbcTemplate.query("""
                SELECT j.id, j.branch_id, j.report_month, j.status, t.version,
                       j.created_at, j.finished_at, j.sha256, j.file_size_bytes,
                       j.student_count, j.failure_code
                FROM export_jobs j
                JOIN report_template_versions t
                  ON t.tenant_id = j.tenant_id AND t.id = j.template_version_id
                WHERE j.tenant_id = ? AND j.id = ?
                """,
                rs -> rs.next() ? mapJob(rs) : null,
                tenantId, jobId);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró la exportación.");
        }
        return result;
    }

    private ExportJobResponse findByIdempotencyKey(
            UUID tenantId, UUID branchId, String key) {
        return jdbcTemplate.query("""
                SELECT j.id, j.branch_id, j.report_month, j.status, t.version,
                       j.created_at, j.finished_at, j.sha256, j.file_size_bytes,
                       j.student_count, j.failure_code
                FROM export_jobs j
                JOIN report_template_versions t
                  ON t.tenant_id = j.tenant_id AND t.id = j.template_version_id
                WHERE j.tenant_id = ? AND j.branch_id = ? AND j.idempotency_key = ?
                """,
                rs -> rs.next() ? mapJob(rs) : null,
                tenantId, branchId, key);
    }

    private static ExportJobResponse mapJob(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        UUID id = rs.getObject("id", UUID.class);
        LocalDate month = rs.getObject("report_month", LocalDate.class);
        ExportJobStatus status = ExportJobStatus.valueOf(rs.getString("status"));
        String downloadPath = status == ExportJobStatus.SUCCEEDED
                ? "/api/v1/export-jobs/" + id + "/file" : null;
        java.time.OffsetDateTime created = rs.getObject("created_at", java.time.OffsetDateTime.class);
        java.time.OffsetDateTime finished = rs.getObject("finished_at", java.time.OffsetDateTime.class);
        Long size = (Long) rs.getObject("file_size_bytes");
        Integer students = (Integer) rs.getObject("student_count");
        return new ExportJobResponse(
                id, rs.getObject("branch_id", UUID.class), month, status,
                rs.getInt("version"), created.toInstant(),
                finished == null ? null : finished.toInstant(), downloadPath,
                rs.getString("sha256"), size, students, rs.getString("failure_code"));
    }

    private static void validateIdempotencyKey(String value) {
        if (value == null || value.isBlank() || value.length() < 8 || value.length() > 128) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Idempotency-Key es obligatorio y debe tener entre 8 y 128 caracteres.");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no está disponible.", exception);
        }
    }

    public record Download(FileSystemResource resource, Long sizeBytes, String fileName) { }
}
