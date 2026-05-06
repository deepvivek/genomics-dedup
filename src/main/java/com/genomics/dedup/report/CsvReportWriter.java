package com.genomics.dedup.report;

import com.genomics.dedup.config.ScanConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Streams duplicate groups to a CSV report.
 * API: open() → writeGroup() × N → close()
 * RAM: O(1) — one group in memory at a time.
 *
 * CSV columns:
 *   group_id, sha256, file_path, file_size_bytes, last_modified, is_original
 */
public class CsvReportWriter {

    private static final Logger log = LoggerFactory.getLogger(CsvReportWriter.class);

    static final String HEADER =
            "group_id,sha256,file_path,file_size_bytes,last_modified,is_original";

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(ZoneId.of("UTC"));

    private final ScanConfig   config;
    private BufferedWriter     writer;
    private long               totalGroups;
    private long               totalRows;

    public CsvReportWriter(ScanConfig config) { this.config = config; }

    public void open() throws IOException {
        Files.createDirectories(config.getOutputDir());
        writer      = Files.newBufferedWriter(config.getReportCsvPath(), StandardCharsets.UTF_8);
        totalGroups = 0;
        totalRows   = 0;
        writer.write(HEADER);
        writer.newLine();
        log.info("CSV report opened: {}", config.getReportCsvPath());
    }

    /**
     * Writes one duplicate group. Paths must be pre-sorted (index 0 = original).
     */
    public void writeGroup(DuplicateGroup group) throws IOException {
        if (writer == null) throw new IllegalStateException("open() must be called first");

        boolean first = true;
        for (Path path : group.getPaths()) {
            writer.write(buildRow(group.getGroupId(), group.getSha256(), path, first));
            writer.newLine();
            first = false;
            totalRows++;
        }
        totalGroups++;
        if (totalGroups % 100 == 0) writer.flush();
    }

    /** Closes the writer and returns total groups written. */
    public long close() throws IOException {
        if (writer != null) {
            writer.flush();
            writer.close();
            writer = null;
        }
        log.info("CSV report closed: {} groups, {} rows", totalGroups, totalRows);
        return totalGroups;
    }

    public long getTotalGroups() { return totalGroups; }
    public long getTotalRows()   { return totalRows; }

    // ── Private ───────────────────────────────────────────────────────────────

    private String buildRow(long groupId, String sha256, Path path, boolean isOriginal) {
        long   size         = 0;
        String lastModified = "unknown";
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            size        = attrs.size();
            lastModified = ISO.format(
                    Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis()));
        } catch (IOException e) {
            log.warn("Cannot read attributes for {}: {}", path, e.getMessage());
        }
        return String.join(",",
                String.valueOf(groupId),
                sha256,
                escapeCsv(path.toString()),
                String.valueOf(size),
                lastModified,
                String.valueOf(isOriginal));
    }

    static String escapeCsv(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
