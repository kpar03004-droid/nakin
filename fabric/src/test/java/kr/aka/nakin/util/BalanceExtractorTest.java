package kr.aka.nakin.util;

import kr.aka.nakin.config.DtConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 동글랜드 우하단 패널(2026-09-28 사용자 스크린샷):
 * 냥 2,273,762 / 퀘스트포인트 146 / 모험포인트 4,910 / 휴식포인트 336 / 동글머니 10,000
 * — 같은 패널에 다른 재화 숫자가 섞여 있어 "가장 큰 숫자"로 고르면 틀린다.
 */
class BalanceExtractorTest {

    private final DtConfig cfg = new DtConfig();

    private static List<String> panel(String nyang, String dongleMoney) {
        return List.of("", "냥 " + nyang, "퀘스트포인트 146", "모험포인트 4,910", "휴식포인트 336",
                "동글머니 " + dongleMoney);
    }

    @Test void 냥_줄을_잔고로_읽는다() {
        BalanceExtractor ex = new BalanceExtractor();
        assertEquals(2_273_762L, ex.extract(panel("2,273,762", "10,000"), cfg.balanceMarker, cfg.balanceRegex));
    }

    @Test void 냥이_동글머니보다_적어도_냥_줄을_읽는다() {
        BalanceExtractor ex = new BalanceExtractor();
        assertEquals(5_000L, ex.extract(panel("5,000", "10,000"), cfg.balanceMarker, cfg.balanceRegex));
    }

    @Test void 잔고가_100냥_미만이어도_읽는다() {
        BalanceExtractor ex = new BalanceExtractor();
        assertEquals(42L, ex.extract(panel("42", "10,000"), cfg.balanceMarker, cfg.balanceRegex));
    }

    @Test void 한번_잡은_줄을_계속_따라간다() {
        BalanceExtractor ex = new BalanceExtractor();
        ex.extract(panel("2,273,762", "10,000"), cfg.balanceMarker, cfg.balanceRegex);
        // 잔고가 줄어 동글머니보다 작아져도 같은 줄 모양을 따라간다
        assertEquals(9_000L, ex.extract(panel("9,000", "10,000"), cfg.balanceMarker, cfg.balanceRegex));
    }

    @Test void 작은_값만_오르내리는_줄은_잔고가_아니라고_보고_다시_찾는다() {
        // 2026-10-03·04 실서버: 접속 직후 잔고 패널이 없을 때 30~50 을 ±1 로 오르내리는 줄을 잡고
        //   하루 종일 놓지 않았다. 진짜 잔고 줄이 뜬 뒤에도 잠금이 안 풀렸다.
        BalanceExtractor ex = new BalanceExtractor();
        assertEquals(34L, ex.extract(List.of("핑 34ms"), cfg.balanceMarker, cfg.balanceRegex));
        long t = 0;
        boolean rejected = false;
        for (long d : new long[]{+1, -1, +1, -1}) rejected |= ex.noteDelta(d, t += 2_000);
        assertFalse(rejected, "방향 전환 3번까지는 버리지 않는다");
        rejected = ex.noteDelta(+1, t += 2_000);
        assertTrue(rejected, "±1 이 1분 안에 네 번 방향을 바꾸면 버린다");
        // 이제 진짜 잔고 줄이 함께 보이면 그쪽을 잡고, 버린 줄은 다시 고르지 않는다
        assertEquals(73_562L, ex.extract(List.of("핑 35ms", "냥 73,562"), cfg.balanceMarker, cfg.balanceRegex));
    }

    @Test void 진짜_잔고의_작은_변동은_버리지_않는다() {
        BalanceExtractor ex = new BalanceExtractor();
        ex.extract(panel("2,273,762", "10,000"), cfg.balanceMarker, cfg.balanceRegex);
        long t = 0;
        // 워프 -5, 판매 +1,000, 워프 -5 … 큰 변동이 끼면 기록을 비운다
        assertFalse(ex.noteDelta(-5, t += 1_000));
        assertFalse(ex.noteDelta(+1_000, t += 1_000));
        assertFalse(ex.noteDelta(-5, t += 1_000));
        assertFalse(ex.noteDelta(+3, t += 1_000));
        assertFalse(ex.noteDelta(-5, t += 70_000)); // 1분 넘게 떨어진 변동은 셈하지 않는다
    }

    /** 칸 구분 글리프 — 2026-10-05 실서버 로그의 실제 코드 포인트(U+D0003 U+CFFF1 U+D0000, 보충 평면 미할당 영역). */
    private static final String G = new String(Character.toChars(0xD0003)) + new String(Character.toChars(0xCFFF1))
            + new String(Character.toChars(0xD0000));

    /** 2026-10-05 /낙인 진단 — 보스바 한 줄에 체력·퀘스트 트래커·재화 패널이 다 들어 있다. */
    private static String bossBar(String nyang) {
        return G + "29" + G + "20 / 20" + G + "19 / 20" + G + "20 / 20" + G + "68 / 70" + G
                + "진행 중: 전직 준비하기" + G + "보상: 300원" + G
                + "대장장이 숙련도 200,000점, " + G + "3,000,000냥, 퀘스트 포인트" + G + "200점을 모으세요." + G
                + "▸ 3,000,000냥 모으기 (135944/3000000)" + G + "새싹마을 | 낙인 | 여름 | 9일 |" + G
                + nyang + G + "150" + G + "4,920" + G + "256.5" + G + "10,000" + G
                + "냥" + G + "퀘스트포인트" + G + "모험포인트" + G + "휴식포인트" + G + "동글머니" + G;
    }

    @Test void 보스바_패널에서_냥_칸의_숫자를_읽는다() {
        BalanceExtractor ex = new BalanceExtractor();
        // 퀘스트 진행도·3,000,000냥 목표가 같은 줄에 있어도, 탭리스트 접속자 수가 있어도 잔고는 패널의 냥 칸
        List<String> lines = List.of(bossBar("135,944"), "DONGLELAND_TOGETHER", "온라인 : 36명");
        assertEquals(135_944L, ex.extract(lines, cfg.balanceMarker, cfg.balanceRegex));
        assertEquals(135_936L, ex.extract(List.of(bossBar("135,936"), "온라인 : 37명"),
                cfg.balanceMarker, cfg.balanceRegex), "레드스톤 2개(8냥) 구매 후");
    }

    @Test void 접속자_수를_잡고_있어도_냥_패널이_뜨면_갈아탄다() {
        BalanceExtractor ex = new BalanceExtractor();
        assertEquals(36L, ex.extract(List.of("온라인 : 36명"), cfg.balanceMarker, cfg.balanceRegex));
        assertEquals(135_944L, ex.extract(List.of(bossBar("135,944"), "온라인 : 36명"),
                cfg.balanceMarker, cfg.balanceRegex));
    }
}
