package com.wmqc.miroot.lyrics;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import rikka.shizuku.Shizuku;

/**
 * 统一启动特权 shell：每次优先 {@code su -c}，启动失败或不可用再回退 Shizuku（与 {@link PrivilegeBackend} 单次检测结果无关）。
 */
public final class ShellCompat {

    private ShellCompat() {}

    /** 与 capability 模块一致，便于 KernelSU / Magisk 下解析到真实 {@code su}。 */
    private static void enrichPrivilegedSuPath(ProcessBuilder pb) {
        Map<String, String> env = pb.environment();
        String path = env.get("PATH");
        if (path == null) {
            path = "/system/bin:/system/xbin:/vendor/bin:/product/bin";
        }
        env.put("PATH", "/data/adb/ksu/bin:/data/adb/magisk:" + path);
    }

    public static Process startShell(String cArg) throws IOException {
        return startShell(cArg, false);
    }

    /**
     * Shizuku API 13 中 {@code Shizuku.newProcess} 为 private，沿用其远程 shell 实现需反射调用。
     */
    private static Process shizukuNewProcessReflect(String cArg) throws IOException {
        try {
            Method m =
                    Shizuku.class.getDeclaredMethod(
                            "newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Object proc = m.invoke(null, new String[] {"sh", "-c", cArg}, null, null);
            return (Process) proc;
        } catch (Exception e) {
            throw new IOException("Shizuku.newProcess failed", e);
        }
    }

    /**
     * @param redirectErr 为 true 时合并 stderr 到 stdout（与 {@link ProcessBuilder#redirectErrorStream(boolean)} 行为一致，便于单流读取）
     */
    /**
     * 仅 {@code su -c}，不经过 Shizuku；供读 {@code /data/data/…} 等他应用私有路径。
     *
     * @throws IOException su 不可用或启动失败
     */
    public static Process startShellSuOnly(String cArg) throws IOException {
        Objects.requireNonNull(cArg, "cArg");
        ProcessBuilder pb = new ProcessBuilder("su", "-c", cArg);
        enrichPrivilegedSuPath(pb);
        return pb.start();
    }

    public static Process startShell(String cArg, boolean redirectErr) throws IOException {
        Objects.requireNonNull(cArg, "cArg");
        PrivilegeBackend.refreshIfUnknown();

        IOException suFailure = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", cArg);
            enrichPrivilegedSuPath(pb);
            if (redirectErr) {
                pb.redirectErrorStream(true);
            }
            return pb.start();
        } catch (IOException e) {
            suFailure = e;
        }

        if (!PrivilegeBackend.testShizukuGranted()) {
            if (suFailure != null) {
                throw suFailure;
            }
            throw new IOException("No root or Shizuku shell privilege");
        }
        try {
            Process p = shizukuNewProcessReflect(cArg);
            if (redirectErr && p != null) {
                InputStream es = p.getErrorStream();
                if (es != null) {
                    new Thread(
                            () -> {
                                try {
                                    byte[] buf = new byte[4096];
                                    while (es.read(buf) != -1) {
                                        // drain
                                    }
                                    es.close();
                                } catch (Exception ignored) {
                                }
                            },
                            "miroot-shizuku-stderr")
                            .start();
                }
            }
            return p;
        } catch (IOException shizukuEx) {
            if (suFailure != null) {
                suFailure.addSuppressed(shizukuEx);
                throw suFailure;
            }
            throw shizukuEx;
        }
    }
}
