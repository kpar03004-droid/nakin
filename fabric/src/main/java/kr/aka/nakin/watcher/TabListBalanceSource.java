package kr.aka.nakin.watcher;

import kr.aka.nakin.mixin.PlayerListHudAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

/** 탭리스트 header/footer 텍스트 수집(accessor mixin 경유). */
public final class TabListBalanceSource implements BalanceSource {

    @Override
    public String debugName() {
        return "tablist";
    }

    @Override
    public List<String> lines(Minecraft client) {
        List<String> out = new ArrayList<>();
        try {
            PlayerTabOverlay hud = client.gui.getTabList();
            if (hud == null) return out;
            PlayerListHudAccessor acc = (PlayerListHudAccessor) hud;
            Component header = acc.getHeader();
            Component footer = acc.getFooter();
            if (header != null) out.add(header.getString());
            if (footer != null) out.add(footer.getString());
        } catch (Throwable t) {
            // 크래시 금지
        }
        return out;
    }
}
