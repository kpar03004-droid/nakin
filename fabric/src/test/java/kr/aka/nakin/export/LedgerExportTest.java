package kr.aka.nakin.export;

import kr.aka.nakin.core.TransactionRecord;
import kr.aka.nakin.core.TransactionRecord.Confidence;
import kr.aka.nakin.core.TransactionRecord.Kind;
import kr.aka.nakin.store.LedgerStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 엑셀 내보내기 검증. 엑셀 없이 열 수 없으므로 zip 구성과 XML 적법성까지 여기서 잡는다
 * (2026-07-30: "열이 무슨 뜻인지 모르겠다" 개편 — 파일이 깨지면 사용자는 원인을 못 찾는다).
 */
class LedgerExportTest {

    /** 리셋 시각 0 = 달력 날짜와 동일. 대부분의 테스트는 이 기준. */
    private static final int RESET_0 = 0;

    @TempDir
    Path dir;

    private List<TransactionRecord> records;

    @BeforeEach
    void setUp() {
        records = new ArrayList<>();
        // 2026-07-30 은 목요일(2026-W31), 2026-07-26 은 일요일(2026-W30) — 주 경계 확인용
        records.add(rec(30, 14, 2, Kind.INCOME, 1_235_000, "유저상점", "스태미나 드링크 IV", 0, true, Confidence.MEDIUM, false, "ΔG 미검출(메시지 금액 사용)"));
        records.add(rec(30, 14, 5, Kind.EXPENSE, 700_000, "강화", "장비 강화 실패", 0, true, Confidence.HIGH, true, null));
        records.add(rec(30, 15, 0, Kind.EXPENSE, 30_000, "각인", "각인석 조사 성공", 0, true, Confidence.HIGH, false, null));
        // 이체는 손익 밖 — 합계에 섞이면 안 된다
        records.add(rec(30, 16, 0, Kind.TRANSFER_OUT, 1_000_000, "플리마켓 금고", "금고 입금", 0, false, Confidence.MEDIUM, false, null));
        records.add(rec(26, 11, 0, Kind.INCOME, 500_000, "낚시", "심해 대왕문어", 3, true, Confidence.HIGH, true, null));
    }

    private TransactionRecord rec(int day, int hour, int min, Kind kind, long amount, String cat, String label,
                                  int qty, boolean counted, Confidence conf, boolean crossed, String note) {
        long ts = LocalDateTime.of(2026, 7, day, hour, min)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new TransactionRecord(ts, kind, amount, cat, label, qty, counted, conf, crossed, note);
    }

    // ── 파일 구조 ──

    @Test
    void xlsx_는_엑셀이_읽을_수_있는_zip_구조여야_한다() throws Exception {
        Path out = LedgerExport.writeXlsx(records, dir.resolve("낙인-2026-07.xlsx"), RESET_0);
        Map<String, String> parts = unzip(out);

        assertTrue(parts.containsKey("[Content_Types].xml"));
        assertTrue(parts.containsKey("_rels/.rels"));
        assertTrue(parts.containsKey("xl/workbook.xml"));
        assertTrue(parts.containsKey("xl/_rels/workbook.xml.rels"));
        assertTrue(parts.containsKey("xl/styles.xml"));
        for (int i = 1; i <= 4; i++) {
            assertTrue(parts.containsKey("xl/worksheets/sheet" + i + ".xml"), "시트 " + i);
        }

        // 모든 파트가 XML 로서 적법해야 한다 — 하나라도 깨지면 엑셀이 "복구 불가" 를 띄운다
        for (Map.Entry<String, String> e : parts.entrySet()) {
            DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(e.getValue().getBytes(StandardCharsets.UTF_8)));
        }

        // rId 중복 금지 — 시트 4장 뒤 rId5 가 스타일
        String rels = parts.get("xl/_rels/workbook.xml.rels");
        assertTrue(rels.contains("Id=\"rId4\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\""));
        assertTrue(rels.contains("Id=\"rId5\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\""));
    }

    @Test
    void 시트는_거래내역_일별_주별_카테고리_네_장_고정() throws Exception {
        Path out = LedgerExport.writeXlsx(records, dir.resolve("a.xlsx"), RESET_0);
        String wbxml = unzip(out).get("xl/workbook.xml");

        assertTrue(wbxml.contains("name=\"거래내역\""));
        assertTrue(wbxml.contains("name=\"일별\""));
        assertTrue(wbxml.contains("name=\"주별\""));
        assertTrue(wbxml.contains("name=\"카테고리\""));
        // 날짜마다 시트를 파면 한 달에 30장이 된다 — 데이터가 늘어도 장수는 고정
        assertEquals(4, wbxml.split("<sheet ").length - 1);
    }

    @Test
    void 첫_행_고정과_열_너비와_필터가_들어간다() throws Exception {
        Path out = LedgerExport.writeXlsx(records, dir.resolve("b.xlsx"), RESET_0);
        Map<String, String> parts = unzip(out);

        for (int i = 1; i <= 4; i++) {
            String sheet = parts.get("xl/worksheets/sheet" + i + ".xml");
            assertTrue(sheet.contains("<pane ySplit=\"1\" topLeftCell=\"A2\""), "시트" + i + " 머리글 고정");
            assertTrue(sheet.contains("customWidth=\"1\""), "시트" + i + " 열 너비");
            assertTrue(sheet.contains("<autoFilter ref=\"A1:"), "시트" + i + " 필터");
            // autoFilter 는 sheetData 뒤에 와야 엑셀이 받아준다
            assertTrue(sheet.indexOf("<autoFilter") > sheet.indexOf("</sheetData>"));
        }
        assertTrue(parts.get("xl/worksheets/sheet1.xml").contains("<autoFilter ref=\"A1:L"),
                "거래내역은 12열(L)까지");
    }

    // ── 거래내역 시트 ──

    @Test
    void 사람이_읽는_열만_남긴다() throws Exception {
        Path out = LedgerExport.writeXlsx(records, dir.resolve("c.xlsx"), RESET_0);
        String sheet = unzip(out).get("xl/worksheets/sheet1.xml");

        for (String h : new String[]{"날짜", "요일", "주차", "시각", "구분", "카테고리",
                                     "내용", "수량", "금액", "손익반영액", "검증", "비고"}) {
            assertTrue(sheet.contains(">" + h + "</t>"), "머리글 " + h);
        }
        // epoch 밀리초·영문 enum·TRUE/FALSE 는 더 이상 나가지 않는다
        assertFalse(sheet.contains("timestamp"));
        assertFalse(sheet.contains("EXPENSE"));
        assertFalse(sheet.contains("countedInPnl"));

        assertTrue(sheet.contains(">2026-07-30</t>"));
        assertTrue(sheet.contains(">14:05</t>"));
        assertTrue(sheet.contains(">지출</t>"));
        assertTrue(sheet.contains(">이체(나감)</t>"));
        assertTrue(sheet.contains(">잔고확인</t>"), "ΔG 검증된 건");
        assertTrue(sheet.contains(">메시지값</t>"), "ΔG 없이 메시지 금액만 쓴 건");
    }

    @Test
    void 요일과_주차로_일별_주별_필터가_가능하다() throws Exception {
        Path out = LedgerExport.writeXlsx(records, dir.resolve("d.xlsx"), RESET_0);
        String sheet = unzip(out).get("xl/worksheets/sheet1.xml");

        assertTrue(sheet.contains(">목</t>"), "2026-07-30 은 목요일");
        assertTrue(sheet.contains(">일</t>"), "2026-07-26 은 일요일");
        assertTrue(sheet.contains(">2026-W31</t>"));
        assertTrue(sheet.contains(">2026-W30</t>"), "일요일은 앞 주(ISO)");
    }

    @Test
    void 손익반영액은_부호가_있고_이체는_0() {
        assertEquals(1_235_000, records.get(0).pnlDelta());
        assertEquals(-700_000, records.get(1).pnlDelta());
        assertEquals(0, records.get(3).pnlDelta(), "이체는 손익 밖");
    }

    // ── 집계 시트 ──

    @Test
    void 일별시트는_하루에_한_행씩_합계를_낸다() throws Exception {
        Path out = LedgerExport.writeXlsx(records, dir.resolve("e.xlsx"), RESET_0);
        String daily = unzip(out).get("xl/worksheets/sheet2.xml");

        assertTrue(daily.contains(">날짜</t>") && daily.contains(">순손익</t>"));
        assertTrue(daily.contains(">2026-07-26</t>"));
        assertTrue(daily.contains(">2026-07-30</t>"));
        // 07-30: 수입 1,235,000 − 지출 730,000 = 505,000
        assertTrue(daily.contains("<v>505000</v>"), "이체 1,000,000 은 순손익에서 빠져야 함");
        // 07-26: 수입 500,000
        assertTrue(daily.contains("<v>500000</v>"));
        assertTrue(daily.contains(">합계</t>"));
        assertTrue(daily.contains("<v>1005000</v>"), "전체 순손익 1,005,000");
    }

    @Test
    void 주별시트는_월요일부터_일요일까지_묶는다() throws Exception {
        Path out = LedgerExport.writeXlsx(records, dir.resolve("f.xlsx"), RESET_0);
        String weekly = unzip(out).get("xl/worksheets/sheet3.xml");

        assertTrue(weekly.contains(">2026-W30</t>") && weekly.contains(">2026-W31</t>"));
        assertTrue(weekly.contains(">2026-07-20</t>"), "W30 시작일(월)");
        assertTrue(weekly.contains(">2026-07-26</t>"), "W30 종료일(일)");
        assertTrue(weekly.contains(">2026-07-27</t>"), "W31 시작일(월)");
        assertTrue(weekly.contains("<v>505000</v>"), "W31 순손익");
        assertTrue(weekly.contains("<v>500000</v>"), "W30 순손익");
    }

    @Test
    void 카테고리시트는_합계와_순손익을_낸다() throws Exception {
        Path out = LedgerExport.writeXlsx(records, dir.resolve("g.xlsx"), RESET_0);
        String cat = unzip(out).get("xl/worksheets/sheet4.xml");

        assertTrue(cat.contains(">카테고리</t>") && cat.contains(">유저상점</t>"));
        assertTrue(cat.contains("<v>1235000</v>"));
        assertTrue(cat.contains(">총 수입</t>"));
        assertTrue(cat.contains("<v>1005000</v>"), "순손익에 이체가 섞이면 안 됨");
    }

    @Test
    void 리셋시각을_반영해_새벽_거래는_전날로_묶는다() throws Exception {
        List<TransactionRecord> dawn = List.of(
                rec(31, 3, 0, Kind.INCOME, 100, "낚시", "새벽 조업", 0, true, Confidence.HIGH, true, null));

        String at0 = unzip(LedgerExport.writeXlsx(dawn, dir.resolve("h0.xlsx"), 0))
                .get("xl/worksheets/sheet2.xml");
        assertTrue(at0.contains(">2026-07-31</t>"), "리셋 0시면 달력 날짜 그대로");

        String at6 = unzip(LedgerExport.writeXlsx(dawn, dir.resolve("h6.xlsx"), 6))
                .get("xl/worksheets/sheet2.xml");
        assertTrue(at6.contains(">2026-07-30</t>"), "리셋 6시면 03:00 은 전날 장부");
        assertTrue(at6.contains("리셋 6시"), "기준을 시트에 밝힌다");
    }

    // ── 범위 지정 ──

    @Test
    void 범위인자로_기간을_좁힐_수_있다() throws Exception {
        LedgerStore store = new LedgerStore(dir.resolve("ledger"));
        LocalDate today = LocalDate.now();
        store.commit(at(today, Kind.INCOME, 100));
        store.commit(at(today.minusDays(3), Kind.INCOME, 200));
        store.commit(at(today.minusDays(20), Kind.INCOME, 400));

        assertEquals(1, LedgerExport.resolve(store, RESET_0, "today").records().size());
        assertEquals(2, LedgerExport.resolve(store, RESET_0, "week").records().size(), "최근 7일");
        assertEquals(YearMonth.from(today).toString(),
                LedgerExport.resolve(store, RESET_0, "").baseName(), "인자 없으면 이번 달");
        assertEquals("2026-06", LedgerExport.resolve(store, RESET_0, "2026-06").baseName());
        assertThrows(IllegalArgumentException.class, () -> LedgerExport.resolve(store, RESET_0, "작년"));
    }

    private TransactionRecord at(LocalDate d, Kind kind, long amount) {
        long ts = d.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new TransactionRecord(ts, kind, amount, "낚시", "테스트", 0, true, Confidence.HIGH, true, null);
    }

    // ── 견고성 ──

    @Test
    void csv_도_같은_열로_함께_저장된다() throws Exception {
        Path out = LedgerExport.writeCsv(records, dir.resolve("i.csv"), RESET_0);
        String text = Files.readString(out, StandardCharsets.UTF_8);

        assertTrue(text.startsWith("﻿"), "엑셀 한글 깨짐 방지 BOM");
        assertTrue(text.contains("날짜,요일,주차,시각,구분,카테고리,내용,수량,금액,손익반영액,검증,비고"));
        assertTrue(text.contains("2026-07-30,목,2026-W31,14:05,지출,강화,장비 강화 실패,,700000,-700000,잔고확인,"));
    }

    @Test
    void 쉼표와_제어문자가_있어도_파일이_깨지지_않는다() throws Exception {
        // U+0001 은 서버 색코드·아이콘 잔재로 라벨에 섞여 들어올 수 있는데,
        // XML 1.0 에서 금지된 문자라 그대로 쓰면 엑셀이 파일을 못 연다.
        records.add(rec(30, 17, 0, Kind.INCOME, 100, "판매", "감자 & 당근, 특급 <최상>", 3,
                true, Confidence.HIGH, true, "메모"));

        Path xlsx = LedgerExport.writeXlsx(records, dir.resolve("j.xlsx"), RESET_0);
        String sheet = unzip(xlsx).get("xl/worksheets/sheet1.xml");
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(sheet.getBytes(StandardCharsets.UTF_8)));
        assertTrue(sheet.contains("&amp;"));
        assertTrue(sheet.contains("&lt;최상&gt;"));
        assertFalse(sheet.contains(""), "XML 1.0 금지 제어문자 제거");

        String csv = Files.readString(LedgerExport.writeCsv(records, dir.resolve("j.csv"), RESET_0),
                StandardCharsets.UTF_8);
        assertTrue(csv.contains("\"감자 & 당근, 특급 <최상>\""), "쉼표 포함 값은 따옴표");
    }

    @Test
    void 내역이_없어도_빈_파일을_만든다() throws Exception {
        Path out = LedgerExport.writeXlsx(List.of(), dir.resolve("k.xlsx"), RESET_0);
        assertTrue(Files.size(out) > 0);
        Map<String, String> parts = unzip(out);
        assertTrue(parts.get("xl/worksheets/sheet1.xml").contains(">날짜</t>"));
        assertTrue(parts.get("xl/worksheets/sheet3.xml").contains(">주차</t>"));
    }

    @Test
    void 한_달치_대량_기록도_적법한_파일로_나온다() throws Exception {
        List<TransactionRecord> many = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            many.add(rec(1 + i % 28, i % 24, i % 60, i % 2 == 0 ? Kind.INCOME : Kind.EXPENSE,
                    1000 + i, "낚시", "대량 " + i, 0, true, Confidence.HIGH, true, null));
        }
        Path out = LedgerExport.writeXlsx(many, dir.resolve("big.xlsx"), RESET_0);

        // 일별·주별 시트는 행이 폭발하지 않는다(28일 + 합계·안내 몇 줄)
        String daily = unzip(out).get("xl/worksheets/sheet2.xml");
        assertTrue(daily.split("<row ").length - 1 < 40, "일별 시트 행 수는 날짜 수 수준");
        assertTrue(Files.size(out) < 5_000_000, "한 달 2만 건이 5MB 미만: " + Files.size(out));
    }

    @Test
    void 열_이름은_A부터_이어진다() {
        assertEquals("A", XlsxWriter.colName(0));
        assertEquals("L", XlsxWriter.colName(11));
        assertEquals("Z", XlsxWriter.colName(25));
        assertEquals("AA", XlsxWriter.colName(26));
    }

    private Map<String, String> unzip(Path file) throws Exception {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(file))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                out.put(e.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
