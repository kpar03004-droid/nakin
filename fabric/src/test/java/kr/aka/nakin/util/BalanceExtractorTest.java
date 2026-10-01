package kr.aka.nakin.util;

import kr.aka.nakin.config.DtConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
