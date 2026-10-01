package kr.aka.nakin.watcher;

import kr.aka.nakin.mixin.BossBarHudAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import java.util.ArrayList;
import java.util.List;

/** 보스바 텍스트 수집(accessor mixin 경유). */
public final class BossBarBalanceSource implements BalanceSource {

    @Override
    public String debugName() {
        return "bossbar";
    }

    @Override
    public List<String> lines(Minecraft client) {
        List<String> out = new ArrayList<>();
        try {
            BossHealthOverlay hud = client.gui.getBossOverlay();
            if (hud == null) return out;
            for (LerpingBossEvent bar : ((BossBarHudAccessor) hud).getBossBars().values()) {
                if (bar.getName() != null) out.add(bar.getName().getString());
            }
        } catch (Throwable t) {
            // 크래시 금지
        }
        return out;
    }
}
