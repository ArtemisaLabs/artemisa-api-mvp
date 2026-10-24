package mx.artemisa.artemisaapi.reporting;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class ReportExportDispatcher {

    private final ReportExportWorker worker;

    public ReportExportDispatcher(ReportExportWorker worker) {
        this.worker = worker;
    }

    @Async("reportExportExecutor")
    public void dispatch(UUID jobId) {
        worker.process(jobId);
    }
}
