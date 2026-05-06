package com.genomics.dedup.engine;

import com.genomics.dedup.config.ScanConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class Phase1WalkerTest {

    private ScanConfig config;
    private Phase1Walker walker;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        config = ScanConfig.builder()
                .scanRoot(tmp.resolve("vcf"))
                .outputDir(tmp.resolve("out"))
                .build();
        walker = new Phase1Walker(config);
    }

    private void createVcf(Path dir, String name, String content) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), content);
    }

    @Test
    void run_noFiles_producesEmptyCheckpoint(@TempDir Path tmp) throws Exception {
        Files.createDirectories(config.getScanRoot());
        walker.run();
        assertThat(walker.countCandidates()).isEqualTo(0);
    }

    @Test
    void run_allUniqueSizes_noCheckpointEntries(@TempDir Path tmp) throws Exception {
        Path vcfDir = config.getScanRoot();
        createVcf(vcfDir, "a.vcf", "content1");
        createVcf(vcfDir, "b.vcf", "content22");
        createVcf(vcfDir, "c.vcf", "content333");
        walker.run();
        // All different sizes → none are candidates
        assertThat(walker.countCandidates()).isEqualTo(0);
    }

    @Test
    void run_twoFilesWithSameSize_bothInCheckpoint(@TempDir Path tmp) throws Exception {
        Path vcfDir = config.getScanRoot();
        createVcf(vcfDir, "a.vcf", "same_content");
        createVcf(vcfDir, "b.vcf", "same_content");
        walker.run();
        assertThat(walker.countCandidates()).isEqualTo(2);
    }

    @Test
    void run_threeFilesWithSameSize_allInCheckpoint(@TempDir Path tmp) throws Exception {
        Path vcfDir = config.getScanRoot();
        createVcf(vcfDir, "a.vcf", "identical");
        createVcf(vcfDir, "b.vcf", "identical");
        createVcf(vcfDir, "c.vcf", "identical");
        walker.run();
        assertThat(walker.countCandidates()).isEqualTo(3);
    }

    @Test
    void run_mixedSizes_onlyDuplicateSizeGroupsKept(@TempDir Path tmp) throws Exception {
        Path vcfDir = config.getScanRoot();
        createVcf(vcfDir, "a.vcf", "same");    // size 4 — group of 2
        createVcf(vcfDir, "b.vcf", "same");    // size 4 — group of 2
        createVcf(vcfDir, "c.vcf", "unique!");  // size 7 — unique, skipped
        walker.run();
        assertThat(walker.countCandidates()).isEqualTo(2);
    }

    @Test
    void run_unsupportedFilesIgnored(@TempDir Path tmp) throws Exception {
        Path vcfDir = config.getScanRoot();
        Files.createDirectories(vcfDir);
        Files.writeString(vcfDir.resolve("data.pdf"), "same");
        Files.writeString(vcfDir.resolve("data.png"), "same");
        Files.writeString(vcfDir.resolve("data.docx"), "same");
        walker.run();
        assertThat(walker.countCandidates()).isEqualTo(0);
    }

    @Test
    void run_vcfGzExtension_detected(@TempDir Path tmp) throws Exception {
        Path vcfDir = config.getScanRoot();
        createVcf(vcfDir, "a.vcf.gz", "compressed_content");
        createVcf(vcfDir, "b.vcf.gz", "compressed_content");
        walker.run();
        assertThat(walker.countCandidates()).isEqualTo(2);
    }

    @Test
    void run_recursiveSubdirectories_allFound(@TempDir Path tmp) throws Exception {
        Path vcfDir = config.getScanRoot();
        createVcf(vcfDir.resolve("batch1"), "a.vcf", "content");
        createVcf(vcfDir.resolve("batch2/sub"), "b.vcf", "content");
        walker.run();
        assertThat(walker.countCandidates()).isEqualTo(2);
    }

    @Test
    void checkpointExists_afterRun_returnsTrue(@TempDir Path tmp) throws Exception {
        Files.createDirectories(config.getScanRoot());
        walker.run();
        assertThat(walker.checkpointExists()).isTrue();
    }

    @Test
    void checkpointExists_beforeRun_returnsFalse() {
        assertThat(walker.checkpointExists()).isFalse();
    }

    @Test
    void streamCandidates_returnsCorrectPaths(@TempDir Path tmp) throws Exception {
        Path vcfDir = config.getScanRoot();
        createVcf(vcfDir, "a.vcf", "same");
        createVcf(vcfDir, "b.vcf", "same");
        walker.run();

        List<Path> collected = new ArrayList<>();
        walker.streamCandidates(collected::add);
        assertThat(collected).hasSize(2);
    }

    @Test
    void deleteCheckpoint_removesFile(@TempDir Path tmp) throws Exception {
        Files.createDirectories(config.getScanRoot());
        walker.run();
        assertThat(walker.checkpointExists()).isTrue();
        walker.deleteCheckpoint();
        assertThat(walker.checkpointExists()).isFalse();
    }

    @Test
    void isSupportedFile_defaultExtensions() {
        assertThat(walker.isSupportedFile(Path.of("sample.vcf"))).isTrue();
        assertThat(walker.isSupportedFile(Path.of("sample.vcf.gz"))).isTrue();
        assertThat(walker.isSupportedFile(Path.of("sample.g.vcf"))).isTrue();
        assertThat(walker.isSupportedFile(Path.of("sample.g.vcf.gz"))).isTrue();
        assertThat(walker.isSupportedFile(Path.of("sample.VCF"))).isTrue(); // case insensitive
        assertThat(walker.isSupportedFile(Path.of("sample.bam"))).isTrue();
        assertThat(walker.isSupportedFile(Path.of("sample.txt"))).isTrue();
        assertThat(walker.isSupportedFile(Path.of("sample.fastq"))).isTrue();
        assertThat(walker.isSupportedFile(Path.of("sample.fastq.gz"))).isTrue();
        assertThat(walker.isSupportedFile(Path.of("sample.pdf"))).isFalse();
        assertThat(walker.isSupportedFile(Path.of("sample.png"))).isFalse();
    }
}
