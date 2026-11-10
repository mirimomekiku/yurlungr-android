package com.termux.terminal;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Exercises the rebuilt JNI without requiring an installed Linux bootstrap. */
public final class NativeSmokeChecks {
    public static void run(Context context) throws Exception {
        File marker = new File(context.getCacheDir(), "native-smoke-" + UUID.randomUUID());
        int[] pid = new int[1];
        int fd = -1;
        boolean childExited = false;
        var executor = Executors.newSingleThreadExecutor();
        try {
            fd = JNI.createSubprocess("/system/bin/sh", context.getCacheDir().getPath(),
                new String[]{"sh", "-c", "printf native-ok > '" + marker.getPath() + "'"},
                new String[]{"PATH=/system/bin"}, pid, 24, 80, 8, 16);
            if (fd < 0 || pid[0] <= 0) throw new AssertionError("PTY creation failed");
            JNI.setPtyWindowSize(fd, 30, 100, 8, 16);
            int exit = executor.submit(() -> JNI.waitFor(pid[0])).get(10, TimeUnit.SECONDS);
            childExited = true;
            if (exit != 0) throw new AssertionError("PTY child exit: " + exit);
            try (FileInputStream input = new FileInputStream(marker)) {
                byte[] bytes = new byte[32];
                int count = input.read(bytes);
                String text = count < 0 ? "" : new String(bytes, 0, count, StandardCharsets.UTF_8);
                if (!text.equals("native-ok")) throw new AssertionError("PTY child output: " + text);
            }
        } finally {
            if (pid[0] > 0 && !childExited) android.os.Process.killProcess(pid[0]);
            if (fd >= 0) JNI.close(fd);
            executor.shutdownNow();
            marker.delete();
        }
        // The terminal's shell must start with SIGHUP neither blocked nor ignored (the app ignores it):
        // otherwise closing a tab can't hang up its jobs and scripts can't trap it.
        File masks = new File(context.getCacheDir(), "native-signals-" + UUID.randomUUID());
        int[] spid = new int[1];
        int sfd = -1;
        var sexec = Executors.newSingleThreadExecutor();
        try {
            sfd = JNI.createSubprocess("/system/bin/sh", context.getCacheDir().getPath(),
                new String[]{"sh", "-c", "grep -E '^Sig(Blk|Ign)' /proc/$$/status > '" + masks.getPath() + "'"},
                new String[]{"PATH=/system/bin"}, spid, 24, 80, 8, 16);
            int exit = sexec.submit(() -> JNI.waitFor(spid[0])).get(10, TimeUnit.SECONDS);
            if (exit != 0) throw new AssertionError("signal-mask child exit: " + exit);
            String text = new String(java.nio.file.Files.readAllBytes(masks.toPath()), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 2 && (Long.parseUnsignedLong(parts[1], 16) & 1L) != 0) {
                    throw new AssertionError("SIGHUP not at its default in terminal children: " + text);
                }
            }
        } finally {
            if (sfd >= 0) JNI.close(sfd);
            sexec.shutdownNow();
            masks.delete();
        }
        // A missing program exits 127 and a missing working folder 126 (not 1, and not "carry on in /"),
        // so the terminal can tell the user the shell is missing.
        expectExit(context, "/system/bin/does-not-exist-" + UUID.randomUUID(), context.getCacheDir().getPath(), 127);
        expectExit(context, "/system/bin/sh", new File(context.getCacheDir(), "missing-" + UUID.randomUUID()).getPath(), 126);
    }

    private static void expectExit(Context context, String cmd, String cwd, int expected) throws Exception {
        int[] pid = new int[1];
        int fd = -1;
        var executor = Executors.newSingleThreadExecutor();
        try {
            fd = JNI.createSubprocess(cmd, cwd, new String[]{"x", "-c", "exit 0"}, new String[]{"PATH=/system/bin"}, pid, 24, 80, 8, 16);
            if (fd < 0 || pid[0] <= 0) throw new AssertionError("PTY creation failed for " + cmd);
            int exit = executor.submit(() -> JNI.waitFor(pid[0])).get(10, TimeUnit.SECONDS);
            if (exit != expected) throw new AssertionError("exec of " + cmd + " in " + cwd + ": exit " + exit + ", expected " + expected);
        } finally {
            if (fd >= 0) JNI.close(fd);
            executor.shutdownNow();
        }
    }
}
