package com.genomics.dedup.report;

import com.genomics.dedup.config.ScanConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class CsvReportWriterTest {

    private ScanConfig config;
    private CsvReportWriter writer;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        config = ScanConfig.builder()
                .scanRoot(tmp.resolve("vcf"))
                .outputDir(tmp.resolve("out"))
                .build();
        writer = new CsvReportWriter(config);
    }

    @Test
    void writeGroup_singleGroup_correctCsvStructure(@TempDir Path tmp) throws Exception {
        writer.open();
        List<Path> paths = List.of(
                tmp.resolve("a.vcf"),
                tmp.resolve("b.vcf"));
        DuplicateGroup group = new DuplicateGroup(1, "abc123".repeat(10) + "abcd", paths, 1024);
        writer.writeGroup(group);
        writer.close();

        List<String> lines = Files.readAllLines(config.getReportCsvPath());
        assertThat(lines).hasSize(3); // header + 2 files
        assertThat(lines.get(0)).isEqualTo(CsvReportWriter.HEADER);
        assertThat(lines.get(1)).contains("1,");       // group_id
        assertThat(lines.get(1)).contains(",true");    // is_original
        assertThat(lines.get(2)).contains(",false");   // duplicate
    }

    @Test
    void writeGroup_firstPathMarkedOriginal(@TempDir Path tmp) throws Exception {
        writer.open();
        List<Path> paths = List.of(
                tmp.resolve("alpha.vcf"),
                tmp.resolve("beta.vcf"),
                tmp.resolve("gamma.vcf"));
        DuplicateGroup group = new DuplicateGroup(1, "x".repeat(64), paths, 500);
        writer.writeGroup(group);
        writer.close();

        List<String> lines = Files.readAllLines(config.getReportCsvPath());
        assertThat(lines.get(1)).endsWith("true");
        assertThat(lines.get(2)).endsWith("false");
        assertThat(lines.get(3)).endsWith("false");
    }

    @Test
    void writeGroup_multipleGroups_allWritten(@TempDir Path tmp) throws Exception {
        writer.open();
        for (int i = 1; i <= 5; i++) {
            List<Path> paths = List.of(
                    tmp.resolve("g" + i + "_a.vcf"),
                    tmp.resolve("g" + i + "_b.vcf"));
            writer.writeGroup(new DuplicateGroup(i, "h".repeat(64), paths, 100));
        }
        long total = writer.close();

        assertThat(total).isEqualTo(5);
        List<String> lines = Files.readAllLines(config.getReportCsvPath());
        assertThat(lines).hasSize(11); // header + 5 groups * 2 files
    }

    @Test
    void writeGroup_beforeOpen_throwsException(@TempDir Path tmp) {
        List<Path> paths = List.of(tmp.resolve("a.vcf"), tmp.resolve("b.vcf"));
        DuplicateGroup group = new DuplicateGroup(1, "x".repeat(64), paths, 100);
        assertThatThrownBy(() -> writer.writeGroup(group))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("open()");
    }

    @Test
    void escapeCsv_pathWithComma_properlyCsvEscaped() {
        assertThat(CsvReportWriter.escapeCsv("/data/vcf, backup/file.vcf"))
                .isEqualTo("\"/data/vcf, backup/file.vcf\"");
    }

    @Test
    void escapeCsv_pathWithQuote_doubledInOutput() {
        assertThat(CsvReportWriter.escapeCsv("/data/vcf\"name/file.vcf"))
                .isEqualTo("\"/data/vcf\"\"name/file.vcf\"");
    }

    @Test
    void escapeCsv_normalPath_wrappedInQuotes() {
        assertThat(CsvReportWriter.escapeCsv("/data/vcf/file.vcf"))
                .isEqualTo("\"/data/vcf/file.vcf\"");
    }

    @Test
    void close_withoutOpen_doesNotThrow() {
        assertThatCode(() -> writer.close()).doesNotThrowAnyException();
    }

    @Test
    void getTotalGroups_afterWrite_correctCount(@TempDir Path tmp) throws Exception {
        writer.open();
        List<Path> paths = List.of(tmp.resolve("a.vcf"), tmp.resolve("b.vcf"));
        writer.writeGroup(new DuplicateGroup(1, "x".repeat(64), paths, 100));
        writer.writeGroup(new DuplicateGroup(2, "y".repeat(64), paths, 200));
        writer.close();
        assertThat(writer.getTotalGroups()).isEqualTo(2);
    }
}
