package kr.aka.nakin.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 새 버전 알림의 순수 로직(버전 비교·JSON 파싱) 검증. 네트워크 없이 돈다.
 * 오탐(멀쩡한 최신 버전인데 "업데이트하세요")이 나면 신뢰를 잃으므로 경계값을 못 박는다.
 */
class UpdateCheckerTest {

    @Test
    void 문자열_비교가_아니라_숫자_마디로_비교한다() {
        // "0.2.10" < "0.2.9" 가 되는 사전순 비교 회귀 방지
        assertTrue(UpdateChecker.isNewer("0.2.10", "0.2.9"));
        assertFalse(UpdateChecker.isNewer("0.2.9", "0.2.10"));
        assertTrue(UpdateChecker.isNewer("0.10.0", "0.9.9"));
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.99.99"));
    }

    @Test
    void 같은_버전이면_알리지_않는다() {
        assertFalse(UpdateChecker.isNewer("0.2.1", "0.2.1"));
        assertFalse(UpdateChecker.isNewer("0.2.1", "0.2.2"), "구버전을 최신이라 우기면 안 됨");
        assertEquals(0, UpdateChecker.compareVersions("0.2.1", "0.2.1"));
    }

    @Test
    void 마디_수가_달라도_처리한다() {
        assertEquals(0, UpdateChecker.compareVersions("0.2", "0.2.0"), "없는 마디는 0");
        assertTrue(UpdateChecker.isNewer("0.2.1", "0.2"));
        assertFalse(UpdateChecker.isNewer("0.2", "0.2.1"));
    }

    @Test
    void v접두사와_꼬리표를_견딘다() {
        assertFalse(UpdateChecker.isNewer("v0.2.1", "0.2.1"), "v 접두사는 무시");
        assertTrue(UpdateChecker.isNewer("0.3.0", "0.2.1-beta"));
        assertEquals(0, UpdateChecker.compareVersions("0.3.0-beta", "0.3.0"), "꼬리표는 숫자만 봄");
    }

    @Test
    void 이상한_버전_문자열에도_안_터진다() {
        assertEquals(0, UpdateChecker.compareVersions("", ""));
        assertEquals(0, UpdateChecker.compareVersions(null, null));
        assertFalse(UpdateChecker.isNewer("", "0.2.1"));
        assertFalse(UpdateChecker.isNewer("알수없음", "0.2.1"));
    }

    @Test
    void JSON_파싱() {
        UpdateChecker.Release r = UpdateChecker.parse(
                "{\"latest\":\"0.2.2\",\"url\":\"https://example.com/x\",\"notes\":\"각인석 조사 추가\"}");
        assertNotNull(r);
        assertEquals("0.2.2", r.version());
        assertEquals("https://example.com/x", r.url());
        assertEquals("각인석 조사 추가", r.notes());
    }

    @Test
    void latest만_있어도_되고_없으면_무시한다() {
        UpdateChecker.Release r = UpdateChecker.parse("{\"latest\":\" 0.3.0 \"}");
        assertNotNull(r);
        assertEquals("0.3.0", r.version(), "앞뒤 공백 정리");
        assertNull(r.url());

        assertNull(UpdateChecker.parse("{\"version\":\"0.3.0\"}"), "키 이름이 다르면 무시");
        assertNull(UpdateChecker.parse("{\"latest\":\"\"}"));
        assertNull(UpdateChecker.parse("깨진 JSON"), "파싱 실패는 조용히 null");
        assertNull(UpdateChecker.parse("[]"));
        assertNull(UpdateChecker.parse(""));
    }

    @Test
    void 주소가_없거나_http가_아니면_요청하지_않는다() {
        UpdateChecker.reset();
        // 콜백이 불리면 실패 — 네트워크로 나가지 않았음을 확인
        UpdateChecker.checkAsync("", "0.2.1", r -> {
            throw new AssertionError("빈 주소로 요청하면 안 됨");
        });
        UpdateChecker.checkAsync(null, "0.2.1", r -> {
            throw new AssertionError("null 주소로 요청하면 안 됨");
        });
        UpdateChecker.checkAsync("file:///etc/passwd", "0.2.1", r -> {
            throw new AssertionError("http(s) 아닌 스킴은 거부");
        });
        assertNull(UpdateChecker.latest());
    }
}
