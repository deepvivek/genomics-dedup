package com.genomics.dedup.xlsx;

import com.genomics.dedup.config.ScanConfig;
import com.genomics.dedup.report.DuplicateGroup;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class XlsxReportGeneratorTest {

    private ScanConfig config;
    private XlsxReportGenerator generator;

    @BeforeEach
    void setUp(@TempDir Path tmp) throws Exception {
        config = ScanConfig.builder()
                .scanRoot(tmp.resolve("vcf"))
                .outputDir(tmp.resolve("out"))
                .build();
        Files.createDirectories(config.getOutputDir());
        generator = new XlsxReportGenerator(config);

        // Write a minimal CSV for tests that need it
        writeSampleCsv();
    }

    private void writeSampleCsv() throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(config.getReportCsvPath())) {
            bw.write("group_id,sha256,file_path,file_size_bytes,last_modified,is_original");
            bw.newLine();
            bw.write("1," + "a".repeat(64) + ",\"/data/vcf/patients/s1.vcf\",2147483648,2024-01-01T00:00:00Z,true");
            bw.newLine();
            bw.write("1," + "a".repeat(64) + ",\"/data/vcf/backup/s1.vcf\",2147483648,2024-01-02T00:00:00Z,false");
            bw.newLine();
            bw.write("2," + "b".repeat(64) + ",\"/data/vcf/patients/s2.vcf\",536870912,2024-01-03T00:00:00Z,true");
            bw.newLine();
            bw.write("2," + "b".repeat(64) + ",\"/data/vcf/archive/s2.vcf\",536870912,2024-01-04T00:00:00Z,false");
            bw.newLine();
        }
    }

    @Test
    void generate_producesXlsxFile() throws Exception {
        XlsxReportGenerator.ScanStats stats =
                new XlsxReportGenerator.ScanStats(2, 2, 2684354560L, "2.5 GB");
        generator.generate(stats);
        assertThat(config.getReportXlsxPath()).exists();
        assertThat(Files.size(config.getReportXlsxPath())).isGreaterThan(1000);
    }

    @Test
    void generate_xlsxIsValidWorkbook() throws Exception {
        XlsxReportGenerator.ScanStats stats =
                new XlsxReportGenerator.ScanStats(2, 2, 1_000_000, "1.0 MB");
        generator.generate(stats);

        try (InputStream is = Files.newInputStream(config.getReportXlsxPath());
             Workbook wb   = new XSSFWorkbook(is)) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(5);
        }
    }

    @Test
    void generate_sheetNamesCorrect() throws Exception {
        XlsxReportGenerator.ScanStats stats =
                new XlsxReportGenerator.ScanStats(2, 2, 1_000_000, "1.0 MB");
        generator.generate(stats);

        try (InputStream is = Files.newInputStream(config.getReportXlsxPath());
             Workbook wb   = new XSSFWorkbook(is)) {
            assertThat(wb.getSheetName(0)).isEqualTo("Summary");
            assertThat(wb.getSheetName(1)).isEqualTo("Review");
            assertThat(wb.getSheetName(2)).isEqualTo("High Priority");
            assertThat(wb.getSheetName(3)).isEqualTo("Suspicious");
            assertThat(wb.getSheetName(4)).isEqualTo("Raw Data");
        }
    }

    @Test
    void generate_reviewSheetHasHeaderRow() throws Exception {
        XlsxReportGenerator.ScanStats stats =
                new XlsxReportGenerator.ScanStats(2, 2, 1_000_000, "1.0 MB");
        generator.generate(stats);

        try (InputStream is = Files.newInputStream(config.getReportXlsxPath());
             Workbook wb   = new XSSFWorkbook(is)) {
            Sheet review = wb.getSheet("Review");
            Row header   = review.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("Group ID");
            assertThat(header.getCell(XlsxReportGenerator.COL_DECISION).getStringCellValue())
                    .isEqualTo("Decision");
            assertThat(header.getCell(XlsxReportGenerator.COL_REVIEWER).getStringCellValue())
                    .isEqualTo("Reviewer");
            assertThat(header.getCell(XlsxReportGenerator.COL_NOTES).getStringCellValue())
                    .isEqualTo("Notes");
        }
    }

    @Test
    void generate_reviewSheetHasDataRows() throws Exception {
        XlsxReportGenerator.ScanStats stats =
                new XlsxReportGenerator.ScanStats(2, 2, 1_000_000, "1.0 MB");
        generator.generate(stats);

        try (InputStream is = Files.newInputStream(config.getReportXlsxPath());
             Workbook wb   = new XSSFWorkbook(is)) {
            Sheet review = wb.getSheet("Review");
            // 2 groups → 2 data rows
            assertThat(review.getLastRowNum()).isEqualTo(2);
        }
    }

    @Test
    void generate_decisionDefaultsPending() throws Exception {
        XlsxReportGenerator.ScanStats stats =
                new XlsxReportGenerator.ScanStats(2, 2, 1_000_000, "1.0 MB");
        generator.generate(stats);

        try (InputStream is = Files.newInputStream(config.getReportXlsxPath());
             Workbook wb   = new XSSFWorkbook(is)) {
            Sheet review = wb.getSheet("Review");
            Row dataRow  = review.getRow(1);
            assertThat(dataRow.getCell(XlsxReportGenerator.COL_DECISION).getStringCellValue())
                    .isEqualTo(XlsxReportGenerator.DECISION_PENDING);
        }
    }

    @Test
    void parseCsvLine_simpleValues_splitCorrectly() {
        String[] cols = XlsxReportGenerator.parseCsvLine("1,abc,/path/file.vcf,1024,2024-01-01,true");
        assertThat(cols).containsExactly("1", "abc", "/path/file.vcf", "1024", "2024-01-01", "true");
    }

    @Test
    void parseCsvLine_quotedPathWithComma_parsedCorrectly() {
        String[] cols = XlsxReportGenerator.parseCsvLine("1,abc,\"/data/vcf, backup/file.vcf\",1024,date,true");
        assertThat(cols[2]).isEqualTo("/data/vcf, backup/file.vcf");
    }

    @Test
    void parseCsvLine_quotedPathWithEscapedQuote_parsedCorrectly() {
        String[] cols = XlsxReportGenerator.parseCsvLine("1,abc,\"/data/vcf\"\"name/file.vcf\",1024,date,true");
        assertThat(cols[2]).isEqualTo("/data/vcf\"name/file.vcf");
    }

    @Test
    void loadGroupsFromCsv_returnsCorrectGroups() throws Exception {
        List<DuplicateGroup> groups = generator.loadGroupsFromCsv(config.getReportCsvPath());
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).getGroupId()).isEqualTo(1);
        assertThat(groups.get(0).getFileCount()).isEqualTo(2);
        assertThat(groups.get(1).getGroupId()).isEqualTo(2);
    }

    @Test
    void loadGroupsFromCsv_originalPathIsFirst() throws Exception {
        List<DuplicateGroup> groups = generator.loadGroupsFromCsv(config.getReportCsvPath());
        assertThat(groups.get(0).getOriginalPath().toString())
                .isEqualTo("/data/vcf/patients/s1.vcf");
    }

    @Test
    void highPrioritySheet_sortedByWastedDesc() throws Exception {
        XlsxReportGenerator.ScanStats stats =
                new XlsxReportGenerator.ScanStats(2, 2, 1_000_000, "1.0 MB");
        generator.generate(stats);

        try (InputStream is = Files.newInputStream(config.getReportXlsxPath());
             Workbook wb   = new XSSFWorkbook(is)) {
            Sheet hp = wb.getSheet("High Priority");
            // Group 1 has larger size (2GB) so should be first after sort
            Row firstData = hp.getRow(1);
            assertThat(firstData.getCell(XlsxReportGenerator.COL_GROUP_ID).getStringCellValue())
                    .isEqualTo("1");
        }
    }
}
