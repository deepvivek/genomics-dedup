package com.genomics.dedup;

import com.genomics.dedup.config.ScanConfig;
import com.genomics.dedup.engine.FileScanEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * VCF Duplicate Detector — main entry point.
 *
 * Usage:
 *   java -Xmx2g -jar vcf-dedup.jar <scanDir> <outputDir> [threads] [throttleMs] [extensions]
 *
 * Outputs:
 *   <outputDir>/report.csv        — raw duplicate data
 *   <outputDir>/review.xlsx       — Excel workbook for manual review
 *   <outputDir>/hash_cache.db     — persistent hash cache (resume support)
 *
 * Arguments:
 *   arg[0] = root directory to scan (required)
 *   arg[1] = output directory (required)
 *   arg[2] = hashing threads (optional, default 8, max 20)
 *   arg[3] = throttle ms per file per thread (optional, default 50)
 *   arg[4] = comma-separated extensions (optional, e.g. ".vcf,.bam,.fastq")
 *            default: .vcf .vcf.gz .g.vcf .g.vcf.gz .bam .fastq .fastq.gz .txt
 */
public class VcfDuplicateDetector {

    private static final Logger log = LoggerFactory.getLogger(VcfDuplicateDetector.class);

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: vcf-dedup <scanDir> <outputDir> [threads] [throttleMs] [extensions]");
            System.err.println("Example: java -Xmx2g -jar vcf-dedup.jar /data/vcf /data/reports 8 50 .vcf,.bam,.fastq");
            System.exit(1);
        }

        Path         scanDir    = Paths.get(args[0]);
        Path         outputDir  = Paths.get(args[1]);
        int          threads    = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        long         throttle   = args.length > 3 ? Long.parseLong(args[3])   : 50L;
        List<String> extensions = args.length > 4
                ? Arrays.asList(args[4].split(","))
                : ScanConfig.DEFAULT_EXTENSIONS;

        ScanConfig config = ScanConfig.builder()
                .scanRoot(scanDir)
                .outputDir(outputDir)
                .threads(threads)
                .throttleMs(throttle)
                .extensions(extensions)
                .build();

        log.info("╔══════════════════════════════════════╗");
        log.info("║     VCF Duplicate Detector v2.0      ║");
        log.info("╚══════════════════════════════════════╝");
        log.info("Scan root  : {}", config.getScanRoot());
        log.info("Output dir : {}", config.getOutputDir());
        log.info("CSV report : {}", config.getReportCsvPath());
        log.info("XLSX report: {}", config.getReportXlsxPath());
        log.info("Threads    : {}", config.getThreads());
        log.info("Throttle   : {}ms per file per thread", config.getThrottleMs());
        log.info("Extensions : {}", config.getExtensions());
        log.info("RAM model  : O(1) streaming pipeline");

        Instant start = Instant.now();

        FileScanEngine engine = new FileScanEngine(config);
        FileScanEngine.ScanResult result = engine.scan();

        Duration elapsed = Duration.between(start, Instant.now());
        log.info("╔══════════════════════════════════════╗");
        log.info("║           Scan Complete              ║");
        log.info("╚══════════════════════════════════════╝");
        log.info("Duplicate groups   : {}", result.getDuplicateGroups());
        log.info("Redundant files    : {}", result.getRedundantFiles());
        log.info("Wasted space       : {}", result.getWastedSpaceHuman());
        log.info("CSV report         : {}", config.getReportCsvPath());
        log.info("XLSX review file   : {}", config.getReportXlsxPath());
        log.info("Total time         : {}m {}s",
                elapsed.toMinutes(), elapsed.toSecondsPart());
    }
}
