package com.wmqc.miroot.car;

import com.wmqc.miroot.lyrics.LogHelper;
import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 车控功能广播执行入口（后台调用，不依赖打开车控投屏UI）。
 *
 * 通过 {@link CarControlIntents#ACTION_CAR_CONTROL_COMMAND}（或 {@link CarControlIntents#ACTION_CAR_CONTROL_COMMAND_MIROOT}）广播唤起，
 * 内部调用 {@link VehicleControlService} 的对应方法执行车控指令。
 *
 * <p>外部主题（如 MAML）集成时可将流程拆为两部分：<b>发送</b>侧在 {@code Trigger} 中用
 * {@code IntentCommand broadcast="true"} 发出本 Action 并携带下方 Extra；<b>接收</b>侧在
 * {@code VariableBinders} 中用 {@code BroadcastBinder} 监听回执
 * {@link CarControlIntents#ACTION_CAR_CONTROL_COMMAND_RESULT}（或由 {@link #EXTRA_REPLY_ACTION} 指定，可为 {@link CarControlIntents#ACTION_CAR_CONTROL_COMMAND_RESULT_MIROOT}），
 * 将 {@code success}、{@code message}、{@link #EXTRA_CALLBACK_PHASE}、{@code data}、{@code postStatus} 等绑定到主题变量。详见 {@code 车控主题对接说明.md}。
 *
 * <p>回执顺序：① {@link #CALLBACK_PHASE_RECEIVED}（收到请求广播后立即发送，表示本服务已受理）；② 失败/超时为
 * {@link #CALLBACK_PHASE_EXECUTION}；成功则在拉取快照后发送 {@link #CALLBACK_PHASE_VEHICLE_STATUS}（完整车辆信息）。</p>
 */
public class CarControlCommandService extends IntentService {
    private static final String TAG = "CarControlCommandService";

    public static final String EXTRA_FUNCTION = "function";
    public static final String EXTRA_DURATION_MINUTES = "durationMinutes";
    public static final String EXTRA_TEMPERATURE = "temperature";
    public static final String EXTRA_LEVEL = "level";
    public static final String EXTRA_SEAT = "seat"; // all|driver|passenger
    public static final String EXTRA_REQUEST_ID = "requestId";
    public static final String EXTRA_REPLY_ACTION = "replyAction";
    public static final String EXTRA_TIMEOUT_MS = "timeoutMs";
    public static final String EXTRA_SUCCESS_VIBRATE_MS = "successVibrateMs";
    /** 回执阶段 Extra 键名 */
    public static final String EXTRA_CALLBACK_PHASE = "callbackPhase";
    /** 收到车控请求广播后立即发送，表示本服务已收到（尚未完成远程执行） */
    public static final String CALLBACK_PHASE_RECEIVED = "received";
    /** 指令执行失败、超时或未知功能等（{@code data}/{@code postStatus} 为空） */
    public static final String CALLBACK_PHASE_EXECUTION = "execution";
    /** 指令执行成功并拉取到车辆状态后发送，{@code data}/{@code postStatus} 为完整 JSON */
    public static final String CALLBACK_PHASE_VEHICLE_STATUS = "vehicleStatus";

    // 默认回执 action（如果调用方不传 replyAction）
    public static final String DEFAULT_REPLY_ACTION = CarControlIntents.ACTION_CAR_CONTROL_COMMAND_RESULT;
    private static final long DEFAULT_TIMEOUT_MS = 15000;
    // 收到车控指令后的短震（已受理反馈）
    private static final long RECEIVE_ACK_VIBRATE_MS = 60;
    // “执行成功长震动”默认时长（调用方仍可通过 EXTRA_SUCCESS_VIBRATE_MS 覆盖）
    private static final long DEFAULT_SUCCESS_VIBRATE_MS = 550;

    public CarControlCommandService() {
        super("CarControlCommandService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        String action = intent.getAction();
        if (!CarControlIntents.isCarControlCommandServiceAction(action)) {
            return;
        }

        if (!CarControlDeviceGate.isAllowed(this)) {
            String requestId = safeString(intent.getStringExtra(EXTRA_REQUEST_ID));
            String replyAction = safeString(intent.getStringExtra(EXTRA_REPLY_ACTION));
            if (replyAction.isEmpty()) {
                replyAction = DEFAULT_REPLY_ACTION;
            }
            VehicleControlService.ControlResult denied = new VehicleControlService.ControlResult();
            denied.success = false;
            denied.message = "当前设备未授权使用车控";
            sendResultBroadcast(
                    replyAction,
                    requestId,
                    "",
                    "",
                    -1,
                    -1,
                    -1,
                    denied,
                    CALLBACK_PHASE_EXECUTION,
                    ""
            );
            LogHelper.w(TAG, "设备未授权，拒绝执行车控指令");
            return;
        }

        String function = safeString(intent.getStringExtra(EXTRA_FUNCTION));
        String seat = safeString(intent.getStringExtra(EXTRA_SEAT));
        int durationMinutes = intent.getIntExtra(EXTRA_DURATION_MINUTES, -1);
        int temperature = intent.getIntExtra(EXTRA_TEMPERATURE, -1);
        int level = intent.getIntExtra(EXTRA_LEVEL, -1);
        String requestId = safeString(intent.getStringExtra(EXTRA_REQUEST_ID));
        String replyAction = safeString(intent.getStringExtra(EXTRA_REPLY_ACTION));
        if (replyAction.isEmpty()) {
            replyAction = DEFAULT_REPLY_ACTION;
        }
        long timeoutMs = intent.getLongExtra(EXTRA_TIMEOUT_MS, DEFAULT_TIMEOUT_MS);
        if (timeoutMs <= 0) {
            timeoutMs = DEFAULT_TIMEOUT_MS;
        }
        long successVibrateMs = intent.getLongExtra(EXTRA_SUCCESS_VIBRATE_MS, DEFAULT_SUCCESS_VIBRATE_MS);
        if (successVibrateMs <= 0) {
            successVibrateMs = DEFAULT_SUCCESS_VIBRATE_MS;
        }

        LogHelper.d(TAG, "📡 收到车控指令: function=" + function + ", seat=" + seat +
                ", durationMinutes=" + durationMinutes + ", temperature=" + temperature +
                ", level=" + level + ", requestId=" + requestId);

        VibrationHelper.vibrateOneShot(this, RECEIVE_ACK_VIBRATE_MS, "收到车控短震失败");

        // 第一次回执：仅表示已收到车控广播（尚未完成接口执行）
        sendReceivedAck(replyAction, requestId, function, seat, durationMinutes, temperature, level);

        if (function.trim().isEmpty()) {
            VehicleControlService.ControlResult missing = new VehicleControlService.ControlResult();
            missing.success = false;
            missing.message = "缺少 function 参数";
            sendResultBroadcast(replyAction, requestId, function, seat, durationMinutes, temperature, level,
                    missing, CALLBACK_PHASE_EXECUTION, "");
            return;
        }

        AtomicBoolean replied = new AtomicBoolean(false);

        // 显式拷贝到final变量，确保兼容旧版编译器对lambda的要求
        final String finalFunction = function;
        final String finalSeat = seat;
        final int finalDurationMinutes = durationMinutes;
        final int finalTemperature = temperature;
        final int finalLevel = level;
        final String finalRequestId = requestId;
        final String finalReplyAction = replyAction;
        final long finalSuccessVibrateMs = successVibrateMs;

        Thread worker = new Thread(() -> {
            VehicleControlService.ControlResult result;
            try {
                result = execute(finalFunction, finalSeat, finalDurationMinutes, finalTemperature, finalLevel);
            } catch (Exception e) {
                LogHelper.e(TAG, "执行车控指令异常", e);
                result = new VehicleControlService.ControlResult();
                result.success = false;
                result.message = "执行异常: " + e.getMessage();
            }

            if (!replied.compareAndSet(false, true)) {
                return;
            }

            // 执行成功：长震一次（不依赖调用方/MAML能力）
            if (result != null && result.success) {
                VibrationHelper.vibrateOneShot(CarControlCommandService.this, finalSuccessVibrateMs, "执行成功长震失败");
                // 成功：不再单独发 execution；异步拉取完整车辆信息后发 vehicleStatus
                final VehicleControlService.ControlResult finalResult = result;
                new Thread(() -> {
                    String postStatusJson = buildVehicleStatusSnapshotJsonSafely();
                    sendResultBroadcast(finalReplyAction, finalRequestId, finalFunction, finalSeat,
                            finalDurationMinutes, finalTemperature, finalLevel, finalResult,
                            CALLBACK_PHASE_VEHICLE_STATUS, postStatusJson);
                }, "CarControlPostStatus").start();
            } else {
                // 失败：发送 execution（success=false，无完整车辆 JSON）
                sendResultBroadcast(finalReplyAction, finalRequestId, finalFunction, finalSeat,
                        finalDurationMinutes, finalTemperature, finalLevel, result,
                        CALLBACK_PHASE_EXECUTION, "");
            }
        }, "CarControlCmdWorker");

        worker.start();

        try {
            worker.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (worker.isAlive() && replied.compareAndSet(false, true)) {
            LogHelper.w(TAG, "⏱️ 车控指令执行超时，发送失败回执并中断执行线程: timeoutMs=" + timeoutMs + ", requestId=" + finalRequestId);
            VehicleControlService.ControlResult timeoutResult = new VehicleControlService.ControlResult();
            timeoutResult.success = false;
            timeoutResult.message = "执行超时";
            sendResultBroadcast(finalReplyAction, finalRequestId, finalFunction, finalSeat,
                    finalDurationMinutes, finalTemperature, finalLevel, timeoutResult,
                    CALLBACK_PHASE_EXECUTION, "");
            try {
                worker.interrupt();
            } catch (Exception ignored) {}
        }
    }

    private VehicleControlService.ControlResult execute(
            String function,
            String seat,
            int durationMinutes,
            int temperature,
            int level
    ) {
        // 兼容UI上的中文按钮名称 + 英文/短命令别名
        String f = normalizeFunction(function);

        switch (f) {
            case "unlock":
                return VehicleControlService.unlock(this);
            case "lock":
                return VehicleControlService.lock(this);
            case "findCar":
                return VehicleControlService.findCar(this);
            case "openTrunk":
                return VehicleControlService.openTrunk(this);
            case "openWindow":
                return VehicleControlService.openWindow(this);
            case "closeWindow":
                return VehicleControlService.closeWindow(this);
            case "ventilate":
                return VehicleControlService.ventilate(this);
            case "startEngine": {
                int d = durationMinutes > 0 ? durationMinutes : 10;
                return VehicleControlService.startEngine(this, d);
            }
            case "stopEngine":
                return VehicleControlService.stopEngine(this);
            case "openAirConditioner": {
                int d = durationMinutes > 0 ? durationMinutes : 10;
                int t = temperature > 0 ? temperature : 24;
                return VehicleControlService.openAirConditioner(this, d, t);
            }
            case "closeAirConditioner":
                return VehicleControlService.closeAirConditioner(this);
            case "openSeatHeatingDriver": {
                int d = durationMinutes > 0 ? durationMinutes : 10;
                int l = level > 0 ? level : 2;
                return VehicleControlService.openDriverSeatHeating(this, d, l);
            }
            case "openSeatHeatingPassenger": {
                int d = durationMinutes > 0 ? durationMinutes : 10;
                int l = level > 0 ? level : 2;
                return VehicleControlService.openPassengerSeatHeating(this, d, l);
            }
            case "openSeatHeating": {
                int d = durationMinutes > 0 ? durationMinutes : 10;
                int l = level > 0 ? level : 2;
                String s = seat.isEmpty() ? "all" : seat;
                if ("driver".equalsIgnoreCase(s)) {
                    return VehicleControlService.openDriverSeatHeating(this, d, l);
                }
                if ("passenger".equalsIgnoreCase(s)) {
                    return VehicleControlService.openPassengerSeatHeating(this, d, l);
                }
                return VehicleControlService.openSeatHeating(this, d, l);
            }
            case "closeSeatHeating": {
                String s = seat.isEmpty() ? "all" : seat;
                if ("driver".equalsIgnoreCase(s)) {
                    return VehicleControlService.closeDriverSeatHeating(this);
                }
                if ("passenger".equalsIgnoreCase(s)) {
                    return VehicleControlService.closePassengerSeatHeating(this);
                }
                return VehicleControlService.closeSeatHeating(this);
            }
            case "navigateToCar":
                return VehicleControlService.navigateToCar(this);
            default: {
                VehicleControlService.ControlResult r = new VehicleControlService.ControlResult();
                r.success = false;
                r.message = "未知功能: " + function;
                return r;
            }
        }
    }

    /**
     * 发送车控指令回执。
     * <p>与 {@link VehicleStatusQueryService} 相同：回执<b>不</b>限定 {@code setPackage(本应用)}，
     * 以便第三方主题进程中的 {@code BroadcastBinder} 能收到 {@code postStatus} 等字段。</p>
     * <p>{@code success} 使用字符串 {@code true}/{@code false}，供 MAML {@code Variable type="string" extra="success"} 读取。</p>
     * <p>{@link #CALLBACK_PHASE_RECEIVED}：已受理请求；{@link #CALLBACK_PHASE_EXECUTION}：执行失败/超时；{@link #CALLBACK_PHASE_VEHICLE_STATUS}：执行成功后的完整车辆 JSON。</p>
     */
    private void sendReceivedAck(
            String replyAction,
            String requestId,
            String function,
            String seat,
            int durationMinutes,
            int temperature,
            int level
    ) {
        VehicleControlService.ControlResult ack = new VehicleControlService.ControlResult();
        ack.success = true;
        ack.message = "已收到车控指令";
        sendResultBroadcast(replyAction, requestId, function, seat, durationMinutes, temperature, level,
                ack, CALLBACK_PHASE_RECEIVED, "");
    }

    private void sendResultBroadcast(
            String replyAction,
            String requestId,
            String function,
            String seat,
            int durationMinutes,
            int temperature,
            int level,
            VehicleControlService.ControlResult result,
            String callbackPhase,
            String postStatusJson
    ) {
        try {
            Intent reply = new Intent(replyAction);
            reply.putExtra(EXTRA_REQUEST_ID, requestId);
            reply.putExtra(EXTRA_FUNCTION, function);
            reply.putExtra(EXTRA_SEAT, seat);
            reply.putExtra(EXTRA_DURATION_MINUTES, durationMinutes);
            reply.putExtra(EXTRA_TEMPERATURE, temperature);
            reply.putExtra(EXTRA_LEVEL, level);
            reply.putExtra(EXTRA_CALLBACK_PHASE, safeString(callbackPhase));
            reply.putExtra("success", (result != null && result.success) ? "true" : "false");
            reply.putExtra("message", result != null ? safeString(result.message) : "");
            String json = safeString(postStatusJson);
            reply.putExtra("postStatus", json);
            // 与 VehicleStatusQueryService 回执字段名对齐，便于主题统一解析 data / postStatus
            reply.putExtra("data", CALLBACK_PHASE_VEHICLE_STATUS.equals(callbackPhase) ? json : "");
            getApplicationContext().sendBroadcast(reply);
            LogHelper.d(TAG, "📤 已发送车控回执: requestId=" + safeString(requestId)
                    + ", function=" + safeString(function)
                    + ", phase=" + safeString(callbackPhase)
                    + ", success=" + (result != null && result.success)
                    + ", postStatusLen=" + json.length());
        } catch (Exception e) {
            LogHelper.e(TAG, "发送车控回执广播失败", e);
        }
    }

    /**
     * 与 {@link VehicleStatusQueryService} 回执字段 {@code data} 使用同一套 JSON（
     * {@link VehicleStatusService#buildVehicleQueryBroadcastJson}），便于主题对
     * {@code postStatus} 与查询结果复用解析逻辑。
     */
    private String buildVehicleStatusSnapshotJsonSafely() {
        try {
            VehicleStatusService.VehicleStatusInfo status = VehicleStatusService.getVehicleStatus(this);
            return VehicleStatusService.buildVehicleQueryBroadcastJson(status).toString();
        } catch (Exception e) {
            LogHelper.w(TAG, "拉取车控后车辆状态快照失败: " + e.getMessage());
            return "";
        }
    }

    private static String safeString(String s) {
        return s == null ? "" : s;
    }

    private static String normalizeFunction(String function) {
        String f = safeString(function).trim();
        if (f.isEmpty()) return "";

        // UI中文
        switch (f) {
            case "解锁":
                return "unlock";
            case "锁车":
                return "lock";
            case "寻车":
                return "findCar";
            case "尾箱":
            case "开后备箱":
            case "打开尾箱":
            case "打开后备箱":
                return "openTrunk";
            case "开窗":
                return "openWindow";
            case "关窗":
                return "closeWindow";
            case "透气":
                return "ventilate";
            case "点火":
                return "startEngine";
            case "熄火":
                return "stopEngine";
            case "打开空调":
                return "openAirConditioner";
            case "关闭空调":
                return "closeAirConditioner";
            case "打开座椅加热":
            case "座椅加热":
                return "openSeatHeating";
            case "关闭座椅加热":
                return "closeSeatHeating";
            case "主驾加热":
                return "openSeatHeatingDriver";
            case "副驾加热":
                return "openSeatHeatingPassenger";
            case "导航到车":
                return "navigateToCar";
            default:
                break;
        }

        // 英文/别名（大小写不敏感）
        String lower = f.toLowerCase();
        if (lower.equals("unlock")) return "unlock";
        if (lower.equals("lock")) return "lock";
        if (lower.equals("find") || lower.equals("findcar") || lower.equals("horn")) return "findCar";
        if (lower.equals("trunk") || lower.equals("opentrunk")) return "openTrunk";
        if (lower.equals("openwindow")) return "openWindow";
        if (lower.equals("closewindow")) return "closeWindow";
        if (lower.equals("ventilate")) return "ventilate";
        if (lower.equals("startengine") || lower.equals("engineon")) return "startEngine";
        if (lower.equals("stopengine") || lower.equals("engineoff")) return "stopEngine";
        if (lower.equals("ac_on") || lower.equals("openac") || lower.equals("openairconditioner")) return "openAirConditioner";
        if (lower.equals("ac_off") || lower.equals("closeac") || lower.equals("closeairconditioner")) return "closeAirConditioner";
        if (lower.equals("seatheat_on") || lower.equals("openseatheating")) return "openSeatHeating";
        if (lower.equals("seatheat_driver") || lower.equals("openseatheating_driver") || lower.equals("driverheat")
                || lower.equals("openseatheatingdriver")) {
            return "openSeatHeatingDriver";
        }
        if (lower.equals("seatheat_passenger") || lower.equals("openseatheating_passenger") || lower.equals("passengerheat")
                || lower.equals("openseatheatingpassenger")) {
            return "openSeatHeatingPassenger";
        }
        if (lower.equals("seatheat_off") || lower.equals("closeseatheating")) return "closeSeatHeating";
        if (lower.equals("navigatetocar")) return "navigateToCar";

        return f;
    }
}

