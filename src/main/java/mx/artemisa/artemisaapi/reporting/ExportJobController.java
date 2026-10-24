package mx.artemisa.artemisaapi.reporting;

import jakarta.validation.Valid;
import mx.artemisa.artemisaapi.reporting.dto.CreateAttendanceExportRequest;
import mx.artemisa.artemisaapi.reporting.dto.ExportJobResponse;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class ExportJobController {

    private final ExportJobService exportJobService;

    public ExportJobController(ExportJobService exportJobService) {
        this.exportJobService = exportJobService;
    }

    @PostMapping("/api/v1/branches/{branchId}/exports/attendance")
    public ResponseEntity<ExportJobResponse> create(
            @PathVariable UUID branchId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateAttendanceExportRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        ExportJobResponse response = exportJobService.create(
                branchId, idempotencyKey, request, jwt);
        return ResponseEntity.accepted().body(response);
    }

    @GetMapping("/api/v1/export-jobs/{jobId}")
    public ExportJobResponse get(
            @PathVariable UUID jobId,
            @AuthenticationPrincipal Jwt jwt) {
        return exportJobService.get(jobId, jwt);
    }

    @GetMapping("/api/v1/export-jobs/{jobId}/file")
    public ResponseEntity<FileSystemResource> download(
            @PathVariable UUID jobId,
            @AuthenticationPrincipal Jwt jwt) {
        ExportJobService.Download download = exportJobService.getDownload(jobId, jwt);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(download.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(download.fileName())
                                .build().toString())
                .body(download.resource());
    }
}
