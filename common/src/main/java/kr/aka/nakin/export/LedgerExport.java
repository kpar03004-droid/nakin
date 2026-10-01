package kr.aka.nakin.export;

import kr.aka.nakin.core.TransactionRecord;
import kr.aka.nakin.store.LedgerStore;
import kr.aka.nakin.util.LedgerDates;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 거래 내역 파일 내보내기. Fabric·NeoForge 양쪽 명령이 공유한다.
 *
 * <p>2026-07-30 개편: epoch 밀리초·영문 enum·불리언 3종이 그대로 나가 "무슨 열인지 모르겠다"는
 * 지적을 받아, 사람이 읽는 열만 남기고 .xlsx(첫 행 고정·열 너비·필터·천단위 서식)로 바꿨다.
 * 이어서 일별·주별 구분 요청 — 날짜마다 시트를 파면 한 달에 30장이 되므로,
 * 시트 수는 4장으로 고정하고 <b>요약 시트</b>와 <b>필터 가능한 날짜·요일·주차 열</b>로 나눈다.
 *
 * <p>날짜는 달력 날짜가 아니라 모드와 같은 <b>장부 날짜</b>(dayResetHour 기준)를 쓴다 —
 * 안 그러면 새벽 거래가 엑셀과 게임 내 집계에서 다른 날로 잡힌다.
 */
public final class LedgerExport {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    /** 주 구분은 ISO 기준(월요일 시작) — 엑셀에서 정렬·필터가 되도록 "2026-W31" 꼴. */
    private static final WeekFields WEEK = WeekFields.ISO;

    private static final String[] HEADERS = {
            "날짜", "요일", "주차", "시각", "구분", "카테고리", "내용", "수량", "금액", "손익반영액", "검증", "비고"
    };
    // 한글은 폭을 넓게 — 내용/비고는 잘리지 않도록 넉넉히.
    private static final double[] WIDTHS = { 12, 6, 11, 7, 12, 14, 32, 6, 13, 13, 10, 26 };

    private static final String[] DOW = { "월", "화", "수", "목", "금", "토", "일" };

    private LedgerExport() {
    }

    /** 내보낼 범위(파일 이름 + 대상 레코드). */
    public record Scope(String baseName, List<TransactionRecord> records) {}

    /**
     * 내보내기 범위를 해석한다. 한 달치가 부담스러울 때 기간을 좁혀 파일을 작게 만드는 용도.
     *
     * <pre>
     *   (없음) / month → 이번 달        today → 오늘        week → 최근 7일
     *   2026-06        → 지정한 달
     * </pre>
     *
     * @throws IllegalArgumentException 알 수 없는 인자
     */
    public static Scope resolve(LedgerStore store, int resetHour, String arg) {
        String a = arg == null ? "" : arg.trim().toLowerCase();

        if (a.isEmpty() || a.equals("month") || a.equals("달") || a.equals("이번달")) {
            YearMonth ym = YearMonth.from(LedgerDates.today(resetHour));
            return new Scope(ym.toString(), List.copyOf(store.loadMonth(ym)));
        }
        if (a.equals("today") || a.equals("day") || a.equals("오늘")) {
            LocalDate d = LedgerDates.today(resetHour);
            return new Scope(DATE.format(d), between(store, resetHour, d, d));
        }
        if (a.equals("week") || a.equals("주") || a.equals("주간")) {
            LocalDate to = LedgerDates.today(resetHour);
            return new Scope(DATE.format(to) + "-최근7일", between(store, resetHour, to.minusDays(6), to));
        }
        try {
            YearMonth ym = YearMonth.parse(a);
            return new Scope(ym.toString(), List.copyOf(store.loadMonth(ym)));
        } catch (Exception ignored) {
            throw new IllegalArgumentException("범위는 month · today · week · 2026-06 중 하나입니다");
        }
    }

    /** 장부 날짜 [from, to] 구간의 레코드. 달 경계를 넘을 수 있어 걸치는 달을 모두 읽는다. */
    private static List<TransactionRecord> between(LedgerStore store, int resetHour,
                                                   LocalDate from, LocalDate to) {
        List<TransactionRecord> out = new ArrayList<>();
        YearMonth end = YearMonth.from(to);
        for (YearMonth ym = YearMonth.from(from); !ym.isAfter(end); ym = ym.plusMonths(1)) {
            for (TransactionRecord r : store.loadMonth(ym)) {
                LocalDate d = LedgerDates.ledgerDate(r.timestamp, resetHour);
                if (!d.isBefore(from) && !d.isAfter(to)) out.add(r);
            }
        }
        out.sort(Comparator.comparingLong(r -> r.timestamp));
        return out;
    }

    /**
     * 내역을 .xlsx 로 쓴다. 시트 4장 — 거래내역 · 일별 · 주별 · 카테고리.
     *
     * @param dayResetHour 하루 리셋 시각(DtConfig.dayResetHour) — 게임 내 집계와 날짜를 맞춘다
     * @return 쓴 파일 경로
     */
    public static Path writeXlsx(List<TransactionRecord> records, Path file, int dayResetHour) throws IOException {
        XlsxWriter wb = new XlsxWriter();

        XlsxWriter.Sheet s = wb.sheet("거래내역")
                .widths(WIDTHS)
                .freezeTopRow()
                .autoFilter();
        s.header(HEADERS);
        for (TransactionRecord r : records) {
            LocalDate d = LedgerDates.ledgerDate(r.timestamp, dayResetHour);
            LocalTime t = Instant.ofEpochMilli(r.timestamp).atZone(ZoneId.systemDefault()).toLocalTime();
            s.row(
                    DATE.format(d),
                    dow(d),
                    weekKey(d),
                    TIME.format(t),
                    kindLabel(r.kind),
                    nz(r.category),
                    nz(r.label),
                    r.qty > 0 ? r.qty : null,
                    r.amount,
                    r.pnlDelta(),
                    verifyLabel(r),
                    nz(r.note));
        }

        writeDaily(wb, records, dayResetHour);
        writeWeekly(wb, records, dayResetHour);
        writeCategory(wb, records);
        wb.write(file);
        return file;
    }

    /** 다른 도구(스프레드시트 외)용 CSV. 엑셀 한글 깨짐 방지로 UTF-8 BOM 을 붙인다. */
    public static Path writeCsv(List<TransactionRecord> records, Path file, int dayResetHour) throws IOException {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(",", HEADERS)).append('\n');
        for (TransactionRecord r : records) {
            LocalDate d = LedgerDates.ledgerDate(r.timestamp, dayResetHour);
            LocalTime t = Instant.ofEpochMilli(r.timestamp).atZone(ZoneId.systemDefault()).toLocalTime();
            sb.append(DATE.format(d)).append(',')
              .append(dow(d)).append(',')
              .append(weekKey(d)).append(',')
              .append(TIME.format(t)).append(',')
              .append(kindLabel(r.kind)).append(',')
              .append(csv(r.category)).append(',')
              .append(csv(r.label)).append(',')
              .append(r.qty > 0 ? String.valueOf(r.qty) : "").append(',')
              .append(r.amount).append(',')
              .append(r.pnlDelta()).append(',')
              .append(verifyLabel(r)).append(',')
              .append(csv(r.note)).append('\n');
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, sb, StandardCharsets.UTF_8);
        return file;
    }

    // ── 집계 시트 ──

    /** 하루에 한 행. 시트를 날짜별로 파지 않고 여기서 한눈에 보게 한다. */
    private static void writeDaily(XlsxWriter wb, List<TransactionRecord> records, int resetHour) {
        Map<LocalDate, Totals> byDay = new TreeMap<>();
        for (TransactionRecord r : records) {
            byDay.computeIfAbsent(LedgerDates.ledgerDate(r.timestamp, resetHour), k -> new Totals()).add(r);
        }

        XlsxWriter.Sheet s = wb.sheet("일별")
                .widths(13, 6, 15, 15, 15, 8)
                .freezeTopRow()
                .autoFilter();
        s.header("날짜", "요일", "수입", "지출", "순손익", "건수");
        Totals sum = new Totals();
        for (Map.Entry<LocalDate, Totals> e : byDay.entrySet()) {
            Totals v = e.getValue();
            sum.addAll(v);
            s.row(DATE.format(e.getKey()), dow(e.getKey()), v.income, v.expense, v.net(), v.count);
        }
        s.row();
        s.row("합계", "", sum.income, sum.expense, sum.net(), sum.count);
        s.row();
        s.row("※ 날짜는 게임 내 집계와 같은 기준입니다(리셋 " + resetHour + "시).");
    }

    /** 한 주에 한 행. ISO 기준 월~일. */
    private static void writeWeekly(XlsxWriter wb, List<TransactionRecord> records, int resetHour) {
        Map<String, Totals> byWeek = new TreeMap<>();
        Map<String, LocalDate> weekStart = new TreeMap<>();
        for (TransactionRecord r : records) {
            LocalDate d = LedgerDates.ledgerDate(r.timestamp, resetHour);
            String key = weekKey(d);
            byWeek.computeIfAbsent(key, k -> new Totals()).add(r);
            weekStart.putIfAbsent(key, d.with(DayOfWeek.MONDAY));
        }

        XlsxWriter.Sheet s = wb.sheet("주별")
                .widths(11, 13, 13, 15, 15, 15, 8)
                .freezeTopRow()
                .autoFilter();
        s.header("주차", "시작일(월)", "종료일(일)", "수입", "지출", "순손익", "건수");
        Totals sum = new Totals();
        for (Map.Entry<String, Totals> e : byWeek.entrySet()) {
            Totals v = e.getValue();
            sum.addAll(v);
            LocalDate from = weekStart.get(e.getKey());
            s.row(e.getKey(), DATE.format(from), DATE.format(from.plusDays(6)),
                    v.income, v.expense, v.net(), v.count);
        }
        s.row();
        s.row("합계", "", "", sum.income, sum.expense, sum.net(), sum.count);
        s.row();
        s.row("※ 월요일~일요일 기준(ISO). 게임 내 '주간' 탭은 최근 7일이라 값이 다를 수 있습니다.");
    }

    /** 카테고리별 수입·지출 요약 — 내역 시트에서 피벗을 만들 필요 없게. */
    private static void writeCategory(XlsxWriter wb, List<TransactionRecord> records) {
        Map<String, Totals> byCat = new TreeMap<>();
        Totals sum = new Totals();
        for (TransactionRecord r : records) {
            byCat.computeIfAbsent(nz(r.category), k -> new Totals()).add(r);
            sum.add(r);
        }

        XlsxWriter.Sheet s = wb.sheet("카테고리")
                .widths(18, 15, 15, 15, 8)
                .freezeTopRow()
                .autoFilter();
        s.header("카테고리", "수입", "지출", "순손익", "건수");

        List<Map.Entry<String, Totals>> rows = new ArrayList<>(byCat.entrySet());
        rows.sort(Comparator.comparingLong(
                (Map.Entry<String, Totals> e) -> e.getValue().income + e.getValue().expense).reversed());
        for (Map.Entry<String, Totals> e : rows) {
            Totals v = e.getValue();
            s.row(e.getKey(), v.income, v.expense, v.net(), v.count);
        }

        s.row();
        s.row("총 수입", sum.income);
        s.row("총 지출", sum.expense);
        s.row("순손익", sum.net());
        s.row();
        s.row("※ 이체(설정에서 이체로 지정한 마을 금고 입출금 등)는 손익에서 제외됩니다.");
        s.row("※ '손익반영액'은 손익에 실제로 더해진 금액(수입 +, 지출 −, 이체 0)입니다.");
    }

    /** 수입·지출·건수 누적기. 손익 제외 레코드는 합계에 넣지 않는다. */
    private static final class Totals {
        long income;
        long expense;
        long count;

        void add(TransactionRecord r) {
            count++;
            if (!r.countedInPnl) return;
            switch (r.kind) {
                case INCOME -> income += r.amount;
                case EXPENSE -> expense += r.amount;
                case TRANSFER_IN, TRANSFER_OUT -> { /* 이체는 손익 밖 */ }
            }
        }

        void addAll(Totals o) {
            income += o.income;
            expense += o.expense;
            count += o.count;
        }

        long net() {
            return income - expense;
        }
    }

    // ── 라벨 ──

    static String weekKey(LocalDate d) {
        return String.format("%d-W%02d", d.get(WEEK.weekBasedYear()), d.get(WEEK.weekOfWeekBasedYear()));
    }

    static String dow(LocalDate d) {
        return DOW[d.getDayOfWeek().getValue() - 1];
    }

    static String kindLabel(TransactionRecord.Kind kind) {
        if (kind == null) return "";
        return switch (kind) {
            case INCOME -> "수입";
            case EXPENSE -> "지출";
            case TRANSFER_IN -> "이체(들어옴)";
            case TRANSFER_OUT -> "이체(나감)";
        };
    }

    /** 금액 신뢰도를 한 열로 압축 — 기존 confidence/crossChecked 두 열이 뜻이 겹쳤다. */
    static String verifyLabel(TransactionRecord r) {
        if (r.crossChecked) return "잔고확인";
        // 금액을 못 알아내 0으로 남긴 건(2026-08-13) — 엑셀에서도 한눈에 보여야 한다
        if (r.amount == 0 && r.note != null && r.note.contains("금액 미확인")) return "⚠ 금액 미확인";
        if (r.confidence == TransactionRecord.Confidence.LOW) return "추정";
        return "메시지값";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 제어문자(서버 색코드·아이콘 잔재)를 털고 쉼표·따옴표가 있으면 감싼다. */
    private static String csv(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= 0x20) sb.append(ch);
        }
        String v = sb.toString();
        if (v.contains(",") || v.contains("\"")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
