package kr.aka.nakin.export;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 외부 라이브러리 없이 .xlsx(SpreadsheetML)를 직접 쓰는 최소 구현.
 *
 * <p>CSV 로는 불가능한 것들(2026-07-30 요청)을 위해 도입:
 * 첫 행 고정 · 열 너비 지정 · 자동필터 · 숫자 서식(#,##0) · 머리글 강조.
 * 문자열은 sharedStrings 대신 inlineStr 로 넣어 파일 구조를 단순하게 유지한다.
 *
 * <p>XML 은 통째로 문자열을 만들지 않고 zip 스트림으로 바로 흘려보낸다 — 한 달치가
 * 수만 행이 돼도 게임 클라이언트 메모리에 사본이 쌓이지 않게 하려는 것.
 *
 * <p>지원 범위는 이 모드가 쓰는 만큼만 — 시트당 단일 머리글 행, 셀 서식 3종.
 */
public final class XlsxWriter {

    /** 셀 서식 슬롯 — styles.xml 의 cellXfs 인덱스와 1:1. */
    private static final int S_DEFAULT = 0;
    private static final int S_HEADER = 1;
    private static final int S_NUMBER = 2;

    private final List<Sheet> sheets = new ArrayList<>();

    public Sheet sheet(String name) {
        Sheet s = new Sheet(name);
        sheets.add(s);
        return s;
    }

    public void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            Writer w = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8), 1 << 16);

            entry(zip, w, "[Content_Types].xml", this::contentTypes);
            entry(zip, w, "_rels/.rels", out -> out
                    .append(XML_DECL)
                    .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
                    .append("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>")
                    .append("</Relationships>"));
            entry(zip, w, "xl/workbook.xml", this::workbook);
            entry(zip, w, "xl/_rels/workbook.xml.rels", this::workbookRels);
            entry(zip, w, "xl/styles.xml", out -> out.append(STYLES));
            for (int i = 0; i < sheets.size(); i++) {
                Sheet s = sheets.get(i);
                entry(zip, w, "xl/worksheets/sheet" + (i + 1) + ".xml", s::writeTo);
            }
        }
    }

    /** zip 엔트리 하나를 열고 body 가 쓴 뒤 flush·close 한다(writer 자체는 닫지 않는다). */
    private void entry(ZipOutputStream zip, Writer w, String name, Body body) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        body.writeTo(w);
        w.flush();
        zip.closeEntry();
    }

    @FunctionalInterface
    private interface Body {
        void writeTo(Writer w) throws IOException;
    }

    private static final String XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";

    private void contentTypes(Writer w) throws IOException {
        w.append(XML_DECL)
         .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
         .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
         .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
         .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
         .append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 0; i < sheets.size(); i++) {
            w.append("<Override PartName=\"/xl/worksheets/sheet").append(String.valueOf(i + 1))
             .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        w.append("</Types>");
    }

    private void workbook(Writer w) throws IOException {
        w.append(XML_DECL)
         .append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"")
         .append(" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        for (int i = 0; i < sheets.size(); i++) {
            w.append("<sheet name=\"").append(esc(sheets.get(i).name))
             .append("\" sheetId=\"").append(String.valueOf(i + 1))
             .append("\" r:id=\"rId").append(String.valueOf(i + 1)).append("\"/>");
        }
        w.append("</sheets></workbook>");
    }

    private void workbookRels(Writer w) throws IOException {
        w.append(XML_DECL)
         .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < sheets.size(); i++) {
            w.append("<Relationship Id=\"rId").append(String.valueOf(i + 1))
             .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\"")
             .append(" Target=\"worksheets/sheet").append(String.valueOf(i + 1)).append(".xml\"/>");
        }
        // 스타일은 시트 뒤 번호를 이어 받는다(중복 rId 금지).
        w.append("<Relationship Id=\"rId").append(String.valueOf(sheets.size() + 1))
         .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
         .append("</Relationships>");
    }

    /** 기본 글꼴 + 굵은 머리글(회색 배경) + 천단위 구분 숫자 서식. */
    private static final String STYLES = XML_DECL
            + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"#,##0\"/></numFmts>"
            + "<fonts count=\"2\">"
            + "<font><sz val=\"11\"/><name val=\"맑은 고딕\"/></font>"
            + "<font><b/><sz val=\"11\"/><name val=\"맑은 고딕\"/></font>"
            + "</fonts>"
            + "<fills count=\"3\">"
            + "<fill><patternFill patternType=\"none\"/></fill>"
            + "<fill><patternFill patternType=\"gray125\"/></fill>"
            + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFD9E1F2\"/><bgColor indexed=\"64\"/></patternFill></fill>"
            + "</fills>"
            + "<borders count=\"2\">"
            + "<border><left/><right/><top/><bottom/><diagonal/></border>"
            + "<border><left style=\"thin\"/><right style=\"thin\"/><top style=\"thin\"/><bottom style=\"thin\"/><diagonal/></border>"
            + "</borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"3\">"
            + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
            + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\">"
            + "<alignment horizontal=\"center\" vertical=\"center\"/></xf>"
            + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
            + "</cellXfs>"
            + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
            + "</styleSheet>";

    // ── 시트 ──

    /** 한 장의 워크시트. 머리글 1행 + 데이터 행들. */
    public static final class Sheet {
        private final String name;
        private final List<List<Cell>> rows = new ArrayList<>();
        private double[] widths = new double[0];
        private boolean freezeTop;
        private boolean autoFilter;

        private Sheet(String name) {
            this.name = name;
        }

        /** 열 너비(엑셀 문자 단위). 지정한 개수만큼만 적용된다. */
        public Sheet widths(double... w) {
            this.widths = w;
            return this;
        }

        /** 스크롤해도 첫 행이 붙어 있게 한다. */
        public Sheet freezeTopRow() {
            this.freezeTop = true;
            return this;
        }

        /** 머리글에 필터 드롭다운을 단다. */
        public Sheet autoFilter() {
            this.autoFilter = true;
            return this;
        }

        public Sheet header(String... titles) {
            List<Cell> row = new ArrayList<>(titles.length);
            for (String t : titles) row.add(new Cell(t, false, S_HEADER));
            rows.add(row);
            return this;
        }

        /** 값 배열로 한 행 추가. Number 는 숫자 셀, null 은 빈 셀, 그 외는 문자열. */
        public Sheet row(Object... values) {
            List<Cell> row = new ArrayList<>(values.length);
            for (Object v : values) {
                if (v == null) {
                    row.add(null);
                } else if (v instanceof Number n) {
                    row.add(new Cell(String.valueOf(n.longValue()), true, S_NUMBER));
                } else {
                    row.add(new Cell(String.valueOf(v), false, S_DEFAULT));
                }
            }
            rows.add(row);
            return this;
        }

        public int rowCount() {
            return rows.size();
        }

        private void writeTo(Writer w) throws IOException {
            int cols = 0;
            for (List<Cell> r : rows) cols = Math.max(cols, r.size());

            w.append(XML_DECL)
             .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
             .append("<sheetViews><sheetView workbookViewId=\"0\">");
            if (freezeTop) {
                w.append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/>")
                 .append("<selection pane=\"bottomLeft\" activeCell=\"A2\" sqref=\"A2\"/>");
            }
            w.append("</sheetView></sheetViews>");

            if (widths.length > 0) {
                w.append("<cols>");
                for (int i = 0; i < widths.length; i++) {
                    w.append("<col min=\"").append(String.valueOf(i + 1))
                     .append("\" max=\"").append(String.valueOf(i + 1))
                     .append("\" width=\"").append(String.valueOf(widths[i]))
                     .append("\" customWidth=\"1\"/>");
                }
                w.append("</cols>");
            }

            w.append("<sheetData>");
            for (int r = 0; r < rows.size(); r++) {
                List<Cell> row = rows.get(r);
                String rowNo = String.valueOf(r + 1);
                w.append("<row r=\"").append(rowNo).append("\">");
                for (int c = 0; c < row.size(); c++) {
                    Cell cell = row.get(c);
                    if (cell == null) continue;
                    w.append("<c r=\"").append(colName(c)).append(rowNo)
                     .append("\" s=\"").append(String.valueOf(cell.style)).append('"');
                    if (cell.numeric) {
                        w.append("><v>").append(cell.value).append("</v></c>");
                    } else {
                        w.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                         .append(esc(cell.value)).append("</t></is></c>");
                    }
                }
                w.append("</row>");
            }
            w.append("</sheetData>");

            // autoFilter 는 스키마상 sheetData 뒤에 와야 한다.
            if (autoFilter && cols > 0 && !rows.isEmpty()) {
                w.append("<autoFilter ref=\"A1:").append(colName(cols - 1))
                 .append(String.valueOf(rows.size())).append("\"/>");
            }
            w.append("</worksheet>");
        }
    }

    private record Cell(String value, boolean numeric, int style) {}

    /** 0-based 열 번호 → 엑셀 열 이름(A, B, … Z, AA …). */
    static String colName(int index) {
        StringBuilder sb = new StringBuilder();
        int i = index;
        while (i >= 0) {
            sb.insert(0, (char) ('A' + i % 26));
            i = i / 26 - 1;
        }
        return sb.toString();
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> {
                    // XML 1.0 이 금지하는 제어문자는 버린다(서버 색코드 잔재 방어).
                    if (ch >= 0x20 || ch == '\t' || ch == '\n') sb.append(ch);
                }
            }
        }
        return sb.toString();
    }
}
