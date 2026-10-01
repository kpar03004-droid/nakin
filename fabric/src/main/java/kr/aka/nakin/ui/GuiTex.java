package kr.aka.nakin.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * 번들 GUI 텍스처(양피지 테마) 렌더 헬퍼 + 카테고리 아이콘 매핑.
 *
 * 텍스처는 assets/nakin/textures/gui/sprites/*.png (+ 9-slice는 .png.mcmeta).
 * blitSprite 가 스프라이트 아틀라스에서 찾아 .mcmeta 스케일링(nine_slice/stretch)으로 그림.
 * 색 팔레트는 Claude Design 스펙(양피지 라이트 테마).
 */
public final class GuiTex {
    private GuiTex() {}

    // ── 양피지(light) 팔레트 ──
    public static final int TEXT     = 0xFF4A3420;
    public static final int LABEL    = 0xFF7A5A34;
    public static final int TITLE    = 0xFF40301C;
    public static final int GOLD     = 0xFFA9791F;
    public static final int GOLD_BAR = 0xFFC8912A;
    public static final int GREEN    = 0xFF2F7D32;
    public static final int RED      = 0xFFB02E26;
    public static final int BLUE     = 0xFF2E5AA0;
    public static final int NEUTRAL  = 0xFF8A7A5A;
    public static final int BTN_TEXT = 0xFFF0E2C2; // 어두운 나무색 버튼(tex_btn_*) 위 밝은 크림 텍스트
    public static final int RULE     = 0x574A3420; // rgba(74,52,32,0.34)
    public static final int TRACK    = 0x294A3420; // rgba(74,52,32,0.16)

    public static Identifier id(String name) { return Identifier.fromNamespaceAndPath("nakin", name); }

    /** 스프라이트를 (x,y)에 w×h로 그림. 9-slice/stretch 는 .mcmeta 가 결정. */
    public static void sprite(GuiGraphicsExtractor ctx, String name, int x, int y, int w, int h) {
        ctx.blitSprite(RenderPipelines.GUI_TEXTURED, id(name), x, y, w, h);
    }

    /** 색(ARGB) 곱 적용 스프라이트 — HUD 투명도처럼 알파만 바꿀 때 0x??FFFFFF 로 넘긴다. */
    public static void sprite(GuiGraphicsExtractor ctx, String name, int x, int y, int w, int h, int argb) {
        ctx.blitSprite(RenderPipelines.GUI_TEXTURED, id(name), x, y, w, h, argb);
    }

    /** 가로 방향 원본 크기 타일 반복(9-slice 아닌 반복 텍스처, 예: 구분선). 우측을 scissor 로 자름. */
    public static void tileH(GuiGraphicsExtractor ctx, String name, int left, int right, int y, int tileW, int tileH) {
        if (right <= left) return;
        ctx.enableScissor(left, y, right, y + tileH);
        for (int px = left; px < right; px += tileW) sprite(ctx, name, px, y, tileW, tileH);
        ctx.disableScissor();
    }

    /** 세로 방향 원본 크기 타일 반복(예: 스크롤바 트랙). 아래를 scissor 로 자름. */
    public static void tileV(GuiGraphicsExtractor ctx, String name, int x, int top, int bottom, int tileW, int tileH) {
        if (bottom <= top) return;
        ctx.enableScissor(x, top, x + tileW, bottom);
        for (int py = top; py < bottom; py += tileH) sprite(ctx, name, x, py, tileW, tileH);
        ctx.disableScissor();
    }

    /**
     * 카테고리 → 번들 아이콘 스프라이트 이름. 카테고리 이름은 core/DongleCategory 와 같은 문자열.
     * 동글랜드 전용 16×16 아이콘(원본·생성 스크립트: design/icons/). 모르는 카테고리는 엽전.
     */
    public static String icon(String category) {
        if (category == null) return "icon_coin";
        return switch (category) {
            case "대장장이"   -> "icon_blacksmith";
            case "농부"       -> "icon_farmer";
            case "요리사"     -> "icon_chef";
            case "연금술사"   -> "icon_alchemist";
            case "다이버"     -> "icon_diver";
            case "낚시"       -> "icon_fishing";
            case "무역상점", "판매" -> "icon_trade_shop";
            case "NPC 상점"   -> "icon_npc_shop";
            case "거래소"     -> "icon_exchange";
            case "송금"       -> "icon_transfer";
            case "직거래"     -> "icon_direct_trade";
            case "마을 금고"  -> "icon_village";
            case "인챈트"     -> "icon_enchant";
            case "강화"       -> "icon_enhance";
            case "전직"       -> "icon_job_change";
            case "이용료", "수수료" -> "icon_service";
            case "카드팩"     -> "icon_card_pack";
            case "보상"       -> "icon_reward";
            case "수동"       -> "icon_manual";    // 관리 탭에서 직접 입력한 기록
            default -> category.contains("플리마켓") ? "icon_flea" : "icon_coin";
        };
    }
}
