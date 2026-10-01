package kr.aka.nakin.gametest;

import kr.aka.nakin.DtLedgerClient;
import kr.aka.nakin.aggregate.DailyBucket;
import kr.aka.nakin.ui.DtHudEditScreen;
import kr.aka.nakin.ui.DtStatScreen;
import kr.aka.nakin.ui.TabButton;
import kr.aka.nakin.ui.TexButton;
import kr.aka.nakin.ui.DtTestScreen;
import kr.aka.nakin.watcher.BalanceWatcher;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * 싱글플레이에서 동글랜드 화면을 흉내 내 낙인을 끝까지 돌려 본다(서버 접속 없음).
 *
 * <ul>
 *   <li>사이드바에 "냥 2273762 / 동글머니 10000" — 동글랜드 우하단 패널 대용</li>
 *   <li>/tellraw 로 실제 서버 문구를 띄우고 사이드바 잔고를 같이 바꿔 ΔG 교차검증까지</li>
 *   <li>HUD·정산창 5탭·HUD 편집 화면 스크린샷(run/screenshots)</li>
 * </ul>
 */
public class NakinClientGameTest implements FabricClientGameTest {

    private long balance = 2_273_762;

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
            sp.getClientLevel().waitForChunksRender();
            TestServerContext server = sp.getServer();

            server.runCommand("scoreboard objectives add bal dummy \"동글\"");
            server.runCommand("scoreboard objectives setdisplay sidebar bal");
            server.runCommand("scoreboard players set 동글머니 bal 10000");
            server.runCommand("scoreboard players set 모험포인트 bal 4910");
            setBalance(server, balance);
            ctx.waitTicks(80); // 접속 직후 잔고 안정화(SETTLE 40틱) + 확정(2틱)

            String read = ctx.computeOnClient(mc -> BalanceWatcher.lastReadInfo());
            check(read != null && read.contains("2,273,762"), "잔고 줄 인식: " + read);

            // ── 메시지 금액 + ΔG 교차검증 ──
            trade(ctx, server, "[동글상점] 보석ㅣ영원 x1 판매에 성공했습니다. 획득 금액: 132 냥, 8.97 대장포인트!", +132);
            trade(ctx, server, "거래소 • 23,250냥을 수령했습니다.", +23_250);
            trade(ctx, server, "거래소 • 원석ㅣ아메트린 x10 을 20,000에 구매했습니다.", -20_000);
            trade(ctx, server, "PlayerA에게서 310,400냥을 받았습니다. (수수료 3% 차감)", +310_400);
            // 광장에서 실시간으로 팔린 거래소 물건 — 체결 알림 한 줄 + 잔고 증가
            trade(ctx, server, "거래소 • PlayerA 님이 주괴ㅣ달빛 x3 을 69,750 냥에 구매했습니다. (수수료: 5,250냥)", +69_750);
            // 같은 알림이 한 번 더(광장 진입 때 겹쳐 오는 경우) — 잔고가 안 늘었으니 버려져야 한다
            say(server, "거래소 • PlayerA 님이 주괴ㅣ달빛 x3 을 69,750 냥에 구매했습니다. (수수료: 5,250냥)");
            // ── 무시돼야 하는 것 ──
            say(server, "마을원 냥이A : 1000냥을 획득했습니다.");
            say(server, "[동글상점] 미확인ㅣ유물 x1 구매에 성공했습니다. 비용: 200 숙련 포인트!");
            say(server, "금고 | PlayerA님이 마을 금고에 100,000냥을(를) 입금했습니다.");
            // ── 금액 없는 지출: 모루 인챈트 → ΔG 로 금액 ──
            say(server, "[인챈트] 인챈트에 성공했습니다! 확률: 100.0%");
            ctx.waitTicks(5);
            setBalance(server, balance -= 12_500);
            ctx.waitTicks(20 * 17); // 금액 없는 신호 대기창 15초

            // ── 생활장비 강화: 채팅 없이 창 설명의 비용 줄 + 잔고 감소로 ──
            server.runCommand("give @a paper[item_name=\"클릭 시 강화가 진행됩니다.\","
                    + "lore=[\"[ 필요한 재료 ]\",\" - 연금술사강화석 43개 [✓]\",\" - 120000원 [✓]\"]] 1");
            ctx.waitTicks(5);
            ctx.setScreen(() -> new InventoryScreen(net.minecraft.client.Minecraft.getInstance().player));
            ctx.waitTicks(10);
            setBalance(server, balance -= 120_000);
            ctx.waitTicks(60);
            ctx.setScreen(() -> null);
            ctx.waitTicks(5);

            DailyBucket today = ctx.computeOnClient(mc -> DtLedgerClient.aggregatorForTests().today());
            long income = 132 + 23_250 + 310_400 + 69_750;
            long expense = 20_000 + 12_500 + 120_000;
            check(today.income == income, "수입 합계 " + today.income + " (기대 " + income + ")");
            check(today.expense == expense, "지출 합계 " + today.expense + " (기대 " + expense + ")");
            check(today.incomeByCategory.getOrDefault("대장장이", 0L) == 132, "대장장이 132: " + today.incomeByCategory);
            check(today.expenseByCategory.getOrDefault("인챈트", 0L) == 12_500, "인챈트 12500: " + today.expenseByCategory);
            check(today.expenseByCategory.getOrDefault("강화", 0L) == 120_000, "강화 120000(창 비용): " + today.expenseByCategory);
            check(today.count == 7, "거래 7건(중복 체결 알림 제외): " + today.count);

            ctx.takeScreenshot("nakin-01-hud");

            // ── 정산 창 5탭 ──
            ctx.runOnClient(mc -> mc.player.connection.sendCommand("낙인"));
            ctx.waitForScreen(DtStatScreen.class);
            ctx.takeScreenshot("nakin-02-stat-today");
            for (String[] tab : new String[][]{{"주간", "03-week"}, {"내역", "04-records"}, {"관리", "05-manage"}, {"설정", "06-settings"}}) {
                pressTab(ctx, tab[0]);
                ctx.waitTicks(3);
                ctx.takeScreenshot("nakin-" + tab[1]);
            }
            ctx.setScreen(() -> null);
            ctx.waitTicks(3);

            // ── /낙인 테스트 창(싱글 전용) — 버튼으로 가짜 패널을 만들고 거래를 흉내 낸다 ──
            ctx.runOnClient(mc -> mc.player.connection.sendCommand("낙인 테스트"));
            ctx.waitForScreen(DtTestScreen.class);
            ctx.waitTicks(3);
            ctx.takeScreenshot("nakin-08-test-screen");
            pressButton(ctx, "가짜 잔고 패널 만들기");
            ctx.waitTicks(80);
            int before = ctx.computeOnClient(mc -> DtLedgerClient.aggregatorForTests().today().count);
            pressButton(ctx, "무역상점 판매 +132");
            ctx.waitTicks(60);
            int after = ctx.computeOnClient(mc -> DtLedgerClient.aggregatorForTests().today().count);
            check(after == before + 1, "테스트 창 버튼으로 거래 1건 추가: " + before + " → " + after);
            ctx.setScreen(() -> null);
            ctx.waitTicks(3);

            // ── HUD 위치 편집 ──
            ctx.runOnClient(mc -> mc.player.connection.sendCommand("낙인 위치"));
            ctx.waitForScreen(DtHudEditScreen.class);
            ctx.takeScreenshot("nakin-07-hud-edit");
            ctx.setScreen(() -> null);
            ctx.waitTicks(3);
        }
        // 월드를 나오면 싱글에서 생긴 기록은 지워져야 한다(진짜 가계부 보호)
        ctx.waitTicks(10);
        int left = ctx.computeOnClient(mc -> DtLedgerClient.aggregatorForTests().today().count);
        check(left == 0, "싱글 기록 자동 삭제: 남은 " + left + "건");
    }

    private static void pressButton(ClientGameTestContext ctx, String name) {
        boolean pressed = ctx.computeOnClient(mc -> {
            for (var child : mc.screen.children()) {
                if (child instanceof TexButton tb && tb.getMessage().getString().equals(name)) {
                    tb.onPress(null);
                    return true;
                }
            }
            return false;
        });
        check(pressed, "버튼 누름: " + name);
    }

    /** 커스텀 탭 버튼은 clickScreenButton 이 못 찾는다 → 화면의 위젯 중 글자가 같은 것을 직접 누른다. */
    private static void pressTab(ClientGameTestContext ctx, String name) {
        boolean pressed = ctx.computeOnClient(mc -> {
            for (var child : mc.screen.children()) {
                if (child instanceof TabButton tb && tb.getMessage().getString().equals(name)) {
                    tb.onPress(null);
                    return true;
                }
            }
            return false;
        });
        check(pressed, "탭 누름: " + name);
    }

    private void trade(ClientGameTestContext ctx, TestServerContext server, String msg, long delta) {
        say(server, msg);
        ctx.waitTicks(3);
        setBalance(server, balance += delta);
        ctx.waitTicks(40); // 결합 시간창 1.5초 + 여유
    }

    private static void say(TestServerContext server, String msg) {
        server.runCommand("tellraw @a \"" + msg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
    }

    private static void setBalance(TestServerContext server, long v) {
        server.runCommand("scoreboard players set 냥 bal " + v);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("[낙인 게임테스트] 실패 — " + what);
        System.out.println("[낙인 게임테스트] OK — " + what);
    }
}
