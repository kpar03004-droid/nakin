package kr.aka.nakin.watcher;

import java.util.List;
import net.minecraft.client.Minecraft;

/** 화면의 특정 표면(스코어보드/보스바/탭리스트 등)에서 텍스트 라인을 수집. read-only. */
public interface BalanceSource {
    String debugName();

    /** 이 표면의 현재 텍스트 라인들. 못 읽으면 빈 리스트(예외 던지지 말 것). */
    List<String> lines(Minecraft client);
}
