package kr.aka.nakin.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * 실제 게임 로그(*.log.gz)를 파서에 그대로 흘려 보는 점검 도구 — 평소 빌드에선 건너뛴다.
 *
 * <pre>./gradlew :fabric:test --tests '*LogReplayTest' -Dnakin.logs=&lt;로그폴더&gt; -Dnakin.replayOut=&lt;출력.md&gt; -Dnakin.self=&lt;내닉&gt;</pre>
 *
 * 출력에는 다른 유저 닉네임이 그대로 들어가므로 공개 저장소 밖(local/)에 쓴다.
 */
@EnabledIfSystemProperty(named = "nakin.logs", matches = ".+")
class LogReplayTest {

    private static final Pattern LINE = Pattern.compile(
            "^\\[(\\d\\d:\\d\\d:\\d\\d)\\] \\[[^\\]]+\\] \\[[^\\]]+\\]: (?:\\[[^\\]]*\\] )?\\[CHAT\\] (.*)$");

    @Test
    void replay() throws Exception {
        Path dir = Path.of(System.getProperty("nakin.logs"));
        Path outFile = Path.of(System.getProperty("nakin.replayOut", "replay.md"));
        String self = System.getProperty("nakin.self", "");
        CurrencyParser parser = new CurrencyParser(() -> self.isBlank() ? null : self);

        Map<String, long[]> byCat = new TreeMap<>();       // 카테고리|방향 → {건수, 합계}
        Map<String, Integer> missed = new TreeMap<>();      // 냥이 있는데 신호 0개인 시스템 메시지 유형
        Map<String, String> missedEx = new TreeMap<>();
        List<String> samples = new ArrayList<>();
        int lines = 0;

        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().endsWith(".log.gz")).sorted().toList();
        }
        long clock = 0;
        for (Path f : files) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(Files.newInputStream(f)), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) {
                    Matcher m = LINE.matcher(l);
                    if (!m.matches()) continue;
                    lines++;
                    clock += 100; // 로그 순서대로 100ms 간격 — 여러 줄 블록 판정용
                    String msg = m.group(2);
                    List<TradeSignal> sigs = parser.parse(msg, clock);
                    for (TradeSignal s : sigs) {
                        String key = s.category + " | " + s.flow + (s.amountFromDelta ? " (ΔG)" : "")
                                + (s.requireDelta ? " (내잔고확인)" : "");
                        long[] v = byCat.computeIfAbsent(key, k -> new long[2]);
                        v[0]++;
                        v[1] += s.amount;
                        if (samples.size() < 400) samples.add(f.getFileName() + " " + m.group(1) + "  " + s + "   ← " + msg);
                    }
                    if (sigs.isEmpty() && msg.contains("냥") && isSystem(msg)) {
                        String t = CurrencyParser.normalize(msg).replaceAll("[0-9][0-9,.]*", "N");
                        missed.merge(t, 1, Integer::sum);
                        missedEx.putIfAbsent(t, msg);
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# 로그 재생 결과 (로컬 전용 — 타 유저 닉 포함)\n\n");
        sb.append("- 로그 ").append(files.size()).append("개, 채팅 ").append(lines).append("줄\n\n");
        sb.append("## 카테고리별\n\n| 카테고리 | 건수 | 합계(냥) |\n|---|--:|--:|\n");
        byCat.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v[0]).append(" | ")
                .append(String.format("%,d", v[1])).append(" |\n"));
        sb.append("\n## 놓친 후보 — '냥'이 있는데 신호가 없는 시스템 메시지\n\n");
        missed.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> sb.append("- [").append(e.getValue()).append("] ").append(e.getKey())
                        .append("\n    ").append(missedEx.get(e.getKey())).append("\n"));
        sb.append("\n## 신호 표본(앞 400개)\n\n");
        for (String s : samples) sb.append("    ").append(s).append("\n");
        Files.createDirectories(outFile.toAbsolutePath().getParent());
        Files.writeString(outFile, sb.toString(), StandardCharsets.UTF_8);
        System.out.println("replay → " + outFile.toAbsolutePath());
    }

    /** 유저 대화가 아닌 줄(파서의 대화 필터와 같은 기준을 느슨하게 재현). */
    private static boolean isSystem(String msg) {
        return !(msg.contains("[전챗]") || msg.matches("^(?:마을원|마을장|부마을장) \\S+ : .*")
                || msg.startsWith("[지역채팅]") || msg.matches("^\\[[^\\]]{1,12}\\] <.*")
                || msg.contains(" → ") || msg.contains("질문") || msg.contains("답변") || msg.contains("[가이드]"));
    }
}
