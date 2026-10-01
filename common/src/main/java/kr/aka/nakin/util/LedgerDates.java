package kr.aka.nakin.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** "장부 날짜" 계산 — 하루 리셋 시각(resetHour)만큼 시프트해 날짜 버킷을 정한다. */
public final class LedgerDates {
    private LedgerDates() {}

    /**
     * epochMillis 를 resetHour 기준 장부 날짜로 변환.
     * 예: resetHour=6 이면 05:59까지는 전날로 집계.
     */
    public static LocalDate ledgerDate(long epochMillis, int resetHour) {
        return Instant.ofEpochMilli(epochMillis)
                .atZone(ZoneId.systemDefault())
                .minusHours(resetHour)
                .toLocalDate();
    }

    public static LocalDate today(int resetHour) {
        return ledgerDate(System.currentTimeMillis(), resetHour);
    }
}
