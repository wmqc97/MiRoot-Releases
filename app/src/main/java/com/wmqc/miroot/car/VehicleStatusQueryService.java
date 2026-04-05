/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 *
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */
package com.wmqc.miroot.car;

import com.wmqc.miroot.lyrics.LogHelper;
import android.app.IntentService;
import android.content.Intent;
import org.json.JSONObject;

/**
 * 车辆状态查询服务（通过广播触发，返回车辆基础信息+状态信息）。
 *
 * 入口广播：
 * - {@link CarControlIntents#ACTION_QUERY_VEHICLE_STATUS} 或 {@link CarControlIntents#ACTION_QUERY_VEHICLE_STATUS_MIROOT}
 *
 * 回执广播（默认）：
 * - {@link CarControlIntents#ACTION_VEHICLE_STATUS_RESULT}；自定义 {@code replyAction} 时可为
 *   {@link CarControlIntents#ACTION_VEHICLE_STATUS_RESULT_MIROOT}
 *
 * <p>优化：若距上次<b>成功</b>拉取不足 5 秒，则直接复用缓存 JSON 回执，
 * 避免主题端高频轮询造成重复 HTTP。（设置页等直接调 {@link VehicleStatusService} 的路径不受影响。）</p>
 */
public class VehicleStatusQueryService extends IntentService {
    private static final String TAG = "VehicleStatusQuerySvc";
    private static final int LOG_JSON_MAX_LEN = 800;

    /** 主题/外部高频轮询时复用最近一次成功结果，减少重复 HTTP */
    private static final Object sStatusCacheLock = new Object();
    private static final long MIN_STATUS_FETCH_INTERVAL_MS = 5000;
    private static long sLastStatusFetchEndMs = 0;
    private static String sCachedStatusJson = "";

    public static final String EXTRA_REQUEST_ID = "requestId";
    public static final String EXTRA_REPLY_ACTION = "replyAction";

    public static final String DEFAULT_REPLY_ACTION = CarControlIntents.ACTION_VEHICLE_STATUS_RESULT;

    public VehicleStatusQueryService() {
        super("VehicleStatusQueryService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        if (!CarControlIntents.isQueryVehicleStatusServiceAction(intent.getAction())) {
            return;
        }

        String requestId = safeString(intent.getStringExtra(EXTRA_REQUEST_ID));
        String replyAction = safeString(intent.getStringExtra(EXTRA_REPLY_ACTION));
        if (replyAction.isEmpty()) {
            replyAction = DEFAULT_REPLY_ACTION;
        }

        LogHelper.d(TAG, "📡 收到车辆状态查询请求: requestId=" + requestId + ", replyAction=" + replyAction);

        boolean success = false;
        String message = "";
        String statusJson = "";

        VehicleControlService.VehicleParams params = VehicleControlService.extractVehicleParams(this);
        if (!params.isValid()) {
            sendResultBroadcast(replyAction, requestId, false, "请先登录或车辆信息未绑定", "");
            return;
        }

        synchronized (sStatusCacheLock) {
            long now = System.currentTimeMillis();
            if (!sCachedStatusJson.isEmpty()
                    && (now - sLastStatusFetchEndMs) < MIN_STATUS_FETCH_INTERVAL_MS) {
                sendResultBroadcast(replyAction, requestId, true, "OK", sCachedStatusJson);
                LogHelper.dThrottled(TAG, "📦 车辆状态查询命中短期缓存", MIN_STATUS_FETCH_INTERVAL_MS);
                return;
            }
        }

        try {
            long t0 = System.currentTimeMillis();
            // 与设置页「车辆状态」卡片内 【车辆状态】【里程能源】 展示规则一致（见 VehicleStatusService#buildVehicleQueryBroadcastJson）
            VehicleStatusService.VehicleStatusInfo status = VehicleStatusService.getVehicleStatus(this);

            JSONObject root = VehicleStatusService.buildVehicleQueryBroadcastJson(status);
            statusJson = root.toString();
            success = true;
            message = "OK";

            LogHelper.d(TAG, "✅ 车辆状态查询成功"
                    + " requestId=" + requestId
                    + ", costMs=" + (System.currentTimeMillis() - t0)
                    + ", updateDateTime=" + safeString(status.updateDateTime)
                    + ", engineStatus=" + safeString(VehicleStatusService.translateEngineStatus(status.engineStatus))
                    + ", window=" + safeString(status.winStatusDriver)
                    + ", trunk=" + safeString(VehicleStatusService.translateTrunkStatus(status.trunkOpenStatus))
                    + ", fuelLevel=" + safeString(status.fuelLevel)
                    + ", distanceToEmpty=" + safeString(status.distanceToEmpty)
                    + ", jsonLen=" + (statusJson == null ? 0 : statusJson.length()));
            synchronized (sStatusCacheLock) {
                sCachedStatusJson = statusJson;
                sLastStatusFetchEndMs = System.currentTimeMillis();
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "查询车辆状态异常", e);
            success = false;
            message = "查询异常: " + e.getMessage();
            synchronized (sStatusCacheLock) {
                sCachedStatusJson = "";
                sLastStatusFetchEndMs = System.currentTimeMillis();
            }
        }

        sendResultBroadcast(replyAction, requestId, success, message, statusJson);
    }

    /**
     * 发送车辆状态查询回执。
     * <p><b>不要</b>对回执 Intent 调用 {@link Intent#setPackage(String)} 限制为仅本应用：
     * 第三方主题（锁屏/MAML 等）运行在其它进程，依赖隐式广播接收 {@code BroadcastBinder}；
     * 若限定 package，则仅该包能收到，主题侧无法回调车辆状态。</p>
     * <p>{@code success} 必须以字符串 {@code true}/{@code false} 写入：MAML
     * {@code Variable type="string" extra="success"} 对应 {@link Intent#getStringExtra(String)}，
     * 若使用 {@link Intent#putExtra(String, boolean)}，主题侧无法读到该键。</p>
     */
    private void sendResultBroadcast(String replyAction, String requestId, boolean success, String message, String statusJson) {
        try {
            Intent reply = new Intent(replyAction);
            reply.putExtra(EXTRA_REQUEST_ID, requestId);
            reply.putExtra("success", success ? "true" : "false");
            reply.putExtra("message", safeString(message));
            String json = safeString(statusJson);
            reply.putExtra("data", json);
            reply.putExtra("postStatus", json);
            sendBroadcast(reply);
            LogHelper.d(TAG, "📤 已发送车辆状态回执广播"
                    + " requestId=" + requestId
                    + ", replyAction=" + replyAction
                    + ", success=" + success
                    + ", message=" + safeString(message)
                    + ", dataLen=" + (statusJson == null ? 0 : statusJson.length())
                    + ", data=" + LogHelper.truncateForLog(safeString(statusJson), LOG_JSON_MAX_LEN));
        } catch (Exception e) {
            LogHelper.e(TAG, "发送车辆状态回执广播失败", e);
        }
    }

    private static String safeString(String s) {
        return s == null ? "" : s;
    }

}

