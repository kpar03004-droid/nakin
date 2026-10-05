package kr.aka.nakin.aggregate;

import kr.aka.nakin.util.GoldFormat;

import java.util.List;
import java.util.Map;

/** 정산 결과를 디스코드 등에 붙일 평문으로 — 오늘/주간 탭에서 Ctrl+C·복사 버튼. 클립보드로만 나간다. */
public final class ShareText {
    private ShareText() {}

    public static String day(DailyBucket b) {
        StringBuilder sb = new StringBuilder("📒 낙인 · ").append(b.date).append('\n');
        sb.append("순익 ").append(GoldFormat.signed(b.netPnl())).append(" 냥  (")
                .append("수입 ").append(GoldFormat.format(b.income))
                .append(" / 지출 ").append(GoldFormat.format(b.expense)).append(")\n");
        top(sb, "수입", b.incomeByCategory);
        top(sb, "지출", b.expenseByCategory);
        return sb.toString().stripTrailing();
    }

    /** @param days 최신이 앞(DailyAggregator.lastDays 순서) */
    public static String week(List<DailyBucket> days) {
        StringBuilder sb = new StringBuilder("📒 낙인 · 최근 ").append(days.size()).append("일\n");
        long in = 0, out = 0;
        for (DailyBucket d : days) {
            in += d.income;
            out += d.expense;
            sb.append(d.date.getMonthValue()).append('/').append(d.date.getDayOfMonth()).append("  ")
                    .append(GoldFormat.signed(d.netPnl())).append('\n');
        }
        sb.append("합계 ").append(GoldFormat.signed(in - out)).append(" 냥  (수입 ")
                .append(GoldFormat.format(in)).append(" / 지출 ").append(GoldFormat.format(out)).append(')');
        return sb.toString();
    }

    private static void top(StringBuilder sb, String title, Map<String, Long> m) {
        if (m.isEmpty()) return;
        sb.append(title).append(" 상위: ");
        m.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(3)
                .forEach(e -> sb.append(e.getKey()).append(' ').append(GoldFormat.format(e.getValue())).append(" · "));
        sb.setLength(sb.length() - 3);
        sb.append('\n');
    }
}
