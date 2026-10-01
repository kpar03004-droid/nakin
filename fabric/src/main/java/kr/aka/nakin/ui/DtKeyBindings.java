package kr.aka.nakin.ui;

import com.mojang.blaze3d.platform.InputConstants;
import kr.aka.nakin.aggregate.DailyAggregator;
import kr.aka.nakin.config.DtConfig;
import kr.aka.nakin.config.DtConfigScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** HUD 토글 / 정산 창 / 설정 키바인드(기본 미할당). */
public final class DtKeyBindings {
    private final DtConfig config;
    private final DailyAggregator aggregator;
    private final LedgerHud hud;
    private final java.util.function.Consumer<kr.aka.nakin.core.TransactionRecord> sink;

    /** 1.21.9+ 키 카테고리는 Identifier 로 등록한 record. 표시명은 lang 의 key.category.nakin.main. */
    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("nakin", "main"));

    private KeyMapping toggleHud;
    private KeyMapping openStats;
    private KeyMapping openConfig;
    private KeyMapping editHud;

    public DtKeyBindings(DtConfig config, DailyAggregator aggregator, LedgerHud hud,
                         java.util.function.Consumer<kr.aka.nakin.core.TransactionRecord> sink) {
        this.config = config;
        this.aggregator = aggregator;
        this.hud = hud;
        this.sink = sink;
    }

    public void register() {
        toggleHud = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "nakin.key.toggle_hud", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
        openStats = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "nakin.key.open_stats", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
        openConfig = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "nakin.key.open_config", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, CATEGORY));
        editHud = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "nakin.key.edit_hud", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private void onTick(Minecraft client) {
        while (toggleHud.consumeClick()) {
            config.hudEnabled = !config.hudEnabled;
            config.save();
        }
        while (openStats.consumeClick()) {
            client.setScreen(new DtStatScreen(config, aggregator, sink, hud));
        }
        while (editHud.consumeClick()) {
            client.setScreen(new DtHudEditScreen(config, hud));
        }
        while (openConfig.consumeClick()) {
            Screen screen = DtConfigScreen.create(config, client.screen);
            if (screen != null) client.setScreen(screen); // YACL 없으면 현재 화면 유지
        }
    }
}
