package com.genomics.dedup.engine;

import com.genomics.dedup.cache.HashCache;
import com.genomics.dedup.config.ScanConfig;
import com.genomics.dedup.hash.FileHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2: Hash candidate files and stream results to disk.
 *
 * Memory model: O(1) RAM — hash results written immediately, never accumulated.
 *
 * Steps:
 *   2a. For each candidate: compute SHA-256 (cache-backed), write "hash|path" to temp
 *   2b. External sort by hash
 *   2c. Stream sorted file, yield groups of 2+ identical hashes
 *
 * Throttle: each thread sleeps throttleMs after each file.
 * Thread priority: MIN_PRIORITY+1 — yields to production workloads.
 */
public class Phase2Hasher {

    private static final Logger log = LoggerFactory.getLogger(Phase2Hasher.class);

    private static final int CACHE_COMMIT_INTERVAL = 1_000;
    private static final int PROGRESS_INTERVAL     = 10_000;
    private static final int SORT_MEMORY_MB        = 512;

    private final ScanConfig config;
    private final FileHasher  hasher;

    public Phase2Hasher(ScanConfig config) {
        this.config = config;
        this.hasher = new FileHasher(config.getReadBufferBytes());
    }

    /**
     * Hashes all candidates from walker, writes results to phase2_hashes.tmp.
     */
    public void hashAllCandidates(Phase1Walker walker, long totalCandidates)
            throws IOException, InterruptedException {

        log.info("Phase 2a: Hashing {} candidates ({} threads, {}ms throttle)",
                totalCandidates, config.getThreads(), config.getThrottleMs());

        AtomicLong processed = new AtomicLong();
        AtomicLong cacheHits = new AtomicLong();
        AtomicLong errors    = new AtomicLong();

        ExecutorService pool = buildPool();

        try (HashCache cache   = new HashCache(config.getHashCachePath());
             BufferedWriter bw = Files.newBufferedWriter(
                     config.getPhase2HashesTmp(), StandardCharsets.UTF_8)) {

            walker.streamCandidates(path -> pool.submit(() -> {
                try {
                    String hash = resolveHash(path, cache, cacheHits);
                    if (hash == null) return null;

                    synchronized (bw) {
                        bw.write(hash + "|" + path.toAbsolutePath());
                        bw.newLine();
                    }

                    long done = processed.incrementAndGet();

                    if (done % CACHE_COMMIT_INTERVAL == 0)
                        synchronized (cache) { cache.commit(); }

                    if (done % PROGRESS_INTERVAL == 0)
                        log.info("  Hashed {}/{} ({} cache hits, {} errors)",
                                done, totalCandidates, cacheHits.get(), errors.get());

                    if (config.getThrottleMs() > 0)
                        Thread.sleep(config.getThrottleMs());

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    errors.incrementAndGet();
                    log.warn("Hash failed (skipping): {} — {}", path, e.getMessage());
                }
                return null;
            }));

            pool.shutdown();
            if (!pool.awaitTermination(24, TimeUnit.HOURS))
                log.error("Hash phase timed out after 24h — partial results");

            cache.commit();
        }

        log.info("Phase 2a complete: {}/{} hashed, {} cache hits, {} errors",
                processed.get(), totalCandidates, cacheHits.get(), errors.get());
    }

    /** Sorts the hash temp file by SHA-256. */
    public void sort() throws IOException, InterruptedException {
        log.info("Phase 2b: Sorting by SHA-256...");
        ExternalSorter.sortInPlace(
                config.getPhase2HashesTmp(), "|", 1, false, SORT_MEMORY_MB);
        log.info("Phase 2b complete");
    }

    /**
     * Streams the sorted hash file, calling consumer for each duplicate group.
     * RAM: holds one group at a time (typically 2-5 paths).
     */
    public void streamDuplicateGroups(DuplicateGroupConsumer consumer) throws IOException {
        log.info("Phase 2c: Streaming duplicate groups...");
        long groupCount = 0;

        try (BufferedReader br = Files.newBufferedReader(
                config.getPhase2HashesTmp(), StandardCharsets.UTF_8)) {

            List<Path> currentGroup = new ArrayList<>();
            String currentHash = null;
            String line;

            while ((line = br.readLine()) != null) {
                int sep = line.indexOf('|');
                if (sep < 0) continue;
                String hash = line.substring(0, sep);
                Path   path = Path.of(line.substring(sep + 1));

                if (!hash.equals(currentHash)) {
                    if (currentGroup.size() >= 2) {
                        consumer.accept(currentHash, currentGroup);
                        groupCount++;
                    }
                    currentGroup = new ArrayList<>();
                    currentHash  = hash;
                }
                currentGroup.add(path);
            }
            // flush last group
            if (currentGroup.size() >= 2) {
                consumer.accept(currentHash, currentGroup);
                groupCount++;
            }
        }
        log.info("Phase 2c complete: {} duplicate groups", groupCount);
    }

    public void deleteTempFile() throws IOException {
        Files.deleteIfExists(config.getPhase2HashesTmp());
        log.info("Phase 2 temp file deleted");
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private String resolveHash(Path path, HashCache cache, AtomicLong cacheHits)
            throws IOException {
        long lastMod = path.toFile().lastModified();
        String cached = cache.get(path, lastMod);
        if (cached != null) { cacheHits.incrementAndGet(); return cached; }
        String hash = hasher.sha256(path);
        synchronized (cache) { cache.put(path, lastMod, hash); }
        return hash;
    }

    private ExecutorService buildPool() {
        return Executors.newFixedThreadPool(config.getThreads(), r -> {
            Thread t = new Thread(r, "vcf-hasher-" + System.nanoTime());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1);
            return t;
        });
    }

    @FunctionalInterface
    public interface DuplicateGroupConsumer {
        void accept(String sha256, List<Path> paths) throws IOException;
    }
}
