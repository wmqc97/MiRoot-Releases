package com.wmqc.miroot.lyrics;

import com.wmqc.miroot.capability.PrivilegedShell;
import java.io.IOException;
import java.util.Objects;

/**
 * 统一启动特权 shell：每次优先 {@code su -c}，启动失败或不可用再回退 Shizuku（与 {@link PrivilegeBackend} 单次检测结果无关）。
 */
public final class ShellCompat {

    private ShellCompat() {}

    public static Process startShell(String cArg) throws IOException {
        return startShell(cArg, false);
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
        return PrivilegedShell.startRootShellOnly(cArg, false);
    }

    public static Process startShell(String cArg, boolean redirectErr) throws IOException {
        Objects.requireNonNull(cArg, "cArg");
        return PrivilegedShell.startShell(cArg, redirectErr);
    }
}
