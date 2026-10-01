package kr.aka.nakin.ui;

import kr.aka.nakin.config.DtConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * HUD 위치 편집 화면. 실제 HUD 를 미리보기로 그리고 드래그로 이동.
 * 방향키 1px(Shift 10px) 미세조정, 닫으면 config 저장.
 */
public final class DtHudEditScreen extends Screen {
    private static final int DIM = 0x99000000;
    private static final int GOLD = 0xFFE3B341;
    private static final int MUTED = 0xFF8B949E;
    private static final int HILITE = 0x66E3B341;

    private final DtConfig config;
    private final LedgerHud hud;
    private final Screen parent;

    private static final int DEFAULT_MARGIN = 8;      // 기본 위치의 화면 가장자리 여백
    private static final int HANDLE = 8;              // 우하단 크기조절 손잡이 크기(px)
    private static final int HANDLE_COLD = 0x88E3B341;
    private static final int HANDLE_HOT = 0xFFE3B341;

    private boolean dragging;
    private boolean resizing;
    private int dragOffX, dragOffY;
    private int lastW = 120, lastH = 60;

    public DtHudEditScreen(DtConfig config, LedgerHud hud) {
        this(config, hud, null);
    }

    public DtHudEditScreen(DtConfig config, LedgerHud hud, Screen parent) {
        super(Component.literal("낙인 HUD 위치 편집"));
        this.config = config;
        this.hud = hud;
        this.parent = parent;
    }

    @Override
    protected void init() {
        pullOnScreen();
        addRenderableWidget(Button.builder(Component.literal("기본값"), b -> {
            config.hudScale = 1.0f;
            moveToDefaultCorner();
        }).bounds(this.width / 2 - 154, this.height - 30, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("작게 −"), b -> addScale(-DtConfig.HUD_SCALE_STEP))
                .bounds(this.width / 2 - 78, this.height - 30, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("크게 ＋"), b -> addScale(DtConfig.HUD_SCALE_STEP))
                .bounds(this.width / 2 - 2, this.height - 30, 70, 20).build());
        addRenderableWidget(Button.builder(Component.literal("완료"), b -> this.onClose())
                .bounds(this.width / 2 + 74, this.height - 30, 70, 20).build());
    }

    /**
     * 저장된 좌표가 현재 화면 밖이면 화면 안으로 당긴다.
     *
     * <p>2026-07-31 제보("HUD 위치 조정이 아예 안 된다 · 집히지도 않는다")의 원인:
     * 인게임 HUD 는 clampedPosition 으로 보정해 그리지만 이 편집 화면은 저장 좌표에 그대로
     * 그려서, 좌표가 화면 밖이면 <b>미리보기가 안 보이고 마우스도 닿을 수 없어</b> 드래그가
     * 아예 시작되지 않았다. 기본값 726,373 은 GUI 크기 2 기준이라 GUI 크기 3·4·자동
     * (1080p 자동 = 4 → 좌표계 480x270)에서는 처음부터 화면 밖이다.
     *
     * <p>init() 은 창 크기가 바뀔 때도 다시 불리므로 여기 두면 리사이즈 후에도 안전하다.
     */
    private void pullOnScreen() {
        int[] wh = hud.measureScaled(this.font);
        lastW = wh[0];
        lastH = wh[1];
        // 비율 승격 먼저 — 구버전 설정이면 현재 화면 기준 비율로 채운다.
        config.ensureHudRatio(this.width, this.height);
        config.setHudPixel(clamp(config.hudPixelX(this.width), this.width - lastW),
                clamp(config.hudPixelY(this.height), this.height - lastH),
                this.width, this.height);
    }

    /** 기본 위치 = 화면 좌측 30% 높이. 고정 좌표를 쓰면 GUI 크기에 따라 화면 밖으로 나간다. */
    private void moveToDefaultCorner() {
        int[] wh = hud.measureScaled(this.font);
        // 기본 위치 = 왼쪽 가장자리, 높이 30%(미니맵 아래). 오른쪽 아래는 동글랜드 서버 잔고 패널 자리.
        config.setHudPixel(clamp(DEFAULT_MARGIN, this.width - wh[0]),
                clamp((int) Math.round(this.height * 0.30), this.height - wh[1]),
                this.width, this.height);
    }

    /** 배율 증감(범위 고정). 소수 오차가 누적되지 않게 스텝 단위로 반올림. */
    private void addScale(float delta) {
        float v = config.hudScaleClamped() + delta;
        v = Math.round(v / DtConfig.HUD_SCALE_STEP) * DtConfig.HUD_SCALE_STEP;
        config.hudScale = Math.max(DtConfig.HUD_SCALE_MIN, Math.min(DtConfig.HUD_SCALE_MAX, v));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, DIM);
        super.extractRenderState(ctx, mouseX, mouseY, delta);

        // 안내
        String hint = "드래그로 이동 · 방향키 미세조정(Shift 10px) · 우하단 모서리로 크기 조절 · ESC 저장";
        ctx.text(this.font, hint,
                (this.width - this.font.width(hint)) / 2, 16, MUTED, false);
        String pos = "X " + config.hudX + "   Y " + config.hudY
                + "   크기 " + Math.round(config.hudScaleClamped() * 100) + "%";
        ctx.text(this.font, pos,
                (this.width - this.font.width(pos)) / 2, 30, GOLD, false);

        // 실제 HUD 미리보기 + 하이라이트 테두리
        int[] wh = hud.drawScaledHud(ctx, this.font, config.hudX, config.hudY);
        lastW = wh[0];
        lastH = wh[1];
        int x = config.hudX, y = config.hudY;
        ctx.fill(x - 1, y - 1, x + lastW + 1, y, HILITE);
        ctx.fill(x - 1, y + lastH, x + lastW + 1, y + lastH + 1, HILITE);
        ctx.fill(x - 1, y, x, y + lastH, HILITE);
        ctx.fill(x + lastW, y, x + lastW + 1, y + lastH, HILITE);

        // 우하단 크기조절 핸들(창 리사이즈처럼 잡아끌기). 호버/드래그 중이면 강조.
        int hx = x + lastW - HANDLE, hy = y + lastH - HANDLE;
        boolean hot = resizing || inHandle(mouseX, mouseY);
        ctx.fill(hx, hy, hx + HANDLE, hy + HANDLE, hot ? HANDLE_HOT : HANDLE_COLD);
        for (int i = 1; i <= 3; i++) { // 사선 그립 무늬
            int off = i * 2;
            ctx.fill(hx + HANDLE - off, hy + HANDLE - 1, hx + HANDLE, hy + HANDLE - 1 + 1, GOLD);
            ctx.fill(hx + HANDLE - 1, hy + HANDLE - off, hx + HANDLE, hy + HANDLE - off + 1, GOLD);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent ev, boolean doubleClick) {
        double mx = ev.x(), my = ev.y();
        int button = ev.button();
        if (button == 0 && inHandle(mx, my)) { // 이동보다 크기조절이 우선(모서리는 핸들 영역)
            resizing = true;
            return true;
        }
        if (button == 0 && inHud(mx, my)) {
            dragging = true;
            dragOffX = (int) mx - config.hudX;
            dragOffY = (int) my - config.hudY;
            return true;
        }
        return super.mouseClicked(ev, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent ev, double dx, double dy) {
        double mx = ev.x(), my = ev.y();
        if (resizing) {
            resizeTo(mx, my);
            return true;
        }
        if (dragging) {
            config.setHudPixel(clamp((int) mx - dragOffX, this.width - lastW),
                    clamp((int) my - dragOffY, this.height - lastH),
                    this.width, this.height);
            return true;
        }
        return super.mouseDragged(ev, dx, dy);
    }

    /**
     * 커서가 HUD 좌상단에서 얼마나 떨어졌는지로 배율을 정한다(비율 유지).
     * 가로/세로 중 더 많이 끈 쪽을 따라가 모서리가 커서를 자연스럽게 쫓아옴.
     * 휠·버튼과 달리 스텝에 안 묶여 자유롭게 조절된다.
     */
    private void resizeTo(double mx, double my) {
        int[] base = hud.measure(this.font); // 배율 1 기준 크기
        if (base[0] <= 0 || base[1] <= 0) return;
        float sx = (float) (mx - config.hudX) / base[0];
        float sy = (float) (my - config.hudY) / base[1];
        float s = Math.max(sx, sy);
        config.hudScale = Math.max(DtConfig.HUD_SCALE_MIN, Math.min(DtConfig.HUD_SCALE_MAX, s));
    }

    /** 우하단 크기조절 핸들 영역인가. */
    private boolean inHandle(double mx, double my) {
        int hx = config.hudX + lastW - HANDLE, hy = config.hudY + lastH - HANDLE;
        return mx >= hx && mx <= hx + HANDLE && my >= hy && my <= hy + HANDLE;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent ev) {
        if (ev.button() == 0) {
            dragging = false;
            resizing = false;
        }
        return super.mouseReleased(ev);
    }

    /** 휠로 HUD 크기 조절(위=확대). */
    @Override
    public boolean mouseScrolled(double mx, double my, double horizontal, double vertical) {
        if (vertical != 0) {
            addScale(vertical > 0 ? DtConfig.HUD_SCALE_STEP : -DtConfig.HUD_SCALE_STEP);
            return true;
        }
        return super.mouseScrolled(mx, my, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(KeyEvent ev) {
        int step = ev.hasShiftDown() ? 10 : 1;
        switch (ev.key()) {
            case GLFW.GLFW_KEY_LEFT -> nudge(-step, 0);
            case GLFW.GLFW_KEY_RIGHT -> nudge(step, 0);
            case GLFW.GLFW_KEY_UP -> nudge(0, -step);
            case GLFW.GLFW_KEY_DOWN -> nudge(0, step);
            default -> {
                return super.keyPressed(ev);
            }
        }
        return true;
    }


    /** 방향키 미세조정 — 픽셀과 비율을 함께 갱신해야 화면 크기가 바뀌어도 위치가 유지된다. */
    private void nudge(int dx, int dy) {
        config.setHudPixel(clamp(config.hudX + dx, this.width - lastW),
                clamp(config.hudY + dy, this.height - lastH),
                this.width, this.height);
    }

    private boolean inHud(double mx, double my) {
        return mx >= config.hudX && mx <= config.hudX + lastW
                && my >= config.hudY && my <= config.hudY + lastH;
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(v, Math.max(0, max)));
    }

    @Override
    public void onClose() {
        config.save();
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent); // 정산 창(또는 게임)으로 복귀
        } else {
            super.onClose();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
