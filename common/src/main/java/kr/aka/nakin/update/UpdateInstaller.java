package kr.aka.nakin.update;

/**
 * {@link ModUpdater} 인스턴스 보관소.
 *
 * <p>로더마다 실제 로드된 JAR 경로를 얻는 방법이 다르므로 생성은 각 로더가 하고,
 * 알림·명령어·업데이트 화면은 여기서 꺼내 쓴다.
 *
 * <p><b>동의는 업데이트 화면이 맡는다</b> — 화면의 "동의 후 다운로드" 버튼만
 * {@code download(offer)} 를 부르고, 그때 넘기는 offer 는 화면에 표시된 바로 그 파일이다.
 * 표시 후 릴리즈가 바뀌었으면 ModUpdater 가 다운로드를 거부한다.
 * 틱·채팅 수신 같은 자동 경로는 {@code check()} 까지만 닿고 다운로드로 이어지지 않는다.
 */
public final class UpdateInstaller {
    private UpdateInstaller() {}

    private static volatile ModUpdater updater;
    private static volatile String unavailableReason = "초기화 전";

    /** 로더 초기화에서 한 번 호출. */
    public static void bind(ModUpdater u) {
        updater = u;
        unavailableReason = null;
    }

    /** 바인딩 실패 사유를 남긴다 — 업데이트 화면이 그대로 보여준다. */
    public static void unavailable(String reason) {
        updater = null;
        unavailableReason = reason;
    }

    public static ModUpdater get() { return updater; }

    /** 업데이터를 못 만들었으면(개발 환경, 신원 파일 누락, mods 밖 설치) 관련 안내를 숨긴다. */
    public static boolean available() { return updater != null; }

    /** 화면에 띄울 사유. 바인딩 성공 시 null. */
    public static String unavailableReason() { return unavailableReason; }
}
