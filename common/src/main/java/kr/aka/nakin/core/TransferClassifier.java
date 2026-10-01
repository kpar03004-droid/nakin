package kr.aka.nakin.core;

import kr.aka.nakin.config.DtConfig;
import kr.aka.nakin.core.TransactionRecord.Confidence;
import kr.aka.nakin.core.TransactionRecord.Kind;

/**
 * TradeSignal → TransactionRecord. 회계 정책의 단일 집결지.
 *
 * <p>신호가 방향·카테고리를 이미 싣고 오므로 여기서는 <b>정책</b>만 적용한다:
 * <ul>
 *   <li>이체(TRANSFER_*)는 설정에 따라 손익에서 제외</li>
 *   <li>마을 금고 입금/인출은 기본 지출/수입. 설정 {@code villageVaultAsTransfer} 를 켜면 이체로
 *       바꿔 손익에서 뺀다 — 세금처럼 "내 돈이 아닌 돈"으로 보고 싶은 유저용(사용자 판단 보류, 2026-09-28)</li>
 * </ul>
 */
public final class TransferClassifier {

    /** ΔG 교차검증 결과. */
    public enum CrossCheck { MATCHED_EXACT, NONE }

    private final DtConfig config;

    public TransferClassifier(DtConfig config) {
        this.config = config;
    }

    /** 메시지 금액 신호를 레코드로. */
    public TransactionRecord classify(TradeSignal sig, CrossCheck cc, long ts) {
        boolean crossed = cc == CrossCheck.MATCHED_EXACT;
        String note = sig.note;
        if (!crossed) note = join(note, "잔고 변동 미확인(메시지 금액 사용)");
        return build(sig, sig.amount, ts, crossed ? Confidence.HIGH : Confidence.MEDIUM, crossed, note);
    }

    /** 잔고 변동으로 금액을 매긴 신호를 레코드로. */
    public TransactionRecord classifyByDelta(TradeSignal sig, long amount, long ts, String note) {
        return build(sig, amount, ts, Confidence.HIGH, true, join(sig.note, note));
    }

    /** 금액을 끝내 못 알아낸 신호 — 금액 0, 손익 미반영, 흔적만 남긴다. */
    public TransactionRecord unresolved(TradeSignal sig, long ts, String reason) {
        TransactionRecord r = build(sig, 0, ts, Confidence.LOW, false,
                "금액 미확인(" + reason + ") — /낙인 추가 로 입력 후 이 줄 삭제");
        r.countedInPnl = false;
        return r;
    }

    private TransactionRecord build(TradeSignal sig, long amount, long ts, Confidence conf,
                                    boolean crossed, String note) {
        Kind kind = switch (sig.flow) {
            case INCOME -> Kind.INCOME;
            case EXPENSE -> Kind.EXPENSE;
            case TRANSFER_IN -> Kind.TRANSFER_IN;
            case TRANSFER_OUT -> Kind.TRANSFER_OUT;
        };
        if (DongleCategory.VILLAGE.equals(sig.category) && config.villageVaultAsTransfer) {
            kind = kind == Kind.EXPENSE ? Kind.TRANSFER_OUT : kind == Kind.INCOME ? Kind.TRANSFER_IN : kind;
        }
        boolean isTransfer = kind == Kind.TRANSFER_IN || kind == Kind.TRANSFER_OUT;
        boolean counted = !(isTransfer && config.transferExcludedFromPnl);
        return new TransactionRecord(ts, kind, amount, sig.category, sig.label, sig.qty, counted,
                conf, crossed, note);
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) return b;
        if (b == null || b.isBlank()) return a;
        return a + " · " + b;
    }
}
