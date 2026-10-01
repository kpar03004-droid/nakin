package kr.aka.nakin.core;

import kr.aka.nakin.core.TradeSignal.Flow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 동글랜드 원문 회귀 테스트. 문구는 전부 사용자 로그 실측(2026-07-09 ~ 09-28)이고
 * 다른 유저 닉네임만 PlayerA·PlayerB 로 익명화했다(공개 저장소).
 */
class CurrencyParserTest {

    private static final String ME = "_me";
    private final CurrencyParser parser = new CurrencyParser(() -> ME);

    private List<TradeSignal> feed(String... lines) {
        List<TradeSignal> out = new ArrayList<>();
        long t = 1_000_000;
        for (String l : lines) out.addAll(parser.parse(l, t += 50));
        return out;
    }

    private TradeSignal only(String... lines) {
        List<TradeSignal> s = feed(lines);
        assertEquals(1, s.size(), "신호 1개 기대: " + s);
        return s.get(0);
    }

    private static void assertSig(TradeSignal s, Flow flow, String cat, long amount) {
        assertEquals(flow, s.flow, s.toString());
        assertEquals(cat, s.category, s.toString());
        assertEquals(amount, s.amount, s.toString());
        assertFalse(s.amountFromDelta, s.toString());
    }

    // ── 무역상점 / NPC ──

    @Test void 무역상점_보석판매는_대장장이_수입() {
        TradeSignal s = only("[동글상점] 보석ㅣ영원 x1 판매에 성공했습니다. 획득 금액: 132 냥, 8.97 대장포인트!");
        assertSig(s, Flow.INCOME, "대장장이", 132);
        assertEquals(1, s.qty);
        assertEquals("보석ㅣ영원", s.label);
    }

    @Test void 무역상점_붙여쓴_냥과_직업_미상은_무역상점() {
        assertSig(only("[동글상점] 고서의 흔적 x3 판매에 성공했습니다. 획득 금액: 300냥!"),
                Flow.INCOME, "무역상점", 300);
    }

    @Test void 요리_판매는_요리사() {
        assertSig(only("[동글상점] 무별요리ㅣ어니언 포테이토 그라탱 x32 판매에 성공했습니다. 획득 금액: 1,744 냥!"),
                Flow.INCOME, "요리사", 1744);
    }

    @Test void 포인트_코인_결제는_냥이_아니라_무시() {
        assertTrue(feed("[동글상점] 미확인ㅣ유물 x1 구매에 성공했습니다. 비용: 200 숙련 포인트!").isEmpty());
        assertTrue(feed("[동글상점] 하급ㅣ유물 x1 판매에 성공했습니다. 획득 금액: 40 모험포인트!").isEmpty());
        assertTrue(feed("[동글상점] [마을전쟁] 전쟁 증표 x1 구매에 성공했습니다. 비용: 전쟁 코인 2점!").isEmpty());
        assertTrue(feed("[동글상점] 호감도ㅣ반짝이는 별사탕 x1 구매에 성공했습니다. 비용: 30포인트!").isEmpty());
    }

    @Test void 중개상인_다이아_구매는_NPC상점_지출() {
        TradeSignal s = only("[동글상점] 다이아몬드 x128 구매에 성공했습니다. 비용: 19,200 냥!");
        assertSig(s, Flow.EXPENSE, "NPC 상점", 19200);
        assertEquals(128, s.qty);
    }

    // ── 거래소 ──

    @Test void 거래소는_수령할때만_수입() {
        // 접속 시 요약 → 수령 두 줄이 같은 돈이다. 수령 한 번만 센다.
        List<TradeSignal> s = feed(
                "오프라인 동안 1개의 아이템이 총 25,000냥 (수수료: 1,750냥)에 판매되었습니다!",
                "• 판매 기록 보기 [클릭]",
                "거래소 • 23,250냥을 수령했습니다.",
                "거래소 • 판매된 대금을 수령했습니다.");
        assertEquals(1, s.size(), s.toString());
        assertSig(s.get(0), Flow.INCOME, "거래소", 23250);
    }

    @Test void 광장에서_팔린_거래소_체결은_내잔고가_늘때만_수입() {
        // 등록가 30,000 − 수수료 2,100 = 실수령 27,900 이 알림에 찍힌다
        TradeSignal s = only("거래소 • PlayerA 님이 이용권ㅣ프리미엄 광산 x1 을 27,900 냥에 구매했습니다. (수수료: 2,100냥)");
        assertSig(s, Flow.INCOME, "거래소", 27900);
        assertEquals("이용권ㅣ프리미엄 광산", s.label);
        assertEquals(1, s.qty);
        assertTrue(s.requireDelta, "광장 진입 때 수령 줄과 겹쳐 오므로 잔고 확인 필수");
    }

    @Test void 거래소_등록은_무시() {
        assertTrue(feed("거래소 • 주괴ㅣ벚꽃 x64 12,000냥에 판매 등록했습니다.").isEmpty());
        assertTrue(feed("등록 가격 : 12000원").isEmpty());
    }

    @Test void 거래소_구매는_냥_글자가_없어도_지출() {
        TradeSignal s = only("거래소 • 원석ㅣ아메트린 x10 을 20,000에 구매했습니다.");
        assertSig(s, Flow.EXPENSE, "거래소", 20000);
        assertEquals(10, s.qty);
        assertTrue(feed("거래소 • 구매한 아이템에서 원석ㅣ아메트린 x10 을 제거했습니다.").isEmpty());
    }

    // ── 송금 ──

    @Test void 송금_확인창은_무시하고_완료만_원금_지출() {
        List<TradeSignal> s = feed(
                "PlayerA님에게 150,000냥을 송금하시겠습니까?",
                "[확인] [취소]  (30초 이내 클릭)",
                "PlayerA에게 150,000냥을 송금했습니다. ",
                " └ 원금 150,000냥 · 수수료 3% 4,500냥 · 상대 실수령 145,500냥");
        assertEquals(1, s.size(), s.toString());
        assertSig(s.get(0), Flow.EXPENSE, "송금", 150000);
    }

    @Test void 송금_받음은_실수령액_수입() {
        List<TradeSignal> s = feed(
                " └ 원금 320,000냥 · 수수료 3% 9,600냥 · 실수령 310,400냥",
                "PlayerA에게서 310,400냥을 받았습니다. (수수료 3% 차감)");
        assertEquals(1, s.size(), s.toString());
        assertSig(s.get(0), Flow.INCOME, "송금", 310400);
    }

    // ── 직거래 ──

    @Test void 직거래_준돈은_지출() {
        TradeSignal s = only(
                " PlayerA님과의 거래가 완료되었습니다! ※ 냥 거래가 포함된 경우 수수료 3%가 차감됩니다",
                "- 28,500냥",
                "+ Stick 15개");
        assertSig(s, Flow.EXPENSE, "직거래", 28500);
    }

    @Test void 직거래_받은돈은_실수령_수입() {
        TradeSignal s = only(
                " PlayerA님과의 거래가 완료되었습니다! ※ 냥 거래가 포함된 경우 수수료 3%가 차감됩니다",
                "+ 145,500냥",
                "- Glowstone Dust 1개");
        assertSig(s, Flow.INCOME, "직거래", 145500);
    }

    @Test void 직거래_머리줄_없는_금액줄은_무시() {
        assertTrue(feed("- 150,000냥").isEmpty());
        assertTrue(feed("+ 145,500냥").isEmpty());
    }

    @Test void 직거래_사이에_대화가_끼어도_이어받는다() {
        TradeSignal s = only(
                " PlayerA님과의 거래가 완료되었습니다! ※ 냥 거래가 포함된 경우 수수료 3%가 차감됩니다",
                "마을원 냥이A : ㅋㅋ",
                "- 5,000냥");
        assertSig(s, Flow.EXPENSE, "직거래", 5000);
    }

    @Test void 직거래_블록은_시간이_지나면_끊긴다() {
        parser.parse(" PlayerA님과의 거래가 완료되었습니다! ※ 냥 거래가 포함된 경우 수수료 3%가 차감됩니다", 0);
        assertTrue(parser.parse("- 5,000냥", 10_000).isEmpty());
    }

    // ── 플리마켓 ──

    @Test void 남의_구매상점에_판매_영수증은_수입() {
        TradeSignal s = only(
                "+---------------------------------------------------+",
                "l 성공적으로 판매했습니다:",
                "l 56개의 보석ㅣ영원 (총 7952.0냥)",
                "+---------------------------------------------------+");
        assertSig(s, Flow.INCOME, "플리마켓", 7952);
        assertEquals(56, s.qty);
        assertEquals("보석ㅣ영원", s.label);
    }

    @Test void 남의_판매상점에서_구매_영수증은_지출() {
        assertSig(only("l 성공적으로 구매했습니다:", "l 32개의 룬ㅣ성급 (총 19200.0냥)"),
                Flow.EXPENSE, "플리마켓", 19200);
    }

    @Test void 소수_금액은_반올림() {
        assertEquals(10454, only("l 성공적으로 판매했습니다:", "l 1개의 보석ㅣ신성 (총 10453.579999999783냥)").amount);
    }

    @Test void 상점_정보_줄은_거래가_아니다() {
        assertTrue(feed(
                "l 상점 정보 :",
                "l 주인 : PlayerA",
                "l 보석ㅣ영원의 개당 가격 - 142.0냥",
                "l 보석ㅣ신성의 64개당 가격 - 12000.0냥",
                "l 이 상점은 아이템을 구매중인 상점입니다.",
                "비용은 12600.0냥 이지만, 상점의 주인이 10453.579999999783냥 원을 가지고 있습니다. (상점 주인의 잔액이 부족합니다.)"
        ).isEmpty());
    }

    @Test void 내_판매상점에서_누가_사면_수입() {
        TradeSignal s = only("PlayerA 님이 당신의 상점 에서 9 생활 강화석 을(를) 구입했으며 당신은 597645.0냥 원 을(를) 얻으셨습니다!");
        assertSig(s, Flow.INCOME, "플리마켓", 597645);
        assertEquals(9, s.qty);
    }

    @Test void 내_구매상점에_누가_팔면_지출() {
        TradeSignal s = only("PlayerA sold 9 원석ㅣ지르콘 to your shop for 77400.0냥.");
        assertSig(s, Flow.EXPENSE, "플리마켓", 77400);
        assertEquals("원석ㅣ지르콘", s.label);
    }

    @Test void 플리마켓_금액없는_짧은_알림은_잔고변동으로() {
        // 2026-10-01 실서버 원문(직후 ΔG −328) — 금액이 없다
        TradeSignal s = only("PlayerA sold 2 다이아몬드");
        assertEquals(Flow.EXPENSE, s.flow);
        assertEquals("플리마켓", s.category);
        assertEquals("다이아몬드", s.label);
        assertEquals(2, s.qty);
        assertTrue(s.amountFromDelta && s.requireDelta, "내 잔고가 줄 때만 기록");
        // 금액이 있는 긴 형태는 여전히 메시지 금액
        assertEquals(77400, only("PlayerA sold 9 원석ㅣ지르콘 to your shop for 77400.0냥.").amount);
    }

    // ── 판매 영수증 · 낚시 · 보상 ──

    @Test void 판매완료_영수증은_합계_한줄만() {
        TradeSignal s = only(
                "[판매 완료]",
                "  [룬ㅣ성급] x32 (만료) 373.1냥x32개 : 11939.2냥",
                "  합계: 11939.2냥");
        assertSig(s, Flow.INCOME, "연금술사", 11939);
        assertTrue(s.label.contains("룬ㅣ성급"));
    }

    @Test void 물고기_판매() {
        assertSig(only("ꐻ • 총 5마리를 판매하여 150냥을 벌었습니다!"), Flow.INCOME, "낚시", 150);
    }

    @Test void 보상_류() {
        assertSig(only("1000냥을 획득했습니다."), Flow.INCOME, "보상", 1000);
        assertSig(only("[길라잡이] 보상금 100원이 지급되었습니다."), Flow.INCOME, "보상", 100);
        assertSig(only("[이벤트] 이벤트 코인 3개 판매 완료! (개당 500냥)"), Flow.INCOME, "보상", 1500);
        assertTrue(feed("[길라잡이] [완료] 1000냥 모으기").isEmpty());
    }

    // ── 무역 카드팩 · 유물 감정 (가격표 결제) ──

    @Test void 카드팩_냥_당첨은_수입_더하기_뒤집기비용_후보() {
        List<TradeSignal> s = feed("[무역 카드팩] 2,000 냥을 뽑았습니다!");
        assertEquals(2, s.size(), s.toString());
        assertSig(s.get(0), Flow.INCOME, "보상", 2000);
        assertTrue(s.get(1).isCostHint());
        assertArrayEquals(new long[]{30_000, 100_000}, s.get(1).expectedCosts, "기본 가격표(2장 3만/3장 10만)");
    }

    @Test void 카드팩_아이템_당첨도_뒤집기비용_후보() {
        TradeSignal s = only("[무역 카드팩] 무역 해금서 x2 뽑았습니다!");
        assertTrue(s.isCostHint());
        assertEquals("카드팩", s.category);
    }

    @Test void 가격표_줄로_내_등급_가격을_배운다() {
        // VVIP 처럼 2장째가 무료인 가격표가 오면 후보는 10만 하나만 남는다
        assertTrue(feed("카드를 뒤집을 땐 [1장:무료 / 2장:무료 / 3장:10만냥]이 소모됩니다.").isEmpty());
        assertArrayEquals(new long[]{100_000}, only("[무역 카드팩] 꼬마돌을(를) 뽑았습니다!").expectedCosts);
    }

    @Test void 감정_안내는_1000냥_후보() {
        TradeSignal s = only("[감정] 유물을 먼저 넣어주세요");
        assertTrue(s.isCostHint());
        assertArrayEquals(new long[]{1_000}, s.expectedCosts);
    }

    // ── 이용료($ 표기) ──

    @Test void 워프는_달러_표기_지출() {
        assertSig(only("동글워프 »  $500가 차감되었습니다."), Flow.EXPENSE, "이용료", 500);
    }

    @Test void 공짜_상자잠금은_기록안함() {
        assertTrue(feed("[보호] 오브젝트를 성공적으로 보호했습니다. 비용: $0.00.").isEmpty());
    }

    // ── 마을 금고: 남의 입금도 방송된다 ──

    @Test void 마을금고는_내_입금만() {
        assertSig(only("금고 | _me님이 마을 금고에 100,000냥을(를) 입금했습니다."), Flow.EXPENSE, "마을 금고", 100000);
        assertTrue(feed("금고 | PlayerA님이 마을 금고에 100,000냥을(를) 입금했습니다.").isEmpty());
        assertTrue(feed("금고 | PlayerA님이 마을 업그레이드 비용으로 금고에서 50,000냥을 사용했습니다.").isEmpty());
    }

    @Test void 닉을_모르면_잔고변동_필수로_넘긴다() {
        CurrencyParser anon = new CurrencyParser(null);
        List<TradeSignal> s = anon.parse("금고 | PlayerA님이 마을 금고에 100,000냥을(를) 입금했습니다.", 0);
        assertEquals(1, s.size());
        assertTrue(s.get(0).requireDelta);
    }

    @Test void 마을은행_인출은_내것만_수입() {
        assertSig(only("은행 | _me님이 마을 은행에서 $30000을(를) 인출했습니다!"), Flow.INCOME, "마을 금고", 30000);
        assertTrue(feed("은행 | PlayerA님이 마을 은행에서 $30000을(를) 인출했습니다!").isEmpty());
    }

    // ── 금액 없는 지출 ──

    @Test void 모루_인챈트와_전직은_잔고변동으로() {
        TradeSignal a = only("[인챈트] 인챈트에 성공했습니다! 확률: 100.0%");
        assertEquals(Flow.EXPENSE, a.flow);
        assertEquals("인챈트", a.category);
        assertTrue(a.amountFromDelta);
        assertTrue(only("전직에 성공하였습니다!").amountFromDelta);
    }

    @Test void 우편_패스_보상은_잔고변동으로() {
        // 2026-09 실서버 로그 원문(앞의 ꐮ 는 서버 아이콘 글리프)
        TradeSignal a = only("ꐮ 3개의 아이템을 수령했습니다.");
        assertEquals(Flow.INCOME, a.flow);
        assertEquals("보상", a.category);
        assertTrue(a.amountFromDelta);
        assertTrue(only("[동글 패스] 보상을 수령하였습니다!").amountFromDelta);
    }

    @Test void 강화_방송은_기록에_쓰지_않는다() {
        // 생활장비 강화는 대장간 창 비용 + 잔고 변동으로 잡는다(GuiCostLore) — 방송은 겹치기만 한다
        assertTrue(feed("[!] PlayerA님께서 4->5강 강화에 성공하셨습니다! [ 네더라이트 부츠 ]").isEmpty());
        assertTrue(feed("[!] _me님께서 5->6강 강화에 성공하셨습니다! [ 네더라이트 투구 ]").isEmpty());
    }

    // ── 대화 채널 ──

    @Test void 대화_채널은_전부_무시() {
        assertTrue(feed(
                "ꑂ[전챗] PlayerB: [삽니다] 강화석 교환권 9만냥",
                "마을원 냥이A : 1000냥을 획득했습니다.",
                "마을장 PlayerA : 거래소 • 5,000냥을 수령했습니다.",
                "부마을장 PlayerA : 100냥",
                "[지역채팅] ꒸² PlayerA: 1000냥을 획득했습니다.",
                "[눈꽃] <PlayerA> 1000냥을 획득했습니다.",
                "PlayerA → 나 | 1000냥을 획득했습니다.",
                "ꑂ[가이드] PlayerA: 1000냥"
        ).isEmpty());
    }
}
