package kr.aka.nakin.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * 새 버전 알림. 구버전을 쓰면서 이미 고친 버그를 제보하는 일이 반복돼 도입(2026-08-07).
 *
 * <p><b>보내는 것 없음</b> — 정해진 주소로 GET 한 번 하는 게 전부다. 닉네임·서버·플레이 정보 등
 * 어떤 식별자도 담지 않으며, 게임 서버와는 무관한 요청이다. 실패하면 조용히 넘어간다
 * (알림 하나 때문에 접속이 느려지거나 오류가 뜨면 안 된다).
 *
 * <p>버전 비교·JSON 파싱은 순수 함수로 분리해 네트워크 없이 검증한다.
 */
public final class UpdateChecker {

    /** 응답 JSON: {"latest":"0.2.2","url":"...","notes":"..."} */
    public record Release(String version, String url, String notes) {}

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** 재확인 간격 — 구버전을 쓰는 동안 1시간마다 다시 알린다(2026-08-19 사용자 요청). */
    private static final long RECHECK_INTERVAL_MS = 3_600_000;

    private static volatile boolean inFlight;
    private static volatile long lastCheckAt;   // 마지막으로 요청을 시작한 시각(0=아직 없음)
    private static volatile Release latest;

    private UpdateChecker() {
    }

    /** 마지막으로 확인한 최신 버전(없으면 null) — 설정 화면 표시용. */
    public static Release latest() {
        return latest;
    }

    /**
     * 비동기로 확인하고, 현재 버전보다 새 버전이 있을 때만 콜백한다.
     * 매 틱 호출해도 안전하다 — 내부에서 {@link #RECHECK_INTERVAL_MS}(1시간) 간격으로만
     * 실제 요청이 나간다. 구버전을 계속 쓰면 1시간마다 다시 알림이 뜬다.
     *
     * @param url            버전 정보 JSON 주소. 비어 있으면 아무것도 안 함.
     * @param currentVersion 현재 모드 버전
     * @param onUpdate       새 버전이 있을 때 호출(클라이언트 스레드 전환은 호출측 책임)
     */
    public static void checkAsync(String url, String currentVersion, Consumer<Release> onUpdate) {
        if (url == null || url.isBlank() || inFlight) return;
        if (!url.startsWith("https://") && !url.startsWith("http://")) return;
        long now = System.currentTimeMillis();
        if (lastCheckAt != 0 && now - lastCheckAt < RECHECK_INTERVAL_MS) return;  // 1시간에 한 번만
        lastCheckAt = now;
        inFlight = true;

        HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

        client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(res -> {
                    if (res.statusCode() == 200) {
                        Release r = parse(res.body());
                        if (r != null && isNewer(r.version(), currentVersion)) {
                            latest = r;
                            onUpdate.accept(r);
                        }
                    }
                })
                .whenComplete((v, err) -> {   // 실패해도 조용히 — 알림은 부가 기능일 뿐
                    inFlight = false;
                    // lastCheckAt 은 유지 → 다음 요청은 1시간 뒤. 실패해도 매 틱 재시도하지 않는다.
                });
    }

    /** 테스트·재확인용 초기화(다음 호출이 즉시 나가도록 타이머도 리셋). */
    public static void reset() {
        inFlight = false;
        lastCheckAt = 0;
        latest = null;
    }

    /** 잘못된 JSON 이면 null. 필수는 latest 뿐. */
    static Release parse(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String v = str(o, "latest");
            if (v == null || v.isBlank()) return null;
            return new Release(v.trim(), str(o, "url"), str(o, "notes"));
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    /** latest 가 current 보다 새 버전인가. */
    public static boolean isNewer(String latest, String current) {
        return compareVersions(latest, current) > 0;
    }

    /**
     * "0.2.10" &gt; "0.2.9" 가 되도록 마디별 숫자로 비교(문자열 비교면 뒤집힌다).
     * 숫자가 아닌 꼬리표("0.3.0-beta")는 숫자만 뽑아 쓰고, 마디 수가 다르면 없는 쪽을 0으로 본다.
     */
    public static int compareVersions(String a, String b) {
        String[] pa = split(a);
        String[] pb = split(b);
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? num(pa[i]) : 0;
            int y = i < pb.length ? num(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static String[] split(String v) {
        if (v == null) return new String[0];
        return v.trim().replaceFirst("^[vV]", "").split("\\.");
    }

    /** "2", "3-beta", "" → 2, 3, 0 */
    private static int num(String part) {
        int i = 0;
        while (i < part.length() && Character.isDigit(part.charAt(i))) i++;
        if (i == 0) return 0;
        try {
            return Integer.parseInt(part.substring(0, i));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
