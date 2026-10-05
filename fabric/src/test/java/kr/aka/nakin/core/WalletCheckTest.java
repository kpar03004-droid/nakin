package kr.aka.nakin.core;

import kr.aka.nakin.config.DtConfig;
import kr.aka.nakin.core.TradeSignal.Flow;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 잔고 대조 — "어떤 기록에도 쓰이지 않은 잔고 변동"만 센다. */
class WalletCheckTest {
    private static final LocalDate D = LocalDate.of(2026, 10, 5);

    private final DtConfig config = new DtConfig();
    private final List<TransactionRecord> out = new ArrayList<>();
    private final TransactionResolver resolver =
            new TransactionResolver(config, new TransferClassifier(config), out::add);

    private void run(long until) {
        for (long t = 0; t <= until; t += 50) resolver.tick(t);
    }

    @Test
    void 기록된_거래만_있으면_설명_안_된_변동은_0() {
        resolver.onSignal(TradeSignal.of(Flow.INCOME, "대장장이", 10_454, 1, "보석", "raw"), 0);
        resolver.onDelta(10_453, 300); // 메시지 반올림과 1냥 차이 — 같은 거래
        run(20_000);
        assertEquals(0, resolver.unexplainedTotal());
    }

    @Test
    void 짝_없는_잔고_변동은_쌓인다() {
        resolver.onDelta(1_000, 0); // 추천 보상 등 문구 없이 +1,000
        run(20_000);
        assertEquals(1_000, resolver.unexplainedTotal());
    }

    @Test
    void 신호_대기가_끝난_뒤_늦게_온_잔고_변동은_그_기록_몫() {
        resolver.onSignal(TradeSignal.of(Flow.TRANSFER_IN, "송금", 436_500, 0, "받음", "raw"), 0);
        resolver.onDelta(436_500, 5_000); // 기록이 먼저 남은 뒤 잔고 갱신(2026-10-02 실측 1초 지연보다 넉넉히)
        run(40_000);
        assertEquals(1, out.size());
        assertEquals(0, resolver.unexplainedTotal(), "늦게 와도 오경고 아님");
    }

    @Test
    void 대조는_안정된_뒤에_확정하고_넘어가면_기준을_옮긴다() {
        WalletCheck w = new WalletCheck();
        w.observe(0, true, D, 0, true);             // 기준
        w.observe(1_000, true, D, 1_000, true);     // 설명 안 된 +1,000
        assertNull(w.unexplained(), "10초 안정 전엔 확정하지 않음");
        w.observe(12_000, true, D, 1_000, true);
        assertEquals(1_000L, w.unexplained());

        w.acknowledge(13_000);
        assertNull(w.unexplained());
        w.observe(24_000, true, D, 1_000, true);
        assertEquals(0L, w.unexplained());
    }

    @Test
    void 처리_중에는_확정하지_않고_날짜가_바뀌면_새_기준() {
        WalletCheck w = new WalletCheck();
        w.observe(0, true, D, 0, true);
        w.observe(1_000, true, D, -50, false);
        w.observe(20_000, true, D, -50, false);
        assertNull(w.unexplained());
        w.observe(21_000, true, D.plusDays(1), -50, true); // 날짜 바뀜 → 기준 재설정
        w.observe(32_000, true, D.plusDays(1), -50, true);
        assertEquals(0L, w.unexplained());
    }

    @Test
    void 잔고를_못_읽으면_대조_불가() {
        WalletCheck w = new WalletCheck();
        w.observe(0, false, D, 0, true);
        assertFalse(w.started());
        assertNull(w.unexplained());
    }
}
