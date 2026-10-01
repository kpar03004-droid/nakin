package kr.aka.nakin.aggregate;

import kr.aka.nakin.core.TransactionRecord;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** 하루치 집계. 손익은 countedInPnl 레코드만 합산. */
public final class DailyBucket {
    public final LocalDate date;
    public long income;
    public long expense;
    public long transferIn;
    public long transferOut;
    public int count;
    public final Map<String, Long> incomeByCategory = new LinkedHashMap<>();
    public final Map<String, Long> expenseByCategory = new LinkedHashMap<>();
    public final Map<String, Long> transferInByCategory = new LinkedHashMap<>();
    public final Map<String, Long> transferOutByCategory = new LinkedHashMap<>();

    public DailyBucket(LocalDate date) {
        this.date = date;
    }

    public void add(TransactionRecord r) {
        count++;
        switch (r.kind) {
            case INCOME -> {
                if (r.countedInPnl) {
                    income += r.amount;
                    incomeByCategory.merge(cat(r), r.amount, Long::sum);
                }
            }
            case EXPENSE -> {
                if (r.countedInPnl) {
                    expense += r.amount;
                    expenseByCategory.merge(cat(r), r.amount, Long::sum);
                }
            }
            case TRANSFER_IN -> {
                transferIn += r.amount;
                transferInByCategory.merge(cat(r), r.amount, Long::sum);
            }
            case TRANSFER_OUT -> {
                transferOut += r.amount;
                transferOutByCategory.merge(cat(r), r.amount, Long::sum);
            }
        }
    }

    private static String cat(TransactionRecord r) {
        return r.category == null || r.category.isEmpty() ? "기타" : r.category;
    }

    public long netPnl() {
        return income - expense;
    }
}
