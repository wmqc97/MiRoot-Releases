package com.wmqc.miroot.display

import android.os.Build
import com.wmqc.miroot.capability.EnvironmentProbe
import com.wmqc.miroot.capability.PrivilegedShell
import com.wmqc.miroot.lyrics.LogHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import java.lang.reflect.Method

/**
 * DisplayControlManager
 *
 * 目标：在 **Root 优先**、无 Root 时自动降级 **Shizuku(system_server)** 的前提下，
 * 修改指定 display 的 DPI（wm density / IWindowManager forced density）与屏幕旋转（settings user_rotation / IWindowManager freezeRotation）。
 *
 * 适配点（Android 16 / HyperOS 3 双屏/背屏）：
 * - DPI 变更优先使用 `wm density ... -d <displayId>`，便于对背屏 (displayId=1) 生效
 * - Shizuku 路径通过 `window` 服务（IWindowManager）反射调用，避免依赖隐藏 API 编译期符号
 *
 * 使用方式示例见 `DisplayControlExampleActivity`。
 */
class DisplayControlManager(
    /** 目标 displayId；主屏一般为 0，小米背屏常为 1（以系统实际分配为准）。 */
    private val displayId: Int = 0,
) {
    data class Snapshot(
        val displayId: Int,
        val physicalDpi: Int,
        val overrideDpi: Int?, // null 表示未设置 override（恢复时可用 reset）
        val userRotation: Int, // 0..3
        val accelerometerRotation: Int, // 0/1
    )

    sealed class ApplyResult {
        data class Success(val backend: Backend) : ApplyResult()
        data class Failed(val error: Throwable? = null) : ApplyResult()
    }

    enum class Backend {
        ROOT_SHELL,
        SHIZUKU_WMS,
    }

    private var snapshot: Snapshot? = null

    /**
     * 保存当前系统配置（density + rotation），供后续 restore。
     *
     * - density：解析 `wm density -d <displayId>` 的 Physical/Override
     * - rotation：读取 `settings get system user_rotation`（并额外保存 `accelerometer_rotation`，保证恢复后与用户一致）
     */
    suspend fun captureOriginal(): Snapshot? = withContext(Dispatchers.IO) {
        try {
            val dpiOut = PrivilegedShell.captureOutput("wm density -d $displayId").orEmpty()
            val physical = Regex("Physical density:\\s*(\\d+)").find(dpiOut)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val override = Regex("Override density:\\s*(\\d+)").find(dpiOut)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val userRot = PrivilegedShell.captureOutput("settings get system user_rotation")
                ?.trim()
                ?.toIntOrNull()
                ?: 0
            val accelRot = PrivilegedShell.captureOutput("settings get system accelerometer_rotation")
                ?.trim()
                ?.toIntOrNull()
                ?: 1

            val snap = Snapshot(
                displayId = displayId,
                physicalDpi = physical ?: (override ?: 0),
                overrideDpi = override,
                userRotation = userRot.coerceIn(0, 3),
                accelerometerRotation = if (accelRot == 0) 0 else 1,
            )
            if (snap.physicalDpi <= 0) {
                LogHelper.w(TAG, "captureOriginal: invalid dpi; out=${LogHelper.truncateForLog(dpiOut, 200)}")
            }
            snapshot = snap
            snap
        } catch (t: Throwable) {
            LogHelper.e(TAG, "captureOriginal failed", t)
            null
        }
    }

    /**
     * 应用自定义 DPI 与旋转（角度制：0/90/180/270）。
     *
     * 执行策略：
     * - 先尝试 Root：`wm density ... -d` + `settings put system ...`
     * - Root 失败再降级 Shizuku：反射 IWindowManager 修改 forced density + freezeRotation/thawRotation
     */
    suspend fun applyCustom(
        dpi: Int,
        rotationDegrees: Int,
        /** 为 true 时强制关闭自动旋转（accelerometer_rotation=0），保证 user_rotation/冻结旋转一定生效。 */
        disableAutoRotation: Boolean = true,
    ): ApplyResult = withContext(Dispatchers.IO) {
        val rot = degreesToUserRotation(rotationDegrees) ?: return@withContext ApplyResult.Failed(
            IllegalArgumentException("rotationDegrees must be 0/90/180/270"),
        )
        if (dpi <= 0) {
            return@withContext ApplyResult.Failed(IllegalArgumentException("dpi must be > 0"))
        }

        // 1) 统一 shell 执行（Root 优先，失败自动回退 Shizuku）
        runCatching {
            if (tryRootApply(dpi = dpi, userRotation = rot, disableAutoRotation = disableAutoRotation)) {
                return@withContext ApplyResult.Success(Backend.ROOT_SHELL)
            }
        }.onFailure { t ->
            LogHelper.w(TAG, "applyCustom root failed: ${t.message}")
        }

        // 2) 若 shell 路径失败，继续尝试 Shizuku system_server 直连 WindowManager
        runCatching {
            if (tryShizukuApply(dpi = dpi, userRotation = rot, disableAutoRotation = disableAutoRotation)) {
                return@withContext ApplyResult.Success(Backend.SHIZUKU_WMS)
            }
        }.onFailure { t ->
            LogHelper.e(TAG, "applyCustom shizuku failed", t)
        }

        ApplyResult.Failed()
    }

    /**
     * 恢复到 captureOriginal 保存的原始值。
     *
     * 说明：
     * - density：若原本无 override，则优先 reset；否则恢复为原 override 值
     * - rotation：恢复 accelerometer_rotation 与 user_rotation（Root 时写 settings；Shizuku 时用 thaw/freeze 尽量对齐）
     */
    suspend fun restoreOriginal(): ApplyResult = withContext(Dispatchers.IO) {
        val snap = snapshot
            ?: return@withContext ApplyResult.Failed(IllegalStateException("No snapshot, call captureOriginal() first"))

        // 1) 统一 shell 执行（Root 优先，失败自动回退 Shizuku）
        runCatching {
            if (tryRootRestore(snap)) {
                return@withContext ApplyResult.Success(Backend.ROOT_SHELL)
            }
        }.onFailure { t ->
            LogHelper.w(TAG, "restoreOriginal root failed: ${t.message}")
        }

        // 2) 若 shell 路径失败，继续尝试 Shizuku system_server 直连 WindowManager
        runCatching {
            if (tryShizukuRestore(snap)) {
                return@withContext ApplyResult.Success(Backend.SHIZUKU_WMS)
            }
        }.onFailure { t ->
            LogHelper.e(TAG, "restoreOriginal shizuku failed", t)
        }

        ApplyResult.Failed()
    }

    private fun degreesToUserRotation(deg: Int): Int? = when (deg) {
        0 -> 0
        90 -> 1
        180 -> 2
        270 -> 3
        else -> null
    }

    /** 统一命令执行入口：Root 优先，失败自动回退 Shizuku。 */
    private fun execCmd(cmd: String): Boolean = PrivilegedShell.execCmd(cmd)

    private fun tryRootApply(dpi: Int, userRotation: Int, disableAutoRotation: Boolean): Boolean {
        // 1) DPI（指定 displayId，背屏/副屏可独立修改）
        val dpiOk = execCmd("wm density $dpi -d $displayId")
        // 2) 旋转（写 settings）
        val autoOk = if (disableAutoRotation) execCmd("settings put system accelerometer_rotation 0") else true
        val rotOk = execCmd("settings put system user_rotation $userRotation")
        val ok = dpiOk && autoOk && rotOk
        LogHelper.d(TAG, "tryRootApply: displayId=$displayId dpi=$dpi dpiOk=$dpiOk rot=$userRotation rotOk=$rotOk autoOff=$disableAutoRotation autoOk=$autoOk ok=$ok")
        return ok
    }

    private fun tryRootRestore(snap: Snapshot): Boolean {
        val densityOk = if (snap.overrideDpi == null) {
            execCmd("wm density reset -d $displayId")
        } else {
            execCmd("wm density ${snap.overrideDpi} -d $displayId")
        }
        val accelOk = execCmd("settings put system accelerometer_rotation ${snap.accelerometerRotation}")
        val rotOk = execCmd("settings put system user_rotation ${snap.userRotation}")
        val ok = densityOk && accelOk && rotOk
        LogHelper.d(TAG, "tryRootRestore: displayId=$displayId densityOk=$densityOk accelOk=$accelOk rotOk=$rotOk ok=$ok")
        return ok
    }

    private fun shizukuReady(): Boolean = try {
        EnvironmentProbe.shizukuServiceRunning() &&
            EnvironmentProbe.shizukuPermissionGranted() &&
            Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    private fun tryShizukuApply(dpi: Int, userRotation: Int, disableAutoRotation: Boolean): Boolean {
        if (!shizukuReady()) return false
        val wm = getIWindowManagerViaShizuku() ?: return false

        val densityOk = setForcedDisplayDensityCompat(wm, displayId, dpi)

        // 旋转：尽量按「关闭自动旋转→冻结到指定方向」
        val rotOk = if (disableAutoRotation) {
            // 没有 WRITE_SETTINGS 权限时无法直接改 accelerometer_rotation；
            // 但 freezeRotation 本身会让方向固定下来，等同于「用户看到的是固定方向」。
            freezeRotationCompat(wm, userRotation)
        } else {
            freezeRotationCompat(wm, userRotation)
        }

        val ok = densityOk && rotOk
        LogHelper.d(TAG, "tryShizukuApply: displayId=$displayId dpiOk=$densityOk rotOk=$rotOk ok=$ok sdk=${Build.VERSION.SDK_INT}")
        return ok
    }

    private fun tryShizukuRestore(snap: Snapshot): Boolean {
        if (!shizukuReady()) return false
        val wm = getIWindowManagerViaShizuku() ?: return false

        val densityOk = if (snap.overrideDpi == null) {
            clearForcedDisplayDensityCompat(wm, displayId)
        } else {
            setForcedDisplayDensityCompat(wm, displayId, snap.overrideDpi)
        }

        // 恢复旋转：如果原本开着自动旋转，则 thawRotation；否则 freeze 回原方向
        val rotOk = if (snap.accelerometerRotation != 0) {
            thawRotationCompat(wm)
        } else {
            freezeRotationCompat(wm, snap.userRotation)
        }

        val ok = densityOk && rotOk
        LogHelper.d(TAG, "tryShizukuRestore: displayId=$displayId densityOk=$densityOk rotOk=$rotOk ok=$ok")
        return ok
    }

    /**
     * 通过 Shizuku(system_server) 获取 `window` 服务并反射成 IWindowManager。
     */
    private fun getIWindowManagerViaShizuku(): Any? {
        return try {
            val smClz = Class.forName("android.os.ServiceManager")
            val getService: Method = smClz.getDeclaredMethod("getService", String::class.java)
            val rawBinder = getService.invoke(null, "window") as? android.os.IBinder ?: return null

            val wrapped = ShizukuBinderWrapper(rawBinder)
            val stubClz = Class.forName("android.view.IWindowManager\$Stub")
            val asInterface = stubClz.getDeclaredMethod("asInterface", android.os.IBinder::class.java)
            asInterface.invoke(null, wrapped)
        } catch (t: Throwable) {
            LogHelper.e(TAG, "getIWindowManagerViaShizuku failed", t)
            null
        }
    }

    private fun currentUserIdCompat(): Int {
        // 多用户环境下尽量不写死 0；无论失败与否，fallback 0 也能覆盖绝大多数机型。
        return try {
            val uh = Class.forName("android.os.UserHandle")
            val m = uh.getDeclaredMethod("myUserId")
            (m.invoke(null) as? Int) ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * Android 版本/厂商差异下 IWindowManager 的 density 接口可能存在多个重载。
     * 这里按常见签名逐个尝试。
     */
    private fun setForcedDisplayDensityCompat(wm: Any, displayId: Int, dpi: Int): Boolean {
        val userId = currentUserIdCompat()
        val candidates: List<Pair<String, Array<Class<*>>>> = listOf(
            "setForcedDisplayDensityForUser" to arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            "setForcedDisplayDensity" to arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
        )
        for ((name, sig) in candidates) {
            try {
                val m = wm.javaClass.getMethod(name, *sig)
                m.isAccessible = true
                when (sig.size) {
                    3 -> m.invoke(wm, displayId, dpi, userId)
                    2 -> m.invoke(wm, displayId, dpi)
                }
                return true
            } catch (_: NoSuchMethodException) {
                // try next
            } catch (t: Throwable) {
                LogHelper.w(TAG, "setForcedDisplayDensity failed: $name(${sig.size}) ${t.message}")
            }
        }
        return false
    }

    private fun clearForcedDisplayDensityCompat(wm: Any, displayId: Int): Boolean {
        val userId = currentUserIdCompat()
        val candidates: List<Pair<String, Array<Class<*>>>> = listOf(
            "clearForcedDisplayDensityForUser" to arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            "clearForcedDisplayDensity" to arrayOf(Int::class.javaPrimitiveType!!),
        )
        for ((name, sig) in candidates) {
            try {
                val m = wm.javaClass.getMethod(name, *sig)
                m.isAccessible = true
                when (sig.size) {
                    2 -> m.invoke(wm, displayId, userId)
                    1 -> m.invoke(wm, displayId)
                }
                return true
            } catch (_: NoSuchMethodException) {
                // try next
            } catch (t: Throwable) {
                LogHelper.w(TAG, "clearForcedDisplayDensity failed: $name(${sig.size}) ${t.message}")
            }
        }
        return false
    }

    private fun freezeRotationCompat(wm: Any, userRotation: Int): Boolean {
        val candidates: List<Pair<String, Array<Class<*>>>> = listOf(
            // 常见：freezeRotation(int rotation)
            "freezeRotation" to arrayOf(Int::class.javaPrimitiveType!!),
            // 部分 ROM/版本可能提供 display 级旋转：setDisplayRotation(int displayId, int rotation)
            "setDisplayRotation" to arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
        )
        for ((name, sig) in candidates) {
            try {
                val m = wm.javaClass.getMethod(name, *sig)
                m.isAccessible = true
                when (sig.size) {
                    1 -> m.invoke(wm, userRotation)
                    2 -> m.invoke(wm, displayId, userRotation)
                }
                return true
            } catch (_: NoSuchMethodException) {
                // try next
            } catch (t: Throwable) {
                LogHelper.w(TAG, "freezeRotation failed: $name(${sig.size}) ${t.message}")
            }
        }
        return false
    }

    private fun thawRotationCompat(wm: Any): Boolean {
        return try {
            val m = wm.javaClass.getMethod("thawRotation")
            m.isAccessible = true
            m.invoke(wm)
            true
        } catch (_: NoSuchMethodException) {
            false
        } catch (t: Throwable) {
            LogHelper.w(TAG, "thawRotation failed: ${t.message}")
            false
        }
    }

    companion object {
        private const val TAG = "DisplayControlManager"
    }
}

