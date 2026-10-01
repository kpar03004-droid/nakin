package kr.aka.nakin.util;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 화면 표면 텍스트에서 골드 잔고를 추출한다.
 *
 * <p><b>왜 이렇게까지 하나</b> — 서버에 따라 잔고 표시가 아이콘 + 숫자뿐이라 화폐 글자가 없을 수 있다.
 * 그래서 마커로 못 찾고 "화면에서 가장 큰 숫자"를 쓸 수밖에 없는데, 그 줄이 한 틱이라도
 * 사라지면 무관한 숫자를 잔고로 집어 <b>거대한 가짜 ΔG</b>가 만들어진다. 실제 사고 2건:
 * <ul>
 *   <li>0원 수리에 −170,901,145 기록(2026-08-08)</li>
 *   <li>5,000 강화에 ΔG −3,220,718 — 실제 잔고 3,315,718인데 100,000으로 읽음(2026-08-14)</li>
 * </ul>
 *
 * <p><b>해법: 줄 모양 잠금.</b> 숫자를 뺀 나머지 문자열(장식 글리프·라벨·구분자)은 잔고가
 * 변해도 그대로다. 한 번 잔고로 인정한 줄의 모양을 기억하고, 이후로는 <b>같은 모양의 줄에서만</b>
 * 읽는다. 그 줄이 안 보이면 다른 숫자로 갈아타는 대신 <b>판단을 보류</b>(null)한다.
 *
 * <p>모양이 오래 안 보이면(서버 UI 개편 등) 잠금을 풀고 다시 찾는다 — 영영 못 읽는 상태를 피한다.
 */
public final class BalanceExtractor {
    private static final String DEFAULT_REGEX = "([0-9][0-9,]{2,})";

    /** 잠긴 모양이 이만큼 연속으로 안 보이면 잠금 해제 후 재탐색(20틱 ≈ 1초). */
    private static final int UNLOCK_AFTER_MISSES = 20;

    private Pattern pattern;
    private String compiledFor;

    /** 숫자를 뺀 줄 모양. 이 줄만 잔고로 인정한다. */
    private String lockedShape;
    private int missCount;

    /** 월드/서버 전환 등으로 기준선을 버릴 때 함께 호출 — 다음 프레임부터 새로 찾는다. */
    public void unlock() {
        lockedShape = null;
        missCount = 0;
    }

    /** 진단용 — 지금 어떤 줄 모양을 따라가고 있는가. */
    public String lockedShape() {
        return lockedShape;
    }

    /**
     * @return 잔고 후보. 못 찾으면 <b>null</b>(이번 틱은 판단 보류) — 엉뚱한 값을 지어내지 않는다.
     */
    public Long extract(List<String> lines, String marker, String regex) {
        Pattern p = compile(regex);

        // ① 잠긴 모양이 있으면 그 줄에서만 읽는다
        if (lockedShape != null) {
            Long locked = bestOfShape(lines, p, lockedShape);
            if (locked != null) {
                missCount = 0;
                return locked;
            }
            if (++missCount < UNLOCK_AFTER_MISSES) {
                return null;   // 잠깐 사라진 것 — 다른 숫자로 갈아타지 않는다
            }
            unlock();          // 오래 안 보이면 UI 가 바뀐 것으로 보고 재탐색
        }

        // ② 잠금이 없으면 후보를 고르고 그 줄 모양을 기억한다
        //    마커('골드')가 있는 줄이 있으면 그쪽을 우선(다른 서버 표기 대비).
        Long best = null;
        String bestShape = null;
        boolean markerSeen = false;

        for (String line : lines) {
            if (line == null || line.isEmpty()) continue;
            if (isNonBalanceLine(line)) continue;        // 퀘스트 진행도 줄은 잔고가 아니다
            boolean hasMarker = marker != null && !marker.isEmpty() && line.contains(marker);
            if (markerSeen && !hasMarker) continue;      // 마커 줄을 이미 봤으면 나머지는 무시
            if (hasMarker && !markerSeen) {              // 첫 마커 줄 — 그동안 고른 건 버린다
                markerSeen = true;
                best = null;
                bestShape = null;
            }
            Matcher m = p.matcher(line);
            while (m.find()) {
                Long v = GoldFormat.parseOrNull(m.group(1));
                if (v == null) continue;
                if (best == null || v > best) {
                    best = v;
                    bestShape = shapeOf(line);
                }
            }
        }
        if (best != null) {
            lockedShape = bestShape;
            missCount = 0;
        }
        return best;
    }

    /** 지정한 모양의 줄들에서 가장 큰 값. 없으면 null. */
    private Long bestOfShape(List<String> lines, Pattern p, String shape) {
        Long best = null;
        for (String line : lines) {
            if (line == null || line.isEmpty()) continue;
            if (isNonBalanceLine(line)) continue;
            if (!shape.equals(shapeOf(line))) continue;
            Matcher m = p.matcher(line);
            while (m.find()) {
                Long v = GoldFormat.parseOrNull(m.group(1));
                if (v != null && (best == null || v > best)) best = v;
            }
        }
        return best;
    }

    /**
     * 잔고로 읽으면 안 되는 줄인가.
     *
     * <p>의뢰 트래커의 진행도 줄이 대표적이다 — "상점에서 골드 소모하기 (0/100,000)".
     * 이런 줄은 "골드"라는 글자까지 들어 있어(마커와 충돌) 진짜 잔고보다 먼저 채택돼 버렸고,
     * 목표 금액 100,000 을 잔고로 오인해 거대한 가짜 ΔG 를 만들었다(2026-08-18 실측).
     * "(현재/목표)" 꼴 괄호 분수는 잔고가 가질 수 없는 모양이므로 통째로 배제한다.
     */
    static boolean isNonBalanceLine(String line) {
        return QUEST_PROGRESS.matcher(line).find();
    }

    /** "(0/100,000)" · "(3 / 30)" 처럼 괄호 안 현재/목표 분수. */
    private static final Pattern QUEST_PROGRESS =
            Pattern.compile("\\(\\s*[0-9][0-9,]*\\s*/\\s*[0-9][0-9,]*\\s*\\)");

    /**
     * 숫자를 지운 줄 모양. "󐀃 3,315,718" → "󐀃 #"
     * 잔고가 바뀌어도 모양은 그대로라 같은 줄을 계속 따라갈 수 있다.
     */
    static String shapeOf(String line) {
        return line.replaceAll("[0-9][0-9,]*", "#");
    }

    private Pattern compile(String regex) {
        String rgx = regex == null || regex.isEmpty() ? DEFAULT_REGEX : regex;
        if (!rgx.equals(compiledFor)) {
            pattern = Pattern.compile(rgx);
            compiledFor = rgx;
        }
        return pattern;
    }
}
