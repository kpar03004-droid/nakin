package kr.aka.nakin.config;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * YACL 설정 화면 빌더. YACL API 가 없거나 버전이 다르면 null 반환(호출측이 폴백).
 * 저장 자체는 DtConfig(Gson)이 담당하므로 이 클래스는 화면 생성만 책임진다.
 */
public final class DtConfigScreen {
    private static final Logger LOG = LoggerFactory.getLogger("nakin");

    private DtConfigScreen() {}

    public static Screen create(DtConfig c, Screen parent) {
        try {
            return YetAnotherConfigLib.createBuilder()
                    .title(Component.literal("낙인 설정"))
                    .category(ConfigCategory.createBuilder()
                            .name(Component.literal("일반"))
                            .option(bool("HUD 표시", () -> c.hudEnabled, v -> c.hudEnabled = v))
                            .option(bool("이체 손익 제외", () -> c.transferExcludedFromPnl, v -> c.transferExcludedFromPnl = v))
                            .option(bool("마을 금고 입금을 이체로(손익 제외)", () -> c.villageVaultAsTransfer, v -> c.villageVaultAsTransfer = v))
                            .option(bool("이체(참고) 표시", () -> c.showTransfers, v -> c.showTransfers = v))
                            .option(intSlider("하루 리셋 시각", 0, 23, () -> c.dayResetHour, v -> c.dayResetHour = v))
                            .build())
                    .category(ConfigCategory.createBuilder()
                            .name(Component.literal("진단/소스"))
                            .option(bool("진단 로거(BalanceProbe)", () -> c.debugProbe, v -> c.debugProbe = v))
                            .option(str("잔고 소스 (AUTO/SCOREBOARD/BOSSBAR/TABLIST/ACTIONBAR)",
                                    () -> c.balanceSourceMode, v -> c.balanceSourceMode = v))
                            .option(str("잔고 마커 단어", () -> c.balanceMarker, v -> c.balanceMarker = v))
                            .build())
                    .save(c::save)
                    .build()
                    .generateScreen(parent);
        } catch (Throwable t) {
            LOG.warn("[nakin] YACL 설정 화면 생성 실패 — config.json 을 직접 편집하세요.", t);
            return null;
        }
    }

    private static Option<Boolean> bool(String name, java.util.function.Supplier<Boolean> get,
                                        java.util.function.Consumer<Boolean> set) {
        return Option.<Boolean>createBuilder()
                .name(Component.literal(name))
                .binding(get.get(), get, set)
                .controller(BooleanControllerBuilder::create)
                .build();
    }

    private static Option<Integer> intSlider(String name, int min, int max,
                                             java.util.function.Supplier<Integer> get,
                                             java.util.function.Consumer<Integer> set) {
        return Option.<Integer>createBuilder()
                .name(Component.literal(name))
                .binding(get.get(), get, set)
                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(min, max).step(1))
                .build();
    }

    private static Option<String> str(String name, java.util.function.Supplier<String> get,
                                      java.util.function.Consumer<String> set) {
        return Option.<String>createBuilder()
                .name(Component.literal(name))
                .binding(get.get(), get, set)
                .controller(StringControllerBuilder::create)
                .build();
    }
}
