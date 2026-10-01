package kr.aka.nakin.debug;

import kr.aka.nakin.watcher.BossBarBalanceSource;
import kr.aka.nakin.watcher.ScoreboardBalanceSource;
import kr.aka.nakin.watcher.TabListBalanceSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * STEP1 진단 로거 — 잔고 소스 A(텍스트)/B(픽셀) 판정용.
 *
 * 스코어보드·보스바·탭리스트 라인을 1초에 1회, 채팅/시스템/액션바 메시지를 전부 콘솔에 로그한다.
 * 게임에서 잔고가 예: 2,273,762 냥일 때 "어느 소스 문자열에 그 숫자가 있는지" 확인:
 *   - 있으면 A 확정 → config.balanceSourceMode 를 해당 소스로 지정.
 *   - 어디에도 없으면 B(픽셀) → 채팅 기반 집계로 전환 후 보고.
 *
 * config.debugProbe = true 일 때만 활성화(기본 true). 판정 끝나면 false 로.
 */
public final class BalanceProbe {
    private static final Logger LOG = LoggerFactory.getLogger("nakin-probe");

    private final ScoreboardBalanceSource scoreboard = new ScoreboardBalanceSource();
    private final BossBarBalanceSource bossbar = new BossBarBalanceSource();
    private final TabListBalanceSource tablist = new TabListBalanceSource();

    private long lastLog = 0;

    public void register() {
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
                LOG.info("[{}] {}", overlay ? "ACTIONBAR" : "GAME", message.getString()));

        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) ->
                LOG.info("[CHAT] {}", message.getString()));

        LOG.info("BalanceProbe 활성화 — 1초마다 소스 라인을 로그합니다. 잔고 숫자를 찾아 소스를 확정하세요.");
    }

    private void onTick(Minecraft client) {
        long now = System.currentTimeMillis();
        if (now - lastLog < 1000) return;
        lastLog = now;
        if (client.player == null || client.level == null) return;

        dump("scoreboard", scoreboard.lines(client));
        dump("bossbar", bossbar.lines(client));
        dump("tablist", tablist.lines(client));
    }

    private void dump(String src, List<String> lines) {
        if (lines.isEmpty()) return;
        for (int i = 0; i < lines.size(); i++) {
            LOG.info("[{}][{}] {}", src, i, lines.get(i));
        }
    }
}
