package kr.aka.nakin.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.network.chat.Component;

/** 양피지 9-slice 버튼(tex_btn_base/hover/press) — Claude Design 텍스처. */
public final class TexButton extends AbstractButton {
    private final Runnable action;

    public TexButton(int x, int y, int w, int h, Component message, Runnable action) {
        super(x, y, w, h, message);
        this.action = action;
    }

    @Override
    public void onPress(InputWithModifiers input) {
        action.run();
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        String tex = this.isHovered() ? "tex_btn_hover" : "tex_btn_base";
        GuiTex.sprite(ctx, tex, getX(), getY(), getWidth(), getHeight());
        var font = Minecraft.getInstance().font;
        int tw = font.width(getMessage());
        ctx.text(font, getMessage(), getX() + (getWidth() - tw) / 2, getY() + (getHeight() - 8) / 2, GuiTex.BTN_TEXT, false);
    }

    @Override
    protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput builder) {
        this.defaultButtonNarrationText(builder);
    }
}
