package mx.artemisa.artemisaapi.reporting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class AttendanceWorkbookWriter {

    private AttendanceWorkbookWriter() { }

    static byte[] write(
            String studentName,
            String studentNumber,
            LocalDate month,
            int templateVersion,
            List<ReportExportWorker.DailyLine> lines) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream xlsx = new ZipOutputStream(bytes)) {
            entry(xlsx, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                      <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    </Types>
                    """);
            entry(xlsx, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>
                    """);
            entry(xlsx, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets><sheet name="Asistencia" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>
                    """);
            entry(xlsx, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    </Relationships>
                    """);
            entry(xlsx, "xl/worksheets/sheet1.xml", sheetXml(
                    studentName, studentNumber, month, templateVersion, lines));
        }
        return bytes.toByteArray();
    }

    private static String sheetXml(
            String studentName,
            String studentNumber,
            LocalDate month,
            int templateVersion,
            List<ReportExportWorker.DailyLine> lines) {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                """);
        rowStart(xml, 1);
        textCell(xml, "A1", "Artemisa | Reporte mensual de asistencia");
        rowEnd(xml);
        rowStart(xml, 2);
        textCell(xml, "A2", "Alumno"); textCell(xml, "B2", studentName);
        rowEnd(xml);
        rowStart(xml, 3);
        textCell(xml, "A3", "Número de alumno"); textCell(xml, "B3", studentNumber);
        rowEnd(xml);
        rowStart(xml, 4);
        textCell(xml, "A4", "Periodo");
        textCell(xml, "B4", month.format(DateTimeFormatter.ofPattern("yyyy-MM")));
        textCell(xml, "D4", "Versión de formato");
        textCell(xml, "E4", "ARTEMISA_ATTENDANCE_V" + templateVersion);
        rowEnd(xml);
        rowStart(xml, 5);
        String[] headers = {"Fecha", "Tipo de día", "Entrada", "Salida",
                "Minutos de estancia", "Minutos contratados", "Tolerancia",
                "Minutos extra", "Horas extra cobrables", "Tarifa por hora",
                "Cargo extra", "Moneda", "Reportable"};
        for (int i = 0; i < headers.length; i++) {
            textCell(xml, column(i + 1) + "5", headers[i]);
        }
        rowEnd(xml);

        int rowNumber = 6;
        for (ReportExportWorker.DailyLine line : lines) {
            rowStart(xml, rowNumber);
            textCell(xml, "A" + rowNumber, line.date().toString());
            textCell(xml, "B" + rowNumber, line.dayType());
            textCell(xml, "C" + rowNumber, line.checkIn() == null ? "" : line.checkIn().toString());
            textCell(xml, "D" + rowNumber, line.checkOut() == null ? "" : line.checkOut().toString());
            numberCell(xml, "E" + rowNumber, line.minutesPresent());
            numberCell(xml, "F" + rowNumber, line.contractedMinutes());
            numberCell(xml, "G" + rowNumber, line.toleranceMinutes());
            numberCell(xml, "H" + rowNumber, line.overtimeMinutes());
            numberCell(xml, "I" + rowNumber, line.billableOvertimeHours());
            decimalCell(xml, "J" + rowNumber, line.hourlyRate());
            decimalCell(xml, "K" + rowNumber, line.extraCharge());
            textCell(xml, "L" + rowNumber, line.currency() == null ? "" : line.currency());
            textCell(xml, "M" + rowNumber, line.reportable() ? "Sí" : "No");
            rowEnd(xml);
            rowNumber++;
        }
        xml.append("</sheetData></worksheet>");
        return xml.toString();
    }

    private static void rowStart(StringBuilder xml, int row) {
        xml.append("<row r=\"").append(row).append("\">");
    }

    private static void rowEnd(StringBuilder xml) { xml.append("</row>"); }

    private static void textCell(StringBuilder xml, String ref, String value) {
        xml.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                .append(escape(value)).append("</t></is></c>");
    }

    private static void numberCell(StringBuilder xml, String ref, long value) {
        xml.append("<c r=\"").append(ref).append("\"><v>").append(value).append("</v></c>");
    }

    private static void decimalCell(StringBuilder xml, String ref, BigDecimal value) {
        if (value == null) {
            textCell(xml, ref, "");
        } else {
            xml.append("<c r=\"").append(ref).append("\"><v>")
                    .append(value.toPlainString()).append("</v></c>");
        }
    }

    private static String column(int number) {
        StringBuilder result = new StringBuilder();
        int value = number;
        while (value > 0) {
            int remainder = (value - 1) % 26;
            result.insert(0, (char) ('A' + remainder));
            value = (value - 1) / 26;
        }
        return result.toString();
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder();
        value.codePoints()
                .filter(AttendanceWorkbookWriter::isValidXmlCodePoint)
                .forEach(codePoint -> {
                    switch (codePoint) {
                        case '&' -> escaped.append("&amp;");
                        case '<' -> escaped.append("&lt;");
                        case '>' -> escaped.append("&gt;");
                        case '"' -> escaped.append("&quot;");
                        case '\'' -> escaped.append("&apos;");
                        default -> escaped.appendCodePoint(codePoint);
                    }
                });
        return escaped.toString();
    }

    private static boolean isValidXmlCodePoint(int codePoint) {
        return codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD
                || (codePoint >= 0x20 && codePoint <= 0xD7FF)
                || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
                || (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
    }

    private static void entry(ZipOutputStream zip, String name, String content)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
