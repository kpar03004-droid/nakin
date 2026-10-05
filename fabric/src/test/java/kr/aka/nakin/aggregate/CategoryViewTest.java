package kr.aka.nakin.aggregate;

import kr.aka.nakin.config.DtConfig;
import kr.aka.nakin.core.TransactionRecord;
import kr.aka.nakin.core.TransactionRecord.Confidence;
import kr.aka.nakin.core.TransactionRecord.Kind;
import kr.aka.nakin.store.LedgerStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** 빌띵 0.2.8 에서 가져온 통계·정산 복사·기록 수정 — 동글랜드 카테고리로. */
class CategoryViewTest {
    private static final LocalDate D = LocalDate.of(2026, 10, 5);

    /** timestamp 자리에 "며칠 전"을 넣고 dateOf 로 풀어 쓴다 — 시간대와 무관한 테스트. */
    private static TransactionRecord rec(int daysAgo, Kind k, long amt, String cat, String label) {
        return new TransactionRecord(daysAgo, k, amt, cat, label, 1, true, Confidence.HIGH, true, null);
    }

    private static final Function<TransactionRecord, LocalDate> DATE = r -> D.minusDays(r.timestamp);

    private static final List<TransactionRecord> RECS = List.of(
            rec(0, Kind.INCOME, 100, "대장장이", "보석ㅣ영원"),
            rec(2, Kind.INCOME, 300, "대장장이", "보석ㅣ영원"),
            rec(2, Kind.INCOME, 50, "대장장이", "주괴ㅣ벚꽃"),
            rec(1, Kind.INCOME, 1_000, "거래소", "보석ㅣ신성"),
            rec(1, Kind.EXPENSE, 70, "대장장이", "x"),
            rec(5, Kind.INCOME, 9_999, "대장장이", "기간 밖"),
            rec(2, Kind.TRANSFER_IN, 5_000, "송금", "받음"));

    @Test
    void 사흘_동안_합계와_날짜별() {
        var r = CategoryView.of(RECS, "대장장이", false, D.minusDays(2), D, DATE);
        assertEquals(450, r.income());
        assertEquals(70, r.expense());
        assertEquals(380, r.net());
        assertEquals(3, r.days());
        assertEquals(350, r.byDay().get(D.minusDays(2))[0]);
        assertEquals(400, r.top().get(0).amount(), "같은 품목은 합친다");
        assertEquals(2, r.top().get(0).count());
        assertEquals("x", r.spent().get(0).label());
    }

    @Test
    void 품목_기준이면_거래소_판매도_직업_분야로() {
        assertEquals("거래소", CategoryView.categoryOf(RECS.get(3), false));
        assertEquals("대장장이", CategoryView.categoryOf(RECS.get(3), true));
        assertNull(CategoryView.categoryOf(RECS.get(6), true), "이체는 분야 수익 아님");
        var r = CategoryView.of(RECS, "대장장이", true, D.minusDays(2), D, DATE);
        assertEquals(1_450, r.income());
    }

    @Test
    void 통계_목록과_복사문() {
        var rows = CategoryView.totals(RECS, false, D.minusDays(2), D, DATE);
        assertEquals("거래소", rows.get(0).getKey());
        assertArrayEquals(new long[]{450, 70}, rows.get(1).getValue());
        assertEquals(2, rows.size(), "이체·기간 밖은 빠진다");
        String t = CategoryView.shareList(rows, D.minusDays(2), D);
        assertTrue(t.contains("순익 +1,380 냥"), t);
        assertTrue(t.contains("· 대장장이  +450  -70"), t);
        String one = CategoryView.share(CategoryView.of(RECS, "대장장이", false, D.minusDays(2), D, DATE));
        assertTrue(one.startsWith("📒 낙인 · 대장장이 · 2026-10-03 ~ 2026-10-05"), one);
        assertTrue(one.contains("번 것\n· 보석ㅣ영원 400 (2건)"), one);
    }

    @Test
    void 오늘_정산_복사는_순익과_상위_3개() {
        DailyBucket b = new DailyBucket(D);
        b.add(rec(0, Kind.INCOME, 500_000, "거래소", "a"));
        b.add(rec(0, Kind.INCOME, 300_000, "대장장이", "b"));
        b.add(rec(0, Kind.INCOME, 200_000, "낚시", "c"));
        b.add(rec(0, Kind.INCOME, 100_000, "보상", "d"));
        b.add(rec(0, Kind.EXPENSE, 270_000, "강화", "e"));
        String t = ShareText.day(b);
        assertTrue(t.contains("순익 +830,000 냥"), t);
        assertTrue(t.contains("수입 상위: 거래소 500,000 · 대장장이 300,000 · 낚시 200,000"), t);
        assertFalse(t.contains("보상"), "3개까지만");
    }

    @Test
    void 내역에서_고친_금액과_카테고리가_집계와_파일에_반영된다(@TempDir Path dir) {
        DtConfig config = new DtConfig();
        LedgerStore store = new LedgerStore(dir);
        DailyAggregator agg = new DailyAggregator(config, store);
        agg.today(); // 실제 시작 순서: 원장 먼저 로드 → 이후 라이브 레코드
        TransactionRecord r = new TransactionRecord(System.currentTimeMillis(), Kind.EXPENSE, 0,
                "플리마켓", "다이아몬드", 1728, false, Confidence.LOW, false, "금액 미확인");
        store.commit(r);
        agg.addLive(r);
        assertEquals(0, agg.today().expense, "미확인 줄은 손익에 안 들어간다");

        agg.editRecord(r, 283_392, "플리마켓", "다이아몬드");

        DailyBucket b = agg.today();
        assertEquals(283_392, b.expense, "금액을 넣으면 손익에 들어간다");
        assertEquals(1, b.count, "고쳐도 건수는 그대로");
        assertTrue(r.note.contains("원래 0"), r.note);
        assertEquals(Confidence.HIGH, r.confidence);

        TransactionRecord reloaded = new LedgerStore(dir).loadMonth(YearMonth.now()).get(0);
        assertEquals(283_392, reloaded.amount);
        assertTrue(reloaded.countedInPnl);
    }
}
