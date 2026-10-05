package kr.aka.nakin.core;

import java.util.regex.Pattern;

/**
 * 채팅 파싱 결과(원자 신호). CurrencyParser 가 만들고 TransactionResolver 가 소비한다.
 *
 * <p>거래 종류마다 enum 값을 두고 여러 곳의 switch 가 해석하면 case 하나를 빠뜨렸을 때 기록이
 * 조용히 증발한다. 그래서 낙인은 규칙이 <b>방향 · 카테고리 · 금액 출처</b>를 신호에 직접 싣는다 — 규칙 하나를 추가하는 일이
 * 정규식 한 줄로 끝나고, 해석하는 쪽은 서버 콘텐츠를 몰라도 된다.
 */
public final class TradeSignal {

    /** 내 지갑 기준 돈의 방향. */
    public enum Flow {
        INCOME,        // 잔고 증가, 손익 +
        EXPENSE,       // 잔고 감소, 손익 −
        TRANSFER_IN,   // 잔고 증가, 손익 제외(내 돈이 돌아옴)
        TRANSFER_OUT;  // 잔고 감소, 손익 제외(내 돈을 맡김)

        /** 잔고가 늘어야 하면 +1, 줄어야 하면 -1. */
        public int sign() {
            return this == INCOME || this == TRANSFER_IN ? +1 : -1;
        }
    }

    // 서버 리소스팩 전용 장식 글리프 제거용: 제어문자 · 사설영역(BMP+보충) · 이 음절(ꑂ 등 채널 아이콘)
    // · 동봉영숫자 · 딩벳 · 기타기호. 동글랜드도 채팅 앞머리에 ꑂ·U+E1xx 아이콘을 붙인다.
    private static final Pattern DECOR_ICONS = Pattern.compile(
            "[\\u0000-\\u001F\\u007F"
          + "\\uE000-\\uF8FF\\uFFF0-\\uFFFF"
          + "\\x{A0000}-\\x{10FFFD}"  // 동글 보스바 글리프는 U+CE000~D00FF(보충 평면 미할당 영역, 2026-10-05 실측)
          + "\\uA000-\\uA4CF"
          + "\\u2460-\\u24FF"
          + "\\u2700-\\u27BF"
          + "\\u2600-\\u26FF"
          + "]");

    public final Flow flow;
    public final String category; // 정산 카테고리(DongleCategory 상수 또는 품목에서 추론한 직업)
    public final long amount;     // 내 잔고가 움직인 금액(양수). amountFromDelta 면 0.
    public final int qty;         // 수량(없으면 0)
    public final String label;    // 품목/설명
    public final String raw;      // 원문(디버그)
    /** true 면 금액을 메시지가 아닌 잔고 변동(ΔG)에서 가져온다 — 모루·전직처럼 채팅에 금액이 없는 거래. */
    public final boolean amountFromDelta;
    /**
     * true 면 내 잔고가 실제로 움직였을 때만 기록한다. 남의 행동도 같은 문구로 방송되는데
     * 내 닉네임을 몰라서 가려낼 수 없을 때의 안전장치(놓치더라도 남의 거래를 섞지 않는다).
     */
    public final boolean requireDelta;
    public final String note;     // 레코드 비고로 그대로 전달(예: "수수료 3% 차감 후")
    /**
     * null 이 아니면 "이 중 하나와 정확히 같은 잔고 감소가 있을 때만" 그 금액으로 기록한다(없으면 조용히 버림).
     * 무역 카드팩 뒤집기처럼 금액은 채팅에 없지만 가격표가 정해진 결제용 — 무료 장은 잔고가 안 움직여
     * 자연히 기록되지 않으므로 일반/VIP/VVIP 등급을 몰라도 된다.
     */
    public final long[] expectedCosts;

    public TradeSignal(Flow flow, String category, long amount, int qty, String label, String raw,
                       boolean amountFromDelta, boolean requireDelta, String note) {
        this(flow, category, amount, qty, label, raw, amountFromDelta, requireDelta, note, null);
    }

    public TradeSignal(Flow flow, String category, long amount, int qty, String label, String raw,
                       boolean amountFromDelta, boolean requireDelta, String note, long[] expectedCosts) {
        this.flow = flow;
        this.category = category;
        this.amount = amount;
        this.qty = qty;
        this.label = clean(label);
        this.raw = raw;
        this.amountFromDelta = amountFromDelta;
        this.requireDelta = requireDelta;
        this.note = note;
        this.expectedCosts = expectedCosts;
    }

    /** 가격표가 정해진 결제 — 후보 금액 중 하나와 같은 잔고 감소가 있을 때만 기록. */
    public static TradeSignal costHint(String category, String label, long[] costs, String raw) {
        return new TradeSignal(Flow.EXPENSE, category, 0, 0, label, raw, false, false, null, costs.clone());
    }

    public boolean isCostHint() {
        return expectedCosts != null;
    }

    /** 메시지에 금액이 있는 일반 거래. */
    public static TradeSignal of(Flow flow, String category, long amount, int qty, String label, String raw) {
        return new TradeSignal(flow, category, amount, qty, label, raw, false, false, null);
    }

    /** 금액이 채팅에 없어 잔고 변동으로 매기는 거래. */
    public static TradeSignal byDelta(Flow flow, String category, String label, String raw) {
        return new TradeSignal(flow, category, 0, 0, label, raw, true, false, null);
    }

    public TradeSignal withNote(String n) {
        return new TradeSignal(flow, category, amount, qty, label, raw, amountFromDelta, requireDelta, n);
    }

    public TradeSignal requiringDelta() {
        return new TradeSignal(flow, category, amount, qty, label, raw, amountFromDelta, true, note);
    }

    /**
     * 라벨에서 색코드(§x)·제어문자·장식 아이콘 글리프를 제거하고 공백 정리.
     * 미지의 아이콘 대비로 "한글/영문/숫자/[ 가 아닌 선두 문자"를 통째로 걷어낸다.
     */
    static String clean(String label) {
        if (label == null) return "";
        String s = label.replaceAll("§.", "");
        s = DECOR_ICONS.matcher(s).replaceAll("");
        s = s.replaceFirst("^[^\\p{IsHangul}A-Za-z0-9\\[]+", "");
        return s.trim().replaceAll("\\s+", " ");
    }

    public int expectedSign() {
        return flow.sign();
    }

    @Override
    public String toString() {
        return "TradeSignal{" + flow + " " + category + (amountFromDelta ? " ΔG" : " " + amount)
                + (qty > 0 ? " qty=" + qty : "") + (requireDelta ? " needΔ" : "")
                + " label='" + label + "'}";
    }
}
