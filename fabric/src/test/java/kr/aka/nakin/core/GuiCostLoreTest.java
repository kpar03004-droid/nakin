package kr.aka.nakin.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 대장간 강화 버튼 설명 — 2026-09-28 사용자 스크린샷 원문. */
class GuiCostLoreTest {

    private static final List<String> ENHANCE_LORE = List.of(
            "§a성공 확률 : 40.0% §f| 실패 시 하락 확률 : 20.0%",
            "§6실패 확률 : 60.0% §f| §c실패 시 파괴 확률 : 3.0%",
            "",
            " ✹ 강화석드롭확률증가 0.14% ➟ 강화석드롭확률증가 0.14%",
            " ✹ 룬2배 1% ➟ 룬2배 3%",
            "",
            " > 동글이의 축복을 받기까지 3회 남았습니다.",
            "",
            "§b[ 필요한 재료 ]",
            " - 연금술사강화석 43개 [✓]",
            " - 120000원 [✓]");

    @Test void 강화_비용을_읽는다() {
        List<GuiCostLore.GuiCost> c = GuiCostLore.parse("클릭 시 강화가 진행됩니다.", ENHANCE_LORE);
        assertEquals(1, c.size(), c.toString());
        assertEquals(120_000, c.get(0).amount());
        assertEquals("강화", c.get(0).category());
        assertEquals("생활장비 강화", c.get(0).label());
    }

    @Test void 재료_개수는_돈이_아니다() {
        List<GuiCostLore.GuiCost> c = GuiCostLore.parse("x", List.of("[ 필요한 재료 ]", " - 연금술사강화석 43개 [✓]"));
        assertTrue(c.isEmpty());
    }

    @Test void 필요한_재료_밖의_금액은_무시() {
        List<GuiCostLore.GuiCost> c = GuiCostLore.parse("보석ㅣ영원", List.of("판매가 - 132원", "- 5000원"));
        assertTrue(c.isEmpty(), "재료 목록 머리줄이 없으면 비용 아님");
    }

    @Test void NPC상점_구매버튼_가격() {
        // 2026-09-28 사용자 스크린샷 원문
        List<GuiCostLore.GuiCost> c = GuiCostLore.parse("§a클릭하여 구매 x1", List.of(
                "§a클릭하여 선택한 수량을", "§a구매합니다.", "", "§e구매: §620 냥", "",
                "§f쉬프트+좌클릭하여 구매!", "§f손교체키: 즐겨찾기 (기본:F key)"));
        assertEquals(1, c.size(), c.toString());
        assertEquals(20, c.get(0).amount());
        assertEquals("NPC 상점", c.get(0).category());
        assertEquals("NPC 상점 구매", c.get(0).label(), "버튼 이름은 품목명이 아니다");
    }

    @Test void 상점_진열품은_품목명을_라벨로() {
        // 2026-09-30 사용자 스크린샷 원문(상점 진열 슬롯에서 쉬프트+좌클릭 구매)
        List<GuiCostLore.GuiCost> c = GuiCostLore.parse("레전더리 확률 랜덤 스톤", List.of(
                "성공,실패 확률을 무작위로 설정 합니다.", "", "구매: 200,000냥", "",
                "쉬프트+좌클릭하여 구매!", "좌클릭: 수량선택", "손교체키: 즐겨찾기 (기본:F key)"));
        assertEquals(1, c.size(), c.toString());
        assertEquals(200_000, c.get(0).amount());
        assertEquals("레전더리 확률 랜덤 스톤", c.get(0).label());
    }

    @Test void 수량선택_버튼에_직전_진열품_이름() {
        // 진열대에서 본 스톤(단가 200,000) → 좌클릭 수량 선택 창의 "클릭하여 구매 x1" 버튼
        GuiCostLore.GuiCost btn = GuiCostLore.parse("클릭하여 구매 x1", List.of(
                "클릭하여 선택한 수량을", "구매합니다.", "", "구매: 200,000냥")).get(0);
        assertEquals("레전더리 확률 랜덤 스톤", GuiCostLore.withProduct(btn, "레전더리 확률 랜덤 스톤", 200_000).label());
        GuiCostLore.GuiCost x3 = new GuiCostLore.GuiCost("NPC 상점", GuiCostLore.BUY_BUTTON_LABEL, 600_000);
        assertEquals("레전더리 확률 랜덤 스톤", GuiCostLore.withProduct(x3, "레전더리 확률 랜덤 스톤", 200_000).label());
        assertEquals(GuiCostLore.BUY_BUTTON_LABEL, GuiCostLore.withProduct(btn, "다른 품목", 30_000).label(),
                "단가의 정수배가 아니면 이름을 붙이지 않는다");
    }

    @Test void 쉼표와_냥_표기도_읽는다() {
        List<GuiCostLore.GuiCost> c = GuiCostLore.parse("조율하기", List.of("[ 필요한 재료 ]", "- 15,000냥"));
        assertEquals(15_000, c.get(0).amount());
        assertEquals("특성 조율", c.get(0).label());
    }
}
