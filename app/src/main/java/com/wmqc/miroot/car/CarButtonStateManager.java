package com.wmqc.miroot.car;

import com.wmqc.miroot.lyrics.LogHelper;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;

import org.json.JSONArray;

/**
 * 车控按钮状态机。
 * <p>职责：</p>
 * <ol>
 *   <li>管理 8 个按钮槽位的 {@link CarButtonInfo} 状态</li>
 *   <li>加载/保存按钮配置</li>
 *   <li>根据远程车辆状态同步按钮状态</li>
 *   <li>执行车控命令 + 乐观更新 + 指数退避轮询 + 超时回滚</li>
 * </ol>
 *
 * <p>设计原则：</p>
 * <ul>
 *   <li>按钮文本 = 按下去会执行的动作（如"解锁"表示执行解锁操作）</li>
 *   <li>按钮颜色 = 当前车辆状态（蓝=活跃/开启状态，白=闲置/关闭状态）</li>
 *   <li>远程 API 状态可轮询的按钮（LOCK/WINDOW/ENGINE/TRUNK）：成功后 2s→4s→8s 退避确认</li>
 *   <li>仅本地状态的按钮（AC/座椅加热）：成功后直接确认，无轮询</li>
 *   <li>单次按钮（寻车/透气）：执行后不改变状态</li>
 *   <li>超时回滚：30s 未确认则自动回滚到操作前状态</li>
 * </ul>
 */
public class CarButtonStateManager {

    private static final String TAG = "CarBtnStateMgr";

    /** 指数退避延迟序列（ms） */
    private static final long[] POLL_DELAYS_MS = {2000, 4000, 8000};
    /** 最大等待时间（ms），超过此时间未确认则强制回滚 */
    private static final long MAX_PENDING_AGE_MS = 30000;
    /** 车控参数配置键（与 SettingsActivity 保持一致） */
    private static final String CAR_CONTROL_PREFS = "CarControlPrefs";
    static final String KEY_AC_STATUS = "ac_status";
    static final String KEY_SEAT_HEATING_STATUS = "seat_heating_status";
    private static final String KEY_AC_DURATION = "ac_duration";
    private static final String KEY_AC_TEMPERATURE = "ac_temperature";
    private static final String KEY_SEAT_HEATING_DURATION = "seat_heating_duration";
    private static final String KEY_SEAT_HEATING_LEVEL = "seat_heating_level";
    private static final int DEFAULT_AC_DURATION = 10;
    private static final int DEFAULT_AC_TEMPERATURE = 22;
    private static final int DEFAULT_SEAT_HEATING_DURATION = 10;
    private static final int DEFAULT_SEAT_HEATING_LEVEL = 1;
    private static final String KEY_REAR_BUTTONS_ORDER = "rear_buttons_order";

    /** 持久化每个槽位最后确认的 remoteOn 状态 */
    private static final String KEY_REMOTE_ON_STATES = "remote_on_states";

    // ── 接口 ──

    public interface Callback {
        /** 某个按钮的显示状态发生变化，需要更新 UI */
        void onButtonStateChanged(int slotIndex);
        /** 车辆状态同步开始（刷新图标旋转等） */
        void onSyncStart();
        /** 车辆状态同步完成（刷新图标停止等） */
        void onSyncComplete();
        /** 命令执行有结果（成功/失败/超时回滚） */
        void onCommandResult(int slotIndex, boolean success, String message);
    }

    // ── 内部状态 ──

    private final CarButtonInfo[] slots = new CarButtonInfo[8];
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Callback callback;
    private Context context;

    /** 每个槽位的待确认操作（有本地 pending 时存在） */
    private final SparseArray<PendingAction> pendingActions = new SparseArray<>();

    private static class PendingAction {
        final int slotIndex;
        final CarButtonInfo.FunctionKey key;
        final boolean originalRemoteOn;  // 乐观翻转前的状态（用于回滚）
        final long createdAtMs;
        int retryCount;
        boolean cancelled;

        PendingAction(int slotIndex, CarButtonInfo.FunctionKey key, boolean originalRemoteOn) {
            this.slotIndex = slotIndex;
            this.key = key;
            this.originalRemoteOn = originalRemoteOn;
            this.createdAtMs = System.currentTimeMillis();
            this.retryCount = 0;
            this.cancelled = false;
        }
    }

    // ── 构造与生命周期 ──

    public CarButtonStateManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /** 设置回调（应在主线程调用） */
    public void setCallback(Callback cb) {
        this.callback = cb;
    }

    /** 释放资源（Activity onDestroy 时调用） */
    public void release() {
        cancelAllPending();
        handler.removeCallbacksAndMessages(null);
        callback = null;
        context = null;
    }

    // ── 配置加载与持久化 ──

    /**
     * 从 SharedPreferences 加载按钮配置（8个槽位）。
     * 优先读取 {@code rear_buttons_order} JSON 数组。
     */
    public void loadFromPrefs(SharedPreferences prefs) {
        String buttonsJson = prefs.getString(KEY_REAR_BUTTONS_ORDER, null);

        // 先清空
        for (int i = 0; i < 8; i++) {
            slots[i] = null;
        }

        if (buttonsJson != null && !buttonsJson.isEmpty()) {
            try {
                JSONArray jsonArray = new JSONArray(buttonsJson);
                LogHelper.d(TAG, "从JSON加载按钮配置，长度=" + jsonArray.length());
                for (int i = 0; i < 8 && i < jsonArray.length(); i++) {
                    String func = jsonArray.optString(i, "");
                    if (func.isEmpty()) continue;
                    CarButtonInfo info = buttonInfoFromConfigName(func, prefs);
                    if (info != null) {
                        slots[i] = info;
                        LogHelper.d(TAG, "  [" + i + "] = " + info.key + " (" + func + ")");
                    }
                }
                // 恢复上次确认的 remoteOn 状态（避免异步 API 返回前按钮显示错误状态）
                restoreRemoteOnStates(prefs);
                return;
            } catch (Exception e) {
                LogHelper.e(TAG, "解析按钮JSON失败", e);
            }
        }

        // 兜底默认配置
        LogHelper.d(TAG, "使用默认按钮配置");
        slots[0] = CarButtonInfo.fromKey(CarButtonInfo.FunctionKey.LOCK);
        slots[1] = CarButtonInfo.fromKey(CarButtonInfo.FunctionKey.FIND_CAR);
        slots[2] = CarButtonInfo.fromKey(CarButtonInfo.FunctionKey.TRUNK);
        slots[3] = CarButtonInfo.fromKey(CarButtonInfo.FunctionKey.WINDOW);
        // slots[4..7] = null

        // 恢复上次确认的 remoteOn 状态（避免异步 API 返回前默认 false 导致按钮闪烁）
        restoreRemoteOnStates(prefs);
    }

    /**
     * 从 SharedPreferences 恢复各槽位上次确认的 remoteOn 状态。
     * 仅在对应槽位已有 CarButtonInfo 时恢复，不覆盖 null 槽位。
     */
    private void restoreRemoteOnStates(SharedPreferences prefs) {
        String json = prefs.getString(KEY_REMOTE_ON_STATES, null);
        if (json == null || json.isEmpty()) return;
        try {
            JSONArray states = new JSONArray(json);
            for (int i = 0; i < 8 && i < states.length(); i++) {
                if (slots[i] != null) {
                    boolean saved = states.optBoolean(i, false);
                    slots[i].remoteOn = saved;
                    LogHelper.d(TAG, "  restore[" + i + "] " + slots[i].key + " remoteOn=" + saved);
                }
            }
        } catch (Exception e) {
            LogHelper.w(TAG, "恢复 remoteOn 状态失败", e);
        }
    }

    /**
     * 保存当前所有非 null 槽位的 remoteOn 到 SharedPreferences。
     * 在 {@link #syncFromRemote} 同步完成后调用，持久化最后确认的状态。
     */
    public void saveRemoteOnStates(SharedPreferences prefs) {
        if (prefs == null) return;
        JSONArray states = new JSONArray();
        for (int i = 0; i < 8; i++) {
            if (slots[i] != null) {
                states.put(slots[i].remoteOn);
            } else {
                states.put(false);
            }
        }
        prefs.edit().putString(KEY_REMOTE_ON_STATES, states.toString()).apply();
        LogHelper.d(TAG, "remoteOn 状态已持久化");
    }

    /**
     * 将配置名称（中文/合并名）转换为 CarButtonInfo。
     * 处理 "锁车/解锁" → LOCK、"空调" → AIR_CONDITIONER 等兼容逻辑。
     */
    private CarButtonInfo buttonInfoFromConfigName(String name, SharedPreferences prefs) {
        if (name == null || name.isEmpty()) return null;

        switch (name) {
            case "锁车/解锁":
            case "解锁":
            case "锁车":
                return CarButtonInfo.fromKey(CarButtonInfo.FunctionKey.LOCK);
            case "开窗/关窗":
            case "开窗":
            case "关窗":
                return CarButtonInfo.fromKey(CarButtonInfo.FunctionKey.WINDOW);
            case "点火/熄火":
            case "点火":
            case "熄火":
                return CarButtonInfo.fromKey(CarButtonInfo.FunctionKey.ENGINE);
            case "空调":
                return CarButtonInfo.fromKey(CarButtonInfo.FunctionKey.AIR_CONDITIONER);
            case "座椅加热":
                return CarButtonInfo.fromKey(CarButtonInfo.FunctionKey.SEAT_HEATING);
            case "主驾加热":
                return CarButtonInfo.fromKey(CarButtonInfo.FunctionKey.SEAT_HEAT_DRIVER);
            case "副驾加热":
                return CarButtonInfo.fromKey(CarButtonInfo.FunctionKey.SEAT_HEAT_PASSENGER);
            case "尾箱":
            case "开后备箱":
            case "打开尾箱":
            case "打开后备箱":
                return CarButtonInfo.fromKey(CarButtonInfo.FunctionKey.TRUNK);
            case "寻车":
                return CarButtonInfo.fromKey(CarButtonInfo.FunctionKey.FIND_CAR);
            case "透气":
                return CarButtonInfo.fromKey(CarButtonInfo.FunctionKey.VENTILATE);
            case "导航到车":
                return CarButtonInfo.fromKey(CarButtonInfo.FunctionKey.NAVIGATE);
            default:
                LogHelper.w(TAG, "未知按钮名称: " + name);
                return null;
        }
    }

    // ── 状态查询 ──

    /** 获取指定槽位的按钮信息（可能为 null） */
    public CarButtonInfo get(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= 8) return null;
        return slots[slotIndex];
    }

    /** 获取指定槽位的显示文本 */
    public String getDisplayText(int slotIndex) {
        CarButtonInfo info = get(slotIndex);
        return info != null ? info.displayText() : "";
    }

    /** 获取指定槽位的 FunctionKey */
    public CarButtonInfo.FunctionKey getFunctionKey(int slotIndex) {
        CarButtonInfo info = get(slotIndex);
        return info != null ? info.key : null;
    }

    /** 获取指定槽位的远程状态 */
    public boolean getRemoteOn(int slotIndex) {
        CarButtonInfo info = get(slotIndex);
        return info != null && info.remoteOn;
    }

    /** 是否有任何待确认的操作 */
    public boolean hasPendingActions() {
        for (int i = 0; i < pendingActions.size(); i++) {
            PendingAction a = pendingActions.valueAt(i);
            if (a != null && !a.cancelled) return true;
        }
        return false;
    }

    // ── 远程状态同步 ──

    /**
     * 从车辆状态信息同步所有按钮的 remoteOn。
     * 会清除已确认的 pending 状态。
     */
    public void syncFromRemote(VehicleStatusService.VehicleStatusInfo status, SharedPreferences prefs) {
        if (status == null) return;

        for (int i = 0; i < 8; i++) {
            CarButtonInfo info = slots[i];
            if (info == null) continue;

            if (info.localPending && info.supportsPolling()) {
                // 轮询态：检查是否已经变成预期状态
                PendingAction a = pendingActions.get(i);
                if (a != null && !a.cancelled) {
                    boolean actualOn = extractStateFromStatus(info.key, status);
                    boolean expectedOn = !a.originalRemoteOn;
                    if (actualOn == expectedOn) {
                        // 确认成功！
                        LogHelper.d(TAG, "✅ 状态确认: [" + i + "] " + info.key
                                + " remoteOn=" + actualOn + " (轮询确认)");
                        info.remoteOn = actualOn;
                        info.clearPending();
                        a.cancelled = true;
                        pendingActions.remove(i);
                        notifyStateChanged(i);
                        if (callback != null) {
                            callback.onCommandResult(i, true, "confirmed");
                        }
                    } else {
                        // 尚未确认，保持 pending
                        LogHelper.d(TAG, "⏳ 状态未变: [" + i + "] " + info.key
                                + " actual=" + actualOn + " expected=" + expectedOn
                                + " retry=" + info.pollRetryCount);
                    }
                }
            } else if (!info.localPending) {
                // 非 pending 态：直接同步远程状态
                boolean newVal = resolveStateFromPrefsOrStatus(info.key, status, prefs);
                if (info.remoteOn != newVal) {
                    info.remoteOn = newVal;
                    notifyStateChanged(i);
                }
            }
            // pending 但不可轮询的（AC/座椅加热）：不处理，等待命令结果
        }

        // 同步完成后持久化已确认的状态
        saveRemoteOnStates(prefs);
    }

    /**
     * 从车辆状态提取某个功能键的 boolean 状态（=远程真实状态）。
     */
    private static boolean extractStateFromStatus(CarButtonInfo.FunctionKey key,
                                                   VehicleStatusService.VehicleStatusInfo status) {
        if (status == null || key == null) return false;
        switch (key) {
            case LOCK:
                String lock = VehicleStatusService.translateDoorLockStatus(status.doorLockStatusDriver);
                return "已锁".equals(lock);
            case WINDOW:
                String win = status.winStatusDriver;
                return "已开".equals(win);
            case ENGINE:
                String eng = VehicleStatusService.translateEngineStatus(status.engineStatus);
                return "运行中".equals(eng);
            case TRUNK:
                String trunk = VehicleStatusService.translateTrunkStatus(status.trunkOpenStatus);
                return "已开".equals(trunk);
            case VENTILATE:
                // 透气 = 车窗位置非0且未全开（一条缝模式）
                return isVentilationPosition(status.winPosDriver);
            default:
                return false;
        }
    }

    /**
     * 判断车窗位置值是否处于透气模式（一条缝）。
     * API 中 winPosDriver=0 表示关闭，非0表示打开。
     * 具体阈值需根据实际返回值调整：0=关，1~50=透气，>50=全开。
     */
    private static boolean isVentilationPosition(String winPosDriver) {
        if (winPosDriver == null || winPosDriver.isEmpty() || "未知".equals(winPosDriver)) {
            return false;
        }
        try {
            int pos = Integer.parseInt(winPosDriver);
            return pos > 0 && pos <= 50;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 解析某功能的远程状态：优先从 prefs（本地维护状态），否则从 API 状态对象提取。
     */
    static boolean resolveStateFromPrefsOrStatus(CarButtonInfo.FunctionKey key,
                                                  VehicleStatusService.VehicleStatusInfo status,
                                                  SharedPreferences prefs) {
        switch (key) {
            case AIR_CONDITIONER:
                return prefs != null && prefs.getBoolean(KEY_AC_STATUS, false);
            case SEAT_HEATING:
                return prefs != null && prefs.getBoolean(KEY_SEAT_HEATING_STATUS, false);
            case SEAT_HEAT_DRIVER:
            case SEAT_HEAT_PASSENGER:
                return false; // always show "off" initially
            default:
                return extractStateFromStatus(key, status);
        }
    }

    // ── 命令执行 ──

    /**
     * 执行车控命令。
     * <ol>
     *   <li>记录操作前状态（用于回滚）</li>
     *   <li>确定要执行的命令（根据操作前状态决定 unlock/lock 等）</li>
     *   <li>乐观翻转按钮状态并通知 UI</li>
     *   <li>在工作线程调用 VehicleControlService 执行具体命令</li>
     *   <li>成功后可轮询的功能启动指数退避状态确认</li>
     *   <li>失败或轮询超时触发回滚</li>
     * </ol>
     */
    public void execute(int slotIndex, Context ctx, SharedPreferences prefs) {
        if (slotIndex < 0 || slotIndex >= 8) return;
        CarButtonInfo info = slots[slotIndex];
        if (info == null) return;
        if (info.localPending) {
            LogHelper.w(TAG, "⚠ [" + slotIndex + "] " + info.key + " 已有待确认操作，忽略重复执行");
            return;
        }

        // 关键：在乐观翻转前记录原始状态，并用它来确定要执行的命令
        final boolean originalRemoteOn = info.remoteOn;

        // 乐观翻转（ONE_SHOT 不翻转）
        if (!info.key.isOneShot) {
            info.remoteOn = !info.remoteOn;
        }
        info.localPending = true;
        info.pendingSinceMs = System.currentTimeMillis();
        info.pollRetryCount = 0;

        // 注册 pending action（保存原始状态供回滚使用）
        PendingAction action = new PendingAction(slotIndex, info.key, originalRemoteOn);
        pendingActions.put(slotIndex, action);

        notifyStateChanged(slotIndex);
        LogHelper.d(TAG, "乐观翻转: [" + slotIndex + "] " + info.key
                + " " + (info.key.isOneShot ? "(one-shot)" : originalRemoteOn + "->" + info.remoteOn));

        // 后台执行命令
        final Context appCtx = context != null ? context : ctx.getApplicationContext();
        final CarButtonInfo.FunctionKey finalKey = info.key;
        new Thread(() -> {
            VehicleControlService.ControlResult result;
            try {
                result = executeCommand(finalKey, originalRemoteOn, appCtx, prefs);
            } catch (Exception e) {
                LogHelper.e(TAG, "执行命令异常", e);
                result = new VehicleControlService.ControlResult();
                result.success = false;
                result.message = "执行异常: " + e.getMessage();
            }

            final VehicleControlService.ControlResult finalResult = result;
            handler.post(() -> handleCommandResult(slotIndex, finalResult, prefs));
        }).start();
    }

    /**
     * 在工作线程执行具体车控命令。
     * 根据 {@code originalRemoteOn}（翻转前的状态）决定执行开启还是关闭操作。
     */
    private VehicleControlService.ControlResult executeCommand(
            CarButtonInfo.FunctionKey key, boolean originalRemoteOn, Context ctx, SharedPreferences prefs) {

        switch (key) {
            case LOCK:
                // originalRemoteOn=true（车已锁）-> 执行解锁；false -> 执行锁车
                return originalRemoteOn
                        ? VehicleControlService.unlock(ctx)
                        : VehicleControlService.lock(ctx);
            case WINDOW:
                // originalRemoteOn=true（窗已开）-> 执行关窗；false -> 执行开窗
                return originalRemoteOn
                        ? VehicleControlService.closeWindow(ctx)
                        : VehicleControlService.openWindow(ctx);
            case ENGINE:
                // originalRemoteOn=true（已点火）-> 执行熄火；false -> 执行点火
                return originalRemoteOn
                        ? VehicleControlService.stopEngine(ctx)
                        : VehicleControlService.startEngine(ctx, 10); // 默认10分钟
            case AIR_CONDITIONER:
                int acDuration = prefs != null
                        ? prefs.getInt(KEY_AC_DURATION, DEFAULT_AC_DURATION) : DEFAULT_AC_DURATION;
                int acTemp = prefs != null
                        ? prefs.getInt(KEY_AC_TEMPERATURE, DEFAULT_AC_TEMPERATURE) : DEFAULT_AC_TEMPERATURE;
                return originalRemoteOn
                        ? VehicleControlService.closeAirConditioner(ctx)
                        : VehicleControlService.openAirConditioner(ctx, acDuration, acTemp);
            case SEAT_HEATING:
                int shDuration = prefs != null
                        ? prefs.getInt(KEY_SEAT_HEATING_DURATION, DEFAULT_SEAT_HEATING_DURATION)
                        : DEFAULT_SEAT_HEATING_DURATION;
                int shLevel = prefs != null
                        ? prefs.getInt(KEY_SEAT_HEATING_LEVEL, DEFAULT_SEAT_HEATING_LEVEL)
                        : DEFAULT_SEAT_HEATING_LEVEL;
                return originalRemoteOn
                        ? VehicleControlService.closeSeatHeating(ctx)
                        : VehicleControlService.openSeatHeating(ctx, shDuration, shLevel);
            case SEAT_HEAT_DRIVER:
                int dDuration = prefs != null
                        ? prefs.getInt(KEY_SEAT_HEATING_DURATION, DEFAULT_SEAT_HEATING_DURATION)
                        : DEFAULT_SEAT_HEATING_DURATION;
                int dLevel = prefs != null
                        ? prefs.getInt(KEY_SEAT_HEATING_LEVEL, DEFAULT_SEAT_HEATING_LEVEL)
                        : DEFAULT_SEAT_HEATING_LEVEL;
                return originalRemoteOn
                        ? VehicleControlService.closeDriverSeatHeating(ctx)
                        : VehicleControlService.openDriverSeatHeating(ctx, dDuration, dLevel);
            case SEAT_HEAT_PASSENGER:
                int pDuration = prefs != null
                        ? prefs.getInt(KEY_SEAT_HEATING_DURATION, DEFAULT_SEAT_HEATING_DURATION)
                        : DEFAULT_SEAT_HEATING_DURATION;
                int pLevel = prefs != null
                        ? prefs.getInt(KEY_SEAT_HEATING_LEVEL, DEFAULT_SEAT_HEATING_LEVEL)
                        : DEFAULT_SEAT_HEATING_LEVEL;
                return originalRemoteOn
                        ? VehicleControlService.closePassengerSeatHeating(ctx)
                        : VehicleControlService.openPassengerSeatHeating(ctx, pDuration, pLevel);
            case TRUNK:
                return VehicleControlService.openTrunk(ctx);
            case FIND_CAR:
                return VehicleControlService.findCar(ctx);
            case VENTILATE:
                return VehicleControlService.ventilate(ctx);
            case NAVIGATE:
                return VehicleControlService.navigateToCar(ctx);
            default:
                VehicleControlService.ControlResult r = new VehicleControlService.ControlResult();
                r.success = false;
                r.message = "未知功能: " + key;
                return r;
        }
    }

    // ── 命令结果处理 ──

    private void handleCommandResult(int slotIndex, VehicleControlService.ControlResult result,
                                      SharedPreferences prefs) {
        CarButtonInfo info = slots[slotIndex];
        if (info == null) return;

        PendingAction action = pendingActions.get(slotIndex);
        if (action == null || action.cancelled) return;

        if (result.success) {
            LogHelper.d(TAG, "命令执行成功: [" + slotIndex + "] " + info.key);

            // 立即通知用户命令已执行（长震动反馈），无需等轮询确认
            if (callback != null) {
                callback.onCommandResult(slotIndex, true, null);
            }

            if (info.supportsPolling()) {
                // 远程可轮询状态：启动指数退避确认
                schedulePoll(slotIndex, prefs);
            } else {
                // 不可轮询（AC/座椅加热/单次开启）：直接确认
                info.clearPending();
                pendingActions.remove(slotIndex);
                saveLocalState(info.key, info.remoteOn, prefs);
                notifyStateChanged(slotIndex);
                if (callback != null) {
                    callback.onCommandResult(slotIndex, true, null);
                }
            }
        } else {
            // 命令调用失败 -> 立即回滚
            LogHelper.w(TAG, "命令执行失败: [" + slotIndex + "] " + info.key
                    + " msg=" + result.message);
            rollback(slotIndex, result.message);
        }

        // 顺便检查是否有其它 pending 超时
        checkTimeouts();
    }

    // ── 指数退避轮询 ──

    /**
     * 轮询时只检查指定槽位的状态，不触碰其他按钮。
     * 与 {@link #syncFromRemote} 不同，此方法仅检查正在轮询的单槽，防止串联按钮错误改变状态。
     */
    private void pollSlot(int slotIndex, VehicleStatusService.VehicleStatusInfo status, SharedPreferences prefs) {
        CarButtonInfo info = slots[slotIndex];
        if (info == null || !info.localPending || !info.supportsPolling()) return;

        PendingAction a = pendingActions.get(slotIndex);
        if (a == null || a.cancelled) return;

        boolean actualOn = extractStateFromStatus(info.key, status);
        boolean expectedOn = !a.originalRemoteOn;
        if (actualOn == expectedOn) {
            LogHelper.d(TAG, "✅ 状态确认: [" + slotIndex + "] " + info.key
                    + " remoteOn=" + actualOn + " (轮询确认)");
            info.remoteOn = actualOn;
            info.clearPending();
            a.cancelled = true;
            pendingActions.remove(slotIndex);
            notifyStateChanged(slotIndex);
            saveRemoteOnStates(prefs);
            if (callback != null) {
                callback.onCommandResult(slotIndex, true, "confirmed");
            }
        } else {
            LogHelper.d(TAG, "⏳ 状态未变: [" + slotIndex + "] " + info.key
                    + " actual=" + actualOn + " expected=" + expectedOn
                    + " retry=" + info.pollRetryCount);
        }
    }

    private void schedulePoll(int slotIndex, SharedPreferences prefs) {
        CarButtonInfo info = slots[slotIndex];
        PendingAction action = pendingActions.get(slotIndex);
        if (action == null || action.cancelled) return;

        // 检查全局超时
        if (isTimedOut(action)) {
            rollback(slotIndex, "操作超时");
            return;
        }

        // 检查重试次数上限
        if (action.retryCount >= POLL_DELAYS_MS.length) {
            rollback(slotIndex, "状态确认超时");
            return;
        }

        long delay = POLL_DELAYS_MS[action.retryCount];
        action.retryCount++;
        info.pollRetryCount = action.retryCount;

        LogHelper.d(TAG, "轮询 [" + slotIndex + "] " + info.key
                + " 第" + action.retryCount + "次, " + delay + "ms后");

        handler.postDelayed(() -> {
            if (action.cancelled) return;

            try {
                VehicleStatusService.VehicleStatusInfo status =
                        VehicleStatusService.getVehicleStatus(context);
                CarVehicleDisplayCache.save(context, status);
                // 只检查当前轮询的按钮，不触碰其他按钮以防串扰
                pollSlot(slotIndex, status, prefs);
            } catch (Exception e) {
                LogHelper.e(TAG, "轮询异常", e);
            }

            // 若仍未确认，继续下一轮轮询
            if (!action.cancelled && info.localPending) {
                schedulePoll(slotIndex, prefs);
            }
        }, delay);
    }

    // ── 回滚 ──

    private void rollback(int slotIndex, String message) {
        CarButtonInfo info = slots[slotIndex];
        PendingAction action = pendingActions.get(slotIndex);
        if (action == null || action.cancelled) return;

        LogHelper.w(TAG, "回滚: [" + slotIndex + "] " + info.key
                + " " + info.remoteOn + "->" + action.originalRemoteOn
                + " reason=" + message);

        info.remoteOn = action.originalRemoteOn;
        info.clearPending();
        action.cancelled = true;
        pendingActions.remove(slotIndex);
        notifyStateChanged(slotIndex);

        if (callback != null) {
            callback.onCommandResult(slotIndex, false, message);
        }
    }

    // ── 超时检查 ──

    private boolean isTimedOut(PendingAction action) {
        return System.currentTimeMillis() - action.createdAtMs > MAX_PENDING_AGE_MS;
    }

    private void checkTimeouts() {
        for (int i = 0; i < pendingActions.size(); i++) {
            PendingAction a = pendingActions.valueAt(i);
            if (a != null && !a.cancelled && isTimedOut(a)) {
                rollback(a.slotIndex, "操作超时");
            }
        }
    }

    private void cancelAllPending() {
        for (int i = 0; i < pendingActions.size(); i++) {
            PendingAction a = pendingActions.valueAt(i);
            if (a != null) {
                a.cancelled = true;
            }
        }
        pendingActions.clear();
    }

    // ── 本地状态持久化 ──

    /**
     * 保存仅本地维护的状态到 SharedPreferences（AC、座椅加热）。
     */
    private void saveLocalState(CarButtonInfo.FunctionKey key, boolean newState,
                                 SharedPreferences prefs) {
        if (prefs == null) return;
        switch (key) {
            case AIR_CONDITIONER:
                prefs.edit().putBoolean(KEY_AC_STATUS, newState).apply();
                LogHelper.d(TAG, "保存空调状态: " + newState);
                break;
            case SEAT_HEATING:
                prefs.edit().putBoolean(KEY_SEAT_HEATING_STATUS, newState).apply();
                LogHelper.d(TAG, "保存座椅加热状态: " + newState);
                break;
            default:
                break;
        }
    }

    // ── UI 通知 ──

    private void notifyStateChanged(int slotIndex) {
        if (callback != null) {
            callback.onButtonStateChanged(slotIndex);
        }
    }
}
