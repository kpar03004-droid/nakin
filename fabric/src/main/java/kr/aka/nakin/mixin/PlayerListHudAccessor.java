package kr.aka.nakin.mixin;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 탭리스트 header/footer 텍스트를 read-only 로 노출. */
@Mixin(PlayerTabOverlay.class)
public interface PlayerListHudAccessor {
    @Accessor("header")
    Component getHeader();

    @Accessor("footer")
    Component getFooter();
}
