package com.genomics.dedup.engine;

import com.genomics.dedup.config.ScanConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end integration tests for FileScanEngine.
 * Requires Linux (for GNU sort).
 */
@EnabledOnOs(OS.LINUX)
class FileScanEngineIntegrationTest {

    private ScanConfig config;
    private FileScanEngine engine;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        config = ScanConfig.builder()
                .scanRoot(tmp.resolve("vcf"))
                .outputDir(tmp.resolve("out"))
                .threads(2)
                .throttleMs(0)  // no throttle in tests
                .build();
        engine = new FileScanEngine(config);
    }

    private Path createVcf(Path dir, String name, String content) throws Exception {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    @Test
    void scan_noDuplicates_returnsZeroGroups(@TempDir Path tmp) throws Exception {
        Path vcf = config.getScanRoot();
        createVcf(vcf, "a.vcf", "unique content A");
        createVcf(vcf, "b.vcf", "unique content BB");
        createVcf(vcf, "c.vcf", "unique content CCC");

        FileScanEngine.ScanResult result = engine.scan();
        assertThat(result.getDuplicateGroups()).isEqualTo(0);
        assertThat(result.getRedundantFiles()).isEqualTo(0);
    }

    @Test
    void scan_exactDuplicate_foundAndReported(@TempDir Path tmp) throws Exception {
        Path vcf = config.getScanRoot();
        createVcf(vcf, "original.vcf",  "##fileformat=VCFv4.2\n");
        createVcf(vcf, "duplicate.vcf", "##fileformat=VCFv4.2\n");

        FileScanEngine.ScanResult result = engine.scan();
        assertThat(result.getDuplicateGroups()).isEqualTo(1);
        assertThat(result.getRedundantFiles()).isEqualTo(1);
    }

    @Test
    void scan_threeDuplicates_oneGroupTwoRedundant(@TempDir Path tmp) throws Exception {
        Path vcf = config.getScanRoot();
        String content = "##VCF4.2\nCHR1\t100\t.\tA\tT\t100\n";
        createVcf(vcf, "a.vcf", content);
        createVcf(vcf, "b.vcf", content);
        createVcf(vcf, "c.vcf", content);

        FileScanEngine.ScanResult result = engine.scan();
        assertThat(result.getDuplicateGroups()).isEqualTo(1);
        assertThat(result.getRedundantFiles()).isEqualTo(2);
    }

    @Test
    void scan_multipleDuplicateGroups_allFound(@TempDir Path tmp) throws Exception {
        Path vcf = config.getScanRoot();
        createVcf(vcf, "g1a.vcf", "group one content");
        createVcf(vcf, "g1b.vcf", "group one content");
        createVcf(vcf, "g2a.vcf", "group two content!");
        createVcf(vcf, "g2b.vcf", "group two content!");
        createVcf(vcf, "solo.vcf", "unique solo file");

        FileScanEngine.ScanResult result = engine.scan();
        assertThat(result.getDuplicateGroups()).isEqualTo(2);
        assertThat(result.getRedundantFiles()).isEqualTo(2);
    }

    @Test
    void scan_csvReportCreated(@TempDir Path tmp) throws Exception {
        Path vcf = config.getScanRoot();
        createVcf(vcf, "a.vcf", "same");
        createVcf(vcf, "b.vcf", "same");

        engine.scan();
        assertThat(config.getReportCsvPath()).exists();
        assertThat(Files.size(config.getReportCsvPath())).isGreaterThan(0);
    }

    @Test
    void scan_xlsxReportCreated(@TempDir Path tmp) throws Exception {
        Path vcf = config.getScanRoot();
        createVcf(vcf, "a.vcf", "same content");
        createVcf(vcf, "b.vcf", "same content");

        engine.scan();
        assertThat(config.getReportXlsxPath()).exists();
        assertThat(Files.size(config.getReportXlsxPath())).isGreaterThan(5000);
    }

    @Test
    void scan_nestedDirectories_duplicatesFoundAcrossDirs(@TempDir Path tmp) throws Exception {
        Path vcf = config.getScanRoot();
        createVcf(vcf.resolve("patients/batch1"), "sample.vcf", "patient data");
        createVcf(vcf.resolve("backup/2024"),     "sample.vcf", "patient data");

        FileScanEngine.ScanResult result = engine.scan();
        assertThat(result.getDuplicateGroups()).isEqualTo(1);
    }

    @Test
    void scan_tempFilesCleanedUp(@TempDir Path tmp) throws Exception {
        Path vcf = config.getScanRoot();
        createVcf(vcf, "a.vcf", "same");
        createVcf(vcf, "b.vcf", "same");

        engine.scan();

        // Temp files must be gone after successful completion
        assertThat(config.getPhase1WalkTmp()).doesNotExist();
        assertThat(config.getPhase1CheckpointPath()).doesNotExist();
        assertThat(config.getPhase2HashesTmp()).doesNotExist();
        // Cache and outputs should remain
        assertThat(config.getHashCachePath()).exists();
        assertThat(config.getReportCsvPath()).exists();
        assertThat(config.getReportXlsxPath()).exists();
    }

    @Test
    void scan_wastedBytesCalculatedCorrectly(@TempDir Path tmp) throws Exception {
        Path vcf = config.getScanRoot();
        String content = "fixed content body"; // known size
        createVcf(vcf, "a.vcf", content);
        createVcf(vcf, "b.vcf", content);
        createVcf(vcf, "c.vcf", content);

        FileScanEngine.ScanResult result = engine.scan();
        long expectedWaste = Files.size(vcf.resolve("a.vcf")) * 2; // 2 redundant copies
        assertThat(result.getWastedBytes()).isEqualTo(expectedWaste);
    }

    @Test
    void scan_emptyDirectory_noError(@TempDir Path tmp) throws Exception {
        Files.createDirectories(config.getScanRoot());
        FileScanEngine.ScanResult result = engine.scan();
        assertThat(result.getDuplicateGroups()).isEqualTo(0);
    }

    @Test
    void scan_sameSizeDifferentContent_notDuplicates(@TempDir Path tmp) throws Exception {
        Path vcf = config.getScanRoot();
        // Same byte length, different content
        createVcf(vcf, "a.vcf", "AAAA");
        createVcf(vcf, "b.vcf", "BBBB");

        FileScanEngine.ScanResult result = engine.scan();
        assertThat(result.getDuplicateGroups()).isEqualTo(0);
    }
}
