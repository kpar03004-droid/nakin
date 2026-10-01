package kr.aka.nakin.ui;

import kr.aka.nakin.aggregate.DailyAggregator;
import kr.aka.nakin.config.DtConfig;
import kr.aka.nakin.core.TransactionRecord;
import kr.aka.nakin.util.GoldFormat;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/**
 * /낙인 테스트 — <b>싱글플레이 전용</b> 동작 확인 창.
 *
 * <p>버튼을 누르면 동글랜드 서버가 보내는 것과 같은 문구를 {@code /tellraw} 로 띄우고, 사이드바에 만든
 * 가짜 "냥" 잔고를 {@code /scoreboard} 로 바꾼다. 모드는 실서버와 똑같은 경로(채팅 수신 → 파서 → 잔고 변동
 * 결합)로 이걸 받으므로 HUD·정산 창이 실제처럼 반응한다. 문구는 전부 사용자 로그 실측.
 *
 * <p>안전장치:
 * <ul>
 *   <li>싱글플레이가 아니면 어떤 명령도 보내지 않는다 — 동글랜드 서버로 tellraw·scoreboard 가 나가면 안 된다.</li>
 *   <li>싱글플레이에서 생긴 기록은 월드를 나갈 때 자동 삭제된다(DtLedgerClient).</li>
 *   <li>명령은 채팅이 아니라 내장 서버에 직접 실행한다 — 치트가 꺼진 월드에서도 된다.</li>
 * </ul>
 */
public final class DtTestScreen extends Screen {

    private static final int W = 360;
    private static final int PAD = 12;
    private static final int ROW = 21;
    private static final int TOP = 14;
    private static final String OBJ = "nakin_test";

    private static long balance = 2_000_000;
    private static String status = "① 먼저 '가짜 잔고 패널 만들기'를 누르세요.";

    private final DtConfig config;
    private final DailyAggregator aggregator;
    private final Consumer<TransactionRecord> sink;
    private final LedgerHud hud;

    private record Scenario(String button, long delta, String... messages) {}

    public DtTestScreen(DtConfig config, DailyAggregator aggregator, Consumer<TransactionRecord> sink, LedgerHud hud) {
        super(Component.literal("낙인 테스트"));
        this.config = config;
        this.aggregator = aggregator;
        this.sink = sink;
        this.hud = hud;
    }

    private static String me() {
        var u = Minecraft.getInstance().getUser();
        return u == null ? "_me" : u.getName();
    }

    private static List<Scenario> scenarios() {
        String line = "+---------------------------------------------------+";
        return List.of(
                new Scenario("무역상점 판매 +132", 132,
                        "[동글상점] 보석ㅣ영원 x1 판매에 성공했습니다. 획득 금액: 132 냥, 8.97 대장포인트!"),
                new Scenario("거래소 광장 판매 +69,750", 69_750,
                        "거래소 • PlayerA 님이 주괴ㅣ달빛 x3 을 69,750 냥에 구매했습니다. (수수료: 5,250냥)"),
                new Scenario("거래소 수령 +23,250", 23_250,
                        "오프라인 동안 1개의 아이템이 총 25,000냥 (수수료: 1,750냥)에 판매되었습니다!",
                        "거래소 • 23,250냥을 수령했습니다.",
                        "거래소 • 판매된 대금을 수령했습니다."),
                new Scenario("거래소 구매 −20,000", -20_000,
                        "거래소 • 원석ㅣ아메트린 x10 을 20,000에 구매했습니다."),
                new Scenario("송금 보냄 −150,000", -150_000,
                        "PlayerA에게 150,000냥을 송금했습니다. ",
                        " └ 원금 150,000냥 · 수수료 3% 4,500냥 · 상대 실수령 145,500냥"),
                new Scenario("송금 받음 +310,400", 310_400,
                        " └ 원금 320,000냥 · 수수료 3% 9,600냥 · 실수령 310,400냥",
                        "PlayerA에게서 310,400냥을 받았습니다. (수수료 3% 차감)"),
                new Scenario("직거래 받음 +145,500", 145_500,
                        " PlayerA님과의 거래가 완료되었습니다! ※ 냥 거래가 포함된 경우 수수료 3%가 차감됩니다",
                        "+ 145,500냥",
                        "- Glowstone Dust 1개"),
                new Scenario("플리마켓 판매 +7,952", 7_952,
                        line, "l 성공적으로 판매했습니다:", "l 56개의 보석ㅣ영원 (총 7952.0냥)", line),
                new Scenario("판매 영수증(룬) +11,939", 11_939,
                        "[판매 완료]", "  [룬ㅣ성급] x32 (만료) 373.1냥x32개 : 11939.2냥", "  합계: 11939.2냥"),
                new Scenario("모루 인챈트 −12,500 (15초 뒤)", -12_500,
                        "[인챈트] 인챈트에 성공했습니다! 확률: 100.0%"),
                new Scenario("카드팩 뒤집기 −30,000", -30_000,
                        "[무역 카드팩] 무역 해금서 x2 뽑았습니다!"),
                new Scenario("유물 감정 −1,000", -1_000,
                        "[감정] 유물 감정이 완료되었습니다."),
                new Scenario("내 마을 금고 입금 −100,000", -100_000,
                        "금고 | " + me() + "님이 마을 금고에 100,000냥을(를) 입금했습니다."),
                new Scenario("무시: 남의 금고 입금", 0,
                        "금고 | PlayerA님이 마을 금고에 100,000냥을(를) 입금했습니다."),
                new Scenario("무시: 마을채팅·포인트", 0,
                        "마을원 냥이A : 1000냥을 획득했습니다.",
                        "[동글상점] 미확인ㅣ유물 x1 구매에 성공했습니다. 비용: 200 숙련 포인트!"));
    }

    private int panelX() { return (this.width - W) / 2; }

    @Override
    protected void init() {
        int x = panelX() + PAD;
        int colW = (W - PAD * 2 - 6) / 2;
        int y = TOP + 50;

        addRenderableWidget(new TexButton(x, y, colW, 18, Component.literal("가짜 잔고 패널 만들기"), this::setup));
        addRenderableWidget(new TexButton(x + colW + 6, y, colW, 18, Component.literal("가짜 패널 지우기"), () -> {
            if (!guard()) return;
            cmd("scoreboard objectives remove " + OBJ);
            status = "가짜 패널을 지웠습니다.";
        }));
        y += ROW + 4;

        List<Scenario> list = scenarios();
        for (int i = 0; i < list.size(); i++) {
            Scenario s = list.get(i);
            int bx = x + (i % 2) * (colW + 6);
            int by = y + (i / 2) * ROW;
            addRenderableWidget(new TexButton(bx, by, colW, 18, Component.literal(s.button()), () -> run(s)));
        }
        int n = list.size() + 1; // 강화 버튼 포함
        int bx = x + (list.size() % 2) * (colW + 6);
        int by = y + (list.size() / 2) * ROW;
        addRenderableWidget(new TexButton(bx, by, colW, 18, Component.literal("생활장비 강화 −120,000"), this::enhance));
        y += ((n + 1) / 2) * ROW + 4;

        int thirdW = (W - PAD * 2 - 12) / 3;
        addRenderableWidget(new TexButton(x, y, thirdW, 18, Component.literal("정산 창"), () ->
                this.minecraft.setScreen(new DtStatScreen(config, aggregator, sink, hud))));
        addRenderableWidget(new TexButton(x + thirdW + 6, y, thirdW, 18, Component.literal("진단"), () -> {
            if (this.minecraft.player != null) this.minecraft.player.connection.sendCommand("낙인 진단");
            this.onClose();
        }));
        addRenderableWidget(new TexButton(x + (thirdW + 6) * 2, y, thirdW, 18, Component.literal("닫기"), this::onClose));
    }

    private int contentBottom() {
        int n = scenarios().size() + 1;
        return TOP + 50 + ROW + 4 + ((n + 1) / 2) * ROW + 4 + 18 + 30;
    }

    // ── 동작 ──

    /** 싱글플레이가 아니면 막는다 — 실서버로 명령이 나가면 안 된다. */
    private boolean guard() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.hasSingleplayerServer()) {
            status = "§c싱글플레이 월드에서만 쓸 수 있습니다.";
            return false;
        }
        return true;
    }

    /**
     * 싱글플레이 내장 서버에 서버 권한으로 직접 실행한다 — 채팅으로 명령을 보내지 않으므로
     * 치트가 꺼진 월드에서도 되고, 싱글이 아니면 내장 서버가 없어 아무것도 실행되지 않는다.
     */
    private static void cmd(String c) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        server.execute(() -> server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(), c));
    }

    private static void say(String msg) {
        cmd("tellraw @a \"" + msg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
    }

    /**
     * 동글랜드 패널처럼 <b>숫자 칸은 비우고 줄 이름에 금액을 넣는다</b>("냥 2,000,000").
     * 점수 칸을 쓰면 Feather 등의 "스코어보드 숫자 숨기기" 옵션에 가려 안 보인다(2026-09-28 사용자 화면).
     * 점수 자체는 정렬용으로만 둔다.
     */
    private static void line(String name, long v) {
        cmd("scoreboard players set " + name + " " + OBJ + " " + Math.min(v, Integer.MAX_VALUE));
        cmd("scoreboard players display name " + name + " " + OBJ + " \"" + name + " " + GoldFormat.format(v) + "\"");
    }

    private static void setBalance(long v) {
        balance = v;
        line("냥", v);
    }

    private void setup() {
        if (!guard()) return;
        cmd("scoreboard objectives add " + OBJ + " dummy \"동글\"");
        cmd("scoreboard objectives modify " + OBJ + " numberformat blank");
        cmd("scoreboard objectives setdisplay sidebar " + OBJ);
        line("동글머니", 10_000);
        line("모험포인트", 4_910);
        setBalance(2_000_000);
        status = "가짜 패널 준비 완료 — 이제 거래 버튼을 눌러 보세요.";
    }

    private void run(Scenario s) {
        if (!guard()) return;
        for (String m : s.messages()) say(m);
        if (s.delta() != 0) {
            long target = balance + s.delta();
            later(5, () -> setBalance(target)); // 실서버처럼 문구 직후 잔고 갱신
            balance = target;
        }
        status = s.button() + " — 보냄" + (s.delta() == 0 ? " (기록되면 안 됨)" : "");
    }

    /** 대장간 대용: 강화 비용 설명을 단 종이를 받고 인벤토리(컨테이너 창)를 연 상태에서 잔고를 줄인다. */
    private void enhance() {
        if (!guard()) return;
        cmd("give @a paper[item_name=\"클릭 시 강화가 진행됩니다.\","
                + "lore=[\"[ 필요한 재료 ]\",\" - 연금술사강화석 43개 [✓]\",\" - 120000원 [✓]\"]] 1");
        long target = balance - 120_000;
        balance = target;
        Minecraft mc = Minecraft.getInstance();
        later(5, () -> { if (mc.player != null) mc.setScreen(new InventoryScreen(mc.player)); });
        later(30, () -> setBalance(target));
        status = "강화 — 인벤토리를 연 채 잔고가 줄어듭니다(창 비용 읽기 확인)";
    }

    // ── 지연 실행(틱 단위) ──

    private static final ArrayDeque<Object[]> QUEUE = new ArrayDeque<>();
    private static boolean tickerRegistered;

    private static void later(int ticks, Runnable r) {
        QUEUE.add(new Object[]{ticks, r});
    }

    /** DtLedgerClient 에서 한 번 부른다. */
    public static void registerTicker() {
        if (tickerRegistered) return;
        tickerRegistered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Iterator<Object[]> it = QUEUE.iterator();
            java.util.List<Runnable> due = new java.util.ArrayList<>();
            while (it.hasNext()) {
                Object[] e = it.next();
                int left = (int) e[0] - 1;
                if (left <= 0) {
                    due.add((Runnable) e[1]);
                    it.remove();
                } else {
                    e[0] = left;
                }
            }
            due.forEach(Runnable::run);
        });
    }

    // ── 그리기 ──

    @Override
    public void extractBackground(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, this.width, this.height, 0x90000000);
        int x = panelX();
        GuiTex.sprite(ctx, "tex_page", x, TOP, W, contentBottom() - TOP);
        ctx.text(font, "낙인 테스트 · 싱글 전용", x + PAD, TOP + 22, GuiTex.TITLE, false);
        String bal = "가짜 잔고 " + GoldFormat.format(balance) + " 냥";
        ctx.text(font, bal, x + W - PAD - font.width(bal), TOP + 22, GuiTex.LABEL, false);
        ctx.text(font, "치트 없어도 됨 · 이 월드에서 생긴 기록은 나갈 때 자동 삭제",
                x + PAD, TOP + 35, GuiTex.LABEL, false);
        String st = font.plainSubstrByWidth(status, W - PAD * 2);
        ctx.text(font, st, x + PAD, contentBottom() - 24, GuiTex.TEXT, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false; // 멈추면 싱글 서버가 명령을 처리하지 않는다
    }
}
