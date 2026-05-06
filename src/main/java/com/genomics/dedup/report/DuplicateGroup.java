package com.genomics.dedup.report;

import java.nio.file.Path;
import java.util.List;

/**
 * Value object representing one group of duplicate files.
 * All files in a group are byte-for-byte identical (same SHA-256).
 */
public class DuplicateGroup {

    private final long       groupId;
    private final String     sha256;
    private final List<Path> paths;      // sorted alphabetically; index 0 = "original"
    private final long       sizeBytes;  // size of each file (all equal)

    public DuplicateGroup(long groupId, String sha256, List<Path> paths, long sizeBytes) {
        this.groupId   = groupId;
        this.sha256    = sha256;
        this.paths     = List.copyOf(paths);
        this.sizeBytes = sizeBytes;
    }

    public long       getGroupId()      { return groupId; }
    public String     getSha256()       { return sha256; }
    public String     getSha256Short()  { return sha256.substring(0, 12) + "..."; }
    public List<Path> getPaths()        { return paths; }
    public Path       getOriginalPath() { return paths.get(0); }
    public List<Path> getDuplicates()   { return paths.subList(1, paths.size()); }
    public long       getSizeBytes()    { return sizeBytes; }
    public int        getFileCount()    { return paths.size(); }

    /** Bytes wasted = (copies - 1) * size */
    public long getWastedBytes() { return (long)(paths.size() - 1) * sizeBytes; }

    /**
     * Returns true if duplicate paths span different top-level directories
     * relative to scanRoot — these are most likely accidental duplicates.
     */
    public boolean isSpanningDirectories(Path scanRoot) {
        if (paths.size() < 2) return false;
        String firstTop = topLevelDir(paths.get(0), scanRoot);
        return paths.stream()
                .skip(1)
                .anyMatch(p -> !topLevelDir(p, scanRoot).equals(firstTop));
    }

    private String topLevelDir(Path path, Path scanRoot) {
        try {
            Path rel = scanRoot.relativize(path);
            return rel.getNameCount() > 1 ? rel.getName(0).toString() : "";
        } catch (Exception e) {
            return path.toString();
        }
    }

    public static String humanSize(long bytes) {
        if (bytes >= 1_099_511_627_776L)
            return String.format("%.1f TB", bytes / 1_099_511_627_776.0);
        if (bytes >= 1_073_741_824L)
            return String.format("%.1f GB", bytes / 1_073_741_824.0);
        if (bytes >= 1_048_576L)
            return String.format("%.1f MB", bytes / 1_048_576.0);
        if (bytes >= 1_024L)
            return String.format("%.1f KB", bytes / 1_024.0);
        return bytes + " B";
    }
}
