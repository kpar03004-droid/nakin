package kr.aka.nakin.watcher;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * 사이드바(스코어보드) 텍스트 수집 — <b>화면에 실제로 그려지는 글자만</b> 읽는다.
 *
 * <p>1.20.3+ 서버는 줄마다 이름 대신 {@code display} 컴포넌트를, 점수 대신 number format
 * (blank/fixed/styled)을 보낼 수 있다. 동글랜드 우하단 패널(`냥 2,273,762`)이 이런 식이라면
 * 예전처럼 {@code owner + " " + value} 를 읽으면 화면에 없는 정렬용 점수를 잔고로 오인한다.
 * 그래서 이름은 display → 팀 장식 이름 순, 값은 바닐라 사이드바와 같은 formatValue 로 만든다.
 */
public final class ScoreboardBalanceSource implements BalanceSource {

    @Override
    public String debugName() {
        return "scoreboard";
    }

    @Override
    public List<String> lines(Minecraft client) {
        List<String> out = new ArrayList<>();
        try {
            if (client.level == null) return out;
            Scoreboard sb = client.level.getScoreboard();
            Objective obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
            if (obj == null) return out;

            out.add(obj.getDisplayName().getString()); // 제목
            var defaultFormat = obj.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
            for (PlayerScoreEntry entry : sb.listPlayerScores(obj)) {
                if (entry.isHidden()) continue;
                Component name = entry.display();
                if (name == null) {
                    PlayerTeam team = sb.getPlayersTeam(entry.owner());
                    name = PlayerTeam.formatNameForTeam(team, entry.ownerName());
                }
                String value = entry.formatValue(defaultFormat).getString();
                String line = name.getString();
                if (!value.isBlank()) line = line + " " + value;
                out.add(line);
            }
        } catch (Throwable t) {
            // API 차이 등 — 진단 명령으로 확인. 크래시 금지.
        }
        return out;
    }
}
