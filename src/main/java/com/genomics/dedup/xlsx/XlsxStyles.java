package com.genomics.dedup.xlsx;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;

/**
 * Pre-built cell styles for the XLSX workbook.
 * Created once and reused — POI limits workbooks to ~64K unique styles.
 */
public class XlsxStyles {

    final CellStyle title;
    final CellStyle sectionHeader;
    final CellStyle label;
    final CellStyle value;
    final CellStyle formulaValue;
    final CellStyle colHeader;
    final CellStyle data;
    final CellStyle mono;
    final CellStyle original;
    final CellStyle duplicate;
    final CellStyle wastedCell;
    final CellStyle decisionPending;
    final CellStyle editable;
    final CellStyle instruction;

    public XlsxStyles(XSSFWorkbook wb) {

        // ── Fonts ─────────────────────────────────────────────────────────────
        Font bold14 = wb.createFont();
        bold14.setBold(true);
        bold14.setFontHeightInPoints((short) 14);

        Font bold11 = wb.createFont();
        bold11.setBold(true);
        bold11.setFontHeightInPoints((short) 11);

        Font regular10 = wb.createFont();
        regular10.setFontHeightInPoints((short) 10);

        Font mono10 = wb.createFont();
        mono10.setFontName("Courier New");
        mono10.setFontHeightInPoints((short) 9);

        Font white11 = wb.createFont();
        white11.setBold(true);
        white11.setFontHeightInPoints((short) 11);
        white11.setColor(IndexedColors.WHITE.getIndex());

        Font green10 = wb.createFont();
        green10.setFontHeightInPoints((short) 10);
        green10.setColor(IndexedColors.DARK_GREEN.getIndex());

        Font orange10 = wb.createFont();
        orange10.setFontHeightInPoints((short) 10);
        orange10.setBold(true);
        orange10.setColor(new XSSFColor(new byte[]{(byte)0x9C,(byte)0x27,(byte)0x00}, null).getIndex());

        Font grey10 = wb.createFont();
        grey10.setFontHeightInPoints((short) 10);
        grey10.setColor(IndexedColors.GREY_50_PERCENT.getIndex());

        // ── Styles ────────────────────────────────────────────────────────────
        title = wb.createCellStyle();
        title.setFont(bold14);

        sectionHeader = wb.createCellStyle();
        sectionHeader.setFont(bold11);
        sectionHeader.setFillForegroundColor(
                new XSSFColor(new byte[]{(byte)0x21,(byte)0x4E,(byte)0x6B}, null).getIndex());
        sectionHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        ((XSSFCellStyle)sectionHeader).setFillForegroundColor(
                new XSSFColor(new byte[]{(byte)0x21,(byte)0x4E,(byte)0x6B}, null));
        sectionHeader.setFont(white11);

        label = wb.createCellStyle();
        label.setFont(bold11);

        value = wb.createCellStyle();
        value.setFont(regular10);
        value.setWrapText(false);

        formulaValue = wb.createCellStyle();
        formulaValue.setFont(bold11);
        ((XSSFCellStyle)formulaValue).setFillForegroundColor(
                new XSSFColor(new byte[]{(byte)0xF0,(byte)0xF4,(byte)0xFF}, null));
        formulaValue.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        colHeader = wb.createCellStyle();
        colHeader.setFont(white11);
        ((XSSFCellStyle)colHeader).setFillForegroundColor(
                new XSSFColor(new byte[]{(byte)0x21,(byte)0x4E,(byte)0x6B}, null));
        colHeader.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        colHeader.setAlignment(HorizontalAlignment.CENTER);
        colHeader.setBorderBottom(BorderStyle.THIN);
        colHeader.setWrapText(false);

        data = wb.createCellStyle();
        data.setFont(regular10);
        data.setWrapText(false);
        setBorder(data, BorderStyle.THIN, IndexedColors.GREY_25_PERCENT);

        mono = wb.createCellStyle();
        mono.setFont(mono10);
        mono.setWrapText(false);
        setBorder(mono, BorderStyle.THIN, IndexedColors.GREY_25_PERCENT);

        original = wb.createCellStyle();
        original.setFont(green10);
        original.setWrapText(false);
        setBorder(original, BorderStyle.THIN, IndexedColors.GREY_25_PERCENT);

        duplicate = wb.createCellStyle();
        duplicate.setFont(regular10);
        duplicate.setWrapText(true);
        duplicate.setVerticalAlignment(VerticalAlignment.TOP);
        setBorder(duplicate, BorderStyle.THIN, IndexedColors.GREY_25_PERCENT);

        wastedCell = wb.createCellStyle();
        wastedCell.setFont(orange10);
        wastedCell.setWrapText(false);
        wastedCell.setAlignment(HorizontalAlignment.RIGHT);
        setBorder(wastedCell, BorderStyle.THIN, IndexedColors.GREY_25_PERCENT);

        decisionPending = wb.createCellStyle();
        decisionPending.setFont(grey10);
        decisionPending.setAlignment(HorizontalAlignment.CENTER);
        setBorder(decisionPending, BorderStyle.MEDIUM, IndexedColors.GREY_50_PERCENT);

        editable = wb.createCellStyle();
        editable.setFont(regular10);
        ((XSSFCellStyle)editable).setFillForegroundColor(
                new XSSFColor(new byte[]{(byte)0xFF,(byte)0xFF,(byte)0xE8}, null));
        editable.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorder(editable, BorderStyle.THIN, IndexedColors.GREY_25_PERCENT);

        instruction = wb.createCellStyle();
        instruction.setFont(regular10);
        instruction.setWrapText(false);
    }

    private static void setBorder(CellStyle style, BorderStyle bs, IndexedColors color) {
        style.setBorderTop(bs);
        style.setBorderBottom(bs);
        style.setBorderLeft(bs);
        style.setBorderRight(bs);
        style.setTopBorderColor(color.getIndex());
        style.setBottomBorderColor(color.getIndex());
        style.setLeftBorderColor(color.getIndex());
        style.setRightBorderColor(color.getIndex());
    }
}
