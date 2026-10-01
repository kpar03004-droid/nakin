package kr.aka.nakin.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 창(대장간 등) 아이템 설명(lore)에서 결제 비용을 읽는다.
 *
 * <p>동글랜드 생활장비 강화는 채팅에 금액이 안 나온다. 대신 강화 버튼의 설명에 비용이 적혀 있다
 * (2026-09-28 사용자 스크린샷):
 * <pre>
 * 클릭 시 강화가 진행됩니다.
 * 성공 확률 : 40.0% | 실패 시 하락 확률 : 20.0% …
 * [ 필요한 재료 ]
 *  - 연금술사강화석 43개 [✓]
 *  - 120000원 [✓]
 * </pre>
 * 이 값을 기억해 두었다가 <b>내 잔고가 정확히 그만큼 줄면</b> 그 결제로 기록한다
 * ({@link TransactionResolver#noteGuiCosts}). 금액이 정확히 맞아야만 인정하므로
 * 엉뚱한 잔고 변동을 가져가지 않고, 성공·실패·하락 모두 잡힌다.
 *
 * <p>창 제목은 리소스팩 때문에 믿을 수 없어(경험상) 아이템 이름·설명만 본다.
 */
public final class GuiCostLore {
    private GuiCostLore() {}

    public record GuiCost(String category, String label, long amount) {}

    /** 품목을 모르는 NPC 상점 구매 버튼(수량 선택 창)의 라벨. */
    public static final String BUY_BUTTON_LABEL = "NPC 상점 구매";

    /**
     * 수량 선택 창 버튼 비용에 직전에 본 진열품 이름을 붙인다. 버튼 금액이 그 품목 단가의 정수배일 때만
     * (x1·x10… 수량 선택) — 다른 품목을 오인하지 않게.
     */
    public static GuiCost withProduct(GuiCost c, String product, long unitPrice) {
        if (product == null || unitPrice <= 0 || !BUY_BUTTON_LABEL.equals(c.label())
                || c.amount() % unitPrice != 0) return c;
        return new GuiCost(c.category(), product, c.amount());
    }

    /** "[ 필요한 재료 ]"(대장간) / "필요 재료:"(유물 감정, 2026-10-01 사용자 스크린샷). */
    private static final Pattern MATERIALS_HEAD = Pattern.compile("필요한? 재료");
    /**
     * NPC 상점 구매 버튼 — "클릭하여 구매 x1 / … / 구매: 20 냥"(2026-09-28 사용자 스크린샷).
     * 구매는 채팅("[동글상점] … 구매에 성공했습니다. 비용: N 냥!")으로도 잡히므로 이건 예비 근거다.
     */
    private static final Pattern BUY_PRICE = Pattern.compile("^구매\\s*:\\s*([0-9][0-9,]*)\\s*냥");
    /** " - 120000원 [✓]" / "- 1,500냥" — 재료 목록 안의 돈 줄. */
    //   감정 창은 앞 대시 없이 "1,000냥" 만 쓴다.
    private static final Pattern MONEY_LINE = Pattern.compile("^(?:-\\s*)?([0-9][0-9,]*)\\s*(?:원|냥)(?:\\s|$|\\[)");

    /**
     * @param itemName 슬롯 아이템 이름
     * @param lore     설명 줄들(색코드 포함 가능)
     * @return 이 아이템이 안내하는 돈 비용(없으면 빈 목록)
     */
    public static List<GuiCost> parse(String itemName, List<String> lore) {
        List<GuiCost> out = new ArrayList<>(1);
        if (lore == null || lore.isEmpty()) return out;
        String name = CurrencyParser.normalize(itemName == null ? "" : itemName);
        boolean inMaterials = false;
        StringBuilder all = new StringBuilder(name);
        List<Long> amounts = new ArrayList<>(1);
        for (String raw : lore) {
            String line = CurrencyParser.normalize(raw == null ? "" : raw);
            all.append('\n').append(line);
            Matcher buy = BUY_PRICE.matcher(line);
            if (buy.find()) {
                long v = CurrencyParser.amount(buy.group(1));
                // 진열 슬롯이면 아이템 이름이 곧 품목명, 수량 선택 창의 "클릭하여 구매 x1" 버튼이면 품목을 모른다
                boolean button = name.isBlank() || name.contains("클릭");
                if (v > 0) out.add(new GuiCost(DongleCategory.NPC_SHOP, button ? BUY_BUTTON_LABEL : name, v));
                continue;
            }
            if (MATERIALS_HEAD.matcher(line).find()) {
                inMaterials = true;
                continue;
            }
            if (!inMaterials) continue;
            Matcher m = MONEY_LINE.matcher(line);
            if (m.find()) {
                long v = CurrencyParser.amount(m.group(1));
                if (v > 0) amounts.add(v);
            }
        }
        if (amounts.isEmpty()) return out;
        String text = all.toString();
        String category;
        String label;
        if (text.contains("조율")) {
            category = DongleCategory.ENHANCE;
            label = "특성 조율";
        } else if (text.contains("강화")) {
            category = DongleCategory.ENHANCE;
            label = "생활장비 강화";
        } else if (text.contains("전직")) {
            category = DongleCategory.JOB_CHANGE;
            label = "전직";
        } else if (text.contains("감정")) {
            category = DongleCategory.SERVICE;
            label = "유물 감정";
        } else {
            category = DongleCategory.SERVICE;
            label = name.isBlank() ? "창 결제" : name;
        }
        for (long v : amounts) out.add(new GuiCost(category, label, v));
        return out;
    }
}
