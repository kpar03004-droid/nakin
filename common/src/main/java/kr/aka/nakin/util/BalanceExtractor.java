package kr.aka.nakin.util;

import java.util.ArrayDeque;
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

    /**
     * 잔고가 아닌 것으로 판명된 줄 모양 — 다시 고르지 않는다(세션 동안 유지).
     *
     * <p>접속 직후 잔고 패널이 아직 안 떴을 때 다른 숫자 줄(30~50 사이를 ±1씩 오르내림 — 핑·접속자 수
     * 같은 줄로 추정)을 잡으면, 그 줄은 계속 보이니 잠금이 풀리지 않아 하루 종일 잔고를 못 읽었다
     * (2026-10-03·04 실서버: ΔG 1,697건이 전부 ±1, 실제 거래 0건 교차확인).
     */
    private final java.util.Map<String, Long> rejectedShapes = new java.util.HashMap<>(); // 모양 → 버린 때(읽기 횟수)
    /** 버린 모양도 이만큼 읽은 뒤(약 10분, 매 틱 1회)엔 다시 후보로 — 진짜 잔고를 잘못 버렸어도 접속 내내 막히지 않게. */
    static final long REJECT_EXPIRE_READS = 12_000;
    private long reads;
    /** 잠긴 줄의 작은 변동 기록 {시각, 부호}. */
    private final ArrayDeque<long[]> smallDeltas = new ArrayDeque<>();
    static final long JITTER_MAX = 5;
    static final long JITTER_WINDOW_MS = 60_000;
    static final int JITTER_FLIPS = 4;

    /** 새 접속 — 지난 접속에서 버린 줄 모양도 잊는다(서버 UI 가 바뀌었을 수 있다). */
    public void forgetRejected() {
        rejectedShapes.clear();
        unlock();
    }

    /** 월드/서버 전환 등으로 기준선을 버릴 때 함께 호출 — 다음 프레임부터 새로 찾는다. */
    public void unlock() {
        lockedShape = null;
        missCount = 0;
        smallDeltas.clear();
    }

    /**
     * 잠긴 줄에서 확정된 변동을 알려준다. {@value #JITTER_MAX} 이하의 작은 값이 {@value #JITTER_WINDOW_MS}ms
     * 안에 {@value #JITTER_FLIPS}번 이상 방향을 바꾸며 오르내리면 잔고가 아니다 — 그 모양을 버리고 다시 찾는다.
     *
     * @return 버렸으면 true (호출 측은 이 변동을 거래로 쓰지 말고 기준선을 비운다)
     */
    public boolean noteDelta(long delta, long now) {
        if (lockedShape == null) return false;
        if (Math.abs(delta) > JITTER_MAX) {
            smallDeltas.clear();
            return false;
        }
        smallDeltas.addLast(new long[]{now, Long.signum(delta)});
        while (now - smallDeltas.peekFirst()[0] > JITTER_WINDOW_MS) smallDeltas.removeFirst();
        int flips = 0;
        long prev = 0;
        for (long[] d : smallDeltas) {
            if (prev != 0 && d[1] != prev) flips++;
            prev = d[1];
        }
        if (flips < JITTER_FLIPS) return false;
        rejectedShapes.put(lockedShape, reads);
        unlock();
        return true;
    }

    /** 진단용 — 지금 어떤 줄 모양을 따라가고 있는가. */
    public String lockedShape() {
        return lockedShape;
    }

    /**
     * @return 잔고 후보. 못 찾으면 <b>null</b>(이번 틱은 판단 보류) — 엉뚱한 값을 지어내지 않는다.
     */
    public Long extract(List<String> rawLines, String marker, String regex) {
        reads++;
        if (!rejectedShapes.isEmpty()) rejectedShapes.values().removeIf(r -> reads - r > REJECT_EXPIRE_READS);
        Pattern p = compile(regex);
        List<String> lines = withPanels(rawLines, marker);

        // ⓪ 마커 없는 줄을 잡고 있는데 마커 줄이 보이면 갈아탄다 — 접속 직후 잔고 패널이 뜨기 전에
        //    "온라인 : 36명" 같은 줄을 잡으면 그 줄이 계속 보여 잠금이 안 풀렸다(2026-10-03·04·05 실서버).
        if (lockedShape != null && hasMarker(marker) && !lockedShape.contains(marker)) {
            for (String line : lines) {
                if (line != null && line.contains(marker) && !isNonBalanceLine(line)) {
                    unlock();
                    break;
                }
            }
        }

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
            if (!rejectedShapes.isEmpty() && rejectedShapes.containsKey(shapeOf(line))) continue; // 잔고 아님 판명
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

    private static boolean hasMarker(String marker) {
        return marker != null && !marker.isEmpty();
    }

    /**
     * 서버 아이콘·간격 글리프 — 리소스팩 패널에서 칸 구분자로 쓰인다. 동글랜드 보스바는 사용자 영역(U+E000~)이
     * 아니라 <b>U+CE000~U+D00FF</b>(보충 평면 미할당 영역)를 쓴다(2026-10-05 실서버 로그 실측) → A0000 부터 넓게.
     */
    private static final Pattern GLYPHS = Pattern.compile("[\\uE000-\\uF8FF\\x{A0000}-\\x{10FFFF}]+");
    private static final Pattern NUMBER_CELL = Pattern.compile("^[0-9][0-9,]*(?:\\.[0-9]+)?$");

    /** 줄 안에 재화 패널이 있으면 "마커 잔고" 한 줄로 바꾼 목록. */
    static List<String> withPanels(List<String> lines, String marker) {
        if (!hasMarker(marker)) return lines;
        List<String> out = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String panel = line == null ? null : panelLine(line, marker);
            if (panel == null) continue;
            if (out == null) out = new java.util.ArrayList<>(lines);
            out.set(i, panel);
        }
        return out == null ? lines : out;
    }

    /**
     * 동글랜드 우하단 재화 패널 — 보스바 <b>한 줄</b>에 아이콘 글리프로 칸이 나뉘어
     * "135,944 │ 150 │ 4,920 │ 256.5 │ 10,000 │ 냥 │ 퀘스트포인트 │ 모험포인트 │ 휴식포인트 │ 동글머니"
     * 처럼 숫자들 다음에 같은 순서의 이름들이 온다(2026-10-05 /낙인 진단). 같은 줄에 퀘스트 트래커
     * "3,000,000냥 모으기 (135944/3000000)" 가 섞여 줄 전체를 진행도 줄로 버리거나 가장 큰 숫자를
     * 고르면 틀린다 → 마커 이름 칸 바로 앞에 이어진 숫자 칸 중 <b>첫 번째</b>(= 첫 이름의 짝)를 잔고로.
     *
     * @return "냥 135,944" 꼴의 줄. 패널 모양이 아니면 null.
     */
    static String panelLine(String line, String marker) {
        if (!line.contains(marker)) return null;
        String[] raw = GLYPHS.split(line);
        List<String> cells = new java.util.ArrayList<>(raw.length);
        for (String c : raw) {
            String t = c.strip();
            if (!t.isEmpty()) cells.add(t);
        }
        for (int i = 1; i < cells.size(); i++) {
            if (!cells.get(i).equals(marker)) continue;
            int j = i - 1;
            while (j >= 0 && NUMBER_CELL.matcher(cells.get(j)).matches()) j--;
            if (j == i - 1) continue;          // 이름 칸 바로 앞이 숫자가 아니다
            return marker + " " + cells.get(j + 1);
        }
        return null;
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
