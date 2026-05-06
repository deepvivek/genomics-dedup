package com.genomics.dedup.engine;

import com.genomics.dedup.config.ScanConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 1: Walk directory tree, filter by size, write candidate checkpoint.
 *
 * Memory model: O(1) RAM — streams to disk at every step.
 *
 * Steps:
 *   1a. Walk tree → write "size|path" lines to phase1_walk.tmp
 *   1b. External sort by size (GNU sort, fixed memory)
 *   1c. Stream sorted file → write only size-groups with 2+ files to checkpoint
 *
 * Checkpoint survives restarts. If it exists on startup, Phase 1 is skipped.
 *
 * Supported extensions: configured via ScanConfig (default: vcf, bam, fastq, txt variants).
 */
public class Phase1Walker {

    private static final Logger log = LoggerFactory.getLogger(Phase1Walker.class);

    private static final int PROGRESS_INTERVAL = 100_000;
    private static final int SORT_MEMORY_MB    = 512;

    private final ScanConfig config;

    public Phase1Walker(ScanConfig config) {
        this.config = config;
    }

    public Path getCheckpointPath() { return config.getPhase1CheckpointPath(); }

    public boolean checkpointExists() {
        return Files.exists(config.getPhase1CheckpointPath());
    }

    /** Runs Phase 1: walk → sort → checkpoint. */
    public void run() throws IOException, InterruptedException {
        Files.createDirectories(config.getOutputDir());

        log.info("Phase 1a: Walking {}", config.getScanRoot());
        long total = walkToTemp();
        log.info("Phase 1a: {} files found", total);

        log.info("Phase 1b: Sorting by size...");
        ExternalSorter.sortInPlace(
                config.getPhase1WalkTmp(), "|", 1, true, SORT_MEMORY_MB);

        log.info("Phase 1c: Filtering unique sizes, writing checkpoint...");
        long candidates = filterAndWriteCheckpoint();
        log.info("Phase 1: {} candidates ({} skipped unique-size)",
                candidates, total - candidates);

        Files.deleteIfExists(config.getPhase1WalkTmp());
    }

    /**
     * Streams candidate paths from checkpoint, one at a time.
     * RAM: O(1).
     */
    public void streamCandidates(CheckedConsumer<Path> consumer) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(
                config.getPhase1CheckpointPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) consumer.accept(Path.of(line));
            }
        }
    }

    /** Counts lines in checkpoint for progress logging. */
    public long countCandidates() throws IOException {
        if (!checkpointExists()) return 0;
        try (BufferedReader br = Files.newBufferedReader(
                config.getPhase1CheckpointPath(), StandardCharsets.UTF_8)) {
            return br.lines().filter(l -> !l.isBlank()).count();
        }
    }

    public void deleteCheckpoint() throws IOException {
        Files.deleteIfExists(config.getPhase1CheckpointPath());
        log.info("Phase 1 checkpoint deleted");
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private long walkToTemp() throws IOException {
        AtomicLong count = new AtomicLong();
        try (BufferedWriter bw = Files.newBufferedWriter(
                config.getPhase1WalkTmp(), StandardCharsets.UTF_8)) {

            Files.walkFileTree(config.getScanRoot(), new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!isSupportedFile(file)) return FileVisitResult.CONTINUE;
                    try {
                        bw.write(attrs.size() + "|" + file.toAbsolutePath());
                        bw.newLine();
                        long n = count.incrementAndGet();
                        if (n % PROGRESS_INTERVAL == 0)
                            log.info("  Walk: {} files...", n);
                    } catch (IOException e) {
                        log.warn("Write failed for {}: {}", file, e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    log.warn("Cannot access (skipping): {} — {}", file, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return count.get();
    }

    private long filterAndWriteCheckpoint() throws IOException {
        long candidateCount = 0;
        try (BufferedReader br = Files.newBufferedReader(
                     config.getPhase1WalkTmp(), StandardCharsets.UTF_8);
             BufferedWriter bw = Files.newBufferedWriter(
                     config.getPhase1CheckpointPath(), StandardCharsets.UTF_8)) {

            List<String> currentGroup = new ArrayList<>();
            long currentSize = Long.MIN_VALUE;
            String line;

            while ((line = br.readLine()) != null) {
                int sep = line.indexOf('|');
                if (sep < 0) continue;
                long size = Long.parseLong(line.substring(0, sep));
                String path = line.substring(sep + 1);

                if (size != currentSize) {
                    candidateCount += flushGroup(currentGroup, bw);
                    currentGroup.clear();
                    currentSize = size;
                }
                currentGroup.add(path);
            }
            candidateCount += flushGroup(currentGroup, bw);
        }
        return candidateCount;
    }

    private long flushGroup(List<String> group, BufferedWriter bw) throws IOException {
        if (group.size() < 2) return 0;
        for (String p : group) { bw.write(p); bw.newLine(); }
        return group.size();
    }

    public boolean isSupportedFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return config.getExtensions().stream().anyMatch(name::endsWith);
    }

    @FunctionalInterface
    public interface CheckedConsumer<T> {
        void accept(T t) throws IOException;
    }
}
