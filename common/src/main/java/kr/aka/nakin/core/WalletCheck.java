package kr.aka.nakin.core;

import java.time.LocalDate;

/**
 * 잔고 대조 — "이번 접속 동안 잔고가 움직였는데 기록이 안 남은 게 있나".
 *
 * <p>어떤 기록에도 쓰이지 않고 버려진 잔고 변동의 합({@link TransactionResolver#unexplainedTotal()})이
 * 기준 시점보다 늘었으면 그만큼이 "기록에 없는 변동"이다. 0 이 아니면 기록이 빠졌거나(누락)
 * 금액이 틀렸다(잔고 변동과 안 맞아 짝을 못 찾음). 제보 전에 사용자가 먼저 알아챈다.
 *
 * <p>처음엔 빌띵처럼 "오늘 장부의 잔고 영향 합 vs 실제 잔고"를 비교했지만, 지난 기록을 고치거나
 * 접속 전에 체결된 거래 알림이 오거나 날짜가 넘어가거나 메시지 금액이 1냥 반올림돼도 경고가 떴다
 * (2026-10-05 Codex 리뷰). 버려진 변동만 세면 그런 경우와 무관하다.
 *
 * <p>거래 처리 중엔 값이 바뀌므로 {@link #STABLE_MS} 동안 그대로이고 대기 중인 거래가 없을 때만 확정한다.
 * MC 의존성 0.
 */
public final class WalletCheck {
    /** 화면·틱 루프가 함께 보는 실행 중 인스턴스. */
    public static final WalletCheck LIVE = new WalletCheck();

    static final long STABLE_MS = 10_000;

    private boolean started;
    private LocalDate day;
    private long base;
    private long last;

    private long candidate;
    private long candidateSince;
    private Long confirmed; // null = 아직 확정 전
    private boolean reading;

    /**
     * 매 틱 호출.
     *
     * @param balanceKnown     잔고를 읽고 있는가(못 읽으면 대조 불가)
     * @param unexplainedTotal 지금까지 버려진 잔고 변동의 합
     * @param idle             대기 중인 거래(신호·ΔG)가 없는가
     */
    public synchronized void observe(long now, boolean balanceKnown, LocalDate today, long unexplainedTotal,
                                     boolean idle) {
        reading = balanceKnown;
        if (!balanceKnown || today == null) return;
        if (!started || !today.equals(day)) {
            started = true;
            day = today;
            rebaseTo(unexplainedTotal, now);
            return;
        }
        last = unexplainedTotal;
        long diff = unexplainedTotal - base;
        boolean changed = diff != candidate;
        if (changed) {
            candidate = diff;
            confirmed = null; // 값이 바뀌었으면 옛 결과("일치")를 계속 보여주지 않는다
        }
        if (!idle || changed) candidateSince = now;
        if (idle && now - candidateSince >= STABLE_MS) confirmed = candidate;
    }

    /** 지금 차이를 "알고 넘어감" — 기준을 현재로 옮긴다. */
    public synchronized void acknowledge(long now) {
        if (started) rebaseTo(last, now);
    }

    /** 재접속·잔고 줄 재탐색 — 다음 틱부터 새 기준. */
    public synchronized void reset() {
        started = false;
        confirmed = null;
    }

    /** 잔고를 읽어 대조 중인가. false 면 대조 불가. 한 번 읽었어도 지금 못 읽고 있으면 false. */
    public synchronized boolean started() { return started && reading; }

    /** 확정된 "기록에 없는 변동"(+면 기록 없이 잔고가 늘었음). 확정 전이면 null. */
    public synchronized Long unexplained() { return confirmed; }

    private void rebaseTo(long total, long now) {
        base = last = total;
        candidate = 0;
        candidateSince = now;
        confirmed = null;
    }
}
