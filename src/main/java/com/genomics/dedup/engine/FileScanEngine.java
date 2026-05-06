package com.genomics.dedup.engine;

import com.genomics.dedup.config.ScanConfig;
import com.genomics.dedup.report.CsvReportWriter;
import com.genomics.dedup.report.DuplicateGroup;
import com.genomics.dedup.xlsx.XlsxReportGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates the full pipeline:
 *   Phase 1 (walk) → Phase 2 (hash) → CSV report → XLSX report
 *
 * Memory model: O(1) RAM throughout — no full-dataset in-memory maps.
 *
 * Restart behaviour:
 *   - phase1_checkpoint.txt exists → skip Phase 1
 *   - hash_cache.db → skip already-hashed files in Phase 2
 *   - Checkpoint deleted only after CSV is fully written
 */
public class FileScanEngine {

    private static final Logger log = LoggerFactory.getLogger(FileScanEngine.class);

    private final ScanConfig      config;
    private final Phase1Walker    walker;
    private final Phase2Hasher    hasher;
    private final CsvReportWriter csvWriter;
    private final XlsxReportGenerator xlsxGenerator;

    public FileScanEngine(ScanConfig config) {
        this.config        = config;
        this.walker        = new Phase1Walker(config);
        this.hasher        = new Phase2Hasher(config);
        this.csvWriter     = new CsvReportWriter(config);
        this.xlsxGenerator = new XlsxReportGenerator(config);
    }

    /**
     * Runs the full pipeline.
     * @return ScanResult with summary statistics
     */
    public ScanResult scan() throws IOException, InterruptedException {
        Files.createDirectories(config.getOutputDir());

        // ── Phase 1 ───────────────────────────────────────────────────────────
        if (walker.checkpointExists()) {
            log.info("Phase 1 checkpoint found — skipping walk (resuming from restart)");
        } else {
            walker.run();
        }

        long totalCandidates = walker.countCandidates();
        log.info("Candidates to hash: {}", totalCandidates);

        if (totalCandidates == 0) {
            log.info("No candidates — no duplicates possible.");
            walker.deleteCheckpoint();
            return new ScanResult(0, 0, 0);
        }

        // ── Phase 2 ───────────────────────────────────────────────────────────
        hasher.hashAllCandidates(walker, totalCandidates);
        hasher.sort();

        // ── CSV report ────────────────────────────────────────────────────────
        log.info("Writing CSV report...");
        csvWriter.open();

        AtomicLong groupCounter     = new AtomicLong(0);
        AtomicLong redundantFiles   = new AtomicLong(0);
        AtomicLong wastedBytes      = new AtomicLong(0);

        hasher.streamDuplicateGroups((sha256, paths) -> {
            // Sort paths alphabetically; first = "original"
            List<Path> sorted = new ArrayList<>(paths);
            sorted.sort(Path::compareTo);

            // Get size from first file (all equal)
            long size = 0;
            try { size = Files.size(sorted.get(0)); } catch (IOException ignored) {}

            long groupId = groupCounter.incrementAndGet();
            DuplicateGroup group = new DuplicateGroup(groupId, sha256, sorted, size);

            csvWriter.writeGroup(group);
            redundantFiles.addAndGet(paths.size() - 1);
            wastedBytes.addAndGet(group.getWastedBytes());
        });

        long totalGroups = csvWriter.close();

        // Cleanup Phase 2 temp + Phase 1 checkpoint only after CSV is safely written
        hasher.deleteTempFile();
        walker.deleteCheckpoint();

        log.info("CSV complete: {} groups, {} redundant files, {} wasted",
                totalGroups, redundantFiles.get(),
                DuplicateGroup.humanSize(wastedBytes.get()));

        // ── XLSX report ───────────────────────────────────────────────────────
        XlsxReportGenerator.ScanStats stats = new XlsxReportGenerator.ScanStats(
                totalGroups,
                redundantFiles.get(),
                wastedBytes.get(),
                DuplicateGroup.humanSize(wastedBytes.get()));

        xlsxGenerator.generate(stats);

        return new ScanResult(totalGroups, redundantFiles.get(), wastedBytes.get());
    }

    // ── Result type ───────────────────────────────────────────────────────────

    public static class ScanResult {
        private final long duplicateGroups;
        private final long redundantFiles;
        private final long wastedBytes;

        public ScanResult(long duplicateGroups, long redundantFiles, long wastedBytes) {
            this.duplicateGroups = duplicateGroups;
            this.redundantFiles  = redundantFiles;
            this.wastedBytes     = wastedBytes;
        }

        public long   getDuplicateGroups()    { return duplicateGroups; }
        public long   getRedundantFiles()     { return redundantFiles; }
        public long   getWastedBytes()        { return wastedBytes; }
        public String getWastedSpaceHuman()   { return DuplicateGroup.humanSize(wastedBytes); }
    }
}
