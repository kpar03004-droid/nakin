package kr.aka.nakin.core;

/**
 * 동글랜드 정산 카테고리.
 *
 * <p>원칙: <b>NPC 에 판 수입은 활동(직업)별</b>, <b>유저끼리의 거래는 창구별</b>.
 * 무역상점·변동상점에서 판 보석은 대장장이 수입이지만, 거래소·플리마켓·송금으로 받은 돈은 무엇을
 * 팔았든 그 창구로 남긴다(무엇을 팔았는지는 라벨에 남는다).
 *
 * <p>동글랜드 아이템은 이름이 {@code 분류ㅣ이름} 꼴이다(보석ㅣ영원, 주괴ㅣ벚꽃, 룬ㅣ성급,
 * 무별요리ㅣ어니언 포테이토 그라탱, 상자ㅣ홍합). 구분자는 한글 모음 'ㅣ'(U+3163)이다 — 로그 실측.
 */
public final class DongleCategory {
    private DongleCategory() {}

    // 직업(NPC 판매 수입)
    public static final String BLACKSMITH = "대장장이";
    public static final String FARMER = "농부";
    public static final String CHEF = "요리사";
    public static final String ALCHEMIST = "연금술사";
    public static final String DIVER = "다이버";
    public static final String FISHING = "낚시";

    // 창구
    public static final String TRADE_SHOP = "무역상점";   // 직업 추론이 안 되는 NPC 판매
    public static final String NPC_SHOP = "NPC 상점";     // NPC 구매(중개상인 다이아 등)
    public static final String EXCHANGE = "거래소";
    public static final String FLEA = "플리마켓";
    public static final String TRANSFER = "송금";
    public static final String DIRECT_TRADE = "직거래";
    public static final String VILLAGE = "마을 금고";

    // 지출 활동
    public static final String ENCHANT = "인챈트";
    public static final String ENHANCE = "강화";
    public static final String JOB_CHANGE = "전직";
    public static final String SERVICE = "이용료";        // 워프·상자 잠금·유물 감정
    public static final String CARD_PACK = "카드팩";      // 무역 카드팩 2·3장째 뒤집기 비용
    public static final String REWARD = "보상";           // 보스·카드팩·이벤트·길라잡이
    public static final String MANUAL = "수동";

    /** 농부 작물(위키 금별 작물 10종) — 이름에 들어 있으면 농부. */
    private static final String[] CROPS = {
            "아스파라거스", "레몬", "양파", "마늘", "파슬리", "복숭아", "고추", "파프리카", "가지", "쌀"
    };

    /**
     * 품목 이름으로 직업을 추론한다. 모르면 fallback.
     * 판매 창구가 NPC(무역상점·변동상점·잡상인)일 때만 쓴다.
     */
    public static String jobOf(String item, String fallback) {
        if (item == null || item.isBlank()) return fallback;
        String s = item.replaceAll("^\\[[^\\]]*\\]\\s*", "").trim(); // "[룬ㅣ성급]" 같은 괄호 표기 대응
        if (s.startsWith("[")) s = s.substring(1);
        String prefix = s.contains("ㅣ") ? s.substring(0, s.indexOf('ㅣ')).trim() : "";

        switch (prefix) {
            case "보석", "주괴", "원석", "광물" -> { return BLACKSMITH; }
            case "룬", "결정", "조주" -> { return ALCHEMIST; }
            // "상자ㅣ홍합"(해산물 상자)과 "상자ㅣ양파"(작물 상자)가 같은 접두어를 쓸 수 있다 → 작물 먼저
            case "상자", "해산물" -> { return isCrop(s) ? FARMER : DIVER; }
            case "작물" -> { return FARMER; }
            default -> { }
        }
        if (prefix.endsWith("요리")) return CHEF;        // 무별요리·은별요리·금별요리
        if (prefix.startsWith("대장장이")) return BLACKSMITH;
        if (s.startsWith("연마석") || s.startsWith("다이아몬드")) return BLACKSMITH;
        if (isCrop(s)) return FARMER;
        return fallback;
    }

    private static boolean isCrop(String s) {
        for (String c : CROPS) if (s.contains(c)) return true;
        return false;
    }
}
