package kr.aka.nakin.update;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Properties;

/** 종료된 게임 뒤에만 활성 JAR를 교체하는 JDK-only 도우미. */
public final class ExitInstaller {
    private ExitInstaller() {}

    public static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[65_536];
            for (int n; (n = in.read(buffer)) >= 0;) if (n > 0) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static Path directFile(Path parent, String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9_.+-]+") || name.equals(".") || name.equals(".."))
            throw new IOException("Invalid filename: " + name);
        Path path = parent.resolve(name);
        if (Files.isSymbolicLink(path)) throw new IOException("Symlink rejected: " + name);
        return path;
    }

    public static void install(Path job) throws Exception {
        job = job.toRealPath();
        Properties p = new Properties();
        try (var in = Files.newInputStream(job.resolve("job.properties"))) { p.load(in); }
        Path mods = Path.of(required(p, "mods")).toRealPath();
        Path staging = mods.resolve(".mod-updates").toRealPath();
        if (!job.startsWith(staging)) throw new IOException("Job outside staging directory: " + job);
        Path oldJar = directFile(mods, required(p, "old"));
        Path target = directFile(mods, required(p, "target"));
        Path staged = directFile(job, "payload.bin");
        Path backup = directFile(job, "previous.jar.disabled");
        if (oldJar.equals(target) || !oldJar.getFileName().toString().endsWith(".jar")
                || !target.getFileName().toString().endsWith(".jar")) throw new IOException("Invalid JAR targets");
        if (!Files.isRegularFile(oldJar) || !Files.isRegularFile(staged)) throw new IOException("Missing staged JAR");
        if (!sha256(oldJar).equalsIgnoreCase(required(p, "oldSha256"))) throw new IOException("Current JAR changed after verification");
        if (!sha256(staged).equalsIgnoreCase(required(p, "sha256"))) throw new IOException("Staged JAR changed after verification");
        if (Files.exists(target) || Files.exists(backup)) throw new IOException("Destination or backup already exists");

        IOException last = null;
        for (int attempt = 0; attempt < 60; attempt++) {
            try { Files.move(oldJar, backup); last = null; break; }
            catch (IOException e) { last = e; Thread.sleep(500); }
        }
        if (last != null) throw new IOException("Could not move current JAR; backup was not created", last);
        try {
            Files.move(staged, target);
        } catch (Exception installFailure) {
            try {
                Files.move(backup, oldJar);
            } catch (Exception rollbackFailure) {
                throw new IOException("Install failed and rollback failed; backup remains at " + backup
                                + "; original target was " + oldJar,
                        rollbackFailure);
            }
            throw new IOException("Install failed; restored current JAR from " + backup, installFailure);
        }
        Files.writeString(job.resolve("result.txt"), "INSTALLED " + target.getFileName(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("Missing job property: " + key);
        return value;
    }

    public static void main(String[] args) throws Exception {
        Path job = Path.of(args[0]).toRealPath();
        try {
            long pid = Long.parseLong(args[1]);
            Instant start = Instant.parse(args[2]);
            ProcessHandle parent = ProcessHandle.of(pid).orElseThrow(() -> new IOException("Game process missing"));
            if (!parent.info().startInstant().orElse(Instant.MIN).equals(start))
                throw new IOException("Game process identity mismatch");
            Files.writeString(job.resolve("ready"), "ready", StandardOpenOption.CREATE_NEW);
            for (int i = 0; !Files.exists(job.resolve("armed")); i++) {
                if (i >= 100 || !parent.isAlive()) throw new IOException("Update was not armed");
                Thread.sleep(100);
            }
            parent.onExit().join();
            install(job);
        } catch (Exception e) {
            Files.writeString(job.resolve("result.txt"), "FAILED: " + e,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }
}
