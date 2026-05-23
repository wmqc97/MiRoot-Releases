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
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 车辆状态服务类
 * 获取车辆状态信息
 */
public class VehicleStatusService {
    private static final String TAG = "VehicleStatusService";
    
    private static final String VEHICLE_URL = "https://api.xchanger.cn/device_platform/user/vehicle";
    private static final String STATUS_URL = "https://api.xchanger.cn/geelyTCAccess/tcservices/vehicle/status/";
    private static final String ACCEPT = "application/json;responseformat=3";
    
    /**
     * 车辆基础信息
     */
    public static class VehicleBasicInfo {
        public String vin = "未知";
        public String colorName = "未知";
        public String modelName = "未知";
        public String plateNo = "未知";
        public String seriesName = "未知";
        public String factoryCode = "未知";
        public String colorCode = "未知";
        public String iccid = "未知";
        public String modelCode = "未知";
        public String defaultVehicle = "未知";
        public String fuelType = "未知";
    }
    
    /**
     * 车辆状态信息
     */
    public static class VehicleStatusInfo {
        // 基础状态
        public String engineStatus = "未知";
        public String speed = "未知";
        public String latitude = "未知";
        public String longitude = "未知";
        public String distanceToEmpty = "未知";
        public String updateTime = "未知";
        public String updateDateTime = "未知";
        
        // 里程保养
        public String odometer = "未知";
        public String distanceToService = "未知";
        public String serviceWarningStatus = "未知";
        
        // 油量与电瓶
        public String fuelLevel = "未知";
        public String fuelLevelStatus = "未知";
        public String voltage = "未知";
        public String stateOfCharge = "未知";
        
        // 驾驶行为
        public String avgSpeed = "未知";
        public String aveFuelConsumption = "未知";
        public String engineSpeed = "未知";
        public String cruiseControlStatus = "未知";
        public String transimissionGearPostion = "未知";
        
        // 温度相关
        public String interiorTemp = "未知";
        public String exteriorTemp = "未知";
        public String engineCoolantTemperature = "未知";
        
        // 胎压相关
        public String tyreStatusDriver = "未知";
        public String tyreStatusPassenger = "未知";
        public String tyreStatusDriverRear = "未知";
        public String tyreStatusPassengerRear = "未知";
        public String tyrePreWarningDriver = "未知";
        public String tyrePreWarningPassenger = "未知";
        
        // 安全状态
        public String doorLockStatusDriver = "未知";
        public String doorOpenStatusDriver = "未知";
        public String seatBeltStatusDriver = "未知";
        public String trunkOpenStatus = "未知";
        public String vehicleAlarm = "未知";
        public String electricParkBrakeStatus = "未知";
        
        // 空调车窗（原始位置值，需要翻译）
        public String sunroofPos = "未知";  // 天窗位置：0=关闭，其他=打开
        public String winPosDriver = "未知";  // 车窗位置：0=关闭，其他=打开
        // 翻译后的状态（兼容旧代码）
        public String sunroofOpenStatus = "未知";
        public String winStatusDriver = "未知";
        public String airCleanSts = "未知";
        public String preClimateActive = "未知";
        
        // 防盗相关
        public String theftActivated = "未知";
    }
    
    /**
     * 获取车辆参数
     */
    private static VehicleControlService.VehicleParams getVehicleParams(Context context) {
        return VehicleControlService.extractVehicleParams(context);
    }
    
    /**
     * 获取车辆基础信息（从0.json文件中提取，登录时已保存）
     */
    public static VehicleBasicInfo getVehicleBasicInfo(Context context) {
        VehicleBasicInfo info = new VehicleBasicInfo();
        
        try {
            // 从0.json文件中提取车辆基础信息
            File loginFile = LoginService.getLoginInfoFile(context);
            if (!loginFile.exists()) {
                LogHelper.d(TAG, "登录文件不存在（未登录）: " + loginFile.getAbsolutePath());
                return info;
            }
            
            String content = readFile(loginFile);
            content = content.trim();
            
            // 处理可能的格式问题（如果文件开头有单独的字段）
            int jsonStart = content.indexOf('{');
            if (jsonStart < 0) {
                LogHelper.e(TAG, "❌ 登录文件中未找到JSON对象");
                return info;
            }
            
            JSONObject json = new JSONObject(content.substring(jsonStart));
            
            // 从根对象中提取车辆信息（登录时已保存到0.json的根对象中）
            info.vin = extractFieldFromJson(json, "vin");
            info.colorName = extractFieldFromJson(json, "colorName");
            info.modelName = extractFieldFromJson(json, "modelName");
            info.plateNo = extractFieldFromJson(json, "plateNo");
            info.seriesName = extractFieldFromJson(json, "seriesName");
            info.colorCode = extractFieldFromJson(json, "colorCode");
            info.iccid = extractFieldFromJson(json, "iccid");
            info.modelCode = extractFieldFromJson(json, "modelCode");
            
            // 尝试从data对象中获取fuelType（如果状态API中有）
            JSONObject data = json.optJSONObject("data");
            if (data != null) {
                // fuelType 可能在状态API中获取，这里先不处理
            }
            
            LogHelper.d(TAG, "✅ 从0.json提取车辆基础信息成功 - 车牌: " + info.plateNo + 
                      ", 系列: " + info.seriesName + ", 型号: " + info.modelName + 
                      ", 颜色: " + info.colorName + ", VIN: " + info.vin);
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 从0.json提取车辆基础信息异常", e);
        }
        
        return info;
    }
    
    /**
     * 读取文件内容
     */
    private static String readFile(File file) {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "读取文件失败: " + file.getAbsolutePath(), e);
        }
        return content.toString().trim();
    }
    
    /**
     * 从JSON对象中提取字段（支持从根对象和data对象中查找）
     */
    private static String extractFieldFromJson(JSONObject json, String fieldName) {
        String value = json.optString(fieldName, "");
        if (value.isEmpty()) {
            JSONObject data = json.optJSONObject("data");
            if (data != null) {
                value = data.optString(fieldName, "");
            }
        }
        return value.isEmpty() ? "未知" : value;
    }
    
    /**
     * 获取车辆状态信息
     */
    public static VehicleStatusInfo getVehicleStatus(Context context) {
        VehicleStatusInfo status = new VehicleStatusInfo();
        VehicleControlService.VehicleParams params = getVehicleParams(context);
        
        if (!params.isValid()) {
            LogHelper.e(TAG, "❌ 车辆参数无效");
            return status;
        }
        
        try {
            String url = STATUS_URL + params.vin + "?userId=" + params.userId + "&latest=false&target=more,basic";
            String response = sendGetRequest(url, params.accessToken, context);
            
            if (response != null && !response.isEmpty()) {
                LogHelper.dResponsePreview(TAG, "📡 车辆状态原始响应", response, 512);
                JSONObject json = new JSONObject(response);
                
                // 按照实际API响应结构解析：data.vehicleStatus
                JSONObject dataObj = json.optJSONObject("data");
                if (dataObj == null) {
                    LogHelper.e(TAG, "❌ 响应中未找到data对象");
                    return status;
                }
                
                JSONObject vehicleStatusObj = dataObj.optJSONObject("vehicleStatus");
                if (vehicleStatusObj == null) {
                    LogHelper.e(TAG, "❌ 响应中未找到vehicleStatus对象");
                    return status;
                }
                
                // 1. 基础状态：data.vehicleStatus.basicVehicleStatus
                JSONObject basicStatus = vehicleStatusObj.optJSONObject("basicVehicleStatus");
                if (basicStatus != null) {
                    status.engineStatus = extractField(basicStatus, "engineStatus");
                    status.speed = extractField(basicStatus, "speed");
                    status.distanceToEmpty = extractField(basicStatus, "distanceToEmpty");
                    
                    // 位置信息在 basicVehicleStatus.position 中
                    JSONObject position = basicStatus.optJSONObject("position");
                    if (position != null) {
                        status.latitude = extractField(position, "latitude");
                        status.longitude = extractField(position, "longitude");
                    }
                }
                
                // 2. 更新时间：data.vehicleStatus.updateTime（在vehicleStatus对象的根级别）
                status.updateTime = extractField(vehicleStatusObj, "updateTime");
                
                // 3. 配置信息：data.vehicleStatus.configuration（包含fuelType和vin）
                JSONObject configuration = vehicleStatusObj.optJSONObject("configuration");
                if (configuration != null) {
                    // fuelType和vin在configuration中，但VehicleStatusInfo没有这些字段
                    // 如果updateTime在vehicleStatus根级别没有找到，尝试从configuration中获取
                    if (status.updateTime.equals("未知") || status.updateTime.isEmpty()) {
                        status.updateTime = extractField(configuration, "updateTime");
                    }
                }
                
                // 转换时间戳为日期时间格式
                if (!status.updateTime.equals("未知") && !status.updateTime.isEmpty()) {
                    status.updateDateTime = timestampToDateTime(status.updateTime);
                    LogHelper.d(TAG, "✅ 更新时间解析: updateTime=" + status.updateTime + ", updateDateTime=" + status.updateDateTime);
                } else {
                    LogHelper.w(TAG, "⚠️ 未找到更新时间");
                }
                
                // 4. additionalVehicleStatus - 包含多个子对象
                JSONObject additionalStatus = vehicleStatusObj.optJSONObject("additionalVehicleStatus");
                if (additionalStatus != null) {
                    // 3.1 保养信息：additionalVehicleStatus.maintenanceStatus
                    JSONObject maintenanceStatus = additionalStatus.optJSONObject("maintenanceStatus");
                    if (maintenanceStatus != null) {
                        String odometerRaw = extractField(maintenanceStatus, "odometer");
                        // 清理总里程中的竖线符号和其他非数字字符（保留数字和小数点）
                        if (!"未知".equals(odometerRaw) && !odometerRaw.isEmpty()) {
                            status.odometer = odometerRaw.replaceAll("[^0-9.]", "");
                            if (status.odometer.isEmpty()) {
                                status.odometer = "未知";
                            }
                        } else {
                            status.odometer = odometerRaw;
                        }
                        status.distanceToService = extractField(maintenanceStatus, "distanceToService");
                        status.serviceWarningStatus = extractField(maintenanceStatus, "serviceWarningStatus");
                        
                        // 胎压信息
                        status.tyreStatusDriver = extractField(maintenanceStatus, "tyreStatusDriver");
                        status.tyreStatusPassenger = extractField(maintenanceStatus, "tyreStatusPassenger");
                        status.tyreStatusDriverRear = extractField(maintenanceStatus, "tyreStatusDriverRear");
                        status.tyreStatusPassengerRear = extractField(maintenanceStatus, "tyreStatusPassengerRear");
                        status.tyrePreWarningDriver = extractField(maintenanceStatus, "tyrePreWarningDriver");
                        status.tyrePreWarningPassenger = extractField(maintenanceStatus, "tyrePreWarningPassenger");
                        
                        // 电瓶信息
                        JSONObject mainBatteryStatus = maintenanceStatus.optJSONObject("mainBatteryStatus");
                        if (mainBatteryStatus != null) {
                            status.voltage = extractField(mainBatteryStatus, "voltage");
                            status.stateOfCharge = extractField(mainBatteryStatus, "stateOfCharge");
                        }
                    }
                    
                    // 3.2 驾驶行为：additionalVehicleStatus.drivingBehaviourStatus
                    JSONObject drivingBehaviour = additionalStatus.optJSONObject("drivingBehaviourStatus");
                    if (drivingBehaviour != null) {
                        status.transimissionGearPostion = extractField(drivingBehaviour, "transimissionGearPostion");
                        status.engineSpeed = extractField(drivingBehaviour, "engineSpeed");
                        status.cruiseControlStatus = extractField(drivingBehaviour, "cruiseControlStatus");
                    }
                    
                    // 3.3 运行状态：additionalVehicleStatus.runningStatus
                    JSONObject runningStatus = additionalStatus.optJSONObject("runningStatus");
                    if (runningStatus != null) {
                        status.fuelLevel = extractField(runningStatus, "fuelLevel");
                        status.fuelLevelStatus = extractField(runningStatus, "fuelLevelStatus");
                        status.avgSpeed = extractField(runningStatus, "avgSpeed");
                        status.aveFuelConsumption = extractField(runningStatus, "aveFuelConsumption");
                        status.engineCoolantTemperature = extractField(runningStatus, "engineCoolantTemperature");
                    }
                    
                    // 气候状态：additionalVehicleStatus.climateStatus
                    JSONObject climateStatus = additionalStatus.optJSONObject("climateStatus");
                    if (climateStatus != null) {
                        status.interiorTemp = extractField(climateStatus, "interiorTemp");
                        status.exteriorTemp = extractField(climateStatus, "exteriorTemp");
                        // 提取原始位置值（根据脚本，主要是 winPosDriver / sunroofPos；部分接口用其它键名）
                        status.winPosDriver = pickFirstKnownClimateValue(climateStatus, "winPosDriver", "winStatusDriver");
                        status.sunroofPos = pickFirstKnownClimateValue(climateStatus, "sunroofPos",
                                "sunroofOpenStatus", "sunRoofPos", "sunroofStatus");
                        // 翻译状态（兼容旧字段名）
                        status.winStatusDriver = translateWindowStatus(status.winPosDriver);
                        status.sunroofOpenStatus = translateSunroofStatus(status.sunroofPos);
                        status.airCleanSts = extractField(climateStatus, "airCleanSts");
                        // preClimateActive 是布尔值
                        if (climateStatus.has("preClimateActive")) {
                            boolean preClimate = climateStatus.optBoolean("preClimateActive", false);
                            status.preClimateActive = preClimate ? "true" : "false";
                        }
                    }
                    
                    // 3.5 安全状态：additionalVehicleStatus.drivingSafetyStatus
                    JSONObject drivingSafety = additionalStatus.optJSONObject("drivingSafetyStatus");
                    if (drivingSafety != null) {
                        status.doorLockStatusDriver = extractField(drivingSafety, "doorLockStatusDriver");
                        status.doorOpenStatusDriver = extractField(drivingSafety, "doorOpenStatusDriver");
                        // seatBeltStatusDriver 是布尔值
                        if (drivingSafety.has("seatBeltStatusDriver")) {
                            boolean seatBelt = drivingSafety.optBoolean("seatBeltStatusDriver", false);
                            status.seatBeltStatusDriver = seatBelt ? "true" : "false";
                        }
                        status.trunkOpenStatus = extractField(drivingSafety, "trunkOpenStatus");
                        status.vehicleAlarm = extractField(drivingSafety, "vehicleAlarm");
                        status.electricParkBrakeStatus = extractField(drivingSafety, "electricParkBrakeStatus");
                    }
                }
                
                // 4. 防盗通知：data.vehicleStatus.theftNotification.activated
                JSONObject theftNotification = vehicleStatusObj.optJSONObject("theftNotification");
                if (theftNotification != null) {
                    status.theftActivated = extractField(theftNotification, "activated");
                }
                
                LogHelper.d(TAG, "✅ 获取车辆状态信息成功 - 发动机: " + status.engineStatus + 
                          ", 车速: " + status.speed + " km/h, 最后更新: " + status.updateDateTime);
            }
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 获取车辆状态信息异常", e);
        }
        
        return status;
    }
    
    /**
     * 提取JSON字段
     */
    private static String extractField(JSONObject json, String fieldName) {
        String value = json.optString(fieldName, "");
        return value.isEmpty() ? "未知" : value;
    }

    /**
     * 读取任意类型（数值/布尔等）为字符串，便于与 {@link #extractField} 互补；缺失时返回空串。
     */
    private static String extractFieldAsStringAnyType(JSONObject json, String fieldName) {
        if (json == null || !json.has(fieldName)) {
            return "";
        }
        Object o = json.opt(fieldName);
        if (o == null || o == JSONObject.NULL) {
            return "";
        }
        return String.valueOf(o);
    }

    /**
     * 车窗/天窗：优先主键，若仍为「未知」则尝试备用键（接口可能用不同字段名或类型）。
     */
    private static String pickFirstKnownClimateValue(JSONObject climateStatus, String primaryKey, String... altKeys) {
        String primary = extractField(climateStatus, primaryKey);
        if (!"未知".equals(primary)) {
            return primary;
        }
        for (String alt : altKeys) {
            String s = extractFieldAsStringAnyType(climateStatus, alt);
            if (s != null && !s.isEmpty() && !"null".equalsIgnoreCase(s)) {
                return s;
            }
        }
        return primary;
    }
    
    /**
     * 时间戳转换为日期时间
     * 支持10位秒级和13位毫秒级时间戳
     */
    private static String timestampToDateTime(String timestamp) {
        try {
            long ts = Long.parseLong(timestamp);
            // 如果是10位秒级时间戳，转换为毫秒
            if (timestamp.length() == 10) {
                ts = ts * 1000;
            }
            // 如果是13位毫秒级时间戳，直接使用
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            return sdf.format(new Date(ts));
        } catch (Exception e) {
            LogHelper.e(TAG, "时间戳转换失败: " + timestamp, e);
            return "时间格式错误";
        }
    }
    
    /**
     * 发送GET请求（带重试机制）
     * @param urlString 请求URL
     * @param accessToken 访问令牌
     * @param context 上下文（用于标记登录失效，可为null）
     * @return 响应内容，失败返回null
     */
    private static String sendGetRequest(String urlString, String accessToken, Context context) {
        return sendGetRequestWithRetry(urlString, accessToken, context, 3); // 最多重试3次
    }
    
    /**
     * 发送GET请求（带重试机制）
     * @param urlString 请求URL
     * @param accessToken 访问令牌
     * @param context 上下文（用于标记登录失效，可为null）
     * @param maxRetries 最大重试次数
     * @return 响应内容，失败返回null
     */
    private static String sendGetRequestWithRetry(String urlString, String accessToken, Context context, int maxRetries) {
        int retryCount = 0;
        Exception lastException = null;
        
        while (retryCount <= maxRetries) {
            try {
                URL url = new URL(urlString);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                
                connection.setRequestMethod("GET");
                connection.setRequestProperty("accept", ACCEPT);
                connection.setRequestProperty("authorization", accessToken);
                // 设置超时：连接超时10秒，读取超时10秒
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                
                int responseCode = connection.getResponseCode();
                
                // 如果HTTP状态码为401（未授权）或403（禁止访问），说明token已失效，不重试
                if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED || 
                    responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                    LogHelper.w(TAG, "⚠️ HTTP状态码 " + responseCode + "（未授权/禁止访问），token已失效");
                    if (context != null) {
                        LoginService.markLoginInvalid(context);
                    }
                    return null;
                }
                
                // 如果请求成功，返回响应
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    InputStream is = connection.getInputStream();
                    if (is != null) {
                        String result = readInputStream(is);
                        if (retryCount > 0) {
                            LogHelper.d(TAG, "✅ 请求成功（重试 " + retryCount + " 次后）");
                        }
                        return result;
                    }
                } else {
                    // 非200状态码，记录但不重试（可能是业务错误）
                    LogHelper.w(TAG, "⚠️ HTTP状态码: " + responseCode);
                    InputStream is = connection.getErrorStream();
                    if (is != null) {
                        String errorResponse = readInputStream(is);
                        LogHelper.w(TAG, "错误响应: " + errorResponse);
                    }
                    return null;
                }
                
            } catch (java.net.SocketTimeoutException e) {
                lastException = e;
                retryCount++;
                if (retryCount <= maxRetries) {
                    LogHelper.w(TAG, "⚠️ 请求超时，重试 " + retryCount + "/" + maxRetries + ": " + e.getMessage());
                    try {
                        Thread.sleep(500 * retryCount); // 递增延迟：500ms, 1000ms, 1500ms
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    LogHelper.e(TAG, "❌ GET请求超时，已重试 " + maxRetries + " 次", e);
                }
            } catch (java.net.ConnectException e) {
                lastException = e;
                retryCount++;
                if (retryCount <= maxRetries) {
                    LogHelper.w(TAG, "⚠️ 连接失败，重试 " + retryCount + "/" + maxRetries + ": " + e.getMessage());
                    try {
                        Thread.sleep(500 * retryCount); // 递增延迟
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    LogHelper.e(TAG, "❌ GET请求连接失败，已重试 " + maxRetries + " 次", e);
                }
            } catch (Exception e) {
                lastException = e;
                retryCount++;
                if (retryCount <= maxRetries) {
                    LogHelper.w(TAG, "⚠️ GET请求异常，重试 " + retryCount + "/" + maxRetries + ": " + e.getMessage());
                    try {
                        Thread.sleep(500 * retryCount); // 递增延迟
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                } else {
                    LogHelper.e(TAG, "❌ GET请求失败，已重试 " + maxRetries + " 次", e);
                }
            }
        }
        
        // 所有重试都失败
        if (lastException != null) {
            LogHelper.e(TAG, "❌ GET请求最终失败（已重试 " + maxRetries + " 次）", lastException);
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
    
    // ==================== 状态码翻译方法 ====================
    
    /**
     * 翻译发动机状态
     * RUNNING = 运行中, 其他 = 已熄火
     */
    public static String translateEngineStatus(String status) {
        if (status == null || status.isEmpty() || "未知".equals(status)) {
            return "未知";
        }
        if ("RUNNING".equalsIgnoreCase(status) || "RUN".equalsIgnoreCase(status) || 
            "ON".equalsIgnoreCase(status) || "1".equals(status)) {
            return "运行中";
        }
        return "已熄火";
    }
    
    /**
     * 翻译门锁状态
     * 1 = 已锁车, 其他 = 未锁车
     */
    public static String translateDoorLockStatus(String status) {
        if (status == null || status.isEmpty() || "未知".equals(status)) {
            return "未知";
        }
        try {
            int value = Integer.parseInt(status);
            return value == 1 ? "已锁" : "未锁";
        } catch (NumberFormatException e) {
            // 非数字，尝试字符串匹配
            if ("LOCKED".equalsIgnoreCase(status) || "true".equalsIgnoreCase(status)) {
                return "已锁";
            }
            return "未锁";
        }
    }
    
    /**
     * 翻译车门开关状态
     * 0 = 已关闭, 其他 = 已开启
     */
    public static String translateDoorOpenStatus(String status) {
        if (status == null || status.isEmpty() || "未知".equals(status)) {
            return "未知";
        }
        try {
            int value = Integer.parseInt(status);
            return value == 0 ? "已关" : "已开";
        } catch (NumberFormatException e) {
            if ("CLOSED".equalsIgnoreCase(status) || "false".equalsIgnoreCase(status) || "0".equals(status)) {
                return "已关";
            }
            return "已开";
        }
    }
    
    /**
     * 翻译车窗状态
     * 注意：根据实际API返回，0=已关，非 0=已开（开度百分比等亦为“已开”）。
     * <p>{@link VehicleStatusInfo#winStatusDriver} 在 {@link #getVehicleStatus} 中已由本方法从
     * {@link VehicleStatusInfo#winPosDriver} 翻译过一次；调用方若再次传入「已开/已关」须原样返回，
     * 否则会落入未知分支被误判为「已关」。</p>
     */
    public static String translateWindowStatus(String status) {
        if (status == null || status.isEmpty() || "未知".equals(status)) {
            return "未知";
        }
        if ("已开".equals(status) || "已关".equals(status)) {
            return status;
        }
        LogHelper.d(TAG, "🔍 [翻译车窗状态] 原始值: " + status);
        try {
            int value = Integer.parseInt(status);
            // 根据脚本和实际API：0=已关，其他=已开（与天窗逻辑相同）
            String result = (value == 0) ? "已关" : "已开";
            LogHelper.d(TAG, "🔍 [翻译车窗状态] 数值: " + value + " -> " + result);
            return result;
        } catch (NumberFormatException e) {
            String upperStatus = status.toUpperCase();
            if ("CLOSED".equals(upperStatus) || "FALSE".equals(upperStatus) || "0".equals(status)) {
                LogHelper.d(TAG, "🔍 [翻译车窗状态] 字符串(已关): " + status);
                return "已关";
            } else if ("OPEN".equals(upperStatus) || "OPENED".equals(upperStatus) || "TRUE".equals(upperStatus) || "1".equals(status)) {
                LogHelper.d(TAG, "🔍 [翻译车窗状态] 字符串(已开): " + status);
                return "已开";
            }
            // 默认返回已关（保守处理）
            LogHelper.w(TAG, "⚠️ [翻译车窗状态] 未知格式，默认返回已关: " + status);
            return "已关";
        }
    }
    
    /**
     * 翻译后备箱状态
     * 0 = 已关闭, 其他 = 已开启
     */
    public static String translateTrunkStatus(String status) {
        if (status == null || status.isEmpty() || "未知".equals(status)) {
            return "未知";
        }
        try {
            int value = Integer.parseInt(status);
            return value == 0 ? "已关" : "已开";
        } catch (NumberFormatException e) {
            if ("CLOSED".equalsIgnoreCase(status) || "false".equalsIgnoreCase(status)) {
                return "已关";
            }
            return "已开";
        }
    }
    
    /**
     * 翻译天窗状态
     * 注意：根据实际API返回，0=已关，非 0=已开。
     * <p>与 {@link #translateWindowStatus} 相同：{@link VehicleStatusInfo#sunroofOpenStatus} 可能已是「已开/已关」，
     * 勿二次翻译误判。</p>
     */
    public static String translateSunroofStatus(String status) {
        if (status == null || status.isEmpty() || "未知".equals(status)) {
            return "未知";
        }
        if ("已开".equals(status) || "已关".equals(status)) {
            return status;
        }
        LogHelper.d(TAG, "🔍 [翻译天窗状态] 原始值: " + status);
        try {
            int value = Integer.parseInt(status);
            // 根据实际API：0=已关，1=已开（修正：改回正确逻辑）
            String result = (value == 0) ? "已关" : "已开";
            LogHelper.d(TAG, "🔍 [翻译天窗状态] 数值: " + value + " -> " + result);
            return result;
        } catch (NumberFormatException e) {
            String upperStatus = status.toUpperCase();
            if ("CLOSED".equals(upperStatus) || "FALSE".equals(upperStatus) || "0".equals(status)) {
                LogHelper.d(TAG, "🔍 [翻译天窗状态] 字符串(已关): " + status);
                return "已关";
            } else if ("OPEN".equals(upperStatus) || "OPENED".equals(upperStatus) || "TRUE".equals(upperStatus) || "1".equals(status)) {
                LogHelper.d(TAG, "🔍 [翻译天窗状态] 字符串(已开): " + status);
                return "已开";
            }
            // 默认返回已关（保守处理）
            LogHelper.w(TAG, "⚠️ [翻译天窗状态] 未知格式，默认返回已关: " + status);
            return "已关";
        }
    }
    
    /**
     * 翻译布尔状态
     */
    public static String translateBooleanStatus(String status, String trueText, String falseText) {
        if (status == null || status.isEmpty() || "未知".equals(status)) {
            return "未知";
        }
        if ("true".equalsIgnoreCase(status) || "1".equals(status) || "ON".equalsIgnoreCase(status)) {
            return trueText;
        }
        return falseText;
    }
    
    /**
     * 翻译手刹状态
     */
    public static String translateParkBrakeStatus(String status) {
        if (status == null || status.isEmpty() || "未知".equals(status)) {
            return "未知";
        }
        // 常见状态值：APPLIED=已拉起, RELEASED=已释放
        if ("APPLIED".equalsIgnoreCase(status) || "1".equals(status) || "true".equalsIgnoreCase(status)) {
            return "已拉起";
        }
        if ("RELEASED".equalsIgnoreCase(status) || "0".equals(status) || "false".equalsIgnoreCase(status)) {
            return "已释放";
        }
        return status; // 未知状态返回原值
    }
    
    /**
     * 翻译安全带状态
     */
    public static String translateSeatBeltStatus(String status) {
        if (status == null || status.isEmpty() || "未知".equals(status)) {
            return "未知";
        }
        if ("true".equalsIgnoreCase(status) || "1".equals(status) || "FASTENED".equalsIgnoreCase(status)) {
            return "已系";
        }
        return "未系";
    }
    
    /**
     * 翻译警报状态
     */
    public static String translateAlarmStatus(String status) {
        if (status == null || status.isEmpty() || "未知".equals(status)) {
            return "未知";
        }
        if ("ACTIVE".equalsIgnoreCase(status) || "1".equals(status) || "true".equalsIgnoreCase(status)) {
            return "触发";
        }
        return "正常";
    }
    
    /**
     * 翻译防盗状态
     */
    public static String translateTheftStatus(String status) {
        if (status == null || status.isEmpty() || "未知".equals(status)) {
            return "未知";
        }
        if ("true".equalsIgnoreCase(status) || "1".equals(status) || "ACTIVATED".equalsIgnoreCase(status)) {
            return "已激活";
        }
        return "未激活";
    }
    
    /**
     * 翻译挡位状态
     */
    public static String translateGearPosition(String status) {
        if (status == null || status.isEmpty() || "未知".equals(status)) {
            return "未知";
        }
        // 常见挡位值
        switch (status.toUpperCase()) {
            case "P": case "PARK": return "P挡";
            case "R": case "REVERSE": return "R挡";
            case "N": case "NEUTRAL": return "N挡";
            case "D": case "DRIVE": return "D挡";
            case "S": case "SPORT": return "S挡";
            case "M": case "MANUAL": return "M挡";
            default: return status;
        }
    }
    
    /**
     * 翻译巡航控制状态
     */
    public static String translateCruiseControlStatus(String status) {
        if (status == null || status.isEmpty() || "未知".equals(status)) {
            return "未知";
        }
        if ("ACTIVE".equalsIgnoreCase(status) || "ON".equalsIgnoreCase(status) || 
            "1".equals(status) || "true".equalsIgnoreCase(status)) {
            return "开启";
        }
        return "关闭";
    }
    
    /**
     * 翻译空气净化状态
     */
    public static String translateAirCleanStatus(String status) {
        if (status == null || status.isEmpty() || "未知".equals(status)) {
            return "未知";
        }
        if ("ON".equalsIgnoreCase(status) || "1".equals(status) || "true".equalsIgnoreCase(status)) {
            return "开启";
        }
        return "关闭";
    }
    
    /**
     * 翻译预约空调状态
     */
    public static String translatePreClimateStatus(String status) {
        if (status == null || status.isEmpty() || "未知".equals(status)) {
            return "未知";
        }
        if ("true".equalsIgnoreCase(status) || "1".equals(status)) {
            return "已预约";
        }
        return "未预约";
    }
    
    /**
     * 翻译保养状态
     */
    public static String translateServiceWarningStatus(String status) {
        if (status == null || status.isEmpty() || "未知".equals(status)) {
            return "未知";
        }
        // 常见值：OK=正常, WARNING=需保养
        if ("OK".equalsIgnoreCase(status) || "NORMAL".equalsIgnoreCase(status) || "0".equals(status)) {
            return "正常";
        }
        if ("WARNING".equalsIgnoreCase(status) || "1".equals(status)) {
            return "需保养";
        }
        return status;
    }

    /** 车窗/天窗等在 {@link #getVehicleStatus} 中已译为「已开/已关/未知」，供广播与 UI 直接使用 */
    private static String safeStatusDisplay(String s) {
        if (s == null || s.trim().isEmpty()) {
            return "未知";
        }
        return s;
    }

    /**
     * 与设置页 {@code SettingsActivity#loadVehicleStatus} 中「【车辆状态】」「【里程能源】」
     * 的翻译与展示规则一致（含未知项是否出现），供 {@code ACTION_VEHICLE_STATUS_RESULT} 的 {@code data} 使用。
     * <p>另含根级数组 {@code vehicleStatusItems}、{@code mileageEnergyItems}：每项为中文 {@code name} 加固定字段，便于顺序解析；对象 {@code vehicleStatus}、{@code mileageEnergy} 便于按键名取值。</p>
     */
    public static JSONObject buildVehicleQueryBroadcastJson(VehicleStatusInfo status) throws JSONException {
        if (status == null) {
            throw new IllegalArgumentException("status is null");
        }
        JSONObject root = new JSONObject();
        root.put("updateTime", status.updateTime == null ? "" : status.updateTime);
        root.put("updateDateTime", status.updateDateTime == null ? "" : status.updateDateTime);

        String engineT = translateEngineStatus(status.engineStatus);
        String lockT = translateDoorLockStatus(status.doorLockStatusDriver);
        String doorT = translateDoorOpenStatus(status.doorOpenStatusDriver);
        // window/sunroof 已在 getVehicleStatus 中从原始值翻译为「已开/已关」，勿二次 translate
        String windowT = safeStatusDisplay(status.winStatusDriver);
        String trunkT = translateTrunkStatus(status.trunkOpenStatus);
        String sunroofT = safeStatusDisplay(status.sunroofOpenStatus);

        JSONObject vehicleStatus = new JSONObject();
        putQueryFieldIfShownInSettings(vehicleStatus, "发动机", engineT);
        putQueryFieldIfShownInSettings(vehicleStatus, "门锁", lockT);
        putQueryFieldIfShownInSettings(vehicleStatus, "车门", doorT);
        putQueryFieldIfShownInSettings(vehicleStatus, "车窗", windowT);
        putQueryFieldIfShownInSettings(vehicleStatus, "后备箱", trunkT);
        putQueryFieldIfShownInSettings(vehicleStatus, "天窗", sunroofT);
        root.put("vehicleStatus", vehicleStatus);

        JSONArray vehicleStatusItems = new JSONArray();
        addNamedStateItem(vehicleStatusItems, "发动机", engineT);
        addNamedStateItem(vehicleStatusItems, "门锁", lockT);
        addNamedStateItem(vehicleStatusItems, "车门", doorT);
        addNamedStateItem(vehicleStatusItems, "车窗", windowT);
        addNamedStateItem(vehicleStatusItems, "后备箱", trunkT);
        addNamedStateItem(vehicleStatusItems, "天窗", sunroofT);
        root.put("vehicleStatusItems", vehicleStatusItems);

        JSONObject mileageEnergy = new JSONObject();
        putMileageEnergyField(mileageEnergy, "总里程", status.odometer, "km");
        putMileageEnergyField(mileageEnergy, "续航", status.distanceToEmpty, "km");
        putMileageEnergyField(mileageEnergy, "油量", status.fuelLevel, "L");
        putMileageEnergyField(mileageEnergy, "油量%", status.fuelLevelStatus, "%");
        putMileageEnergyField(mileageEnergy, "电瓶", status.voltage, "V");
        putMileageEnergyField(mileageEnergy, "油耗", status.aveFuelConsumption, "L/100km");
        root.put("mileageEnergy", mileageEnergy);

        JSONArray mileageEnergyItems = new JSONArray();
        addNamedMetricItem(mileageEnergyItems, "总里程", status.odometer, "km");
        addNamedMetricItem(mileageEnergyItems, "续航", status.distanceToEmpty, "km");
        addNamedMetricItem(mileageEnergyItems, "油量", status.fuelLevel, "L");
        addNamedMetricItem(mileageEnergyItems, "油量%", status.fuelLevelStatus, "%");
        addNamedMetricItem(mileageEnergyItems, "电瓶", status.voltage, "V");
        addNamedMetricItem(mileageEnergyItems, "油耗", status.aveFuelConsumption, "L/100km");
        root.put("mileageEnergyItems", mileageEnergyItems);

        StringBuilder vsLine = new StringBuilder("【车辆状态】");
        appendCompactLineLikeSettings(vsLine, "发动机", engineT);
        appendCompactLineLikeSettings(vsLine, "门锁", lockT);
        appendCompactLineLikeSettings(vsLine, "车门", doorT);
        appendCompactLineLikeSettings(vsLine, "车窗", windowT);
        appendCompactLineLikeSettings(vsLine, "后备箱", trunkT);
        appendCompactLineLikeSettings(vsLine, "天窗", sunroofT);
        root.put("vehicleStatusText", vsLine.toString());

        StringBuilder meLine = new StringBuilder("【里程能源】");
        appendCompactValueWithUnitLikeSettings(meLine, "总里程", status.odometer, "km");
        appendCompactValueWithUnitLikeSettings(meLine, "续航", status.distanceToEmpty, "km");
        appendCompactValueWithUnitLikeSettings(meLine, "油量", status.fuelLevel, "L");
        appendCompactValueWithUnitLikeSettings(meLine, "油量%", status.fuelLevelStatus, "%");
        appendCompactValueWithUnitLikeSettings(meLine, "电瓶", status.voltage, "V");
        appendCompactValueWithUnitLikeSettings(meLine, "油耗", status.aveFuelConsumption, "L/100km");
        root.put("mileageEnergyText", meLine.toString());

        return root;
    }

    /** 与 {@code SettingsActivity#isUnknown} 一致 */
    private static boolean isStatusValueUnknownForSettings(String value) {
        return value == null || value.isEmpty() || "未知".equals(value) || "null".equalsIgnoreCase(value);
    }

    private static void putQueryFieldIfShownInSettings(JSONObject o, String key, String translatedOrDisplay)
            throws JSONException {
        o.put(key, isStatusValueUnknownForSettings(translatedOrDisplay) ? "" : translatedOrDisplay);
    }

    private static void putMileageEnergyField(JSONObject o, String key, String raw, String unit)
            throws JSONException {
        if (isStatusValueUnknownForSettings(raw) || raw.isEmpty()) {
            o.put(key, "");
        } else {
            o.put(key, raw + unit);
        }
    }

    private static void appendCompactLineLikeSettings(StringBuilder sb, String label, String value) {
        if (!isStatusValueUnknownForSettings(value)) {
            sb.append(" ").append(label).append(":").append(value);
        }
    }

    private static void appendCompactValueWithUnitLikeSettings(StringBuilder sb, String label, String value, String unit) {
        if (!isStatusValueUnknownForSettings(value) && !value.isEmpty()) {
            sb.append(" ").append(label).append(":").append(value).append(unit);
        }
    }

    /** 车辆状态项：中文名 + 状态文案（未知为空串）；顺序固定便于按下标解析 */
    private static void addNamedStateItem(JSONArray arr, String name, String translated) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("state", isStatusValueUnknownForSettings(translated) ? "" : translated);
        arr.put(o);
    }

    /**
     * 里程能源项：中文名 + 与设置页一致的拼接 state（如 500km）+ 拆分的 value / unit（未知时 state、value 为空串）。
     */
    private static void addNamedMetricItem(JSONArray arr, String name, String raw, String unit) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("unit", unit == null ? "" : unit);
        if (isStatusValueUnknownForSettings(raw) || (raw != null && raw.isEmpty())) {
            o.put("state", "");
            o.put("value", "");
        } else {
            o.put("state", raw + unit);
            o.put("value", raw);
        }
        arr.put(o);
    }

    // -------------------------------------------------------------------------
    // 投屏 / 设置页共用的车辆状态展示解析（与 RearScreenCarControlActivity.initCarInfo 规则一致）
    // -------------------------------------------------------------------------

    /**
     * 解析续航里程（{@code distanceToEmpty}，单位 km）。无法解析时返回 -1。
     */
    public static int parseDistanceToEmptyKm(String raw) {
        if (raw == null || raw.isEmpty() || "未知".equals(raw)) {
            return -1;
        }
        try {
            String s = raw.trim().replace(",", "").replace("，", "");
            return (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 投屏 HUD：无效或负值按 0 km 展示（与历史行为一致） */
    public static int distanceToEmptyKmForHud(int parsedKm) {
        return parsedKm < 0 ? 0 : parsedKm;
    }

    /**
     * 解析油量百分比（{@code fuelLevelStatus}）。支持可选 {@code %} 后缀与小数；结果夹在 0–100。
     * 无法解析时返回 -1。
     */
    public static int parseFuelLevelPercent(String raw) {
        if (raw == null || raw.isEmpty() || "未知".equals(raw)) {
            return -1;
        }
        try {
            String s = raw.trim().replace(",", "").replace("，", "");
            if (s.endsWith("%")) {
                s = s.substring(0, s.length() - 1).trim();
            }
            int pct = (int) Math.round(Double.parseDouble(s));
            return Math.min(100, Math.max(0, pct));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 投屏 HUD：解析失败时按 0% 展示 */
    public static int fuelLevelPercentForHud(int parsedPercent) {
        return parsedPercent < 0 ? 0 : parsedPercent;
    }

    /**
     * 总里程展示用整数公里数字符串；无法解析时返回 {@code null}。
     */
    public static String formatOdometerKmDigitsOrNull(String odometerRaw) {
        if (odometerRaw == null || odometerRaw.isEmpty() || "未知".equals(odometerRaw)) {
            return null;
        }
        try {
            String cleaned = odometerRaw.replaceAll("[^0-9.]", "");
            if (cleaned.isEmpty()) {
                return null;
            }
            double odometerValue = Double.parseDouble(cleaned);
            return String.format(Locale.ROOT, "%.0f", odometerValue);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 投屏：无法解析时为「未知」 */
    public static String formatOdometerKmDisplayOrUnknown(String odometerRaw) {
        String d = formatOdometerKmDigitsOrNull(odometerRaw);
        return d != null ? d : "未知";
    }

    /** 温度整数摄氏度数字符串；无法解析时返回 {@code null}（设置页可用占位符代替「未知」） */
    public static String formatTempCelsiusDigitsOrNull(String raw) {
        if (raw == null || raw.isEmpty() || "未知".equals(raw)) {
            return null;
        }
        try {
            double tempValue = Double.parseDouble(raw.trim());
            return String.format(Locale.ROOT, "%.0f", tempValue);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 投屏：无法解析时为「未知」 */
    public static String formatTempCelsiusDigitsOrUnknown(String raw) {
        String d = formatTempCelsiusDigitsOrNull(raw);
        return d != null ? d : "未知";
    }

    /**
     * {@code updateDateTime} 一般为 {@code yyyy-MM-dd HH:mm:ss}；成功则返回 {@code MM-dd HH:mm}，
     * 否则返回 {@code invalidText}（设置页可传资源里的 “--”）。
     */
    public static String formatUpdateTimeShortMmDdHhMm(String updateDateTime, String invalidText) {
        String fallback = invalidText != null ? invalidText : "未知";
        if (updateDateTime == null || updateDateTime.isEmpty() || "未知".equals(updateDateTime)
                || "时间格式错误".equals(updateDateTime)) {
            return fallback;
        }
        try {
            String dateTimeStr = updateDateTime.trim();
            if (!dateTimeStr.contains(" ")) {
                return fallback;
            }
            String[] parts = dateTimeStr.split(" ");
            if (parts.length < 2) {
                return fallback;
            }
            String datePart = parts[0].trim();
            String timePart = parts[1].trim();
            String[] dateParts = datePart.split("-");
            if (dateParts.length < 3) {
                return fallback;
            }
            String month = dateParts[1];
            String day = dateParts[2];
            String[] timeParts = timePart.split(":");
            if (timeParts.length >= 2) {
                return month + "-" + day + " " + timeParts[0] + ":" + timeParts[1];
            }
            if (timePart.length() >= 5) {
                return month + "-" + day + " " + timePart.substring(0, 5);
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /**
     * 背屏车控顶栏等窄位：仅 {@code HH:mm}，与占位 {@code --:--} 宽度接近，避免刷新后文案突然变长。
     * 入参规则同 {@link #formatUpdateTimeShortMmDdHhMm}。
     */
    public static String formatUpdateTimeShortHhMm(String updateDateTime, String invalidText) {
        String fallback = invalidText != null ? invalidText : "未知";
        if (updateDateTime == null || updateDateTime.isEmpty() || "未知".equals(updateDateTime)
                || "时间格式错误".equals(updateDateTime)) {
            return fallback;
        }
        try {
            String dateTimeStr = updateDateTime.trim();
            if (!dateTimeStr.contains(" ")) {
                return fallback;
            }
            String[] parts = dateTimeStr.split(" ");
            if (parts.length < 2) {
                return fallback;
            }
            String timePart = parts[1].trim();
            String[] timeParts = timePart.split(":");
            if (timeParts.length >= 2) {
                return timeParts[0] + ":" + timeParts[1];
            }
            if (timePart.length() >= 5) {
                return timePart.substring(0, 5);
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }
}

