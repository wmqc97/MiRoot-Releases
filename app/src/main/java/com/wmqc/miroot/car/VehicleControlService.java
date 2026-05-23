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
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 车辆控制服务类
 * 将shell脚本逻辑转换为Java代码
 */
public class VehicleControlService {
    private static final String TAG = "VehicleControlService";
    
    // API地址
    private static final String BASE_URL = "https://api.xchanger.cn/geelyTCAccess/tcservices/vehicle/telematics/";
    private static final String VEHICLE_URL = "https://api.xchanger.cn/device_platform/user/vehicle";
    
    // API Headers
    private static final String APP_ID = "xiaokanl";
    private static final String ACCEPT = "application/json;responseformat=3";
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";
    private static final String OPERATOR_CODE = "GEELY";
    
    // 高德地图API Key
    private static final String AMAP_API_KEY = "d8d772c43a33e003b5c77d12e6a52e09";
    
    /**
     * 提取车辆参数（从0.json文件）
     * 参考 car/提取key.sh 的逻辑
     */
    public static class VehicleParams {
        public String accessToken = "";
        public String userId = "";
        public String vin = "";
        
        public boolean isValid() {
            return accessToken != null && !accessToken.isEmpty() &&
                   userId != null && !userId.isEmpty() &&
                   vin != null && !vin.isEmpty();
        }
    }
    
    /**
     * 提取车辆参数（仅从0.json中提取，使用JSON解析）
     * 所有功能调用和获取车辆状态都只从0.json里面提取accessToken、userId和vin
     */
    public static VehicleParams extractVehicleParams(Context context) {
        VehicleParams params = new VehicleParams();
        
        try {
            // 使用LoginService获取登录信息文件路径（应用私有目录）
            File loginFile = LoginService.getLoginInfoFile(context);
            
            if (!loginFile.exists()) {
                LogHelper.d(TAG, "登录文件不存在（未登录）: " + loginFile.getAbsolutePath());
                return params;
            }
            
            String content = readFile(loginFile);
            content = content.trim(); // 去除首尾空白
            
            LogHelper.dThrottled(TAG, "📄 读取登录文件: " + loginFile.getAbsolutePath()
                    + ", 内容长度: " + content.length(), 4000);
            
            // 处理可能的格式问题（如果文件开头有单独的字段）
            int jsonStart = content.indexOf('{');
            if (jsonStart < 0) {
                LogHelper.e(TAG, "❌ 登录文件中未找到JSON对象");
                return params;
            }
            
            String jsonContent = content.substring(jsonStart);
            JSONObject json = new JSONObject(jsonContent);
            
            // 从data对象中提取accessToken和userId
            JSONObject data = json.optJSONObject("data");
            if (data != null) {
                params.accessToken = data.optString("accessToken", "");
                params.userId = data.optString("userId", "");
            } else {
                LogHelper.e(TAG, "❌ 登录文件中未找到data对象");
                return params;
            }
            
            // 校验 accessToken 和 userId
            if (params.accessToken.isEmpty() || params.userId.isEmpty()) {
                LogHelper.e(TAG, "❌ accessToken或userId提取失败: accessToken=" + 
                      (params.accessToken.isEmpty() ? "空" : "已提取") + 
                      ", userId=" + (params.userId.isEmpty() ? "空" : "已提取"));
                return params;
            }
            
            // 从0.json中提取vin（先从根对象，再从data对象）
            params.vin = json.optString("vin", "");
            if (params.vin.isEmpty() && data != null) {
                params.vin = data.optString("vin", "");
            }
            
            if (params.vin.isEmpty()) {
                LogHelper.w(TAG, "⚠️ 从0.json中未找到vin");
            }

            // 校验参数
            if (params.isValid()) {
                LogHelper.d(TAG, "✅ extractVehicleParams: userId=" + params.userId + ", vin=" + params.vin);
            } else {
                LogHelper.e(TAG, "❌ 参数提取失败: accessToken=" + (params.accessToken.isEmpty() ? "空" : "已提取") + 
                      ", userId=" + (params.userId.isEmpty() ? "空" : "已提取") + 
                      ", vin=" + (params.vin.isEmpty() ? "空" : "已提取"));
            }
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 提取车辆参数异常", e);
        }
        
        return params;
    }
    
    /**
     * 执行车控命令
     */
    public static class ControlResult {
        public boolean success = false;
        public String message = "";
    }
    
    /**
     * 执行车控命令（通用方法）
     * 参考脚本格式：
     * curl -s --http2 -X PUT \
     *   -H "x-app-id:xiaokanl" \
     *   -H "accept:application/json;responseformat=3" \
     *   -H "content-type:application/json; charset=utf-8" \
     *   -H "x-operator-code:GEELY" \
     *   -H "authorization:$accessToken" \
     *   -d "{\"command\":\"start\",\"creator\":\"tc\",\"operationScheduling\":{\"duration\":0,\"interval\":0,\"occurs\":1,\"recurrentOperation\":false},\"serviceId\":\"RDL\",\"serviceParameters\":[],\"timestamp\":\"\",\"userId\":\"$userId\"}" \
     *   "https://api.xchanger.cn/geelyTCAccess/tcservices/vehicle/telematics/$vin"
     * 
     * 注意：脚本中duration单位可能有误（time*6），这里使用正确的分钟转秒计算（durationMinutes*60）
     */
    private static ControlResult executeCommand(VehicleParams params, String serviceId, String command, 
                                                JSONArray serviceParameters, int durationMinutes, Context context) {
        ControlResult result = new ControlResult();
        
        if (!params.isValid()) {
            result.message = "车辆参数无效";
            return result;
        }
        
        try {
            // 构建请求体JSON（完全匹配脚本格式）
            JSONObject requestBody = new JSONObject();
            requestBody.put("command", command); // "start" 或 "stop"
            requestBody.put("creator", "tc");
            
            // operationScheduling对象（匹配脚本格式）
            JSONObject operationScheduling = new JSONObject();
            operationScheduling.put("duration", durationMinutes * 60); // 转换为秒（脚本中duration单位可能有误，使用正确的分钟转秒）
            operationScheduling.put("interval", 0);
            operationScheduling.put("occurs", 1);
            operationScheduling.put("recurrentOperation", false);
            requestBody.put("operationScheduling", operationScheduling);
            
            requestBody.put("serviceId", serviceId); // 如 "RDU", "RDL", "RWS", "RES", "RCE", "RTU", "RHF" 等
            requestBody.put("serviceParameters", serviceParameters != null ? serviceParameters : new JSONArray());
            requestBody.put("timestamp", "");
            requestBody.put("userId", params.userId);
            
            // 发送PUT请求（URL: BASE_URL + vin）
            String url = BASE_URL + params.vin;
            String response = sendPutRequest(url, params.accessToken, requestBody.toString(), context);
            
            LogHelper.dResponsePreview(TAG, "📡 " + serviceId + " " + command + " 接口返回", response, 400);
            
            if (response != null && !response.isEmpty()) {
                try {
                    // 解析JSON响应，检查是否成功（按照shell脚本逻辑：检查是否包含"操作成功"）
                    JSONObject responseJson = new JSONObject(response);
                    String message = responseJson.optString("message", "");
                    boolean success = responseJson.optBoolean("success", false);
                    String code = responseJson.optString("code", "");
                    
                    // 检查响应中是否包含"操作成功"（shell脚本：grep -q "操作成功"）
                    if (response.contains("操作成功") || (success && "操作成功".equals(message))) {
                        result.success = true;
                        result.message = "指令发送成功";
                    } else {
                        result.success = false;
                        // 提取错误信息
                        if (!message.isEmpty()) {
                            result.message = "指令发送失败: " + message;
                        } else if (!code.isEmpty()) {
                            result.message = "指令发送失败 (code: " + code + ")";
                        } else {
                            result.message = "指令发送失败: " + response;
                        }
                    }
                } catch (Exception e) {
                    // JSON解析失败，使用字符串匹配（兼容shell脚本逻辑）
                    if (response.contains("操作成功")) {
                        result.success = true;
                        result.message = "指令发送成功";
                    } else {
                        result.success = false;
                        result.message = "指令发送失败: " + response;
                    }
                }
            } else {
                result.success = false;
                result.message = "指令发送失败: 响应为空";
            }
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 执行车控命令异常", e);
            result.message = "执行异常: " + e.getMessage();
        }
        
        return result;
    }
    
    // ========== 车控功能方法 ==========
    
    /**
     * 解锁
     */
    public static ControlResult unlock(Context context) {
        VehicleParams params = extractVehicleParams(context);
        return executeCommand(params, "RDU", "start", null, 0, context);
    }
    
    /**
     * 锁车
     */
    public static ControlResult lock(Context context) {
        VehicleParams params = extractVehicleParams(context);
        return executeCommand(params, "RDL", "start", null, 0, context);
    }
    
    /**
     * 点火
     */
    public static ControlResult startEngine(Context context, int durationMinutes) {
        VehicleParams params = extractVehicleParams(context);
        return executeCommand(params, "RES", "start", null, durationMinutes, context);
    }
    
    /**
     * 熄火
     */
    public static ControlResult stopEngine(Context context) {
        VehicleParams params = extractVehicleParams(context);
        return executeCommand(params, "RES", "stop", null, 0, context);
    }
    
    /**
     * 打开空调
     */
    public static ControlResult openAirConditioner(Context context, int durationMinutes, int temperature) {
        VehicleParams params = extractVehicleParams(context);
        try {
            JSONArray paramsArray = new JSONArray();
            JSONObject param = new JSONObject();
            param.put("key", "rce");
            param.put("value", String.valueOf(temperature));
            paramsArray.put(param);
            return executeCommand(params, "RCE", "start", paramsArray, durationMinutes, context);
        } catch (Exception e) {
            ControlResult result = new ControlResult();
            result.message = "构建请求参数失败: " + e.getMessage();
            return result;
        }
    }
    
    /**
     * 关闭空调
     */
    public static ControlResult closeAirConditioner(Context context) {
        VehicleParams params = extractVehicleParams(context);
        return executeCommand(params, "RCE", "stop", null, 0, context);
    }
    
    /**
     * 开窗
     */
    public static ControlResult openWindow(Context context) {
        VehicleParams params = extractVehicleParams(context);
        try {
            JSONArray paramsArray = new JSONArray();
            JSONObject param = new JSONObject();
            param.put("key", "rws");
            param.put("value", "all");
            paramsArray.put(param);
            return executeCommand(params, "RWS", "start", paramsArray, 0, context);
        } catch (Exception e) {
            ControlResult result = new ControlResult();
            result.message = "构建请求参数失败: " + e.getMessage();
            return result;
        }
    }
    
    /**
     * 关窗
     */
    public static ControlResult closeWindow(Context context) {
        VehicleParams params = extractVehicleParams(context);
        try {
            JSONArray paramsArray = new JSONArray();
            JSONObject param = new JSONObject();
            param.put("key", "rws");
            param.put("value", "all");
            paramsArray.put(param);
            return executeCommand(params, "RWS", "stop", paramsArray, 0, context);
        } catch (Exception e) {
            ControlResult result = new ControlResult();
            result.message = "构建请求参数失败: " + e.getMessage();
            return result;
        }
    }
    
    /**
     * 透气
     */
    public static ControlResult ventilate(Context context) {
        VehicleParams params = extractVehicleParams(context);
        try {
            JSONArray paramsArray = new JSONArray();
            JSONObject param = new JSONObject();
            param.put("key", "rws");
            param.put("value", "ventilate");
            paramsArray.put(param);
            return executeCommand(params, "RWS", "start", paramsArray, 0, context);
        } catch (Exception e) {
            ControlResult result = new ControlResult();
            result.message = "构建请求参数失败: " + e.getMessage();
            return result;
        }
    }
    
    /**
     * 开后备箱
     */
    public static ControlResult openTrunk(Context context) {
        VehicleParams params = extractVehicleParams(context);
        return executeCommand(params, "RTU", "start", null, 0, context);
    }
    
    /**
     * 打开座椅加热（全部）
     */
    public static ControlResult openSeatHeating(Context context, int durationMinutes, int level) {
        VehicleParams params = extractVehicleParams(context);
        try {
            JSONArray paramsArray = new JSONArray();
            JSONObject param1 = new JSONObject();
            param1.put("key", "rsh.seat");
            param1.put("value", "front-left");
            paramsArray.put(param1);
            JSONObject param2 = new JSONObject();
            param2.put("key", "rsh.seat");
            param2.put("value", "front-right");
            paramsArray.put(param2);
            JSONObject param3 = new JSONObject();
            param3.put("key", "rsh.level");
            param3.put("value", String.valueOf(level));
            paramsArray.put(param3);
            return executeCommand(params, "RSH", "start", paramsArray, durationMinutes, context);
        } catch (Exception e) {
            ControlResult result = new ControlResult();
            result.message = "构建请求参数失败: " + e.getMessage();
            return result;
        }
    }
    
    /**
     * 关闭座椅加热（全部）
     */
    public static ControlResult closeSeatHeating(Context context) {
        VehicleParams params = extractVehicleParams(context);
        try {
            JSONArray paramsArray = new JSONArray();
            JSONObject param1 = new JSONObject();
            param1.put("key", "rsh.seat");
            param1.put("value", "front-left");
            paramsArray.put(param1);
            JSONObject param2 = new JSONObject();
            param2.put("key", "rsh.seat");
            param2.put("value", "front-right");
            paramsArray.put(param2);
            JSONObject param3 = new JSONObject();
            param3.put("key", "rsh.level");
            param3.put("value", "2");
            paramsArray.put(param3);
            return executeCommand(params, "RSH", "stop", paramsArray, 0, context);
        } catch (Exception e) {
            ControlResult result = new ControlResult();
            result.message = "构建请求参数失败: " + e.getMessage();
            return result;
        }
    }
    
    /**
     * 打开主驾座椅加热
     */
    public static ControlResult openDriverSeatHeating(Context context, int durationMinutes, int level) {
        VehicleParams params = extractVehicleParams(context);
        try {
            JSONArray paramsArray = new JSONArray();
            JSONObject param1 = new JSONObject();
            param1.put("key", "rsh.seat");
            param1.put("value", "front-left");
            paramsArray.put(param1);
            JSONObject param2 = new JSONObject();
            param2.put("key", "rsh.level");
            param2.put("value", String.valueOf(level));
            paramsArray.put(param2);
            return executeCommand(params, "RSH", "start", paramsArray, durationMinutes, context);
        } catch (Exception e) {
            ControlResult result = new ControlResult();
            result.message = "构建请求参数失败: " + e.getMessage();
            return result;
        }
    }
    
    /**
     * 关闭主驾座椅加热
     */
    public static ControlResult closeDriverSeatHeating(Context context) {
        VehicleParams params = extractVehicleParams(context);
        try {
            JSONArray paramsArray = new JSONArray();
            JSONObject param = new JSONObject();
            param.put("key", "rsh.seat");
            param.put("value", "front-left");
            paramsArray.put(param);
            return executeCommand(params, "RSH", "stop", paramsArray, 0, context);
        } catch (Exception e) {
            ControlResult result = new ControlResult();
            result.message = "构建请求参数失败: " + e.getMessage();
            return result;
        }
    }
    
    /**
     * 打开副驾座椅加热
     */
    public static ControlResult openPassengerSeatHeating(Context context, int durationMinutes, int level) {
        VehicleParams params = extractVehicleParams(context);
        try {
            JSONArray paramsArray = new JSONArray();
            JSONObject param1 = new JSONObject();
            param1.put("key", "rsh.seat");
            param1.put("value", "front-right");
            paramsArray.put(param1);
            JSONObject param2 = new JSONObject();
            param2.put("key", "rsh.level");
            param2.put("value", String.valueOf(level));
            paramsArray.put(param2);
            return executeCommand(params, "RSH", "start", paramsArray, durationMinutes, context);
        } catch (Exception e) {
            ControlResult result = new ControlResult();
            result.message = "构建请求参数失败: " + e.getMessage();
            return result;
        }
    }
    
    /**
     * 关闭副驾座椅加热
     */
    public static ControlResult closePassengerSeatHeating(Context context) {
        VehicleParams params = extractVehicleParams(context);
        try {
            JSONArray paramsArray = new JSONArray();
            JSONObject param = new JSONObject();
            param.put("key", "rsh.seat");
            param.put("value", "front-right");
            paramsArray.put(param);
            return executeCommand(params, "RSH", "stop", paramsArray, 0, context);
        } catch (Exception e) {
            ControlResult result = new ControlResult();
            result.message = "构建请求参数失败: " + e.getMessage();
            return result;
        }
    }
    
    /**
     * 导航到车辆位置
     * 参考：星瑞车控/导航到车.sh
     * 获取车辆位置后，调用高德地图导航到车辆位置
     */
    public static ControlResult navigateToCar(Context context) {
        ControlResult result = new ControlResult();
        
        try {
            // 1. 获取车辆状态（包含位置信息）
            VehicleStatusService.VehicleStatusInfo status = VehicleStatusService.getVehicleStatus(context);
            
            // 2. 检查位置信息是否有效
            if (status.latitude == null || status.latitude.equals("未知") || 
                status.longitude == null || status.longitude.equals("未知")) {
                result.success = false;
                result.message = "无法获取车辆位置信息";
                LogHelper.e(TAG, "❌ 车辆位置信息无效: lat=" + status.latitude + ", lon=" + status.longitude);
                return result;
            }
            
            // 3. 转换经纬度（API返回的是毫秒单位，需要除以3600000转换为度）
            double lat, lon;
            try {
                // 尝试解析为数字（可能是毫秒单位）
                double latMs = Double.parseDouble(status.latitude);
                double lonMs = Double.parseDouble(status.longitude);
                
                // 如果数值很大（超过360），说明是毫秒单位，需要转换
                if (latMs > 360 || lonMs > 360) {
                    lat = latMs / 3600000.0;
                    lon = lonMs / 3600000.0;
                    LogHelper.d(TAG, "📍 位置转换（毫秒->度）: lat=" + latMs + " -> " + lat + ", lon=" + lonMs + " -> " + lon);
                } else {
                    // 已经是度单位
                    lat = latMs;
                    lon = lonMs;
                    LogHelper.d(TAG, "📍 位置已是度单位: lat=" + lat + ", lon=" + lon);
                }
            } catch (NumberFormatException e) {
                result.success = false;
                result.message = "位置信息格式错误: " + e.getMessage();
                LogHelper.e(TAG, "❌ 位置信息解析失败", e);
                return result;
            }
            
            // 4. 格式化经纬度为6位小数
            String latStr = String.format("%.6f", lat);
            String lonStr = String.format("%.6f", lon);
            
            LogHelper.d(TAG, "🚗 寻车功能: 车辆位置 lat=" + latStr + ", lon=" + lonStr);
            
            // 5. 调用高德地图导航（参考脚本：步行导航）
            // 脚本中使用：amapuri://openFeature?featureName=OnFootNavi&lon=$lon&lat=$lat&dev=1
            // 或者使用路线规划：amapuri://route/plan/?&dlon=$lon&dlat=$lat&dname=车位置&dev=1&t=2&rideType=elebike
            // 这里使用路线规划方式，让用户选择导航方式
            // 添加高德地图API key
            String uriString = String.format(
                "amapuri://route/plan/?&dlon=%s&dlat=%s&dname=车位置&dev=1&t=2&rideType=elebike&key=%s",
                lonStr, latStr, AMAP_API_KEY
            );
            
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(Uri.parse(uriString));
            intent.setPackage("com.autonavi.minimap"); // 指定高德地图包名
            
            // 检查是否有应用可以处理此Intent
            if (intent.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(intent);
                result.success = true;
                result.message = "正在打开导航...";
                LogHelper.d(TAG, "✅ 寻车功能：已启动高德地图导航");
            } else {
                // 如果没有安装高德地图，尝试使用通用导航Intent
                // 或者使用步行导航方式
                uriString = String.format(
                    "amapuri://openFeature?featureName=OnFootNavi&lon=%s&lat=%s&dev=1&key=%s",
                    lonStr, latStr, AMAP_API_KEY
                );
                intent.setData(Uri.parse(uriString));
                
                if (intent.resolveActivity(context.getPackageManager()) != null) {
                    context.startActivity(intent);
                    result.success = true;
                    result.message = "正在打开导航...";
                    LogHelper.d(TAG, "✅ 寻车功能：已启动高德地图导航（步行方式）");
                } else {
                    // 如果高德地图未安装，尝试使用通用地图Intent
                    uriString = String.format("geo:%s,%s?q=车位置", latStr, lonStr);
                    intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(Uri.parse(uriString));
                    
                    if (intent.resolveActivity(context.getPackageManager()) != null) {
                        context.startActivity(intent);
                        result.success = true;
                        result.message = "正在打开地图...";
                        LogHelper.d(TAG, "✅ 寻车功能：已启动通用地图应用");
                    } else {
                        result.success = false;
                        result.message = "未找到可用的地图应用，请安装高德地图";
                        LogHelper.e(TAG, "❌ 寻车功能：未找到可用的地图应用");
                    }
                }
            }
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 寻车功能异常", e);
            result.success = false;
            result.message = "寻车失败: " + e.getMessage();
        }
        
        return result;
    }
    
    /**
     * 寻车功能（车辆鸣笛双闪）
     * 参考：星瑞车控/寻车.sh
     * 使用serviceId: RHL (Remote Horn Light) 实现车辆鸣笛双闪
     * serviceParameters: [{"key":"rhl","value":"horn-light-flash"}]
     */
    public static ControlResult findCar(Context context) {
        VehicleParams params = extractVehicleParams(context);
        try {
            JSONArray serviceParameters = new JSONArray();
            JSONObject param = new JSONObject();
            param.put("key", "rhl");
            param.put("value", "horn-light-flash");
            serviceParameters.put(param);
            // 使用RHL serviceId实现鸣笛双闪功能
            // duration设置为0，表示执行一次（通常鸣笛双闪会自动停止）
            return executeCommand(params, "RHL", "start", serviceParameters, 0, context);
        } catch (Exception e) {
            ControlResult result = new ControlResult();
            result.message = "构建请求参数失败: " + e.getMessage();
            LogHelper.e(TAG, "❌ 寻车功能参数构建失败", e);
            return result;
        }
    }
    
    // ========== 网络请求方法 ==========
    
    /**
     * 发送PUT请求
     * @param urlString 请求URL
     * @param accessToken 访问令牌
     * @param jsonBody 请求体JSON
     * @param context 上下文（用于标记登录失效，可为null）
     */
    private static String sendPutRequest(String urlString, String accessToken, String jsonBody, Context context) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            
            connection.setRequestMethod("PUT");
            connection.setRequestProperty("x-app-id", APP_ID);
            connection.setRequestProperty("accept", ACCEPT);
            connection.setRequestProperty("content-type", CONTENT_TYPE);
            connection.setRequestProperty("x-operator-code", OPERATOR_CODE);
            connection.setRequestProperty("authorization", accessToken);
            connection.setDoOutput(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            
            // 写入JSON数据
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
            
            // 读取响应
            int responseCode = connection.getResponseCode();
            
            // 如果HTTP状态码为401（未授权）或403（禁止访问），说明token已失效
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED || 
                responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                LogHelper.w(TAG, "⚠️ HTTP状态码 " + responseCode + "（未授权/禁止访问），token已失效");
                if (context != null) {
                    LoginService.markLoginInvalid(context);
                }
                return null;
            }
            
            InputStream is = (responseCode == HttpURLConnection.HTTP_OK) ? 
                connection.getInputStream() : connection.getErrorStream();
            
            if (is != null) {
                return readInputStream(is);
            }
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ PUT请求失败", e);
        }
        
        return null;
    }
    
    /**
     * 发送GET请求
     */
    /**
     * 发送GET请求
     * @param urlString 请求URL
     * @param accessToken 访问令牌
     * @param context 上下文（用于标记登录失效，可为null）
     */
    private static String sendGetRequest(String urlString, String accessToken, Context context) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            
            connection.setRequestMethod("GET");
            connection.setRequestProperty("accept", ACCEPT);
            connection.setRequestProperty("authorization", accessToken);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            
            int responseCode = connection.getResponseCode();
            
            // 如果HTTP状态码为401（未授权）或403（禁止访问），说明token已失效
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED || 
                responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                LogHelper.w(TAG, "⚠️ HTTP状态码 " + responseCode + "（未授权/禁止访问），token已失效");
                if (context != null) {
                    LoginService.markLoginInvalid(context);
                }
                return null;
            }
            
            InputStream is = (responseCode == HttpURLConnection.HTTP_OK) ? 
                connection.getInputStream() : connection.getErrorStream();
            
            if (is != null) {
                return readInputStream(is);
            }
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ GET请求失败", e);
        }
        
        return null;
    }
    
    /**
     * 发送GET请求（兼容旧接口，不传递context）
     */
    private static String sendGetRequest(String urlString, String accessToken) {
        return sendGetRequest(urlString, accessToken, null);
    }
    
    /**
     * 读取输入流
     */
    private static String readInputStream(InputStream is) throws Exception {
        StringBuilder response = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
        }
        return response.toString();
    }
    
    /**
     * 读取文件内容
     */
    private static String readFile(File file) throws Exception {
        StringBuilder content = new StringBuilder();
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file);
             java.io.InputStreamReader isr = new java.io.InputStreamReader(fis, StandardCharsets.UTF_8);
             java.io.BufferedReader reader = new java.io.BufferedReader(isr)) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
        }
        return content.toString();
    }
    
    /**
     * 更新0.json文件中的vin字段
     */
    private static void updateVinInFile(Context context, String vin) {
        try {
            // 使用LoginService获取登录信息文件路径（应用私有目录）
            File loginFile = LoginService.getLoginInfoFile(context);
            
            if (!loginFile.exists()) {
                LogHelper.w(TAG, "⚠️ 登录文件不存在，无法更新vin");
                return;
            }
            
            String content = readFile(loginFile);
            content = content.trim();
            
            // 处理可能的格式问题
            int jsonStart = content.indexOf('{');
            String prefix = "";
            if (jsonStart > 0) {
                prefix = content.substring(0, jsonStart);
                content = content.substring(jsonStart);
            }
            
            JSONObject json = new JSONObject(content);
            
            // 更新vin字段（在根对象中）
            json.put("vin", vin);
            
            // 如果在data对象中也存在，也更新
            JSONObject data = json.optJSONObject("data");
            if (data != null) {
                data.put("vin", vin);
            }
            
            // 写入文件
            try (java.io.FileWriter writer = new java.io.FileWriter(loginFile, false)) {
                writer.write(prefix + json.toString());
            }
            
            LogHelper.d(TAG, "✅ VIN已更新到登录文件: " + vin);
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 更新VIN失败", e);
        }
    }
}

