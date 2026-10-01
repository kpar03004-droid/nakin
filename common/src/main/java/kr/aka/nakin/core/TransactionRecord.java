package kr.aka.nakin.core;

/**
 * 확정된 회계 레코드(저장·집계 단위). Gson 직렬화 대상 — 필드 직접 사용.
 */
public final class TransactionRecord {

    public enum Kind {
        INCOME,        // 수입 (+손익)
        EXPENSE,       // 지출 (-손익)
        TRANSFER_IN,   // 이체 유입 (손익 제외)
        TRANSFER_OUT   // 이체 유출 (손익 제외)
    }

    public enum Confidence {
        HIGH,   // 메시지 + ΔG 정확 매칭
        MEDIUM, // 메시지 금액만(잔고 변동 미확인)
        LOW     // 금액 미확인(0원 흔적 레코드)
    }

    public long timestamp;      // epoch millis
    public Kind kind;
    public long amount;         // 항상 양수
    public String category;     // DongleCategory 상수(대장장이·거래소·송금·마을 금고 …)
    public String label;        // 품목/설명
    public int qty;
    public boolean countedInPnl; // 손익 합산 여부
    public Confidence confidence;
    public boolean crossChecked; // ΔG 로 검증됨
    public String note;          // 경고/비고(금액 불일치 등)

    public TransactionRecord() {
        // Gson
    }

    public TransactionRecord(long timestamp, Kind kind, long amount, String category, String label,
                             int qty, boolean countedInPnl, Confidence confidence,
                             boolean crossChecked, String note) {
        this.timestamp = timestamp;
        this.kind = kind;
        this.amount = amount;
        this.category = category;
        this.label = label;
        this.qty = qty;
        this.countedInPnl = countedInPnl;
        this.confidence = confidence;
        this.crossChecked = crossChecked;
        this.note = note;
    }

    /** 손익 기여분: 수입 +amount, 지출 -amount, 이체 0. countedInPnl=false 면 0. */
    public long pnlDelta() {
        if (!countedInPnl) return 0;
        return switch (kind) {
            case INCOME -> amount;
            case EXPENSE -> -amount;
            case TRANSFER_IN, TRANSFER_OUT -> 0;
        };
    }

    public boolean isTransfer() {
        return kind == Kind.TRANSFER_IN || kind == Kind.TRANSFER_OUT;
    }

    @Override
    public String toString() {
        return "TxRecord{" + kind + " " + amount + " [" + category + "/" + label + "] pnl="
                + pnlDelta() + " " + confidence + (crossChecked ? " ✓" : "") + "}";
    }
}
