package kr.aka.nakin.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ExitInstallerTest {
    @TempDir Path dir;

    @Test void installsOnlyVerifiedPayloadAndLeavesBackup() throws Exception {
        Path mods = Files.createDirectory(dir.resolve("mods"));
        Path staging = Files.createDirectory(mods.resolve(".mod-updates"));
        Path job = Files.createDirectory(staging.resolve("job"));
        Path old = mods.resolve("nakin-fabric-0.1.1.jar");
        Path payload = job.resolve("payload.bin");
        Files.writeString(old, "old"); Files.writeString(payload, "new");
        Properties p = new Properties();
        p.setProperty("mods", mods.toString()); p.setProperty("old", old.getFileName().toString());
        p.setProperty("target", "nakin-fabric-0.1.2.jar");
        p.setProperty("sha256", ExitInstaller.sha256(payload)); p.setProperty("oldSha256", ExitInstaller.sha256(old));
        try (var out = Files.newOutputStream(job.resolve("job.properties"))) { p.store(out, "test"); }
        ExitInstaller.install(job);
        assertEquals("new", Files.readString(mods.resolve("nakin-fabric-0.1.2.jar")));
        assertFalse(Files.exists(old));
        assertEquals("old", Files.readString(job.resolve("previous.jar.disabled")));
        assertEquals("INSTALLED nakin-fabric-0.1.2.jar", Files.readString(job.resolve("result.txt")));
    }

    @Test void rejectsDestinationCollisionAndPathTraversal() throws Exception {
        Path mods = Files.createDirectory(dir.resolve("mods"));
        Path staging = Files.createDirectory(mods.resolve(".mod-updates"));
        Path job = Files.createDirectory(staging.resolve("job"));
        Path old = mods.resolve("old.jar"); Files.writeString(old, "old");
        Files.writeString(mods.resolve("new.jar"), "existing");
        Files.writeString(job.resolve("payload.bin"), "new");
        Properties p = new Properties();
        p.setProperty("mods", mods.toString()); p.setProperty("old", old.getFileName().toString());
        p.setProperty("target", "new.jar"); p.setProperty("sha256", ExitInstaller.sha256(job.resolve("payload.bin")));
        p.setProperty("oldSha256", ExitInstaller.sha256(old));
        try (var out = Files.newOutputStream(job.resolve("job.properties"))) { p.store(out, "test"); }
        assertThrows(Exception.class, () -> ExitInstaller.install(job));
        assertEquals("old", Files.readString(old));
        assertThrows(Exception.class, () -> ExitInstaller.directFile(mods, "..\\outside.jar"));
    }
    @Test void helperArmsBeforeParentExitAndInstallsAfterExit() throws Exception {
        Path mods = Files.createDirectory(dir.resolve("mods"));
        Path staging = Files.createDirectory(mods.resolve(".mod-updates"));
        Path job = Files.createDirectory(staging.resolve("job"));
        Path old = mods.resolve("old.jar"); Path payload = job.resolve("payload.bin");
        Files.writeString(old, "old"); Files.writeString(payload, "new");
        Properties p = new Properties();
        p.setProperty("mods", mods.toString()); p.setProperty("old", "old.jar");
        p.setProperty("target", "new.jar"); p.setProperty("sha256", ExitInstaller.sha256(payload));
        p.setProperty("oldSha256", ExitInstaller.sha256(old));
        try (var out = Files.newOutputStream(job.resolve("job.properties"))) { p.store(out, "test"); }
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
        String classPath = Path.of(ExitInstaller.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                + java.io.File.pathSeparator + Path.of(Sleeper.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process parent = new ProcessBuilder(javaExecutable.toString(), "-cp", classPath, Sleeper.class.getName()).start();
        Process helper = null;
        try {
            Instant start = parent.toHandle().info().startInstant().orElseThrow();
            helper = new ProcessBuilder(javaExecutable.toString(), "-cp", classPath,
                    ExitInstaller.class.getName(), job.toString(), Long.toString(parent.pid()), start.toString()).start();
            for (int i = 0; i < 100 && !Files.exists(job.resolve("ready")); i++) Thread.sleep(20);
            assertTrue(Files.exists(job.resolve("ready")));
            Files.writeString(job.resolve("armed"), "ok");
            Thread.sleep(150);
            assertTrue(parent.isAlive());
            assertTrue(helper.isAlive());
            assertEquals("old", Files.readString(old));
            assertFalse(Files.exists(mods.resolve("new.jar")));
            parent.destroy();
            assertTrue(parent.waitFor(5, TimeUnit.SECONDS));
            assertTrue(helper.waitFor(5, TimeUnit.SECONDS));
            assertEquals("new", Files.readString(mods.resolve("new.jar")));
            assertEquals("old", Files.readString(job.resolve("previous.jar.disabled")));
        } finally {
            parent.destroyForcibly(); parent.waitFor(5, TimeUnit.SECONDS);
            if (helper != null) { helper.destroyForcibly(); helper.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    @Test void changedOldOrPayloadHashAbortsWithoutMovingOriginal() throws Exception {
        for (boolean changeOld : new boolean[] {true, false}) {
            Path mods = Files.createDirectory(dir.resolve(changeOld ? "old-mods" : "payload-mods"));
            Path staging = Files.createDirectory(mods.resolve(".mod-updates"));
            Path job = Files.createDirectory(staging.resolve("job"));
            Path old = mods.resolve("old.jar"); Path payload = job.resolve("payload.bin");
            Files.writeString(old, "old"); Files.writeString(payload, "new");
            String oldHash = ExitInstaller.sha256(old); String payloadHash = ExitInstaller.sha256(payload);
            if (changeOld) Files.writeString(old, "changed"); else Files.writeString(payload, "changed");
            Properties p = new Properties(); p.setProperty("mods", mods.toString()); p.setProperty("old", "old.jar");
            p.setProperty("target", "new.jar"); p.setProperty("sha256", payloadHash); p.setProperty("oldSha256", oldHash);
            try (var out = Files.newOutputStream(job.resolve("job.properties"))) { p.store(out, "test"); }
            assertThrows(Exception.class, () -> ExitInstaller.install(job));
            assertEquals(changeOld ? "changed" : "old", Files.readString(old));
            assertEquals(changeOld ? "new" : "changed", Files.readString(payload));
            assertFalse(Files.exists(mods.resolve("new.jar")));
        }
    }

    public static final class Sleeper {
        public static void main(String[] args) throws Exception { Thread.sleep(30_000); }
    }
}
