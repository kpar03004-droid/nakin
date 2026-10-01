package kr.aka.nakin.util;

/** 골드 숫자 표기 유틸. 콤마 파싱/포맷 단일 집결지. */
public final class GoldFormat {
    private GoldFormat() {}

    /** "6,240,574" → 6240574. 실패 시 예외 대신 null 반환. */
    public static Long parseOrNull(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replaceAll("[^0-9-]", "");
        if (cleaned.isEmpty() || cleaned.equals("-")) return null;
        try {
            return Long.parseLong(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 콤마 제거 후 long. 매칭된 그룹 전용(형식 신뢰). */
    public static long parse(String raw) {
        return Long.parseLong(raw.replace(",", "").trim());
    }

    /** 1234567 → "1,234,567" */
    public static String format(long v) {
        return String.format("%,d", v);
    }

    /** 부호 포함: +1,234 / -1,234 */
    public static String signed(long v) {
        return (v >= 0 ? "+" : "-") + format(Math.abs(v));
    }
}
