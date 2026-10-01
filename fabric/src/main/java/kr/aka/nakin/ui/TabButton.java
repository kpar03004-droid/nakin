package kr.aka.nakin.ui;

import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.network.chat.Component;

/** 양피지 북마크 탭(tex_tab_active/inactive) — 활성 상태는 외부 상태값(BooleanSupplier)로 결정. */
public final class TabButton extends AbstractButton {
    private final BooleanSupplier isActive;
    private final Runnable action;

    public TabButton(int x, int y, int w, int h, Component message, BooleanSupplier isActive, Runnable action) {
        super(x, y, w, h, message);
        this.isActive = isActive;
        this.action = action;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        action.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        boolean active = isActive.getAsBoolean();
        String tex = active ? "tex_tab_active" : (isHovered() ? "tex_tab_hover" : "tex_tab_inactive");
        GuiTex.sprite(ctx, tex, getX(), getY(), getWidth(), getHeight());
        var font = Minecraft.getInstance().font;
        int color = active ? GuiTex.TITLE : 0xFFF0E2C2;
        int tw = font.width(getMessage());
        int ty = getY() + (getHeight() - 8) / 2 - 1; // 활성/비활성 공통 세로 중앙
        ctx.text(font, getMessage(), getX() + (getWidth() - tw) / 2, ty, color, false);
    }

    @Override
    protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput builder) {
        this.defaultButtonNarrationText(builder);
    }
}
