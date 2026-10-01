package kr.aka.nakin.core;

import kr.aka.nakin.config.DtConfig;
import kr.aka.nakin.core.TradeSignal.Flow;
import kr.aka.nakin.core.TransactionRecord.Confidence;
import kr.aka.nakin.core.TransactionRecord.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 채팅 신호 ↔ 잔고 변동 결합 규칙. 시계는 테스트가 직접 넘긴다. */
class TransactionResolverTest {

    private final DtConfig config = new DtConfig();
    private final List<TransactionRecord> out = new ArrayList<>();
    private final List<String> notices = new ArrayList<>();
    private final TransactionResolver resolver =
            new TransactionResolver(config, new TransferClassifier(config), out::add);

    { resolver.setNotifier(notices::add); }

    private static TradeSignal sale(long amount) {
        return TradeSignal.of(Flow.INCOME, "대장장이", amount, 1, "보석ㅣ영원", "raw");
    }

    @Test void 메시지와_잔고변동이_맞으면_HIGH() {
        resolver.onSignal(sale(132), 0);
        resolver.onDelta(132, 100);
        resolver.tick(200);
        assertEquals(1, out.size());
        assertEquals(Confidence.HIGH, out.get(0).confidence);
        assertTrue(out.get(0).crossChecked);
        assertEquals(132, out.get(0).amount);
    }

    @Test void 소수_잔고라_1냥_차이는_같은거래() {
        resolver.onSignal(sale(10454), 0); // 메시지 10453.58 반올림
        resolver.onDelta(10453, 100);      // 화면 잔고는 내림 표시
        resolver.tick(200);
        assertEquals(Confidence.HIGH, out.get(0).confidence);
        assertEquals(10454, out.get(0).amount, "금액은 메시지 기준");
    }

    @Test void 잔고변동이_없어도_메시지금액으로_기록() {
        resolver.onSignal(sale(500), 0);
        resolver.tick(1000);
        assertTrue(out.isEmpty(), "시간창 동안은 기다린다");
        resolver.tick(config.matchWindowMs + 1);
        assertEquals(1, out.size());
        assertEquals(Confidence.MEDIUM, out.get(0).confidence);
        assertEquals(500, out.get(0).amount);
    }

    @Test void 신호없는_잔고변동은_기록하지_않는다() {
        resolver.onDelta(-7777, 0);
        resolver.tick(100_000);
        assertTrue(out.isEmpty(), "catch-all(기타)은 폐기된 설계 — 되살리지 말 것");
    }

    @Test void 남의것일수있는_신호는_내잔고가_안움직이면_버린다() {
        TradeSignal vault = TradeSignal.of(Flow.EXPENSE, "마을 금고", 100_000, 0, "마을 금고 입금", "raw")
                .requiringDelta();
        resolver.onSignal(vault, 0);
        resolver.tick(TransactionResolver.DELTA_SIGNAL_WAIT_MS + 10);
        assertTrue(out.isEmpty());
    }

    @Test void 남의것일수있는_신호도_내잔고가_움직이면_기록() {
        TradeSignal vault = TradeSignal.of(Flow.EXPENSE, "마을 금고", 100_000, 0, "마을 금고 입금", "raw")
                .requiringDelta();
        resolver.onSignal(vault, 0);
        resolver.onDelta(-100_000, 3_000);
        resolver.tick(3_100);
        assertEquals(1, out.size());
        assertEquals(Kind.EXPENSE, out.get(0).kind);
    }

    @Test void 광장_진입때_겹친_거래소_알림은_한번만() {
        // 실측 순서: 수령 36,166 → 같은 금액 체결 알림 ×2. 잔고는 한 번만 늘었다.
        CurrencyParser p = new CurrencyParser(() -> "_me");
        List<TradeSignal> sigs = new ArrayList<>();
        sigs.addAll(p.parse("거래소 • 36,166냥을 수령했습니다.", 0));
        sigs.addAll(p.parse("거래소 • PlayerA 님이 이용권ㅣ프리미엄 광산 x1 을 36,166 냥에 구매했습니다. (수수료: 2,722냥)", 2_000));
        sigs.addAll(p.parse("거래소 • PlayerA 님이 이용권ㅣ프리미엄 광산 x1 을 36,166 냥에 구매했습니다. (수수료: 2,722냥)", 4_000));
        assertEquals(3, sigs.size());
        resolver.onSignal(sigs.get(0), 0);
        resolver.onDelta(36_166, 100);
        resolver.tick(200);
        resolver.onSignal(sigs.get(1), 2_000);
        resolver.onSignal(sigs.get(2), 4_000);
        resolver.tick(4_000 + TransactionResolver.DELTA_SIGNAL_WAIT_MS + 10);
        assertEquals(1, out.size(), out.toString());
        assertEquals(36_166, out.get(0).amount);
    }

    @Test void 광장에서_실시간으로_팔리면_기록() {
        CurrencyParser p = new CurrencyParser(() -> "_me");
        TradeSignal s = p.parse("거래소 • PlayerA 님이 주괴ㅣ달빛 x3 을 69,750 냥에 구매했습니다. (수수료: 5,250냥)", 0).get(0);
        resolver.onSignal(s, 0);
        resolver.onDelta(69_750, 300);
        resolver.tick(400);
        assertEquals(1, out.size());
        assertEquals(69_750, out.get(0).amount);
        assertEquals("거래소", out.get(0).category);
    }

    private static List<GuiCostLore.GuiCost> enhanceCost(long v) {
        return List.of(new GuiCostLore.GuiCost("강화", "생활장비 강화", v));
    }

    @Test void 창에_보인_강화비용만큼_빠지면_강화_지출() {
        resolver.noteGuiCosts(enhanceCost(120_000), 0);
        resolver.onDelta(-120_000, 1_000);
        resolver.tick(1_000 + config.matchWindowMs + 1);
        assertEquals(1, out.size());
        assertEquals("강화", out.get(0).category);
        assertEquals(120_000, out.get(0).amount);
        assertEquals(Kind.EXPENSE, out.get(0).kind);
    }

    @Test void 연타로_합쳐진_강화비용은_나눈다() {
        resolver.noteGuiCosts(enhanceCost(120_000), 0);
        resolver.onDelta(-240_000, 1_000);
        resolver.tick(1_000 + config.matchWindowMs + 1);
        assertEquals(2, out.size());
        assertTrue(out.stream().allMatch(r -> r.amount == 120_000));
    }

    @Test void 창_비용과_다른_금액은_가져가지_않는다() {
        resolver.noteGuiCosts(enhanceCost(120_000), 0);
        resolver.onDelta(-55_555, 1_000);
        resolver.tick(1_000 + config.matchWindowMs + 1);
        assertTrue(out.isEmpty());
    }

    @Test void 오래전에_본_비용은_인정하지_않는다() {
        resolver.noteGuiCosts(enhanceCost(120_000), 0);
        resolver.onDelta(-120_000, TransactionResolver.GUI_COST_FRESH_MS + 5_000);
        resolver.tick(TransactionResolver.GUI_COST_FRESH_MS + 5_000 + config.matchWindowMs + 1);
        assertTrue(out.isEmpty());
    }

    @Test void 채팅_문구가_있으면_그쪽이_먼저() {
        resolver.noteGuiCosts(enhanceCost(120_000), 0);
        resolver.onSignal(TradeSignal.of(Flow.EXPENSE, "송금", 120_000, 0, "PlayerA에게 송금", "r"), 900);
        resolver.onDelta(-120_000, 1_000);
        resolver.tick(1_000 + config.matchWindowMs + 1);
        assertEquals(1, out.size());
        assertEquals("송금", out.get(0).category, "같은 금액이어도 채팅으로 확인된 거래가 우선");
    }

    @Test void 카드팩_유료장은_가격표_금액만큼_빠질때_기록() {
        long[] costs = {30_000, 100_000};
        resolver.onSignal(TradeSignal.costHint("카드팩", "카드팩 뒤집기", costs, "r"), 0);      // 1장째 무료
        resolver.onSignal(TradeSignal.costHint("카드팩", "카드팩 뒤집기", costs, "r"), 2_000);  // 2장째 3만
        resolver.onDelta(-30_000, 2_300);
        resolver.onSignal(TradeSignal.costHint("카드팩", "카드팩 뒤집기", costs, "r"), 4_000);  // 3장째 10만
        resolver.onDelta(-100_000, 4_300);
        resolver.tick(4_400);
        resolver.tick(4_000 + TransactionResolver.DELTA_SIGNAL_WAIT_MS + 10);
        assertEquals(2, out.size(), out.toString());
        assertEquals(130_000, out.stream().mapToLong(r -> r.amount).sum());
        assertTrue(out.stream().allMatch(r -> "카드팩".equals(r.category)));
    }

    @Test void 무료_뒤집기는_기록하지_않는다() {
        resolver.onSignal(TradeSignal.costHint("카드팩", "카드팩 뒤집기", new long[]{30_000, 100_000}, "r"), 0);
        resolver.tick(TransactionResolver.DELTA_SIGNAL_WAIT_MS + 10);
        assertTrue(out.isEmpty());
    }

    @Test void 가격표와_다른_금액은_가져가지_않는다() {
        resolver.onSignal(TradeSignal.costHint("이용료", "유물 감정", new long[]{1_000}, "r"), 0);
        resolver.onDelta(-5_000, 300);
        resolver.tick(TransactionResolver.DELTA_SIGNAL_WAIT_MS + 10);
        assertTrue(out.isEmpty());
    }

    @Test void 감정은_1000냥_빠지면_기록() {
        resolver.onSignal(TradeSignal.costHint("이용료", "유물 감정", new long[]{1_000}, "r"), 0);
        resolver.onDelta(-1_000, 3_000);
        resolver.tick(3_100);
        assertEquals(1, out.size());
        assertEquals("유물 감정", out.get(0).label);
        assertEquals(1_000, out.get(0).amount);
    }

    @Test void 금액없는_지출은_잔고변동으로() {
        resolver.onSignal(TradeSignal.byDelta(Flow.EXPENSE, "인챈트", "모루 인챈트", "raw"), 0);
        resolver.onDelta(-12_500, 400);
        resolver.tick(TransactionResolver.DELTA_SIGNAL_WAIT_MS + 10);
        assertEquals(1, out.size());
        assertEquals(12_500, out.get(0).amount);
        assertEquals(Kind.EXPENSE, out.get(0).kind);
    }

    @Test void 우편_수령은_잔고가_늘때만_보상() {
        resolver.onSignal(TradeSignal.byDelta(Flow.INCOME, "보상", "우편 수령", "raw").requiringDelta(), 0);
        resolver.onDelta(1_000, 300);
        resolver.onSignal(TradeSignal.byDelta(Flow.INCOME, "보상", "우편 수령", "raw").requiringDelta(), 60_000);
        resolver.tick(TransactionResolver.DELTA_SIGNAL_WAIT_MS + 10);
        resolver.tick(60_000 + TransactionResolver.DELTA_SIGNAL_WAIT_MS + 10);
        assertEquals(1, out.size(), "아이템만 받은 두 번째 수령은 기록되지 않는다: " + out);
        assertEquals(1_000, out.get(0).amount);
        assertEquals(Kind.INCOME, out.get(0).kind);
        assertTrue(notices.isEmpty(), "미확인 알림도 없다");
    }

    private static TradeSignal fleaSold(int qty, String item) {
        return new TradeSignal(Flow.EXPENSE, "플리마켓", 0, qty, item, "raw", true, true, null);
    }

    @Test void 겹친_플리마켓_알림은_각자_자기_잔고변동과_짝() {
        // 다이아 2개(−328) → 5초 뒤 철 5개(−100). 사이에 상점 창 구매 −200,000 이 끼어 있다.
        resolver.noteGuiCosts(List.of(new GuiCostLore.GuiCost("NPC 상점", "레전더리 스톤", 200_000)), 2_000);
        resolver.onSignal(fleaSold(2, "다이아몬드"), 0);
        resolver.onDelta(-328, 900);
        resolver.onDelta(-200_000, 3_000);
        resolver.onSignal(fleaSold(5, "철"), 5_000);
        resolver.onDelta(-100, 5_800);
        for (long t = 0; t <= 40_000; t += 50) resolver.tick(t);
        assertEquals(3, out.size(), out.toString());
        assertEquals("다이아몬드", out.get(0).label);
        assertEquals(328, out.get(0).amount);
        assertEquals(2, out.get(0).qty);
        assertTrue(out.stream().anyMatch(r -> r.label.equals("철") && r.amount == 100), out.toString());
        assertTrue(out.stream().anyMatch(r -> r.label.equals("레전더리 스톤") && r.amount == 200_000),
                "상점 구매를 플리마켓이 가져가면 안 된다: " + out);
    }

    @Test void 동시에_온_플리마켓_알림의_합쳐진_잔고변동은_나눈다() {
        resolver.onSignal(fleaSold(2, "다이아몬드"), 0);
        resolver.onSignal(fleaSold(2, "다이아몬드"), 400);
        resolver.onDelta(-656, 1_000); // 잔고가 한 번에 내려옴
        for (long t = 0; t <= 20_000; t += 50) resolver.tick(t);
        assertEquals(2, out.size(), out.toString());
        assertEquals(328, out.get(0).amount);
        assertEquals(328, out.get(1).amount);
    }

    @Test void 연속_시도로_합쳐진_잔고변동은_횟수로_나눈다() {
        resolver.onSignal(TradeSignal.byDelta(Flow.EXPENSE, "인챈트", "모루 인챈트", "raw"), 0);
        resolver.onSignal(TradeSignal.byDelta(Flow.EXPENSE, "인챈트", "모루 인챈트", "raw"), 300);
        resolver.onDelta(-25_000, 900);
        resolver.tick(TransactionResolver.DELTA_SIGNAL_WAIT_MS + 400);
        assertEquals(2, out.size());
        assertEquals(12_500, out.get(0).amount);
        assertEquals(12_500, out.get(1).amount);
    }

    @Test void 금액을_못찾으면_0원_미확인으로_남기고_알린다() {
        resolver.onSignal(TradeSignal.byDelta(Flow.EXPENSE, "전직", "전직", "raw"), 0);
        resolver.tick(TransactionResolver.DELTA_SIGNAL_WAIT_MS + 10);
        assertEquals(1, out.size());
        assertEquals(0, out.get(0).amount);
        assertEquals(Confidence.LOW, out.get(0).confidence);
        assertEquals(0, out.get(0).pnlDelta(), "손익을 오염시키지 않는다");
        assertEquals(1, notices.size());
    }

    @Test void 같은금액_지출이_연달아와도_각각_짝짓는다() {
        resolver.onSignal(TradeSignal.of(Flow.EXPENSE, "송금", 5_000, 0, "a", "r"), 0);
        resolver.onSignal(TradeSignal.of(Flow.EXPENSE, "송금", 5_000, 0, "b", "r"), 10);
        resolver.onDelta(-5_000, 50);
        resolver.onDelta(-5_000, 700); // 500ms dedup 창 밖
        resolver.tick(800);
        assertEquals(2, out.size());
        assertTrue(out.stream().allMatch(r -> r.crossChecked));
    }

    @Test void 마을금고를_이체로_보는_설정() {
        config.villageVaultAsTransfer = true;
        resolver.onSignal(TradeSignal.of(Flow.EXPENSE, "마을 금고", 100_000, 0, "마을 금고 입금", "raw"), 0);
        resolver.onDelta(-100_000, 100);
        resolver.tick(200);
        assertEquals(Kind.TRANSFER_OUT, out.get(0).kind);
        assertEquals(0, out.get(0).pnlDelta(), "이체는 손익 제외");
    }
}
