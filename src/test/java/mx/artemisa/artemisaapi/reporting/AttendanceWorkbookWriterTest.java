package mx.artemisa.artemisaapi.reporting;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AttendanceWorkbookWriterTest {

    @Test
    void createsAnXlsxPackageAndEscapesSpreadsheetText() throws Exception {
        byte[] bytes = AttendanceWorkbookWriter.write(
                "A&B <prueba>\u0001",
                "ALU-01",
                LocalDate.of(2026, 10, 1),
                1,
                List.of(new ReportExportWorker.DailyLine(
                        LocalDate.of(2026, 10, 1), "WEEKLY_SCHEDULE", true,
                        "08:00", "17:00", 540, 480, 15,
                        45, 1, new BigDecimal("100.00"),
                        new BigDecimal("100.00"), "MXN")));

        boolean contentTypes = false;
        boolean sheetFound = false;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("[Content_Types].xml".equals(entry.getName())) contentTypes = true;
                if ("xl/worksheets/sheet1.xml".equals(entry.getName())) {
                    String xml = new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    assertTrue(xml.contains("A&amp;B &lt;prueba&gt;"));
                    assertTrue(!xml.contains("\u0001"));
                    sheetFound = true;
                }
            }
        }
        assertTrue(contentTypes);
        assertTrue(sheetFound);
    }
}
