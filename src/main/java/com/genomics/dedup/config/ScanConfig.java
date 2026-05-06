package com.genomics.dedup.config;

import java.nio.file.Path;
import java.util.List;

/**
 * Immutable configuration for a scan run.
 * All paths are derived from outputDir so nothing is scattered.
 */
public class ScanConfig {

    private final Path         scanRoot;
    private final Path         outputDir;
    private final int          threads;
    private final long         throttleMs;
    private final int          readBufferBytes;
    private final List<String> extensions;

    public static final List<String> DEFAULT_EXTENSIONS = List.of(
            ".vcf", ".vcf.gz", ".g.vcf", ".g.vcf.gz",
            ".bam", ".fastq", ".fastq.gz", ".txt"
    );

    private ScanConfig(Builder b) {
        this.scanRoot        = b.scanRoot;
        this.outputDir       = b.outputDir;
        this.threads         = b.threads;
        this.throttleMs      = b.throttleMs;
        this.readBufferBytes = b.readBufferBytes;
        this.extensions      = b.extensions;
    }

    // ── Derived paths — all outputs live under outputDir ─────────────────────

    public Path getReportCsvPath()       { return outputDir.resolve("report.csv"); }
    public Path getReportXlsxPath()      { return outputDir.resolve("review.xlsx"); }
    public Path getHashCachePath()       { return outputDir.resolve("hash_cache.db"); }
    public Path getPhase1WalkTmp()       { return outputDir.resolve("phase1_walk.tmp"); }
    public Path getPhase1CheckpointPath(){ return outputDir.resolve("phase1_checkpoint.txt"); }
    public Path getPhase2HashesTmp()     { return outputDir.resolve("phase2_hashes.tmp"); }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public Path         getScanRoot()        { return scanRoot; }
    public Path         getOutputDir()       { return outputDir; }
    public int          getThreads()         { return threads; }
    public long         getThrottleMs()      { return throttleMs; }
    public int          getReadBufferBytes() { return readBufferBytes; }
    public List<String> getExtensions()      { return extensions; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Path         scanRoot;
        private Path         outputDir;
        private int          threads         = 8;
        private long         throttleMs      = 50L;
        private int          readBufferBytes = 256 * 1024;
        private List<String> extensions      = DEFAULT_EXTENSIONS;

        public Builder scanRoot(Path v)         { scanRoot = v;        return this; }
        public Builder outputDir(Path v)        { outputDir = v;       return this; }
        public Builder threads(int v)           { threads = v;         return this; }
        public Builder throttleMs(long v)       { throttleMs = v;      return this; }
        public Builder readBufferBytes(int v)   { readBufferBytes = v; return this; }
        public Builder extensions(List<String> v){ extensions = v;     return this; }

        public ScanConfig build() {
            if (scanRoot == null)  throw new IllegalStateException("scanRoot is required");
            if (outputDir == null) throw new IllegalStateException("outputDir is required");
            if (threads < 1 || threads > 20)
                throw new IllegalArgumentException("threads must be between 1 and 20");
            if (throttleMs < 0)
                throw new IllegalArgumentException("throttleMs must be >= 0");
            return new ScanConfig(this);
        }
    }
}
