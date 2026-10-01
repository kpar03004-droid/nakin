package kr.aka.nakin.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import kr.aka.nakin.core.TransactionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 레코드 영속화. config/nakin/ledger-YYYY-MM.json 에 월별 저장.
 * 쓰기는 5초 디바운스(tick 에서 flush). 재접속 시 당월 로드.
 */
public final class LedgerStore {
    private static final Logger LOG = LoggerFactory.getLogger("nakin");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<ArrayList<TransactionRecord>>() {}.getType();
    private static final long DEBOUNCE_MS = 5000;

    private final Path dir;
    private final Map<YearMonth, List<TransactionRecord>> loaded = new HashMap<>();
    private final Set<YearMonth> dirty = new HashSet<>();
    private long dirtySince = 0;

    /** @param dir 원장 저장 디렉터리(예: <configDir>/nakin) — 각 로더 진입점이 주입 */
    public LedgerStore(Path dir) {
        this.dir = dir;
    }

    public static YearMonth monthOf(long epochMillis) {
        return YearMonth.from(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    /** 클라 시작 시 당월 레코드 로드(→ aggregator 로 재생). */
    public List<TransactionRecord> loadCurrentMonth() {
        return new ArrayList<>(loadMonth(YearMonth.now()));
    }

    public List<TransactionRecord> loadMonth(YearMonth ym) {
        return loaded.computeIfAbsent(ym, this::readFile);
    }

    /** 특정 장부날짜의 레코드를 제거하고 즉시 flush. @return 지운 건수 */
    public int removeDay(java.time.LocalDate date, int resetHour) {
        int removed = 0;
        for (Map.Entry<YearMonth, List<TransactionRecord>> e : loaded.entrySet()) {
            List<TransactionRecord> list = e.getValue();
            int before = list.size();
            list.removeIf(r -> kr.aka.nakin.util.LedgerDates
                    .ledgerDate(r.timestamp, resetHour).equals(date));
            if (list.size() != before) {
                dirty.add(e.getKey());
                removed += before - list.size();
            }
        }
        if (!dirty.isEmpty()) flushNow();
        return removed;
    }

    /**
     * 기간(양끝 포함)의 레코드를 제거하고 즉시 flush. 주간·구간 초기화용.
     * @return 지운 건수
     */
    public int removeRange(java.time.LocalDate from, java.time.LocalDate to, int resetHour) {
        int removed = 0;
        for (Map.Entry<YearMonth, List<TransactionRecord>> e : loaded.entrySet()) {
            List<TransactionRecord> list = e.getValue();
            int before = list.size();
            list.removeIf(r -> {
                java.time.LocalDate d = kr.aka.nakin.util.LedgerDates
                        .ledgerDate(r.timestamp, resetHour);
                return !d.isBefore(from) && !d.isAfter(to);
            });
            if (list.size() != before) {
                dirty.add(e.getKey());
                removed += before - list.size();
            }
        }
        if (!dirty.isEmpty()) flushNow();
        return removed;
    }

    /**
     * 잘못 기록된 레코드 1건만 제거(내역에서 개별 삭제). 같은 인스턴스를 우선 지우고,
     * 없으면 같은 시각·종류·금액·라벨의 첫 건을 지운다(재로딩으로 인스턴스가 바뀐 경우 대비).
     * @return 지웠으면 true
     */
    public boolean removeRecord(TransactionRecord target) {
        if (target == null) return false;
        YearMonth ym = monthOf(target.timestamp);
        List<TransactionRecord> list = loadMonth(ym);
        boolean removed = list.removeIf(r -> r == target);
        if (!removed) {
            for (int i = 0; i < list.size(); i++) {
                TransactionRecord r = list.get(i);
                if (r.timestamp == target.timestamp && r.kind == target.kind
                        && r.amount == target.amount
                        && java.util.Objects.equals(r.label, target.label)
                        && java.util.Objects.equals(r.category, target.category)) {
                    list.remove(i);
                    removed = true;
                    break;
                }
            }
        }
        if (removed) {
            dirty.add(ym);
            flushNow();
        }
        return removed;
    }

    public void commit(TransactionRecord r) {
        YearMonth ym = monthOf(r.timestamp);
        loadMonth(ym).add(r);
        if (dirty.isEmpty()) dirtySince = System.currentTimeMillis();
        dirty.add(ym);
    }

    /** 클라 틱에서 호출 — 디바운스 경과 시 flush. */
    public void tick(long now) {
        if (dirty.isEmpty()) return;
        if (now - dirtySince < DEBOUNCE_MS) return;
        flushNow();
    }

    public void flushNow() {
        if (dirty.isEmpty()) return;
        for (YearMonth ym : dirty) {
            writeFile(ym, loaded.getOrDefault(ym, List.of()));
        }
        dirty.clear();
    }

    private Path pathFor(YearMonth ym) {
        return dir.resolve(String.format("ledger-%04d-%02d.json", ym.getYear(), ym.getMonthValue()));
    }

    private List<TransactionRecord> readFile(YearMonth ym) {
        Path p = pathFor(ym);
        try {
            if (Files.exists(p)) {
                List<TransactionRecord> list = GSON.fromJson(Files.readString(p), LIST_TYPE);
                if (list != null) return list;
            }
        } catch (Exception e) {
            LOG.warn("[nakin] 원장 로드 실패: {}", p, e);
        }
        return new ArrayList<>();
    }

    /**
     * 임시 파일에 먼저 쓰고 원자적으로 교체한다.
     *
     * <p>{@code Files.writeString} 은 기존 파일을 <b>먼저 0바이트로 자른다</b>. 쓰는 도중
     * 게임이 죽으면 그 달 원장이 통째로 날아간다. flush 시점이 접속 종료·게임 종료와 겹쳐서
     * 위험이 실재한다. temp → move 로 바꾸면 최악의 경우에도 직전 파일이 그대로 남는다.
     */
    private void writeFile(YearMonth ym, List<TransactionRecord> list) {
        Path p = pathFor(ym);
        try {
            Files.createDirectories(dir);
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(list, LIST_TYPE));
            try {
                Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // 일부 환경(네트워크 드라이브·OneDrive 등)은 원자적 이동을 지원하지 않는다
                Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            LOG.warn("[nakin] 원장 저장 실패: {}", p, e);
        }
    }
}
