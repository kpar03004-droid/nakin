package kr.aka.nakin.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ActivityLogTest {

    @Test
    void 제보문은_한도_안에서_최신_활동을_남긴다() {
        ActivityLog.clearForTest();
        for (int i = 0; i < 200; i++) ActivityLog.delta(-1000L * i);
        ActivityLog.unmatched("제작 대기열이 가득 찼습니다."); // 냥 없음 → 안 남김
        ActivityLog.unmatched("무언가 50,000냥을 받았습니다.");
        String r = ActivityLog.report(List.of("낙인 0.1.2 · fabric"));
        assertTrue(r.length() <= 2_000, "len=" + r.length());
        assertTrue(r.contains("미인식 무언가 50,000냥"), "가장 최근 줄은 반드시 포함");
        assertFalse(r.contains("대기열"));
        assertTrue(r.startsWith("```\n낙인 0.1.2"));
    }

    @Test
    void 활동이_없으면_없다고_쓴다() {
        ActivityLog.clearForTest();
        assertTrue(ActivityLog.report(List.of()).contains("(기록된 활동 없음)"));
    }

    @Test
    void 잔고_읽기_상태의_글리프와_색코드를_지운다() {
        String glyph = new String(Character.toChars(0xE001)) + new String(Character.toChars(0xD005A));
        String s = ActivityLog.strip("§f확정 " + glyph + " 73,562");
        assertFalse(s.contains("§"), s);
        assertFalse(s.contains(new String(Character.toChars(0xE001))), s);
        assertFalse(s.contains(new String(Character.toChars(0xD005A))), s);
        assertTrue(s.contains("73,562"), s);
    }
}
