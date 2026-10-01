package kr.aka.nakin.ui;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /낙인 명령어가 실제로 파싱·실행되는지 확인.
 *
 * <p>브리가디어는 리터럴을 <b>문자열 그대로</b> 맵에서 찾고 부분문자열로 비교하므로 한글도 되지만,
 * "될 것 같다"로 넘기면 인게임에서야 알게 된다. 명령어 이름을 바꾸거나 별칭을 손댈 때
 * 이 테스트가 먼저 깨지도록 실제 디스패처를 돌려 둔다.
 *
 * <p>여기서 검증하지 <b>못하는</b> 것: 마인크래프트 채팅창의 한글 IME 입력.
 * 그게 안 되는 환경이 있어서 영문 별칭(dtstat)을 남긴다.
 */
class KoreanLiteralTest {

    /** DtStatCommand.tree() 와 같은 모양의 축소판 — 한글 기본 + 영문 별칭. */
    private static CommandDispatcher<Object> build(List<String> log) {
        CommandDispatcher<Object> d = new CommandDispatcher<>();
        for (String root : new String[]{"낙인", "dtstat"}) {
            LiteralArgumentBuilder<Object> r = LiteralArgumentBuilder.<Object>literal(root)
                    .executes(c -> { log.add("창"); return 1; });
            for (String n : new String[]{"오늘", "today"}) {
                r.then(LiteralArgumentBuilder.<Object>literal(n)
                        .executes(c -> { log.add("오늘"); return 1; }));
            }
            for (String n : new String[]{"내보내기", "export"}) {
                r.then(LiteralArgumentBuilder.<Object>literal(n)
                        .executes(c -> { log.add("내보내기:이번달"); return 1; })
                        .then(RequiredArgumentBuilder.<Object, String>argument("범위", StringArgumentType.string())
                                .executes(c -> {
                                    log.add("내보내기:" + StringArgumentType.getString(c, "범위"));
                                    return 1;
                                })));
            }
            for (String n : new String[]{"추가", "add"}) {
                LiteralArgumentBuilder<Object> add = LiteralArgumentBuilder.literal(n);
                for (String k : new String[]{"지출", "expense"}) {
                    add.then(LiteralArgumentBuilder.<Object>literal(k)
                            .then(RequiredArgumentBuilder.<Object, String>argument("args", StringArgumentType.greedyString())
                                    .executes(c -> {
                                        log.add("지출:" + StringArgumentType.getString(c, "args"));
                                        return 1;
                                    })));
                }
                r.then(add);
            }
            d.register(r);
        }
        return d;
    }

    private static List<String> run(String... commands) throws Exception {
        List<String> log = new ArrayList<>();
        CommandDispatcher<Object> d = build(log);
        for (String c : commands) d.execute(c, new Object());
        return log;
    }

    @Test
    void 한글_명령어가_실행된다() throws Exception {
        assertEquals(List.of("창", "오늘"), run("낙인", "낙인 오늘"));
    }

    @Test
    void 영문_별칭도_그대로_동작한다() throws Exception {
        // 기존 사용자의 손버릇·공지·설명서가 살아 있어야 한다
        assertEquals(List.of("창", "오늘", "오늘"),
                run("dtstat", "dtstat today", "낙인 today"));
    }

    @Test
    void 한글_하위명령에_인자를_붙일_수_있다() throws Exception {
        assertEquals(List.of("내보내기:이번달", "내보내기:week", "내보내기:2026-06"),
                run("낙인 내보내기", "낙인 내보내기 week", "낙인 내보내기 2026-06"));
    }

    @Test
    void 미확인_안내가_알려주는_명령이_그대로_통한다() throws Exception {
        // TransactionResolver 가 채팅으로 "/낙인 추가 지출 <금액> <라벨>" 을 안내한다.
        // 안내한 명령이 실제로 안 먹으면 안내가 거짓말이 된다.
        assertEquals(List.of("지출:5000 재배학개론 스킬"),
                run("낙인 추가 지출 5000 재배학개론 스킬"));
    }

    @Test
    void 한글_자동완성이_뜬다() throws Exception {
        CommandDispatcher<Object> d = build(new ArrayList<>());
        List<String> sug = d.getCompletionSuggestions(d.parse("낙인 ", new Object()))
                .join().getList().stream().map(s -> s.getText()).toList();
        assertTrue(sug.contains("오늘"), "한글 하위명령이 후보에 떠야 한다: " + sug);
        assertTrue(sug.contains("내보내기"), "후보: " + sug);
    }
}
