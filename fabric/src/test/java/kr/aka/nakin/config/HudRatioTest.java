package kr.aka.nakin.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HUD 위치를 화면 비율로 저장하는 로직 검증.
 *
 * <p>배경(2026-07-31 제보): 절대 픽셀 좌표 726,373 은 1080p·GUI 크기 2(960x540)에서 잡힌 값이라
 * GUI 크기 3·4·'자동'(640x360, 480x270)에서는 처음부터 화면 밖이었다. 비율로 저장하면
 * 화면 크기가 달라져도 같은 자리에 남는다.
 */
class HudRatioTest {

    // 1080p 기준 GUI 크기별 실제 좌표계
    private static final int W_SCALE2 = 960, H_SCALE2 = 540;
    private static final int W_SCALE3 = 640, H_SCALE3 = 360;
    private static final int W_SCALE4 = 480, H_SCALE4 = 270;

    @Test
    void 구버전_설정은_현재_화면_기준으로_1회_승격된다() {
        DtConfig c = new DtConfig();
        assertEquals(5, c.hudX, "기본 픽셀값은 그대로 둔다(호환)");
        assertTrue(c.hudXRatio < 0, "처음엔 비율 미설정");

        assertTrue(c.ensureHudRatio(W_SCALE2, H_SCALE2), "첫 승격은 변경 발생");
        assertEquals(5.0 / 960, c.hudXRatio, 1e-9);
        assertEquals(162.0 / 540, c.hudYRatio, 1e-9);

        assertFalse(c.ensureHudRatio(W_SCALE3, H_SCALE3), "이미 승격됐으면 다시 안 바꾼다");
        assertEquals(5.0 / 960, c.hudXRatio, 1e-9, "나중 화면 크기에 오염되면 안 됨");
    }

    @Test
    void 화면_크기가_바뀌어도_같은_상대_위치를_유지한다() {
        DtConfig c = new DtConfig();
        c.ensureHudRatio(W_SCALE2, H_SCALE2); // GUI 크기 2에서 잡음

        // GUI 크기를 3, 4로 바꿔도 화면 안이고 비율이 같다
        for (int[] wh : new int[][]{{W_SCALE3, H_SCALE3}, {W_SCALE4, H_SCALE4}}) {
            int x = c.hudPixelX(wh[0]);
            int y = c.hudPixelY(wh[1]);
            assertTrue(x >= 0 && x < wh[0], "가로가 화면 안: " + x + " / " + wh[0]);
            assertTrue(y >= 0 && y < wh[1], "세로가 화면 안: " + y + " / " + wh[1]);
            assertEquals(5.0 / 960, (double) x / wh[0], 0.01, "상대 위치 유지");
        }
        assertEquals(3, c.hudPixelX(W_SCALE3), "640 * 5/960 ≈ 3");
        assertEquals(3, c.hudPixelX(W_SCALE4), "480 * 5/960 = 2.5 → 3");
        assertEquals(108, c.hudPixelY(H_SCALE3), "360 * 0.30 = 108");
    }

    @Test
    void 승격_전에는_예전_픽셀값을_그대로_쓴다() {
        DtConfig c = new DtConfig();
        assertEquals(5, c.hudPixelX(W_SCALE4), "비율 미설정이면 구값 사용(렌더 쪽 클램프가 막음)");
        assertEquals(162, c.hudPixelY(H_SCALE4));
    }

    @Test
    void 위치를_옮기면_픽셀과_비율이_함께_갱신된다() {
        DtConfig c = new DtConfig();
        c.setHudPixel(120, 60, W_SCALE3, H_SCALE3);

        assertEquals(120, c.hudX);
        assertEquals(60, c.hudY);
        assertEquals(120.0 / 640, c.hudXRatio, 1e-9);
        assertEquals(60.0 / 360, c.hudYRatio, 1e-9);
        // 다른 화면에서도 같은 상대 위치
        assertEquals(180, c.hudPixelX(W_SCALE2));
        assertEquals(90, c.hudPixelY(H_SCALE2));
    }

    @Test
    void 손상된_설정값을_방어한다() {
        DtConfig c = new DtConfig();
        c.hudXRatio = Double.NaN;
        c.hudYRatio = Double.POSITIVE_INFINITY;
        // NaN·무한대는 "미설정"으로 보고 다시 승격
        assertTrue(c.ensureHudRatio(W_SCALE2, H_SCALE2));
        assertTrue(Double.isFinite(c.hudXRatio) && Double.isFinite(c.hudYRatio));

        c.hudXRatio = 5.0;   // 범위 밖
        assertEquals(W_SCALE2, c.hudPixelX(W_SCALE2), "1.0 으로 잘려 화면 오른쪽 끝");
        assertEquals(0, DtConfig.pixelFromRatio(0.5, 0), "화면 크기 0 방어");
    }

    @Test
    void 화면_크기를_모르면_승격하지_않는다() {
        DtConfig c = new DtConfig();
        assertFalse(c.ensureHudRatio(0, 0), "초기화 전 0 크기로 비율이 오염되면 안 됨");
        assertTrue(c.hudXRatio < 0);
    }
}
