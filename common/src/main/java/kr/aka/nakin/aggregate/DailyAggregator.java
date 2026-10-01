package kr.aka.nakin.aggregate;

import kr.aka.nakin.config.DtConfig;
import kr.aka.nakin.core.TransactionRecord;
import kr.aka.nakin.store.LedgerStore;
import kr.aka.nakin.util.LedgerDates;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 날짜별 집계 보관소. 라이브 레코드와 과거 원장을 같은 버킷에 모은다(중복 로드 방지). */
public final class DailyAggregator {
    private final DtConfig config;
    private final LedgerStore store;

    private static final int RECENT_MAX = 300;

    private final Map<LocalDate, DailyBucket> buckets = new HashMap<>();
    private final Set<YearMonth> loadedMonths = new HashSet<>();
    private final List<TransactionRecord> pending = new ArrayList<>();
    private final List<TransactionRecord> recent = new ArrayList<>(); // 오늘 전체 내역(최신 뒤)

    public DailyAggregator(DtConfig config, LedgerStore store) {
        this.config = config;
        this.store = store;
    }

    /** 라이브 확정 레코드 반영(저장은 별도). */
    public void addLive(TransactionRecord r) {
        applyToBucket(r);
    }

    private void applyToBucket(TransactionRecord r) {
        LocalDate d = LedgerDates.ledgerDate(r.timestamp, config.dayResetHour);
        buckets.computeIfAbsent(d, DailyBucket::new).add(r);
        if (r.confidence == TransactionRecord.Confidence.LOW || "기타".equals(r.category)) {
            pending.add(r);
        }
        if (d.equals(LedgerDates.today(config.dayResetHour))) {
            recent.add(r);
            if (recent.size() > RECENT_MAX) recent.remove(0);
        }
    }

    /** 해당 월 원장을 1회만 버킷에 반영. */
    public void ensureMonthLoaded(YearMonth ym) {
        if (!loadedMonths.add(ym)) return;
        for (TransactionRecord r : store.loadMonth(ym)) {
            applyToBucket(r);
        }
    }

    public DailyBucket today() {
        return day(LedgerDates.today(config.dayResetHour));
    }

    public DailyBucket day(LocalDate d) {
        ensureMonthLoaded(YearMonth.from(d));
        // 리셋 시각 시프트로 인접 월 경계 레코드가 있을 수 있어 앞뒤 달도 로드
        ensureMonthLoaded(YearMonth.from(d).minusMonths(1));
        ensureMonthLoaded(YearMonth.from(d).plusMonths(1));
        return buckets.getOrDefault(d, new DailyBucket(d));
    }

    /** 오늘 포함 최근 n일(내림차순). */
    public List<DailyBucket> lastDays(int n) {
        LocalDate today = LedgerDates.today(config.dayResetHour);
        List<DailyBucket> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(day(today.minusDays(i)));
        }
        return out;
    }

    public List<TransactionRecord> pending() {
        return pending;
    }

    /** 오늘 전체 내역(최신이 뒤). 내역 탭용. */
    public List<TransactionRecord> recent() {
        return recent;
    }

    /** 오늘 데이터 초기화 — 버킷·미분류·내역·저장 원장에서 오늘 레코드 제거. @return 지운 건수 */
    public int resetToday() {
        LocalDate today = LedgerDates.today(config.dayResetHour);
        buckets.remove(today);
        pending.removeIf(r -> LedgerDates.ledgerDate(r.timestamp, config.dayResetHour).equals(today));
        recent.removeIf(r -> LedgerDates.ledgerDate(r.timestamp, config.dayResetHour).equals(today));
        return store.removeDay(today, config.dayResetHour);
    }

    /** 특정 날짜 초기화(오늘이 아니어도 됨). @return 지운 건수 */
    public int resetDay(LocalDate date) {
        int n = store.removeDay(date, config.dayResetHour);
        rebuild();
        return n;
    }

    /** 최근 n일(오늘 포함) 초기화. @return 지운 건수 */
    public int resetLastDays(int n) {
        LocalDate today = LedgerDates.today(config.dayResetHour);
        int removed = store.removeRange(today.minusDays(n - 1L), today, config.dayResetHour);
        rebuild();
        return removed;
    }

    /** 잘못 기록된 레코드 1건만 삭제(내역에서 개별 수정). @return 지웠으면 true */
    public boolean deleteRecord(TransactionRecord r) {
        if (!store.removeRecord(r)) return false;
        rebuild();
        return true;
    }

    /**
     * 메모리 집계를 원장에서 다시 계산. 부분 삭제 후 합계·내역을 정확히 맞추기 위해
     * 누적값을 빼는 대신 통째로 재구성한다(반올림·누락 없이 항상 원장과 일치).
     */
    public void rebuild() {
        buckets.clear();
        loadedMonths.clear();
        pending.clear();
        recent.clear();
        LocalDate today = LedgerDates.today(config.dayResetHour);
        ensureMonthLoaded(YearMonth.from(today));
        ensureMonthLoaded(YearMonth.from(today).minusMonths(1)); // 리셋 시각 시프트 대비
    }
}
