package kr.aka.nakin.core;

import kr.aka.nakin.util.GoldFormat;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 제보용 최근 활동 기록 — 인식한 채팅 · 잔고 변동 · 확정 기록을 시간순으로 모은다.
 *
 * <p>"금액이 이상해요" 제보는 스크린샷만으론 원인을 못 찾는다. 같은 순간의 채팅 원문,
 * 잔고 변동, 모드가 적은 기록을 한 덩어리로 받으면 바로 재현 테스트를 만들 수 있다.
 * <b>메모리에만 두고 파일·서버로 보내지 않는다.</b> {@code /낙인 제보}가 클립보드로만 꺼내고,
 * 붙여넣을지는 사용자가 정한다.
 */
public final class ActivityLog {
    private ActivityLog() {}

    /** 건의·버그 제보 구글 폼(로그인 불필요). */
    public static final String REPORT_FORM_URL = "https://forms.gle/4PifTzDVemPqjPQN7";

    private static final int MAX = 80;
    /** 디스코드 메시지·폼 칸에 그대로 들어가게. */
    static final int REPORT_LIMIT = 1_900;
    private static final DateTimeFormatter T = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();

    static synchronized void add(String kind, String text) {
        LINES.addLast(LocalTime.now().format(T) + " " + kind + " " + text);
        while (LINES.size() > MAX) LINES.removeFirst();
    }

    public static void signal(TradeSignal s) {
        if (s != null) add("채팅", s.flow + " " + s.category + " | " + s.raw);
    }

    /** 규칙에 안 걸린 채팅 중 돈 얘기만 — 새 서버 문구를 찾는 단서. */
    public static void unmatched(String raw) {
        if (raw != null && raw.contains("냥")) add("미인식", raw);
    }

    public static void delta(long d) {
        add("잔고", GoldFormat.signed(d));
    }

    public static void record(TransactionRecord r) {
        if (r == null) return;
        String sign = r.kind == TransactionRecord.Kind.INCOME || r.kind == TransactionRecord.Kind.TRANSFER_IN ? "+" : "-";
        add("기록", sign + GoldFormat.format(r.amount) + " " + r.category + " / " + r.label
                + (r.note == null ? "" : " (" + r.note + ")"));
    }

    /** 제보문 머리말 — 버전·잔고 읽기 상태·잔고 대조·마지막 처리 결과. */
    public static List<String> header(String version, String balanceRead) {
        List<String> h = new ArrayList<>();
        h.add("낙인 " + version + " · fabric · MC 26.1.2");
        h.add("잔고 읽기: " + (balanceRead == null ? "한 번도 못 읽음" : strip(balanceRead)));
        Long gap = WalletCheck.LIVE.unexplained();
        h.add("잔고 대조: " + (!WalletCheck.LIVE.started() ? "불가" : gap == null ? "확인 중"
                : gap == 0 ? "일치" : "기록에 없는 변동 " + GoldFormat.signed(gap)));
        String settle = TransactionResolver.lastSettleInfo();
        if (settle != null) h.add("최근 처리: " + settle);
        return h;
    }

    /** 마인크래프트 색 코드(§x)·서버 아이콘 글리프(사용자 영역 문자) 제거. */
    static String strip(String s) {
        return s.replaceAll("§.", "").replaceAll("[\\uE000-\\uF8FF\\x{A0000}-\\x{10FFFF}]+", " ");
    }

    /** 그대로 붙일 평문. 길면 오래된 줄부터 뺀다. */
    public static synchronized String report(List<String> header) {
        List<String> lines = new ArrayList<>(LINES);
        StringBuilder head = new StringBuilder("```\n");
        for (String h : header) head.append(h).append('\n');
        head.append("--- 최근 활동 (오래된 순) ---\n");
        String tail = "```\n※ 다른 사람 이름이 있으면 지우고 붙여 주세요.";
        int budget = REPORT_LIMIT - head.length() - tail.length();
        int from = lines.size();
        int used = 0;
        while (from > 0 && used + lines.get(from - 1).length() + 1 <= budget) {
            used += lines.get(from - 1).length() + 1;
            from--;
        }
        StringBuilder out = new StringBuilder(head);
        if (from == lines.size()) out.append("(기록된 활동 없음)\n");
        for (int i = from; i < lines.size(); i++) out.append(lines.get(i)).append('\n');
        return out.append(tail).toString();
    }

    static synchronized void clearForTest() {
        LINES.clear();
    }
}
