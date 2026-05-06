package com.genomics.dedup.xlsx;

import com.genomics.dedup.config.ScanConfig;
import com.genomics.dedup.report.DuplicateGroup;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Generates a multi-sheet Excel (.xlsx) workbook for manual duplicate review.
 *
 * Sheets:
 *   1. Summary      — scan statistics + live formula counters
 *   2. Review       — one row per group, decision dropdown, reviewer, notes
 *   3. High Priority — top 500 groups by wasted space
 *   4. Suspicious   — groups spanning different top-level directories
 *   5. Raw Data     — original CSV data
 */
public class XlsxReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(XlsxReportGenerator.class);

    static final String DECISION_PENDING  = "Pending";
    static final String DECISION_ARCHIVE  = "Archive duplicates";
    static final String DECISION_KEEP     = "Keep all";
    static final String DECISION_ESCALATE = "Escalate";

    static final int COL_GROUP_ID    = 0;
    static final int COL_HASH_SHORT  = 1;
    static final int COL_FILE_COUNT  = 2;
    static final int COL_SIZE_EACH   = 3;
    static final int COL_WASTED      = 4;
    static final int COL_ORIGINAL    = 5;
    static final int COL_DUPLICATES  = 6;
    static final int COL_DECISION    = 7;
    static final int COL_REVIEWER    = 8;
    static final int COL_NOTES       = 9;
    static final int TOTAL_COLS      = 10;

    // Excel column letter for COL_DECISION (H = index 7)
    private static final String DECISION_COL_LETTER = "H";

    private final ScanConfig config;

    public XlsxReportGenerator(ScanConfig config) {
        this.config = config;
    }

    public void generate(ScanStats scanStats) throws IOException {
        log.info("Generating XLSX review workbook...");
        List<DuplicateGroup> allGroups = loadGroupsFromCsv(config.getReportCsvPath());
        log.info("Loaded {} duplicate groups from CSV", allGroups.size());

        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XlsxStyles styles = new XlsxStyles(wb);

            buildSummarySheet(wb, styles, scanStats, allGroups.size());
            buildReviewSheet(wb, styles, allGroups, "Review");
            buildHighPrioritySheet(wb, styles, allGroups);
            buildSuspiciousSheet(wb, styles, allGroups);
            buildRawDataSheet(wb, styles);

            Files.createDirectories(config.getOutputDir());
            try (OutputStream os = Files.newOutputStream(config.getReportXlsxPath())) {
                wb.write(os);
            }
        }
        log.info("XLSX written: {}", config.getReportXlsxPath());
    }

    // ── Sheet 1: Summary ─────────────────────────────────────────────────────

    private void buildSummarySheet(XSSFWorkbook wb, XlsxStyles styles,
                                   ScanStats stats, int groupCount) {
        XSSFSheet sheet = wb.createSheet("Summary");
        sheet.setColumnWidth(0, 38 * 256);
        sheet.setColumnWidth(1, 30 * 256);

        int row = 0;
        Row titleRow = sheet.createRow(row++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("VCF Duplicate Detection Report");
        titleCell.setCellStyle(styles.title);
        row++;

        row = addSummaryRow(sheet, styles, row, "Scan root",   config.getScanRoot().toString());
        row = addSummaryRow(sheet, styles, row, "Scan date",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        row = addSummaryRow(sheet, styles, row, "Duplicate groups",   String.valueOf(groupCount));
        row = addSummaryRow(sheet, styles, row, "Redundant files",    String.valueOf(stats.redundantFiles()));
        row = addSummaryRow(sheet, styles, row, "Total wasted space", stats.wastedSpaceHuman());
        row++;

        Row lh = sheet.createRow(row++);
        Cell lhc = lh.createCell(0);
        lhc.setCellValue("Review progress (updates live as team fills in decisions)");
        lhc.setCellStyle(styles.sectionHeader);

        String dc = DECISION_COL_LETTER;
        row = addSummaryRow(sheet, styles, row, "Groups reviewed",
                "=COUNTA(Review!" + dc + "2:" + dc + "10000)-COUNTIF(Review!" + dc + "2:" + dc + "10000,\"Pending\")");
        row = addSummaryRow(sheet, styles, row, "Marked for archive",
                "=COUNTIF(Review!" + dc + "2:" + dc + "10000,\"Archive duplicates\")");
        row = addSummaryRow(sheet, styles, row, "Marked keep all",
                "=COUNTIF(Review!" + dc + "2:" + dc + "10000,\"Keep all\")");
        row = addSummaryRow(sheet, styles, row, "Escalated",
                "=COUNTIF(Review!" + dc + "2:" + dc + "10000,\"Escalate\")");
        row = addSummaryRow(sheet, styles, row, "Pending",
                "=COUNTIF(Review!" + dc + "2:" + dc + "10000,\"Pending\")");
        row++;

        Row ih = sheet.createRow(row++);
        Cell ihc = ih.createCell(0);
        ihc.setCellValue("Instructions");
        ihc.setCellStyle(styles.sectionHeader);

        String[] instructions = {
            "1. Start with 'High Priority' sheet — largest wasted space first.",
            "2. Set the Decision dropdown in column L for each group.",
            "3. Enter your name in the Reviewer column (column M).",
            "4. If unsure — set Escalate and add a Note.",
            "5. 'Original (keep)' = suggested file to keep (alphabetically first path).",
            "6. NO files are modified by this tool. All decisions here are advisory only.",
            "7. Filter column L by 'Archive duplicates' to get the final action list."
        };
        for (String instr : instructions) {
            Row r = sheet.createRow(row++);
            Cell c = r.createCell(0);
            c.setCellValue(instr);
            c.setCellStyle(styles.instruction);
        }
    }

    private int addSummaryRow(XSSFSheet sheet, XlsxStyles styles,
                              int rowIdx, String label, String value) {
        Row row = sheet.createRow(rowIdx);
        Cell lbl = row.createCell(0);
        lbl.setCellValue(label);
        lbl.setCellStyle(styles.label);
        Cell val = row.createCell(1);
        // Try as formula first, fallback to string
        if (value.startsWith("=")) {
            try { val.setCellFormula(value.substring(1)); }
            catch (Exception e) { val.setCellValue(value); }
            val.setCellStyle(styles.formulaValue);
        } else {
            val.setCellValue(value);
            val.setCellStyle(styles.value);
        }
        return rowIdx + 1;
    }

    // ── Review sheets ─────────────────────────────────────────────────────────

    private void buildReviewSheet(XSSFWorkbook wb, XlsxStyles styles,
                                  List<DuplicateGroup> groups, String sheetName) {
        XSSFSheet sheet = wb.createSheet(sheetName);

        sheet.setColumnWidth(COL_GROUP_ID,   10 * 256);
        sheet.setColumnWidth(COL_HASH_SHORT, 18 * 256);
        sheet.setColumnWidth(COL_FILE_COUNT, 12 * 256);
        sheet.setColumnWidth(COL_SIZE_EACH,  14 * 256);
        sheet.setColumnWidth(COL_WASTED,     16 * 256);
        sheet.setColumnWidth(COL_ORIGINAL,   55 * 256);
        sheet.setColumnWidth(COL_DUPLICATES, 55 * 256);
        sheet.setColumnWidth(COL_DECISION,   22 * 256);
        sheet.setColumnWidth(COL_REVIEWER,   18 * 256);
        sheet.setColumnWidth(COL_NOTES,      40 * 256);

        sheet.createFreezePane(0, 1);

        // Header
        Row header = sheet.createRow(0);
        String[] headers = buildHeaders();
        for (int i = 0; i < headers.length; i++) {
            Cell c = header.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(styles.colHeader);
        }
        sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                0, 0, 0, TOTAL_COLS - 1));

        // Data rows
        int rowIdx = 1;
        for (DuplicateGroup group : groups) {
            Row row = sheet.createRow(rowIdx);
            writeGroupRow(row, group, styles);
            addDecisionValidation(sheet, rowIdx);
            rowIdx++;
        }

        addDecisionConditionalFormatting(sheet, rowIdx);
    }

    private void buildHighPrioritySheet(XSSFWorkbook wb, XlsxStyles styles,
                                        List<DuplicateGroup> allGroups) {
        List<DuplicateGroup> top = allGroups.stream()
                .sorted(Comparator.comparingLong(DuplicateGroup::getWastedBytes).reversed())
                .limit(500).toList();
        buildReviewSheet(wb, styles, top, "High Priority");
    }

    private void buildSuspiciousSheet(XSSFWorkbook wb, XlsxStyles styles,
                                      List<DuplicateGroup> allGroups) {
        List<DuplicateGroup> suspicious = allGroups.stream()
                .filter(g -> g.isSpanningDirectories(config.getScanRoot()))
                .limit(2000).toList();
        buildReviewSheet(wb, styles, suspicious, "Suspicious");
    }

    private void writeGroupRow(Row row, DuplicateGroup group, XlsxStyles styles) {
        setCellStr(row, COL_GROUP_ID,   String.valueOf(group.getGroupId()), styles.data);
        setCellStr(row, COL_HASH_SHORT, group.getSha256Short(),             styles.mono);
        setCellStr(row, COL_FILE_COUNT, String.valueOf(group.getFileCount()),styles.data);
        setCellStr(row, COL_SIZE_EACH,  DuplicateGroup.humanSize(group.getSizeBytes()),   styles.data);
        setCellStr(row, COL_WASTED,     DuplicateGroup.humanSize(group.getWastedBytes()), styles.wastedCell);
        setCellStr(row, COL_ORIGINAL,   group.getOriginalPath().toString(),  styles.original);

        List<Path> dups = group.getDuplicates();
        String allDups = dups.stream().map(Path::toString).collect(java.util.stream.Collectors.joining("\n"));
        setCellStr(row, COL_DUPLICATES, allDups, styles.duplicate);
        row.setHeightInPoints(Math.max(15f, dups.size() * 15f));

        setCellStr(row, COL_DECISION, DECISION_PENDING, styles.decisionPending);
        setCellStr(row, COL_REVIEWER, "",               styles.editable);
        setCellStr(row, COL_NOTES,    "",               styles.editable);
    }

    private void addDecisionValidation(XSSFSheet sheet, int rowIdx) {
        DataValidationHelper dvh = sheet.getDataValidationHelper();
        DataValidationConstraint dvc = dvh.createExplicitListConstraint(
                new String[]{DECISION_PENDING, DECISION_ARCHIVE,
                             DECISION_KEEP,    DECISION_ESCALATE});
        DataValidation dv = dvh.createValidation(dvc,
                new CellRangeAddressList(rowIdx, rowIdx, COL_DECISION, COL_DECISION));
        // Note: setShowDropDown(false) means SHOW the arrow — POI naming is inverted
        // Use setSuppressDropDownArrow(false) on XSSFDataValidation for clarity
        dv.setShowErrorBox(true);
        dv.createErrorBox("Invalid choice", "Use the dropdown to select a decision.");
        sheet.addValidationData(dv);
    }

    private void addDecisionConditionalFormatting(XSSFSheet sheet, int lastRow) {
        SheetConditionalFormatting scf = sheet.getSheetConditionalFormatting();

        // Green = Archive duplicates
        addCfRule(scf, lastRow, DECISION_ARCHIVE,
                new byte[]{(byte)0xC6,(byte)0xEF,(byte)0xCE},
                new byte[]{(byte)0x27,(byte)0x6E,(byte)0x2B});

        // Blue = Keep all
        addCfRule(scf, lastRow, DECISION_KEEP,
                new byte[]{(byte)0xBD,(byte)0xD7,(byte)0xEE},
                new byte[]{(byte)0x15,(byte)0x63,(byte)0x82});

        // Amber = Escalate
        addCfRule(scf, lastRow, DECISION_ESCALATE,
                new byte[]{(byte)0xFF,(byte)0xEB,(byte)0x9C},
                new byte[]{(byte)0x9C,(byte)0x5A,(byte)0x00});
    }

    private void addCfRule(SheetConditionalFormatting scf, int lastRow,
                           String value, byte[] bg, byte[] fg) {
        ConditionalFormattingRule rule = scf.createConditionalFormattingRule(
                ComparisonOperator.EQUAL, "\"" + value + "\"");
        rule.createPatternFormatting()
            .setFillBackgroundColor(new XSSFColor(bg, null));
        rule.createFontFormatting()
            .setFontColor(new XSSFColor(fg, null));

        // POI API: addConditionalFormatting takes CellRangeAddress[] not CellRangeAddressList[]
        org.apache.poi.ss.util.CellRangeAddress[] ranges = {
            new org.apache.poi.ss.util.CellRangeAddress(
                    1, Math.max(lastRow, 2), COL_DECISION, COL_DECISION)
        };
        scf.addConditionalFormatting(ranges, rule);
    }

    // ── Sheet 5: Raw data ─────────────────────────────────────────────────────

    private void buildRawDataSheet(XSSFWorkbook wb, XlsxStyles styles) throws IOException {
        XSSFSheet sheet = wb.createSheet("Raw Data");
        int[] widths = {12, 68, 55, 18, 22, 14};
        for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
        sheet.createFreezePane(0, 1);

        int rowIdx = 0;
        boolean isHeader = true;
        try (BufferedReader br = Files.newBufferedReader(
                config.getReportCsvPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] cols = parseCsvLine(line);
                Row row = sheet.createRow(rowIdx++);
                for (int i = 0; i < cols.length; i++) {
                    Cell c = row.createCell(i);
                    c.setCellValue(cols[i]);
                    c.setCellStyle(isHeader ? styles.colHeader : styles.data);
                }
                isHeader = false;
            }
        }
    }

    // ── CSV parsing ───────────────────────────────────────────────────────────

    List<DuplicateGroup> loadGroupsFromCsv(Path csvPath) throws IOException {
        Map<Long, GroupBuilder> builders = new LinkedHashMap<>();
        try (BufferedReader br = Files.newBufferedReader(csvPath, StandardCharsets.UTF_8)) {
            br.readLine(); // skip header
            String line;
            while ((line = br.readLine()) != null) {
                String[] cols = parseCsvLine(line);
                if (cols.length < 6) continue;
                long    groupId   = parseLongSafe(cols[0].trim());
                String  sha256    = cols[1].trim();
                String  pathStr   = cols[2].trim();
                long    size      = parseLongSafe(cols[3].trim());
                boolean isOrig    = "true".equalsIgnoreCase(cols[5].trim());
                builders.computeIfAbsent(groupId, id -> new GroupBuilder(id, sha256, size))
                        .addPath(Path.of(pathStr), isOrig);
            }
        }
        List<DuplicateGroup> result = new ArrayList<>();
        builders.values().forEach(b -> result.add(b.build()));
        return result;
    }

    static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuote && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"'); i++;
                } else { inQuote = !inQuote; }
            } else if (ch == ',' && !inQuote) {
                fields.add(sb.toString()); sb.setLength(0);
            } else { sb.append(ch); }
        }
        fields.add(sb.toString());
        return fields.toArray(new String[0]);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String[] buildHeaders() {
        String[] h = new String[TOTAL_COLS];
        h[COL_GROUP_ID]   = "Group ID";
        h[COL_HASH_SHORT] = "SHA-256 (short)";
        h[COL_FILE_COUNT] = "File count";
        h[COL_SIZE_EACH]  = "Size each";
        h[COL_WASTED]     = "Wasted space";
        h[COL_ORIGINAL]    = "Original (keep)";
        h[COL_DUPLICATES]  = "Duplicates (all paths)";
        h[COL_DECISION]    = "Decision";
        h[COL_REVIEWER]   = "Reviewer";
        h[COL_NOTES]      = "Notes";
        return h;
    }

    private static void setCellStr(Row row, int col, String value, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(value != null ? value : "");
        if (style != null) c.setCellStyle(style);
    }

    private static long parseLongSafe(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private static class GroupBuilder {
        final long groupId; final String sha256; final long sizeBytes;
        Path originalPath;
        final List<Path> duplicates = new ArrayList<>();

        GroupBuilder(long g, String s, long sz) { groupId=g; sha256=s; sizeBytes=sz; }

        void addPath(Path path, boolean isOriginal) {
            if (isOriginal) originalPath = path;
            else            duplicates.add(path);
        }

        DuplicateGroup build() {
            List<Path> all = new ArrayList<>();
            if (originalPath != null) all.add(originalPath);
            all.addAll(duplicates);
            return new DuplicateGroup(groupId, sha256, all, sizeBytes);
        }
    }

    public record ScanStats(long duplicateGroups, long redundantFiles,
                            long wastedBytes, String wastedSpaceHuman) {}
}
