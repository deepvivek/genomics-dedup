package com.genomics.dedup.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Delegates large-file sorting to GNU sort.
 *
 * GNU sort uses external merge-sort — fixed RAM regardless of input size.
 * Safe for hundreds of millions of lines with -S 512M buffer.
 */
public class ExternalSorter {

    private static final Logger log = LoggerFactory.getLogger(ExternalSorter.class);

    /**
     * Sorts a delimited file in-place by a specific field.
     *
     * @param file       file to sort (modified in place)
     * @param delimiter  field delimiter (e.g. "|")
     * @param fieldIndex 1-based field index to sort by
     * @param numeric    true for numeric sort, false for lexicographic
     * @param memoryMb   RAM buffer to allow the sort process
     */
    public static void sortInPlace(Path file, String delimiter, int fieldIndex,
                                   boolean numeric, int memoryMb)
            throws IOException, InterruptedException {

        String fieldSpec = fieldIndex + "," + fieldIndex + (numeric ? "n" : "");

        ProcessBuilder pb = new ProcessBuilder(
                "sort",
                "-t", delimiter,
                "-k", fieldSpec,
                "-S", memoryMb + "M",
                "--parallel=2",
                "-o", file.toString(),
                file.toString()
        );
        pb.inheritIO();

        log.info("External sort: {} (field={}, numeric={}, mem={}MB)",
                file.getFileName(), fieldIndex, numeric, memoryMb);

        Process proc = pb.start();
        int exit = proc.waitFor();
        if (exit != 0) {
            throw new IOException(
                "External sort failed (exit " + exit + ") on: " + file);
        }
        log.info("Sort complete: {}", file.getFileName());
    }
}
