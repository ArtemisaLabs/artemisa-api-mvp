package mx.artemisa.artemisaapi.reporting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.UUID;

@Component
public class ReportExportJobCoordinator {

    private final JdbcTemplate jdbcTemplate;
    private final ReportExportDispatcher dispatcher;

    public ReportExportJobCoordinator(
            JdbcTemplate jdbcTemplate,
            ReportExportDispatcher dispatcher) {
        this.jdbcTemplate = jdbcTemplate;
        this.dispatcher = dispatcher;
    }

    @TransactionalEventListener
    public void afterExportRequested(ExportJobRequested requested) {
        dispatcher.dispatch(requested.jobId());
    }

    @Scheduled(
            initialDelayString = "${reports.pending-scan-initial-delay-ms:10000}",
            fixedDelayString = "${reports.pending-scan-delay-ms:60000}")
    public void dispatchPendingJobs() {
        List<UUID> ids = jdbcTemplate.queryForList("""
                SELECT id FROM export_jobs
                WHERE status = 'PENDING'
                ORDER BY created_at
                LIMIT 20
                """, UUID.class);
        ids.forEach(dispatcher::dispatch);
    }
}
