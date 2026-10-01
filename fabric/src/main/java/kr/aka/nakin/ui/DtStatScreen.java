package kr.aka.nakin.ui;

import kr.aka.nakin.aggregate.DailyAggregator;
import kr.aka.nakin.aggregate.DailyBucket;
import kr.aka.nakin.aggregate.RecordGrouping;
import kr.aka.nakin.config.DtConfig;
import kr.aka.nakin.core.TransactionRecord;
import kr.aka.nakin.util.GoldFormat;
import kr.aka.nakin.util.LedgerDates;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /낙인 정산 창(양피지 장부) — 오늘 · 주간 · 내역 · 관리 · 설정 5탭.
 * 배경/탭/버튼/구분선/스크롤바 전부 Claude Design 9-slice 텍스처(GuiTex). 전부 read-only 표시.
 */
public final class DtStatScreen extends Screen {

    private static final int DIM = 0x90000000;

    private static final int W = 360;
    private static final int PAD = 12;
    private static final int ROW_H = 13;
    private static final int TOP = 36; // 패널 상단 고정 — 위젯 좌표 안정성
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("MM-dd");

    private static final int TAB_STEP = 54;
    private static final int TAB_W = 52;
    private static final int TAB_H = 20;

    private final DtConfig config;
    private final DailyAggregator aggregator;
    private final LedgerHud hud;
    private final java.util.function.Consumer<TransactionRecord> sink;
    private int tab = 0; // 0=오늘 1=주간 2=내역 3=관리 4=설정

    // 관리 탭
    private EditBox mAmount;
    private EditBox mLabel;
    private TexButton mIncome;
    private TexButton mExpense;
    private TexButton mReset;
    private TexButton mResetWeek;
    private TexButton mResetDate;
    private EditBox mDate;
    /** 어떤 초기화 버튼이 확인 대기 중인가(0=없음 1=오늘 2=최근7일 3=지정날짜) — 오폭 방지 2단계 확인. */
    private int resetArmedKind;

    // 설정 탭
    private TexButton sHud;
    private TexButton sConfig;
    private TexButton sOpacityDown;
    private TexButton sOpacityUp;

    private static final int VISIBLE_PENDING = 12; // 내역 탭 한 화면 행 수(나머지는 휠 스크롤)
    private int pendingScroll;

    public DtStatScreen(DtConfig config, DailyAggregator aggregator,
                        java.util.function.Consumer<TransactionRecord> sink, LedgerHud hud) {
        super(Component.literal("낙인 정산"));
        this.config = config;
        this.aggregator = aggregator;
        this.sink = sink;
        this.hud = hud;
    }

    private int panelX() { return (this.width - W) / 2; }

    @Override
    protected void init() {
        int x = panelX();
        int tabY = TOP + 38; // 헤더 텍스트(TOP+24) 바로 아래. 페이지 상단 테두리(20px) 밖
        String[] names = {"오늘", "주간", "내역", "관리", "설정"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            addRenderableWidget(new TabButton(x + PAD + i * TAB_STEP, tabY, TAB_W, TAB_H, Component.literal(names[i]),
                    () -> this.tab == idx, () -> {
                this.tab = idx;
                resetArmedKind = 0; // 탭 이동 시 확인 대기 취소
                updateWidgets();
            }));
        }

        int cy = TOP + 62;

        // 관리 탭 위젯 (수동 입력 + 오늘 초기화)
        mAmount = new EditBox(this.font, x + PAD + 2, cy + 14, 106, 14, Component.literal("금액"));
        mAmount.setMaxLength(15);
        mAmount.setBordered(false);
        mAmount.setTextColor(GuiTex.TEXT);
        addRenderableWidget(mAmount);

        mLabel = new EditBox(this.font, x + PAD + 118, cy + 14, W - PAD * 2 - 120, 14, Component.literal("설명"));
        mLabel.setMaxLength(40);
        mLabel.setBordered(false);
        mLabel.setTextColor(GuiTex.TEXT);
        addRenderableWidget(mLabel);

        mIncome = new TexButton(x + PAD, cy + 36, 150, 18, Component.literal("+ 수입 추가"), () -> addManual(true));
        addRenderableWidget(mIncome);

        mExpense = new TexButton(x + W - PAD - 150, cy + 36, 150, 18, Component.literal("− 지출 추가"), () -> addManual(false));
        addRenderableWidget(mExpense);

        mReset = new TexButton(x + PAD, cy + 68, (W - PAD * 2 - 4) / 2, 18,
                Component.literal("오늘 초기화"), this::onResetClick);
        addRenderableWidget(mReset);

        mResetWeek = new TexButton(x + PAD + (W - PAD * 2 - 4) / 2 + 4, cy + 68, (W - PAD * 2 - 4) / 2, 18,
                Component.literal("최근 7일 초기화"), this::onResetWeekClick);
        addRenderableWidget(mResetWeek);

        // 특정 날짜만 지우기 — 잘못 기록된 날짜를 직접 지정(YYYY-MM-DD, 비우면 오늘)
        mDate = new EditBox(this.font, x + PAD + 2, cy + 104, 106, 14, Component.literal("날짜"));
        mDate.setMaxLength(10);
        mDate.setBordered(false);
        mDate.setTextColor(GuiTex.TEXT);
        // placeholder 는 그림자가 붙어 양피지 위에서 글자가 두 겹으로 보여 쓰지 않음 —
        // 형식 안내는 위 라벨에 넣는다(2026-07-28).
        addRenderableWidget(mDate);

        mResetDate = new TexButton(x + PAD + 116, cy + 102, W - PAD * 2 - 116, 18,
                Component.literal("이 날짜 초기화"), this::onResetDateClick);
        addRenderableWidget(mResetDate);

        // 설정 탭 위젯
        sHud = new TexButton(x + PAD, cy + 14, W - PAD * 2, 20, Component.literal("HUD 위치 설정"), () ->
                this.minecraft.setScreen(new DtHudEditScreen(config, hud, this)));
        addRenderableWidget(sHud);

        sConfig = new TexButton(x + PAD, cy + 40, W - PAD * 2, 20, Component.literal("설정 열기"), () -> {
            Screen screen = kr.aka.nakin.config.DtConfigScreen.create(config, this);
            if (screen != null) this.minecraft.setScreen(screen);
        });
        addRenderableWidget(sConfig);

        sOpacityDown = new TexButton(x + PAD, cy + 66, 30, 18, Component.literal("-"), () -> adjustOpacity(-0.1f));
        addRenderableWidget(sOpacityDown);
        sOpacityUp = new TexButton(x + W - PAD - 30, cy + 66, 30, 18, Component.literal("+"), () -> adjustOpacity(0.1f));
        addRenderableWidget(sOpacityUp);

        updateWidgets();
    }

    private void updateWidgets() {
        boolean manage = tab == 3;
        mAmount.visible = manage;
        mLabel.visible = manage;
        mIncome.visible = manage;
        mExpense.visible = manage;
        mReset.visible = manage;
        if (mResetWeek != null) mResetWeek.visible = manage;
        if (mResetDate != null) mResetDate.visible = manage;
        if (mDate != null) mDate.visible = manage;
        // 확인 대기 중인 버튼만 문구가 바뀐다(어느 걸 지우려는지 헷갈리지 않게)
        if (mReset != null) mReset.setMessage(Component.literal(
                resetArmedKind == 1 ? "정말 지울까요?" : "오늘 초기화"));
        if (mResetWeek != null) mResetWeek.setMessage(Component.literal(
                resetArmedKind == 2 ? "정말 지울까요?" : "최근 7일 초기화"));
        if (mResetDate != null) mResetDate.setMessage(Component.literal(
                resetArmedKind == 3 ? "정말 지울까요?" : "이 날짜 초기화"));

        boolean settings = tab == 4;
        if (sHud != null) sHud.visible = settings;
        if (sConfig != null) sConfig.visible = settings;
        if (sOpacityDown != null) sOpacityDown.visible = settings;
        if (sOpacityUp != null) sOpacityUp.visible = settings;
    }

    private void adjustOpacity(float delta) {
        config.hudOpacity = Math.round(Math.max(0.2f, Math.min(1.0f, config.hudOpacity + delta)) * 100) / 100f;
        config.save();
    }

    private void addManual(boolean income) {
        String digits = mAmount.getValue().replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return;
        long amt;
        try {
            amt = Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return;
        }
        String label = mLabel.getValue().isBlank() ? "수동 입력" : mLabel.getValue().trim();
        TransactionRecord rec = new TransactionRecord(System.currentTimeMillis(),
                income ? TransactionRecord.Kind.INCOME : TransactionRecord.Kind.EXPENSE,
                amt, "수동", label, 0, true, TransactionRecord.Confidence.HIGH, false, "수동 입력");
        sink.accept(rec);
        mAmount.setValue("");
        mLabel.setValue("");
    }

    /**
     * 내역 탭에서 Shift+클릭한 줄의 기록을 삭제(2026-07-28 요청: 잘못된 데이터만 고치고 싶은 경우).
     * 묶음("×N") 줄이면 그 묶음에 들어간 원본 전부를 지운다. 집계·저장 원장 양쪽에서 제거됨.
     * @return 지웠으면 true
     */
    private boolean deleteRowAt(double mouseX, double mouseY) {
        int x = panelX();
        int left = x + PAD, right = x + W - PAD;
        if (mouseX < left || mouseX > right) return false;

        List<RecordGrouping.Grouped> p = groupedPending();
        if (p.isEmpty()) return false;
        int total = p.size();
        int start = Math.max(0, Math.min(pendingScroll, Math.max(0, total - VISIBLE_PENDING)));
        int end = Math.min(total, start + VISIBLE_PENDING);

        int y0 = TOP + 62; // renderPending 시작 y 와 동일해야 함
        int row = (int) ((mouseY - y0) / ROW_H);
        if (row < 0 || row >= end - start) return false;

        RecordGrouping.Grouped g = p.get(total - 1 - (start + row)); // 최신이 위 — 렌더와 동일 순서
        boolean removed = false;
        for (TransactionRecord r : g.sources()) {
            if (aggregator.deleteRecord(r)) removed = true;
        }
        if (removed) pendingScroll = 0; // 목록이 줄어 스크롤 위치가 어긋나지 않게
        return removed;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent ev, boolean doubleClick) {
        if (tab == 2 && ev.button() == 0 && ev.hasShiftDown() && deleteRowAt(ev.x(), ev.y())) {
            return true;
        }
        return super.mouseClicked(ev, doubleClick);
    }

    private void onResetClick() {
        if (armOrConfirm(1)) aggregator.resetToday();
    }

    private void onResetWeekClick() {
        if (armOrConfirm(2)) aggregator.resetLastDays(7);
    }

    /** 지정 날짜(YYYY-MM-DD, 비우면 오늘)만 초기화 — 잘못 기록된 특정 날짜 수정용. */
    private void onResetDateClick() {
        java.time.LocalDate date = parseDateInput();
        if (date == null) { // 형식이 틀리면 아무것도 지우지 않고 대기 해제
            resetArmedKind = 0;
            updateWidgets();
            return;
        }
        if (armOrConfirm(3)) aggregator.resetDay(date);
    }

    private java.time.LocalDate parseDateInput() {
        String s = mDate == null ? "" : mDate.getValue().trim();
        if (s.isEmpty()) return LedgerDates.today(config.dayResetHour);
        try {
            return java.time.LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 2단계 확인: 처음 누르면 해당 버튼만 "정말 지울까요?"로 바뀌고, 한 번 더 눌러야 실행.
     * 다른 초기화 버튼을 누르면 이전 대기는 취소된다(오폭 방지).
     * @return 실제로 실행할 차례면 true
     */
    private boolean armOrConfirm(int kind) {
        if (resetArmedKind != kind) {
            resetArmedKind = kind;
            updateWidgets();
            return false;
        }
        resetArmedKind = 0;
        updateWidgets();
        return true;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, DIM);
        drawPanel(ctx);
    }

    private void drawPanel(GuiGraphicsExtractor ctx) {
        int x = panelX();
        int h = 68 + contentHeight() + PAD; // cy(TOP+62) + 6
        int right = x + W;

        GuiTex.sprite(ctx, "tex_page", x, TOP, W, h);

        // 헤더 — 9-slice 테두리(20px) 바로 아래(4px 여백)
        ctx.text(font, "낙인", x + PAD, TOP + 24, GuiTex.TITLE, false);
        String today = LedgerDates.today(config.dayResetHour).toString();
        ctx.text(font, today, right - PAD - font.width(today), TOP + 24, GuiTex.LABEL, false);

        int cy = TOP + 62;
        switch (tab) {
            case 0 -> renderToday(ctx, x, cy);
            case 1 -> renderWeek(ctx, x, cy);
            case 2 -> renderPending(ctx, x, cy);
            case 3 -> renderManage(ctx, x, cy);
            case 4 -> renderSettings(ctx, x, cy);
        }
    }

    private int contentHeight() {
        return switch (tab) {
            case 0 -> {
                DailyBucket b = aggregator.today();
                int cats = shownCats(b.incomeByCategory) + shownCats(b.expenseByCategory);
                yield 64 + (cats > 0 ? 16 + cats * ROW_H : 0) + (showTransfers(b) ? 16 : 0);
            }
            case 1 -> 7 * (ROW_H + 1) + 26 + ROW_H; // +범례 한 줄
            case 2 -> Math.max(28, Math.min(Math.max(groupedPending().size(), 1), VISIBLE_PENDING) * ROW_H + 16);
            case 3 -> 140; // 수동입력 + 초기화 3종(날짜칸·안내문 포함) — 패널 밖으로 넘치지 않게
            default -> 96; // 설정
        };
    }

    private static int shownCats(Map<String, Long> m) { return Math.min(m.size(), 5); }

    private boolean showTransfers(DailyBucket b) {
        return config.showTransfers && (b.transferIn > 0 || b.transferOut > 0);
    }

    // ── 오늘 탭 ──
    private void renderToday(GuiGraphicsExtractor ctx, int x, int y) {
        DailyBucket b = aggregator.today();
        long net = b.netPnl();
        int left = x + PAD, right = x + W - PAD;

        GuiTex.sprite(ctx, "tex_card", left, y, right - left, 30);
        ctx.text(font, "오늘 순익", left + 8, y + 5, GuiTex.LABEL, false);
        String netStr = GoldFormat.signed(net) + " 냥";
        int netColor = net > 0 ? GuiTex.GREEN : (net < 0 ? GuiTex.RED : GuiTex.NEUTRAL);
        ctx.text(font, netStr, left + 8, y + 16, netColor, false);
        String cnt = b.count + "건";
        ctx.text(font, cnt, right - 8 - font.width(cnt), y + 16, GuiTex.LABEL, false);
        y += 36;

        int half = (right - left - 8) / 2;
        ctx.text(font, "▲ 수입", left, y, GuiTex.GREEN, false);
        String in = GoldFormat.format(b.income);
        ctx.text(font, in, left + half - font.width(in), y, GuiTex.TEXT, false);
        ctx.text(font, "▼ 지출", left + half + 8, y, GuiTex.RED, false);
        String out = GoldFormat.format(b.expense);
        ctx.text(font, out, right - font.width(out), y, GuiTex.TEXT, false);
        y += 16;

        int cats = shownCats(b.incomeByCategory) + shownCats(b.expenseByCategory);
        if (cats > 0) {
            GuiTex.tileH(ctx, "tex_divider", left, right, y, 48, 6);
            y += 6;
            long max = 1;
            for (long v : b.incomeByCategory.values()) max = Math.max(max, v);
            for (long v : b.expenseByCategory.values()) max = Math.max(max, v);
            y = catRows(ctx, b.incomeByCategory, left, right, y, max, GuiTex.GREEN);
            y = catRows(ctx, b.expenseByCategory, left, right, y, max, GuiTex.RED);
        }

        if (showTransfers(b)) {
            y += 4;
            String tr = "이체(손익 제외)  +" + GoldFormat.format(b.transferIn) + " / -" + GoldFormat.format(b.transferOut);
            ctx.text(font, tr, left, y, GuiTex.BLUE, false);
        }
    }

    private int catRows(GuiGraphicsExtractor ctx, Map<String, Long> map, int left, int right, int y,
                        long max, int accent) {
        int shown = 0;
        for (Map.Entry<String, Long> e : sortDesc(map).entrySet()) {
            if (shown++ >= 5) break;
            GuiTex.sprite(ctx, GuiTex.icon(e.getKey()), left, y, 12, 12); // 12px 카테고리 아이콘
            int labelLeft = left + 15;
            String label = font.plainSubstrByWidth(e.getKey(), 60);
            ctx.text(font, label, labelLeft, y + 2, GuiTex.LABEL, false);
            int barLeft = left + 80;
            int barMax = right - barLeft - 76;
            int bw = (int) Math.max(2, barMax * e.getValue() / max);
            ctx.fill(barLeft, y + 3, barLeft + barMax, y + 9, GuiTex.TRACK);
            ctx.fill(barLeft, y + 3, barLeft + bw, y + 9, accent);
            String amt = GoldFormat.format(e.getValue());
            ctx.text(font, amt, right - font.width(amt), y + 2, GuiTex.TEXT, false);
            y += ROW_H;
        }
        return y;
    }

    private static Map<String, Long> sortDesc(Map<String, Long> m) {
        Map<String, Long> out = new LinkedHashMap<>();
        m.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    // ── 주간 탭 ──
    private void renderWeek(GuiGraphicsExtractor ctx, int x, int y) {
        List<DailyBucket> days = aggregator.lastDays(7);
        int left = x + PAD, right = x + W - PAD;
        // 수입·지출을 같이 보여준다(2026-07-28 요청) — 가운데 축 기준 오른쪽=수입, 왼쪽=지출.
        // 막대 기준은 순익이 아니라 수입/지출 각각의 최대값이라 규모 비교가 된다.
        long maxAbs = 1, totIn = 0, totOut = 0;
        for (DailyBucket d : days) {
            maxAbs = Math.max(maxAbs, Math.max(d.income, d.expense));
            totIn += d.income;
            totOut += d.expense;
        }

        int cx = left + 120;
        int barMax = 70;
        for (DailyBucket d : days) {
            long net = d.netPnl();
            ctx.text(font, d.date.format(DAY_FMT), left, y + 2, GuiTex.LABEL, false);
            ctx.fill(cx, y + 1, cx + 1, y + 11, GuiTex.RULE);
            if (d.income > 0) {
                int bw = Math.max((int) (barMax * d.income / maxAbs), 1);
                ctx.fill(cx + 1, y + 2, cx + 1 + bw, y + 6, GuiTex.GREEN);   // 위: 수입
            }
            if (d.expense > 0) {
                int bw = Math.max((int) (barMax * d.expense / maxAbs), 1);
                ctx.fill(cx - bw, y + 6, cx, y + 10, GuiTex.RED);            // 아래: 지출
            }
            // 오른쪽: 순익(맨 끝) + 그날 지출(빨강, 바로 왼쪽) — 빨간 막대가 얼마인지 숫자로도 표시
            String v = GoldFormat.signed(net);
            int vc = net > 0 ? GuiTex.GREEN : (net < 0 ? GuiTex.RED : GuiTex.NEUTRAL);
            ctx.text(font, v, right - font.width(v), y + 2, vc, false);
            if (d.expense > 0) {
                String ex = "-" + GoldFormat.format(d.expense);
                int exX = right - font.width(v) - 8 - font.width(ex);
                ctx.text(font, ex, exX, y + 2, GuiTex.RED, false);
            }
            y += ROW_H + 1;
        }

        // 범례 — 막대 두 개가 각각 무엇인지
        ctx.fill(cx + 1, y + 3, cx + 9, y + 7, GuiTex.GREEN);
        ctx.text(font, "수입", cx + 12, y + 1, GuiTex.LABEL, false);
        ctx.fill(cx + 40, y + 3, cx + 48, y + 7, GuiTex.RED);
        ctx.text(font, "지출", cx + 51, y + 1, GuiTex.LABEL, false);
        y += ROW_H;

        y += 4;
        GuiTex.tileH(ctx, "tex_divider", left, right, y, 48, 6);
        y += 5;
        long totNet = totIn - totOut;
        ctx.text(font, "7일 합계", left, y, GuiTex.LABEL, false);
        String sum = "+" + GoldFormat.format(totIn) + " / -" + GoldFormat.format(totOut)
                + "  =  " + GoldFormat.signed(totNet);
        int sc = totNet >= 0 ? GuiTex.GREEN : GuiTex.RED;
        ctx.text(font, sum, right - font.width(sum), y, sc, false);
    }

    // ── 내역 탭 (오늘 전체 거래, 카테고리 표시, 휠 스크롤, 최신 위) ──
    /** 표시용 캐시 — 스크롤·렌더 양쪽에서 같은 그룹핑 결과를 쓰도록 한 프레임 동안 재사용. */
    private List<RecordGrouping.Grouped> groupedPending() {
        return RecordGrouping.collapseConsecutive(aggregator.recent());
    }

    private void renderPending(GuiGraphicsExtractor ctx, int x, int y) {
        List<RecordGrouping.Grouped> p = groupedPending(); // 연속 동일거래 묶음("×N건") — 도배 방지
        int left = x + PAD, right = x + W - PAD;
        if (p.isEmpty()) {
            ctx.text(font, "오늘 거래 내역이 없습니다.", left, y + 4, GuiTex.LABEL, false);
            return;
        }
        int total = p.size();
        int maxScroll = Math.max(0, total - VISIBLE_PENDING);
        pendingScroll = Math.max(0, Math.min(pendingScroll, maxScroll));
        int start = pendingScroll;
        int end = Math.min(total, start + VISIBLE_PENDING);

        int y0 = y;
        boolean scrollable = total > VISIBLE_PENDING;
        int rowRight = scrollable ? right - 10 : right;
        for (int i = start; i < end; i++) {
            RecordGrouping.Grouped r = p.get(total - 1 - i); // 최신이 위로
            boolean plus = r.kind() == TransactionRecord.Kind.INCOME || r.kind() == TransactionRecord.Kind.TRANSFER_IN;
            int color = switch (r.kind()) {
                case INCOME -> GuiTex.GREEN;
                case EXPENSE -> GuiTex.RED;
                default -> GuiTex.BLUE; // 이체
            };
            GuiTex.sprite(ctx, GuiTex.icon(r.category()), left, y, 12, 12); // 12px 카테고리 아이콘
            int textLeft = left + 15;
            String amt = (plus ? "+" : "-") + GoldFormat.format(r.amount())
                    + (r.qty() > 0 ? " (" + r.qty() + "개)" : "");
            ctx.text(font, amt, textLeft, y + 2, color, false);
            int amtW = font.width(amt);
            String cat = r.category() == null ? "" : r.category();
            String lbl = r.label() == null || r.label().isEmpty() || r.label().equals(cat) ? "" : "  " + r.label();
            String mult = r.count() > 1 ? "  ×" + r.count() : "";
            String catLine = font.plainSubstrByWidth(cat + lbl + mult, rowRight - textLeft - amtW - 8);
            ctx.text(font, catLine, rowRight - font.width(catLine), y + 2, GuiTex.LABEL, false);
            y += ROW_H;
        }

        if (scrollable) {
            int trackTop = y0, trackH = VISIBLE_PENDING * ROW_H;
            int barX = right - 8;
            GuiTex.tileV(ctx, "tex_scroll_track", barX, trackTop, trackTop + trackH, 8, 16);
            int thumbH = Math.max(16, trackH * VISIBLE_PENDING / total);
            int thumbY = trackTop + (int) ((long) (trackH - thumbH) * start / maxScroll);
            GuiTex.sprite(ctx, "tex_scroll_thumb", barX, thumbY, 8, thumbH);
            String pos = (start + 1) + "–" + end + " / " + total + "  · 휠 스크롤";
            ctx.text(font, pos, left, y + 2, GuiTex.LABEL, false);
        }
        // 잘못 들어간 기록만 지우는 방법 안내(2026-07-28)
        String tip = "Shift+클릭 = 그 줄 삭제";
        ctx.text(font, tip, right - font.width(tip), y + 2, GuiTex.LABEL, false);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double horiz, double vert) {
        if (tab == 2) {
            pendingScroll -= (int) Math.signum(vert);
            int maxScroll = Math.max(0, groupedPending().size() - VISIBLE_PENDING);
            pendingScroll = Math.max(0, Math.min(pendingScroll, maxScroll));
            return true;
        }
        return super.mouseScrolled(mx, my, horiz, vert);
    }

    // ── 관리 탭 (수동 입력 + 오늘 초기화) ──
    private void renderManage(GuiGraphicsExtractor ctx, int x, int y) {
        int left = x + PAD;
        // 반투명 placeholder 대신 박스 위에 또렷한 라벨을 따로 그림(가독성).
        ctx.text(font, "금액", left, y, GuiTex.LABEL, false);
        ctx.text(font, "설명 (선택)", x + PAD + 116, y, GuiTex.LABEL, false);
        GuiTex.sprite(ctx, "tex_input", x + PAD, y + 12, 110, 18);
        GuiTex.sprite(ctx, "tex_input", x + PAD + 116, y + 12, W - PAD * 2 - 116, 18);
        ctx.text(font, "'수동' 카테고리로 오늘 집계에 반영됩니다.", left, y + 58, GuiTex.LABEL, false);

        // 날짜 지정 초기화 — 잘못 기록된 특정 날짜만 지울 때
        ctx.text(font, "날짜  예) 2026-07-28 · 비우면 오늘", left, y + 90, GuiTex.LABEL, false);
        GuiTex.sprite(ctx, "tex_input", x + PAD, y + 102, 110, 18);
        ctx.text(font, "내역 탭에서 항목을 Shift+클릭하면 그 기록만 삭제됩니다.",
                left, y + 124, GuiTex.LABEL, false);
    }

    // ── 설정 탭 (HUD 위치 · 설정 열기) ──
    private void renderSettings(GuiGraphicsExtractor ctx, int x, int y) {
        int left = x + PAD;
        ctx.text(font, "HUD 위치·표시 및 상세 설정을 여기서 엽니다.", left, y, GuiTex.LABEL, false);
        // 버튼(y+14, y+40) 은 위젯이 렌더
        String pct = "HUD 투명도  " + Math.round(config.hudOpacity * 100) + "%";
        int tw = font.width(pct);
        ctx.text(font, pct, x + (W - tw) / 2, y + 71, GuiTex.TEXT, false);
        // -/+ 버튼(y+66) 은 위젯이 렌더
    }
}
