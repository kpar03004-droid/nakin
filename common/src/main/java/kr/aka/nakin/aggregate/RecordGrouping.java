package kr.aka.nakin.aggregate;

import kr.aka.nakin.core.TransactionRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 내역 탭 표시 전용 — 연속으로 완전히 동일한 거래(종류·카테고리·라벨·단위금액·단위수량)를
 * 하나의 행으로 묶는다("uniq -c" 방식). 플리마켓 즉시구매처럼 같은 물건을 짧은 시간에
 * 반복 거래하면 내역이 같은 줄로 도배되는 것을 막는다.
 *
 * 표시용 집계일 뿐 — 원본 TransactionRecord(저장·CSV 내보내기·오늘 집계 합산 등)는 그대로.
 * 시간순으로 "바로 붙어있는" 것만 묶는다(순서 안 섞임 — 사이에 다른 거래가 끼면 새 그룹).
 */
public final class RecordGrouping {
    private RecordGrouping() {}

    /**
     * @param sources 이 묶음에 들어간 원본 레코드들 — 내역에서 개별 삭제할 때 필요(2026-07-28).
     *                묶음 한 줄을 지우면 여기 담긴 원본을 모두 지운다.
     */
    public record Grouped(TransactionRecord.Kind kind, String category, String label,
                          long amount, int qty, int count, List<TransactionRecord> sources) {}

    /** @param records 시간순(오래된 것 먼저) 원본 목록 */
    public static List<Grouped> collapseConsecutive(List<TransactionRecord> records) {
        List<Grouped> out = new ArrayList<>();
        TransactionRecord.Kind curKind = null;
        String curCat = null, curLabel = null;
        long curUnitAmt = 0, sumAmt = 0;
        int curUnitQty = 0, sumQty = 0, count = 0;
        List<TransactionRecord> group = new ArrayList<>();

        for (TransactionRecord r : records) {
            boolean same = count > 0 && curKind == r.kind && Objects.equals(curCat, r.category)
                    && Objects.equals(curLabel, r.label) && curUnitAmt == r.amount && curUnitQty == r.qty;
            if (same) {
                sumAmt += r.amount;
                sumQty += r.qty;
                count++;
                group.add(r);
            } else {
                if (count > 0) out.add(new Grouped(curKind, curCat, curLabel, sumAmt, sumQty, count, group));
                curKind = r.kind;
                curCat = r.category;
                curLabel = r.label;
                curUnitAmt = r.amount;
                curUnitQty = r.qty;
                sumAmt = r.amount;
                sumQty = r.qty;
                count = 1;
                group = new ArrayList<>();
                group.add(r);
            }
        }
        if (count > 0) out.add(new Grouped(curKind, curCat, curLabel, sumAmt, sumQty, count, group));
        return out;
    }
}
