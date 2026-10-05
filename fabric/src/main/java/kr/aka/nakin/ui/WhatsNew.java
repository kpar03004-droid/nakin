package kr.aka.nakin.ui;

import java.util.List;

/**
 * 업데이트 후 처음 창을 열면 오늘 탭 맨 위에 한 번 뜨는 「새로워진 점」.
 * 새 버전을 낼 때 {@link #VERSION}과 {@link #LINES}만 바꾼다. 줄은 3개 이하, 짧게.
 */
public final class WhatsNew {
    private WhatsNew() {}

    public static final String VERSION = "0.1.2";
    public static final List<String> LINES = List.of(
            "잔고 대조: 기록에 없는 잔고 변동을 오늘 탭 아래에 알려줘요",
            "[통계] = 기간별·분야별 수익, 수입·지출을 누르면 뭘 팔고 샀는지까지",
            "내역 줄 클릭 = 수정 · /낙인 제보 · Ctrl+C 정산 복사 · 숫자키 탭 이동");

    /** 이 사용자가 아직 이번 버전 소식을 안 봤는가. */
    public static boolean unseen(String lastSeen) {
        return !VERSION.equals(lastSeen);
    }
}
