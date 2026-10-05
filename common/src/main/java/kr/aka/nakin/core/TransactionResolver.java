package kr.aka.nakin.core;

import kr.aka.nakin.config.DtConfig;
import kr.aka.nakin.core.TransferClassifier.CrossCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 시간창 안에서 채팅 신호 ↔ 잔고 변동(ΔG)을 결합해 최종 레코드를 만든다.
 *
 * <ol>
 *   <li>메시지에 금액이 있는 신호: 그 금액을 쓰고, 같은 부호·같은 금액(±{@value #AMOUNT_TOLERANCE}냥)의
 *       ΔG 가 오면 교차검증 표시(HIGH). 안 와도 메시지 금액으로 기록(MEDIUM).</li>
 *   <li>requireDelta 신호(남의 행동일 수 있는 방송): ΔG 매칭이 없으면 기록하지 않는다.</li>
 *   <li>금액 없는 신호(모루·전직·강화): 대기창 동안 부호가 맞는 ΔG 를 모아 <b>총액 ÷ 시도 횟수</b>.
 *       연속 시도는 잔고가 합쳐 들어오기 때문(실측: 강화 4회에 변동 3번).</li>
 *   <li>어디에도 안 걸린 ΔG 는 버린다 — 메시지 없는 잔고 변동을 추정 기록하던 "기타"는
 *       과거에 반복적으로 오집계를 내 폐기됐다. 되살리지 말 것.</li>
 * </ol>
 *
 * <p>±1냥 허용: 동글랜드 잔고는 내부적으로 소수(플리마켓 10453.58냥 등)인데 화면은 정수로 보여서
 * 메시지 금액(반올림)과 ΔG 가 1냥 어긋날 수 있다.
 *
 * <p>스레드: 클라이언트 메인 틱에서만 호출.
 */
public final class TransactionResolver {
    private static final Logger LOG = LoggerFactory.getLogger("nakin");

    static final long AMOUNT_TOLERANCE = 1;
    private static final long DELTA_DEDUP_MS = 500;
    /**
     * ΔG 로 금액을 매기는 신호·내 잔고 확인이 필요한 신호의 대기창. GUI 안에서 일어나는 거래는
     * 잔고 표시 갱신이 채팅보다 한참 늦게 오는 경우가 많다(실측: 1.5초로는 통째로 누락됐다).
     */
    static final long DELTA_SIGNAL_WAIT_MS = 15_000;

    private static final class PendingSignal {
        final TradeSignal sig;
        final long ts;
        boolean matched;
        PendingSignal(TradeSignal sig, long ts) { this.sig = sig; this.ts = ts; }
    }

    private static final class PendingDelta {
        final long delta;
        final long ts;
        /** 이미 메시지 금액으로 남긴 기록의 늦은 갱신으로 짝지어짐 — 다른 신호가 가져가지 않고 대조에도 안 넣는다. */
        boolean explained;

        PendingDelta(long delta, long ts) {
            this.delta = delta;
            this.ts = ts;
        }
    }

    /** 창(대장간 등)에서 본 결제 비용 — 금액 → (카테고리, 라벨, 마지막으로 본 시각). */
    private record SeenCost(String category, String label, long seenAt) {}
    private final java.util.Map<Long, SeenCost> guiCosts = new java.util.HashMap<>();
    /** 비용을 본 뒤 이 시간 안에 같은 금액이 빠져야 그 결제로 인정한다. */
    static final long GUI_COST_FRESH_MS = 10_000;
    /** 연타로 잔고 표시가 합쳐 내려와도(−240,000 = 120,000 × 2) 이 배수까지는 나눠 기록한다. */
    private static final int GUI_COST_MAX_MULTIPLE = 10;

    private final DtConfig config;
    private final TransferClassifier classifier;
    private final Consumer<TransactionRecord> sink;
    private Consumer<String> notifier = msg -> { };

    private final List<PendingSignal> signals = new ArrayList<>();
    private final List<PendingDelta> deltas = new ArrayList<>();
    private long lastDeltaValue = 0;
    private long lastDeltaTs = 0;

    // 진단용(/낙인 진단)
    private static volatile String lastSignalInfo;
    private static volatile String lastDeltaInfo;
    private static volatile String lastSettleInfo;
    private static volatile String lastGuiCostInfo;
    public static String lastGuiCostInfo() { return lastGuiCostInfo; }
    public static String lastSignalInfo() { return lastSignalInfo; }
    public static String lastDeltaInfo() { return lastDeltaInfo; }
    public static String lastSettleInfo() { return lastSettleInfo; }

    public TransactionResolver(DtConfig config, TransferClassifier classifier,
                               Consumer<TransactionRecord> sink) {
        this.config = config;
        this.classifier = classifier;
        this.sink = sink;
    }

    /** 금액을 못 알아낸 거래를 사용자에게 알리는 통로(로더가 채팅으로 연결). */
    public void setNotifier(Consumer<String> n) {
        if (n != null) this.notifier = n;
    }

    public void onSignal(TradeSignal sig) {
        onSignal(sig, System.currentTimeMillis());
    }

    public void onSignal(TradeSignal sig, long now) {
        if (sig == null) return;
        ActivityLog.signal(sig);
        lastSignalInfo = sig.category + " · " + sig.label + (sig.amountFromDelta ? "" : " · " + sig.amount);
        signals.add(new PendingSignal(sig, now));
    }

    /**
     * 로더가 열린 창의 아이템 설명에서 읽은 비용을 알려준다(창이 열려 있는 동안 주기적으로).
     * 채팅 문구와 짝지어지지 않은 잔고 감소가 이 금액과 같으면 그 결제로 기록한다 — 생활장비 강화처럼
     * 채팅에 금액이 없는 거래를 성공·실패 구분 없이 잡는 방법(GuiCostLore 참고).
     */
    public void noteGuiCosts(List<GuiCostLore.GuiCost> costs, long now) {
        if (costs == null || costs.isEmpty()) return;
        for (GuiCostLore.GuiCost c : costs) guiCosts.put(c.amount(), new SeenCost(c.category(), c.label(), now));
        guiCosts.values().removeIf(sc -> now - sc.seenAt > 120_000);
        StringBuilder sb = new StringBuilder();
        for (var e : guiCosts.entrySet()) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(e.getValue().label).append(' ').append(String.format("%,d", e.getKey()));
        }
        lastGuiCostInfo = sb.toString();
    }

    public void onDelta(long delta) {
        onDelta(delta, System.currentTimeMillis());
    }

    public void onDelta(long delta, long now) {
        if (delta == 0) return;
        // 같은 ΔG 가 아주 짧게 반복되면(잔고 소스 중복 읽기) 무시. 단 금액 없는 신호가 대기 중이면
        // 같은 금액을 연속으로 쓰는 정상 반복(모루 연속 등)이라 예외.
        if (delta == lastDeltaValue && now - lastDeltaTs < DELTA_DEDUP_MS && !hasSignalWaitingForDelta()) {
            LOG.info("[nakin] 중복 ΔG 무시: {}", delta);
            return;
        }
        lastDeltaValue = delta;
        lastDeltaTs = now;
        lastDeltaInfo = (delta > 0 ? "+" : "") + delta;
        deltas.add(new PendingDelta(delta, now));
    }

    private boolean hasSignalWaitingForDelta() {
        for (PendingSignal ps : signals) {
            if (!ps.matched && (ps.sig.amountFromDelta || ps.sig.requireDelta || ps.sig.isCostHint())) return true;
        }
        return false;
    }

    /** 처리 대기 중인 신호·ΔG 가 없는가 — 잔고 대조가 "지금 값이 확정된 상태"인지 판단. */
    public boolean isIdle() {
        return signals.isEmpty() && deltas.isEmpty();
    }

    /** 만료된 신호/델타를 확정. 클라 틱마다 호출. */
    public void tick(long now) {
        tickNow = now;
        long w = config.matchWindowMs;

        // 1) 메시지 금액 신호 ↔ ΔG 정확 매칭(부호 + 금액 ±1)
        for (PendingSignal ps : signals) {
            if (ps.matched || ps.sig.amountFromDelta || ps.sig.isCostHint()) continue;
            if (takeDelta(ps.sig.expectedSign(), ps.sig.amount)) ps.matched = true;
        }

        // 1-b) 가격표 결제(카드팩 뒤집기·감정): 후보 금액 중 하나와 정확히 같은 감소가 있으면 그 금액으로
        Iterator<PendingSignal> hit = signals.iterator();
        while (hit.hasNext()) {
            PendingSignal ps = hit.next();
            if (!ps.sig.isCostHint()) continue;
            for (long c : ps.sig.expectedCosts) {
                if (takeDelta(-1, c)) {
                    emit(classifier.classifyByDelta(ps.sig, c, ps.ts, "가격표 금액과 잔고 변동 일치"));
                    lastSettleInfo = ps.sig.label + " " + String.format("%,d", c) + " — 가격표로 기록";
                    hit.remove();
                    break;
                }
            }
        }

        // 2) 금액 없는 신호 — 카테고리별로 묶어 정산
        settleDeltaSignals(now, w);

        // 3) 메시지 금액 신호 확정
        Iterator<PendingSignal> sit = signals.iterator();
        while (sit.hasNext()) {
            PendingSignal ps = sit.next();
            if (ps.sig.amountFromDelta) continue;
            if (ps.sig.isCostHint()) {
                // 대기창 안에 가격표 금액이 안 빠졌으면 무료였던 것 — 조용히 버린다
                if (now - ps.ts > Math.max(w, DELTA_SIGNAL_WAIT_MS)) sit.remove();
                continue;
            }
            // 매칭됐으면 바로 확정, 아니면 대기창이 끝날 때까지 ΔG 를 기다린다
            long limit = ps.sig.requireDelta ? Math.max(w, DELTA_SIGNAL_WAIT_MS) : w;
            if (!ps.matched && now - ps.ts <= limit) continue;
            if (ps.sig.requireDelta && !ps.matched) {
                LOG.info("[nakin] {} — 내 잔고 무변동, 남의 거래로 보고 스킵", ps.sig.label);
                lastSettleInfo = ps.sig.label + " — 내 잔고 무변동이라 기록 안 함";
                sit.remove();
                continue;
            }
            emit(classifier.classify(ps.sig, ps.matched ? CrossCheck.MATCHED_EXACT : CrossCheck.NONE, ps.ts));
            sit.remove();
        }

        // 4) 만료 ΔG — 창에서 본 비용과 정확히 같으면 그 결제로, 아니면 폐기
        //    (메시지 신호가 먼저 가져갈 수 있게 만료 시점까지 기다린 뒤에만 쓴다)
        long keep = hasSignalWaitingForDelta() ? Math.max(w, DELTA_SIGNAL_WAIT_MS) : w;
        Iterator<PendingDelta> dit = deltas.iterator();
        while (dit.hasNext()) {
            PendingDelta pd = dit.next();
            if (now - pd.ts <= keep) continue;
            // 늦게 온 ΔG 가 이미 메시지 금액으로 남긴 기록 몫이면 그걸로 끝 — 창 비용으로 또 기록하지 않는다
            if (pd.explained || matchesRecentMessageRecord(pd, true)) {
                dit.remove();
                continue;
            }
            boolean used = pd.delta < 0 && settleByGuiCost(pd);
            if (!used) unexplainedTotal += pd.delta; // 잔고 대조용
            dit.remove();
        }
    }

    /** 창 비용 매칭 결과: 단가 × 횟수. */
    private record GuiMatch(SeenCost cost, long unit, int times) {}

    /** 이 잔고 감소가 최근 창에 보인 비용(또는 그 정수배)과 같으면 그 결제. 없으면 null. */
    private GuiMatch findGuiCost(PendingDelta pd) {
        if (pd.delta >= 0) return null;
        long mag = -pd.delta;
        GuiMatch best = null;
        for (var e : guiCosts.entrySet()) {
            SeenCost sc = e.getValue();
            if (pd.ts - sc.seenAt > GUI_COST_FRESH_MS || sc.seenAt - pd.ts > GUI_COST_FRESH_MS) continue;
            long cost = e.getKey();
            int n;
            if (Math.abs(mag - cost) <= AMOUNT_TOLERANCE) n = 1;
            else if (cost > 0 && mag % cost == 0 && mag / cost <= GUI_COST_MAX_MULTIPLE) n = (int) (mag / cost);
            else continue;
            if (best == null || n < best.times) best = new GuiMatch(sc, cost, n); // 배수보다 정확 일치 우선
        }
        return best;
    }

    /** 짝 없는 잔고 감소가 최근 창에 보인 비용(또는 그 정수배)과 같으면 결제 기록. @return 기록했으면 true */
    private boolean settleByGuiCost(PendingDelta pd) {
        GuiMatch m = findGuiCost(pd);
        if (m == null) return false;
        long mag = -pd.delta;
        for (int i = 0; i < m.times; i++) {
            TradeSignal sig = TradeSignal.of(TradeSignal.Flow.EXPENSE, m.cost.category,
                    i == m.times - 1 ? mag - m.unit * (m.times - 1) : m.unit, 0, m.cost.label, "gui-cost");
            emit(classifier.classifyByDelta(sig, sig.amount, pd.ts,
                    "창 표시 비용과 잔고 변동 일치" + (m.times > 1 ? " (" + m.times + "회 합산 분할)" : "")));
        }
        lastSettleInfo = m.cost.label + " " + m.times + "건 — 창 표시 비용으로 기록";
        return true;
    }

    /**
     * 어떤 기록에도 쓰이지 않고 버려진 잔고 변동의 합 — 잔고 대조({@link WalletCheck})가 읽는다.
     * 장부 합계를 잔고와 비교하지 않고 "설명 안 된 변동"만 세므로, 지난 기록 수정·날짜 경계·
     * 메시지 금액과 실제 변동의 1냥 반올림 차이·접속 전 체결 알림에 흔들리지 않는다(2026-10-05 Codex 리뷰).
     */
    private long unexplainedTotal;
    /** 마지막 tick 시각 — 기록을 남긴 처리 시각(신호 시각이 아니라). */
    private long tickNow;

    /** 잔고 확인 없이(메시지 금액으로) 남긴 최근 기록 {처리 시각, 잔고 영향} — 늦게 온 ΔG 를 그 기록 몫으로 본다. */
    private final java.util.ArrayDeque<long[]> recentMessageOnly = new java.util.ArrayDeque<>();
    private static final long LATE_DELTA_MS = 30_000;

    public long unexplainedTotal() {
        return unexplainedTotal;
    }

    /** 새 접속 — 이전 서버의 대기 ΔG·늦은 갱신 짝 후보는 이번 접속과 무관하다. */
    public void newSession() {
        deltas.clear();
        recentMessageOnly.clear();
        // 금액을 잔고 변동으로 정할 신호는 새 서버의 변동과 짝지으면 안 된다 — 지금 정산(미확인)하고 비운다
        List<PendingSignal> stale = new ArrayList<>();
        for (PendingSignal ps : signals) {
            if (ps.sig.amountFromDelta || ps.sig.isCostHint()) stale.add(ps);
        }
        signals.removeAll(stale);
        for (PendingSignal ps : stale) {
            if (ps.sig.amountFromDelta && !ps.sig.requireDelta) emitUnresolved(List.of(ps), "접속이 바뀌어 잔고 변동 미확인");
        }
    }

    /** 잔고로 잘못 잡았던 줄에서 나온 대기 ΔG 를 버린다(거래가 아니므로 대조에도 넣지 않는다). */
    public void discardPendingDeltas() {
        deltas.clear();
    }

    /**
     * 신호 대기창이 끝난 뒤에야 잔고가 갱신된 경우(송금 등) — 같은 방향·같은 금액(±1)의 최근 기록이 있으면 설명됨.
     * @param consume 짝을 찾으면 후보에서 지울지(만료 정산) / 확인만 할지(다른 신호가 가져가지 않게)
     */
    private boolean matchesRecentMessageRecord(PendingDelta pd, boolean consume) {
        recentMessageOnly.removeIf(e -> pd.ts - e[0] > LATE_DELTA_MS);
        Iterator<long[]> it = recentMessageOnly.iterator();
        while (it.hasNext()) {
            long[] e = it.next();
            if (Long.signum(e[1]) == Long.signum(pd.delta)
                    && Math.abs(Math.abs(e[1]) - Math.abs(pd.delta)) <= AMOUNT_TOLERANCE) {
                if (consume) it.remove();
                return true;
            }
        }
        return false;
    }

    /** 잔고 표시가 채팅보다 먼저 갱신되는 경우를 위한 여유. */
    static final long DELTA_LEAD_MS = 1_000;
    /** 같은 종류 알림이 이 간격 안에 연달아 오면 한 묶음 — 잔고가 합쳐 한 번에 내려올 수 있다. */
    static final long BURST_MS = 2_000;

    /**
     * 만료된 금액 없는 신호를 확정한다. 신호마다 <b>자기 뒤에 온 잔고 변동 하나</b>와 짝짓는다.
     *
     * <p>예전엔 창 안의 같은 방향 변동을 전부 모아 나눴다 — 플리마켓 알림이 겹치면(2개 328냥,
     * 5개 100냥) 둘을 합쳐 나누고, 같은 15초 안의 상점 구매(200,000)까지 가져가 버렸다. 그래서:
     * <ul>
     *   <li>후보는 신호 직전 {@value #DELTA_LEAD_MS}ms ~ 대기창 끝 사이 변동만, 창 비용으로 설명되는
     *       변동(상점·강화 결제)은 제외.</li>
     *   <li>{@value #BURST_MS}ms 안에 연달아 온 같은 종류 신호는 한 묶음. 변동 수가 신호 수 이상이면
     *       순서대로 하나씩, 모자라면(잔고가 합쳐 내려옴) 합계를 나눈다 — 안 나눠떨어지면 순서대로 배정.</li>
     *   <li>남는 변동은 건드리지 않는다(다른 신호·창 비용 몫).</li>
     * </ul>
     */
    private void settleDeltaSignals(long now, long w) {
        long limit = Math.max(w, DELTA_SIGNAL_WAIT_MS);
        while (true) {
            PendingSignal oldest = null;
            for (PendingSignal ps : signals) {
                if (ps.sig.amountFromDelta && now - ps.ts > limit) { oldest = ps; break; }
            }
            if (oldest == null) return;
            String key = oldest.sig.category + "|" + oldest.sig.flow;
            List<PendingSignal> group = new ArrayList<>();
            for (PendingSignal ps : signals) {
                if (ps.sig.amountFromDelta && key.equals(ps.sig.category + "|" + ps.sig.flow)
                        && ps.ts >= oldest.ts && ps.ts - oldest.ts <= BURST_MS) group.add(ps);
            }
            signals.removeAll(group);
            settleGroup(group, limit);
        }
    }

    private void settleGroup(List<PendingSignal> group, long limit) {
        PendingSignal first = group.get(0);
        int sign = first.sig.expectedSign();
        long from = first.ts - DELTA_LEAD_MS;
        long to = group.get(group.size() - 1).ts + limit;
        List<PendingDelta> cand = new ArrayList<>();
        for (PendingDelta pd : deltas) {
            if (Long.signum(pd.delta) != sign || pd.ts < from || pd.ts > to) continue;
            if (findGuiCost(pd) != null) continue; // 창에 보인 결제 — settleByGuiCost 몫
            if (pd.explained) continue;
            if (matchesRecentMessageRecord(pd, true)) { // 이미 기록된 거래의 늦은 잔고 갱신 — 일대일로 짝지음
                pd.explained = true;
                continue;
            }
            cand.add(pd);
        }
        int n = group.size();
        String cat = first.sig.category;
        if (cand.isEmpty()) {
            // requireDelta(우편 수령처럼 돈이 안 올 수도 있는 문구)는 잔고가 그대로면 거래가 아니다
            if (!first.sig.requireDelta) emitUnresolved(group, "잔고 변동 미검출");
            return;
        }
        if (cand.size() >= n) {
            for (int i = 0; i < n; i++) {
                PendingSignal ps = group.get(i);
                PendingDelta pd = cand.get(i);
                deltas.remove(pd);
                emit(classifier.classifyByDelta(ps.sig, Math.abs(pd.delta), ps.ts, null));
            }
        } else {
            long total = 0;
            for (PendingDelta pd : cand) total += Math.abs(pd.delta);
            deltas.removeAll(cand);
            if (total % n == 0) {
                for (PendingSignal ps : group) {
                    emit(classifier.classifyByDelta(ps.sig, total / n, ps.ts,
                            "잔고 변동 합계를 " + n + "회로 나눔"));
                }
            } else {
                for (int i = 0; i < n; i++) {
                    PendingSignal ps = group.get(i);
                    if (i < cand.size()) {
                        emit(classifier.classifyByDelta(ps.sig, Math.abs(cand.get(i).delta), ps.ts, null));
                    } else if (!ps.sig.requireDelta) {
                        emitUnresolved(List.of(ps), "잔고 변동 수 부족");
                    }
                }
            }
        }
        lastSettleInfo = cat + " " + n + "건 — 잔고 변동으로 기록";
    }

    /** 금액을 끝내 못 알아낸 거래 — 버리지 않고 금액 0·미확인으로 남겨 빠졌다는 사실이 보이게 한다. */
    private void emitUnresolved(List<PendingSignal> group, String reason) {
        if (group.isEmpty()) return;
        TradeSignal first = group.get(0).sig;
        lastSettleInfo = first.category + " " + group.size() + "건 — 미확인(" + reason + ")";
        if (!config.recordUnresolved) {
            LOG.info("[nakin] {} {}건 — {} (미확인 기록 꺼짐)", first.category, group.size(), reason);
            return;
        }
        for (PendingSignal ps : group) emit(classifier.unresolved(ps.sig, ps.ts, reason));
        String word = first.expectedSign() < 0 ? "지출" : "수입";
        notifier.accept("§6[낙인] §f" + first.label + " §7금액을 확인하지 못했습니다"
                + (group.size() > 1 ? " (" + group.size() + "건)" : "")
                + "\n§7  내역에 §e미확인§7 으로 남겼습니다. §f/낙인 추가 " + word + " <금액> " + first.label);
    }

    /** 부호가 같고 금액이 ±1냥 안인 ΔG 하나를 꺼낸다. */
    private boolean takeDelta(int sign, long magnitude) {
        Iterator<PendingDelta> it = deltas.iterator();
        while (it.hasNext()) {
            PendingDelta pd = it.next();
            if (pd.explained || Long.signum(pd.delta) != sign) continue;
            if (Math.abs(Math.abs(pd.delta) - magnitude) > AMOUNT_TOLERANCE) continue;
            it.remove();
            return true;
        }
        return false;
    }

    private void emit(TransactionRecord rec) {
        if (!rec.crossChecked && rec.amount > 0) {
            boolean in = rec.kind == TransactionRecord.Kind.INCOME || rec.kind == TransactionRecord.Kind.TRANSFER_IN;
            recentMessageOnly.addLast(new long[]{tickNow, in ? rec.amount : -rec.amount});
        }
        LOG.info("[nakin] 레코드: {}", rec);
        sink.accept(rec);
    }
}
