package kr.aka.nakin.ui;

import kr.aka.nakin.aggregate.DailyAggregator;
import kr.aka.nakin.aggregate.DailyBucket;
import kr.aka.nakin.config.DtConfig;
import kr.aka.nakin.util.GoldFormat;
import kr.aka.nakin.util.LedgerDates;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 양피지 가계부 HUD (Claude Design 1c 안). panel_light 9-slice 배경 + 잉크 팔레트 +
 * 카테고리 top 아이콘·막대. 거래 시 실시간 갱신.
 *
 * 26.1: HudRenderCallback 이 없어져 HudElementRegistry 로 등록한다. RenderSystem.setShaderColor 도
 * 없어져 투명도(config.hudOpacity)는 그리는 색마다 알파를 곱하는 방식({@link #a})으로 적용한다.
 */
public final class LedgerHud {

    private static final int PAD_X = 11;   // 좌우 여백(9-slice 프레임 안쪽)
    private static final int PAD_TOP = 9;
    private static final int PAD_BOT = 10;
    private static final int LINE = 11;    // 기본 행 높이
    private static final int GAP = 12;     // 라벨-값 최소 간격
    private static final int ICON = 11;    // 카테고리 아이콘 크기
    private static final int MIN_W = 132;
    private static final int TOP_CATS = 4; // HUD 카테고리 최대 표시 수

    private final DtConfig config;
    private final DailyAggregator aggregator;
    /** 그리는 동안의 투명도(0~1). draw() 진입 시 config 에서 잡는다. */
    private float alpha = 1f;

    public LedgerHud(DtConfig config, DailyAggregator aggregator) {
        this.config = config;
        this.aggregator = aggregator;
    }

    public void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("nakin", "ledger_hud"),
                (ctx, tickCounter) -> render(ctx));
    }

    /** ARGB 색의 알파에 HUD 투명도를 곱한다. */
    private int a(int argb) {
        if (alpha >= 1f) return argb;
        int al = Math.round(((argb >>> 24) & 0xFF) * alpha);
        return (al << 24) | (argb & 0x00FFFFFF);
    }

    private void render(GuiGraphicsExtractor ctx) {
        if (!config.hudEnabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui) return;
        int[] pos = clampedPosition(mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        drawScaledHud(ctx, mc.font, pos[0], pos[1]);
    }

    /**
     * config.hudScale 배율을 적용해 (x,y)에 그리고 {실제 너비, 실제 높이}(배율 반영) 반환.
     * 내부 레이아웃은 배율 1 기준으로 계산하고 행렬로만 확대/축소해 코드 중복을 없앤다.
     */
    public int[] drawScaledHud(GuiGraphicsExtractor ctx, Font font, int x, int y) {
        float s = config.hudScaleClamped();
        if (s == 1.0f) return draw(ctx, font, x, y);
        var m = ctx.pose();
        m.pushMatrix();
        m.translate((float) x, (float) y);
        m.scale(s, s);
        int[] wh = draw(ctx, font, 0, 0);
        m.popMatrix();
        return new int[]{Math.round(wh[0] * s), Math.round(wh[1] * s)};
    }

    /** 배율까지 반영한 HUD 크기(그리지 않음) — 화면 클램프·편집 히트박스용. */
    public int[] measureScaled(Font font) {
        float s = config.hudScaleClamped();
        int[] wh = measure(font);
        return new int[]{Math.round(wh[0] * s), Math.round(wh[1] * s)};
    }

    private record Cat(String name, long amount, long max) {}

    private List<Cat> topCats(DailyBucket b) {
        List<Cat> out = new ArrayList<>();
        long max = 1;
        for (long v : b.incomeByCategory.values()) max = Math.max(max, v);
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(b.incomeByCategory.entrySet());
        sorted.sort((a, c) -> Long.compare(c.getValue(), a.getValue()));
        for (int i = 0; i < sorted.size() && out.size() < TOP_CATS; i++) {
            out.add(new Cat(sorted.get(i).getKey(), sorted.get(i).getValue(), max));
        }
        return out;
    }

    /** HUD 를 (x,y)에 그리고 {너비,높이} 반환. 편집 화면 미리보기 재사용. */
    public int[] draw(GuiGraphicsExtractor ctx, Font font, int x, int y) {
        DailyBucket b = aggregator.today();
        long net = b.netPnl();
        int netColor = net > 0 ? GuiTex.GREEN : (net < 0 ? GuiTex.RED : GuiTex.NEUTRAL);

        LocalDate today = LedgerDates.today(config.dayResetHour);
        String title = "낙인 · 오늘";
        String date = today.getMonthValue() + "/" + today.getDayOfMonth();
        String netVal = GoldFormat.signed(net) + " 냥";
        String inVal = GoldFormat.format(b.income) + " 냥";
        String outVal = GoldFormat.format(b.expense) + " 냥";
        boolean showTr = config.showTransfers && (b.transferIn > 0 || b.transferOut > 0);
        long trNet = b.transferIn - b.transferOut;
        String trVal = GoldFormat.signed(trNet) + " 냥";
        List<Cat> cats = topCats(b);
        boolean showCats = !cats.isEmpty();

        float NET_SCALE = 1.35f;

        // 크기는 measure() 한 곳에서만 계산한다 — 예전엔 여기서 따로 계산하고 measure() 는
        // 너비를 154 로 고정 반환해, 금액 자릿수가 커지면 편집 화면 히트박스·크기조절이 어긋났다.
        int[] wh = measure(font);
        int w = wh[0];
        int h = wh[1];

        // ── 배경 (양피지 9-slice) ── 이하 전체를 투명도로 곱해서 그림(config.hudOpacity)
        alpha = Math.max(0f, Math.min(1f, config.hudOpacity));
        int tint = a(0xFFFFFFFF);
        GuiTex.sprite(ctx, "panel_light", x, y, w, h, tint);

        int left = x + PAD_X;
        int right = x + w - PAD_X;
        int ty = y + PAD_TOP;

        // 제목 + 날짜
        ctx.text(font, title, left, ty, a(GuiTex.TITLE), false);
        ctx.text(font, date, right - font.width(date), ty, a(GuiTex.LABEL), false);
        ty += LINE;
        ctx.fill(left, ty, right, ty + 1, a(GuiTex.RULE));
        ty += 5;

        // 순익 (확대)
        ctx.text(font, "순익", left, ty + (int) (font.lineHeight * (NET_SCALE - 1) / 2), a(GuiTex.LABEL), false);
        drawScaled(ctx, font, netVal, right - (int) (font.width(netVal) * NET_SCALE), ty, a(netColor), NET_SCALE);
        ty += (int) (font.lineHeight * NET_SCALE) + 3;

        // 수입 / 지출
        row(ctx, font, "수입", inVal, left, right, ty, a(GuiTex.LABEL), a(GuiTex.GREEN));
        ty += LINE;
        row(ctx, font, "지출", outVal, left, right, ty, a(GuiTex.LABEL), a(GuiTex.RED));
        ty += LINE;
        if (showTr) {
            row(ctx, font, "이체", trVal, left, right, ty, a(GuiTex.LABEL), a(GuiTex.BLUE));
            ty += LINE;
        }

        // 카테고리
        if (showCats) {
            ty += 1;
            ctx.fill(left, ty, right, ty + 1, a(GuiTex.RULE));
            ty += 5;
            for (Cat c : cats) {
                GuiTex.sprite(ctx, GuiTex.icon(c.name), left, ty - 1, ICON, ICON, tint);
                int nameX = left + ICON + 4;
                ctx.text(font, c.name, nameX, ty, a(GuiTex.TEXT), false);
                String amt = GoldFormat.format(c.amount);
                ctx.text(font, amt, right - font.width(amt), ty, a(GuiTex.TEXT), false);
                // 막대
                int barY = ty + LINE - 1;
                int barW = right - nameX;
                ctx.fill(nameX, barY, right, barY + 2, a(GuiTex.TRACK));
                int fill = (int) Math.max(2, (long) barW * c.amount / c.max);
                ctx.fill(nameX, barY, nameX + fill, barY + 2, a(GuiTex.GOLD_BAR));
                ty += LINE + 4;
            }
        }

        alpha = 1f;
        return new int[]{w, h};
    }

    /**
     * 배율 1 기준 HUD 크기(그리지 않음) — draw 와 편집 화면 히트박스가 공유하는 유일한 크기 계산.
     * 내용에 따라 너비가 달라지므로 실제로 그릴 문자열을 그대로 재서 잰다.
     */
    public int[] measure(Font font) {
        DailyBucket b = aggregator.today();
        LocalDate today = LedgerDates.today(config.dayResetHour);
        String title = "낙인 · 오늘";
        String date = today.getMonthValue() + "/" + today.getDayOfMonth();
        String netVal = GoldFormat.signed(b.netPnl()) + " 냥";
        String inVal = GoldFormat.format(b.income) + " 냥";
        String outVal = GoldFormat.format(b.expense) + " 냥";
        boolean showTr = config.showTransfers && (b.transferIn > 0 || b.transferOut > 0);
        String trVal = GoldFormat.signed(b.transferIn - b.transferOut) + " 냥";
        List<Cat> cats = topCats(b);
        float NET_SCALE = 1.35f;

        // ── 너비 ──
        int w = font.width(title) + GAP + font.width(date);
        w = Math.max(w, font.width("순익") + GAP + (int) (font.width(netVal) * NET_SCALE));
        w = Math.max(w, font.width("수입") + GAP + font.width(inVal));
        w = Math.max(w, font.width("지출") + GAP + font.width(outVal));
        if (showTr) w = Math.max(w, font.width("이체") + GAP + font.width(trVal));
        for (Cat c : cats) {
            w = Math.max(w, ICON + 4 + font.width(c.name) + GAP + font.width(GoldFormat.format(c.amount)));
        }
        w = Math.max(w, MIN_W) + PAD_X * 2;

        // ── 높이 ──
        int catRowH = LINE + 4; // 카테고리 행: 텍스트 + 막대
        int h = PAD_TOP
                + LINE            // 제목
                + 5               // rule
                + (int) (font.lineHeight * NET_SCALE) + 3 // 순익
                + LINE * 2        // 수입/지출
                + (showTr ? LINE : 0)
                + (!cats.isEmpty() ? 6 + cats.size() * catRowH : 0)
                + PAD_BOT;
        return new int[]{w, h};
    }

    /**
     * 화면 밖으로 안 나가게 보정한 렌더 좌표(저장된 config.hudX/Y 는 건드리지 않음).
     * 창 크기가 일시적으로 작아져도(창모드 전환 등) 사용자가 지정한 원래 위치가 유지되고,
     * 화면이 다시 커지면 그 위치로 돌아온다 — 과거엔 clampToScreen 이 config 를 직접 덮어써
     * 창모드↔전체화면 전환마다 저장 위치가 영구 손상됐음(2026-07-23 수정).
     */
    private int[] clampedPosition(int screenW, int screenH) {
        // 구버전 설정(절대 픽셀)을 현재 화면 기준 비율로 1회 승격 — 이후엔 GUI 크기가 바뀌어도
        // 화면상 같은 자리에 남는다. 승격 자체가 사용자 설정 변경이라 저장까지 해 둔다.
        if (config.ensureHudRatio(screenW, screenH)) config.save();
        int[] wh = measureScaled(Minecraft.getInstance().font);
        int x = Math.max(0, Math.min(config.hudPixelX(screenW), Math.max(0, screenW - wh[0])));
        int y = Math.max(0, Math.min(config.hudPixelY(screenH), Math.max(0, screenH - wh[1])));
        return new int[]{x, y};
    }

    private static void row(GuiGraphicsExtractor ctx, Font font, String label, String value,
                            int left, int right, int y, int labelColor, int valueColor) {
        ctx.text(font, label, left, y, labelColor, false);
        ctx.text(font, value, right - font.width(value), y, valueColor, false);
    }

    private static void drawScaled(GuiGraphicsExtractor ctx, Font font, String s, int x, int y, int color, float scale) {
        var m = ctx.pose();
        m.pushMatrix();
        m.translate((float) x, (float) y);
        m.scale(scale, scale);
        ctx.text(font, s, 0, 0, color, false);
        m.popMatrix();
    }
}
