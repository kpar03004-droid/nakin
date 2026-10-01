package kr.aka.nakin.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;

/** 현재 표시 중인 보스바 맵을 read-only 로 노출. */
@Mixin(BossHealthOverlay.class)
public interface BossBarHudAccessor {
    @Accessor("events")
    Map<UUID, LerpingBossEvent> getBossBars();
}
