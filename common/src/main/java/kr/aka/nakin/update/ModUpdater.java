package kr.aka.nakin.update;

import com.google.gson.*;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.concurrent.Flow;

/** 로더와 무관한 동의 기반 업데이트 코어. */
public final class ModUpdater {
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private static final long COOLDOWN_NANOS = TimeUnit.HOURS.toNanos(1);
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final Set<String> HOSTS = Set.of("api.github.com", "github.com",
            "release-assets.githubusercontent.com", "objects.githubusercontent.com");

    public record Offer(String version, String fileName, long size, String sha256, String url) {}

    private final Properties identity = new Properties();
    private final Path mods;
    private final Path currentJar;
    private final Consumer<String> message;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mod-update"); t.setDaemon(true); return t;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Fetcher fetcher;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile Offer offer;
    private volatile String version;
    private volatile String status = "확인 전";
    private volatile boolean notified;
    private volatile boolean queued;
    private volatile long lastCheckNanos = Long.MIN_VALUE;

    public ModUpdater(String modId, Path mods, Path currentJar, Consumer<String> message) throws IOException {
        this(loadIdentity(modId), modId, mods, currentJar, message, null);
    }

    ModUpdater(Properties suppliedIdentity, String modId, Path mods, Path currentJar,
               Consumer<String> message, Fetcher fetcher) throws IOException {
        this.mods = mods.toAbsolutePath().normalize();
        this.currentJar = currentJar.toAbsolutePath().normalize();
        this.message = message == null ? ignored -> {} : message;
        identity.putAll(suppliedIdentity);
        this.fetcher = fetcher == null ? this::fetchHttp : fetcher;
        if (!Objects.equals(modId, identity.getProperty("modId"))) throw new IOException("Update identity mismatch");
        requireIdentity("loader"); requireIdentity("minecraft"); requireIdentity("version");
        requireIdentity("repository"); requireIdentity("prefix");
        if (!VERSION.matcher(identity.getProperty("version")).matches()) throw new IOException("Invalid current version");
        if (!repository().matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) throw new IOException("Invalid repository");
        if (!identity.getProperty("loader").matches("fabric|neoforge")) throw new IOException("Invalid loader");
    }

    public Offer offer() { return offer; }
    public String repository() { return identity.getProperty("repository", ""); }
    public String availableVersion() { return version; }
    public String status() { return status; }
    public boolean isBusy() { return busy.get(); }
    public boolean isQueued() { return queued; }

    public boolean takeNotification() {
        if (offer == null || notified || queued) return false;
        notified = true; return true;
    }

    public void reportAndCheck() { message.accept(status); check(); }

    public static boolean newer(String remote, String current) {
        if (!VERSION.matcher(remote).matches() || !VERSION.matcher(current).matches()) return false;
        String[] a = remote.split("\\."), b = current.split("\\.");
        for (int i = 0; i < 3; i++) {
            int cmp = new java.math.BigInteger(a[i]).compareTo(new java.math.BigInteger(b[i]));
            if (cmp != 0) return cmp > 0;
        }
        return false;
    }

    public void check() {
        long now = System.nanoTime();
        if (lastCheckNanos != Long.MIN_VALUE && now - lastCheckNanos < COOLDOWN_NANOS) return;
        if (!busy.compareAndSet(false, true)) return;
        lastCheckNanos = now;
        clearOffer();
        status = "업데이트 확인 중";
        worker.execute(() -> {
            try {
                URI uri = URI.create("https://api.github.com/repos/" + repository() + "/releases/latest");
                JsonObject release = JsonParser.parseString(new String(fetch(uri, 2 * 1024 * 1024), StandardCharsets.UTF_8)).getAsJsonObject();
                if (release.get("draft").getAsBoolean() || release.get("prerelease").getAsBoolean()) throw new IOException("Not a stable release");
                String remote = release.get("tag_name").getAsString().replaceFirst("^v", "");
                if (!newer(remote, identity.getProperty("version"))) {
                    status = "현재 최신 버전입니다."; return;
                }
                String expected = identity.getProperty("prefix") + remote + ".jar";
                JsonObject match = null;
                for (JsonElement element : release.getAsJsonArray("assets")) {
                    JsonObject asset = element.getAsJsonObject();
                    if (expected.equals(asset.get("name").getAsString())) {
                        if (match != null) throw new IOException("Duplicate release asset");
                        match = asset;
                    }
                }
                if (match == null) throw new IOException("이 로더용 배포 파일이 없습니다.");
                String digest = match.has("digest") && !match.get("digest").isJsonNull()
                        ? match.get("digest").getAsString() : "";
                if (!digest.matches("sha256:[a-fA-F0-9]{64}")) throw new IOException("GitHub SHA-256 검증값이 없습니다.");
                URI download = URI.create(match.get("browser_download_url").getAsString());
                validateDownloadUrl(download);
                long size = match.get("size").getAsLong();
                if (size <= 0 || size > MAX_BYTES) throw new IOException("Invalid asset size");
                offer = new Offer(remote, expected, size, digest.substring(7).toLowerCase(Locale.ROOT), download.toString());
                version = remote; notified = false; status = "새 버전 v" + remote;
            } catch (Exception e) {
                clearOffer(); status = "업데이트 확인 실패: " + safeMessage(e);
            } finally { busy.set(false); }
        });
    }

    public void download(Offer approved) {
        Offer current = offer;
        if (queued) { message.accept("종료 후 업데이트가 예약되어 있습니다."); return; }
        if (approved == null || current == null || !approved.equals(current) || !newer(current.version(), identity.getProperty("version"))) {
            message.accept("업데이트 정보가 바뀌어 다운로드를 취소했습니다. 다시 확인해 주세요."); return;
        }
        if (!busy.compareAndSet(false, true)) { message.accept("업데이트 작업이 진행 중입니다."); return; }
        status = "v" + approved.version() + " 다운로드 준비 중";
        worker.execute(() -> {
            Process helper = null; Path job = null;
            try {
                if (!approved.equals(offer)) throw new IOException("Offer changed");
                Path realMods = mods.toRealPath();
                Path realCurrent = currentJar.toRealPath();
                if (!realCurrent.getParent().equals(realMods) || Files.isSymbolicLink(currentJar)
                        || !Files.isRegularFile(currentJar)) throw new IOException("mods 폴더에 직접 설치된 단독 JAR만 자동 교체할 수 있습니다.");
                Path target = ExitInstaller.directFile(realMods, approved.fileName());
                if (Files.exists(target)) throw new IOException("새 버전 파일이 이미 있습니다. 중복 설치를 확인하세요.");
                Path stageRoot = realMods.resolve(".mod-updates");
                if (Files.isSymbolicLink(stageRoot)) throw new IOException("Invalid staging directory");
                Files.createDirectories(stageRoot);
                job = Files.createTempDirectory(stageRoot, identity.getProperty("modId") + "-");
                Path payload = job.resolve("payload.bin");
                status = "v" + approved.version() + " 다운로드 중";
                message.accept(status + "...");
                byte[] bytes = fetch(URI.create(approved.url()), MAX_BYTES);
                if (bytes.length != approved.size()) throw new IOException("다운로드 크기가 일치하지 않습니다.");
                Files.write(payload, bytes, StandardOpenOption.CREATE_NEW);
                String hash = ExitInstaller.sha256(payload);
                if (!hash.equalsIgnoreCase(approved.sha256())) throw new IOException("SHA-256 검증 실패");
                validateJar(payload, identity, approved.version());
                Properties p = new Properties();
                p.setProperty("mods", realMods.toString()); p.setProperty("old", realCurrent.getFileName().toString());
                p.setProperty("target", approved.fileName()); p.setProperty("sha256", hash);
                p.setProperty("oldSha256", ExitInstaller.sha256(realCurrent));
                try (var out = Files.newOutputStream(job.resolve("job.properties"))) { p.store(out, "One-time update"); }
                String helperResource = ExitInstaller.class.getName().replace('.', '/') + ".class";
                Path helperFile = job.resolve(helperResource);
                Files.createDirectories(helperFile.getParent());
                try (var in = ExitInstaller.class.getResourceAsStream("/" + helperResource)) {
                    if (in == null) throw new IOException("Missing installer");
                    Files.copy(in, helperFile);
                }
                boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
                Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "javaw.exe" : "java");
                ProcessHandle self = ProcessHandle.current();
                helper = new ProcessBuilder(java.toString(), "-cp", job.toString(), ExitInstaller.class.getName(), job.toString(),
                        Long.toString(self.pid()), self.info().startInstant().orElseThrow().toString())
                        .redirectErrorStream(true).redirectOutput(job.resolve("helper.log").toFile()).start();
                for (int i = 0; !Files.exists(job.resolve("ready")); i++) {
                    if (i >= 100 || !helper.isAlive()) throw new IOException("종료 후 설치 도우미를 시작하지 못했습니다.");
                    Thread.sleep(100);
                }
                Files.writeString(job.resolve("armed"), "consented", StandardOpenOption.CREATE_NEW);
                queued = true;
                status = "v" + approved.version() + " 설치 예약 완료";
                message.accept("검증 완료! 게임을 완전히 종료하고 약 5초 뒤 다시 실행하세요. 구버전은 복구용 백업으로 보관됩니다.");
            } catch (Exception e) {
                if (helper != null) helper.destroy();
                status = "업데이트 실패: " + safeMessage(e);
                message.accept(status + " (현재 버전 유지)");
            } finally { busy.set(false); }
        });
    }

    public static void validateJar(Path file, Properties expected, String version) throws Exception {
        try (JarFile jar = new JarFile(file.toFile())) {
            String modId = required(expected, "modId");
            Properties actual = new Properties();
            var identityEntry = jar.getJarEntry("META-INF/" + modId + "-update.properties");
            if (identityEntry == null) throw new IOException("Missing update identity");
            actual.load(new java.io.ByteArrayInputStream(readEntry(jar, identityEntry, 16_384)));
            for (String key : List.of("modId", "loader", "minecraft", "repository", "prefix"))
                if (!Objects.equals(expected.getProperty(key), actual.getProperty(key))) throw new IOException("Incompatible " + key);
            if (!version.equals(actual.getProperty("version"))) throw new IOException("Version mismatch");
            String loader = expected.getProperty("loader");
            if ("fabric".equals(loader)) validateFabric(jar, modId, version, expected.getProperty("minecraft"));
            else if ("neoforge".equals(loader)) validateNeoForge(jar, modId, version, expected.getProperty("minecraft"));
            else throw new IOException("Unknown loader");
        }
    }

    private static void validateFabric(JarFile jar, String id, String version, String minecraft) throws IOException {
        var entry = jar.getJarEntry("fabric.mod.json");
        if (entry == null) throw new IOException("Fabric metadata missing");
        try (var in = new java.io.ByteArrayInputStream(readEntry(jar, entry, 256_000))) {
            JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!id.equals(json.get("id").getAsString()) || !version.equals(json.get("version").getAsString())) throw new IOException("Fabric ID/version mismatch");
            if (!"client".equals(json.get("environment").getAsString())) throw new IOException("Fabric environment mismatch");
            JsonObject depends = json.getAsJsonObject("depends");
            if (depends == null || !minecraftRangeMatches(depends.get("minecraft").getAsString(), minecraft)) throw new IOException("Fabric Minecraft dependency mismatch");
        } catch (JsonParseException | IllegalStateException | NullPointerException e) { throw new IOException("Invalid Fabric metadata", e); }
    }

    private static void validateNeoForge(JarFile jar, String id, String version, String minecraft) throws IOException {
        var entry = jar.getJarEntry("META-INF/neoforge.mods.toml");
        if (entry == null) throw new IOException("NeoForge metadata missing");
        String text = new String(readEntry(jar, entry, 256_000), StandardCharsets.UTF_8);
        TomlArrays toml = parseTomlArrays(text);
        Map<String, String> modSection = toml.first("mods", "modId", id);
        if (modSection == null || !version.equals(modSection.get("version")))
            throw new IOException("NeoForge ID/version mismatch");
        Map<String, String> dependency = toml.first("dependencies." + id, "modId", "minecraft");
        boolean compatible = dependency != null && minecraftRangeMatches(dependency.get("versionRange"), minecraft);
        if (!compatible) throw new IOException("NeoForge Minecraft dependency mismatch");
    }

    private record TomlArrays(Map<String, List<Map<String, String>>> arrays) {
        Map<String, String> first(String array, String key, String value) {
            for (Map<String, String> item : arrays.getOrDefault(array, List.of()))
                if (value.equals(item.get(key))) return item;
            return null;
        }
    }

    /** Parses the canonical quoted scalars used by NeoForge, while treating multiline strings as opaque. */
    private static TomlArrays parseTomlArrays(String text) throws IOException {
        Map<String, List<Map<String, String>>> arrays = new LinkedHashMap<>();
        Map<String, String> current = null;
        String multiline = null;
        int lineNo = 0;
        for (String raw : text.split("\\R", -1)) {
            lineNo++;
            String line = raw.strip();
            if (multiline != null) {
                if (line.contains(multiline)) multiline = null;
                continue;
            }
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.matches("\\[\\[[A-Za-z0-9_.-]+]]")) {
                String name = line.substring(2, line.length() - 2);
                current = new LinkedHashMap<>();
                arrays.computeIfAbsent(name, ignored -> new ArrayList<>()).add(current);
                continue;
            }
            if (line.matches("\\[[A-Za-z0-9_.-]+]")) { current = null; continue; }
            if (line.startsWith("[")) throw new IOException("Invalid NeoForge TOML table at line " + lineNo);
            int equals = line.indexOf('=');
            if (equals < 1) throw new IOException("Invalid NeoForge TOML at line " + lineNo);
            String key = line.substring(0, equals).strip();
            String value = line.substring(equals + 1).strip();
            if (!key.matches("[A-Za-z0-9_-]+")) throw new IOException("Invalid NeoForge TOML key");
            if (value.startsWith("'''" ) || value.startsWith("\"\"\"")) {
                String delimiter = value.substring(0, 3);
                if (value.indexOf(delimiter, 3) < 0) multiline = delimiter;
                continue;
            }
            if (current == null) continue;
            String scalar = quotedTomlString(value);
            if (scalar != null && current.putIfAbsent(key, scalar) != null)
                throw new IOException("Duplicate NeoForge TOML key: " + key);
        }
        if (multiline != null) throw new IOException("Unterminated NeoForge TOML string");
        return new TomlArrays(arrays);
    }

    private static String quotedTomlString(String value) throws IOException {
        if (value.length() < 2 || (value.charAt(0) != '"' && value.charAt(0) != '\'')) return null;
        char quote = value.charAt(0);
        int end = value.lastIndexOf(quote);
        if (end == 0 || !value.substring(end + 1).strip().matches("(?:#.*)?"))
            throw new IOException("Invalid NeoForge TOML string");
        String scalar = value.substring(1, end);
        if (quote == '"') scalar = scalar.replace("\\\"", "\"").replace("\\\\", "\\");
        return scalar;
    }

    private static boolean minecraftRangeMatches(String range, String expected) {
        if (range == null || expected == null) return false;
        // 낙인(Fabric)은 "~26.1.2" 를 쓴다. "[x]"·"[x,1.22)" 는 NeoForge 메타 표기로,
        //   NeoForge 검증 경로와 함께 남아 있을 뿐 낙인 배포물에는 쓰이지 않는다.
        return range.equals(expected) || range.equals("~" + expected)
                || range.equals("[" + expected + "]") || range.equals("[" + expected + ",1.22)");
    }

    private static byte[] readEntry(JarFile jar, java.util.jar.JarEntry entry, int limit) throws IOException {
        if (entry.getSize() > limit) throw new IOException("Metadata too large: " + entry.getName());
        try (var in = jar.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Metadata too large: " + entry.getName());
            return bytes;
        }
    }

    private byte[] fetch(URI uri, long limit) throws Exception { return fetcher.fetch(uri, limit); }

    private byte[] fetchHttp(URI uri, long limit) throws Exception {
        for (int redirects = 0; redirects < 6; redirects++) {
            validateDownloadUrl(uri);
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(90))
                    .header("User-Agent", identity.getProperty("modId") + "/" + identity.getProperty("version")).GET().build();
            var response = http.send(request, boundedBody(limit));
            if (response.body().length > limit) throw new IOException("Response too large");
            if (response.statusCode() >= 300 && response.statusCode() < 400) {
                uri = uri.resolve(response.headers().firstValue("Location").orElseThrow()); continue;
            }
            if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
            return response.body();
        }
        throw new IOException("Too many redirects");
    }

    private static HttpResponse.BodyHandler<byte[]> boundedBody(long limit) {
        return info -> {
            if (info.headers().firstValueAsLong("Content-Length").orElse(-1L) > limit)
                throw new IllegalArgumentException("Response too large");
            return new LimitedBodySubscriber(limit);
        };
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final long limit;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        private long size;
        LimitedBodySubscriber(long limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription s) { subscription = s; s.request(Long.MAX_VALUE); }
        public void onNext(List<ByteBuffer> buffers) {
            try {
                for (ByteBuffer buffer : buffers) {
                    int n = buffer.remaining();
                    if (size + n > limit) {
                        subscription.cancel(); result.completeExceptionally(new IOException("Response too large")); return;
                    }
                    byte[] chunk = new byte[n]; buffer.get(chunk); bytes.writeBytes(chunk); size += n;
                }
            } catch (RuntimeException e) { result.completeExceptionally(e); }
        }
        public void onError(Throwable t) { result.completeExceptionally(t); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }

    private void validateDownloadUrl(URI uri) throws IOException {
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || !HOSTS.contains(uri.getHost())) throw new IOException("Untrusted download URL");
        if ("api.github.com".equals(uri.getHost()) && !uri.getPath().startsWith("/repos/" + repository() + "/"))
            throw new IOException("Unexpected API URL");
        if ("github.com".equals(uri.getHost()) && !uri.getPath().startsWith("/" + repository() + "/releases/"))
            throw new IOException("Unexpected release URL");
    }

    private void clearOffer() { offer = null; version = null; notified = false; }
    @FunctionalInterface interface Fetcher { byte[] fetch(URI uri, long limit) throws Exception; }
    private static Properties loadIdentity(String modId) throws IOException {
        Properties result = new Properties();
        try (var in = ModUpdater.class.getResourceAsStream("/META-INF/" + modId + "-update.properties")) {
            if (in == null) throw new IOException("Missing update identity");
            result.load(in);
        }
        return result;
    }
    private void requireIdentity(String key) throws IOException { required(identity, key); }
    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key); if (value == null || value.isBlank()) throw new IOException("Missing identity: " + key); return value;
    }
    private static String safeMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
