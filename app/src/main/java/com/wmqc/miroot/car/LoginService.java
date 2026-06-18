
package com.wmqc.miroot.car;

import com.wmqc.miroot.lyrics.LogHelper;
import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * 登录服务类
 * 将shell脚本逻辑转换为Java代码
 */
public class LoginService {
    private static final String TAG = "LoginService";
    
    // API地址
    private static final String LOGIN_URL = "https://api.xchanger.cn/api/v1/user/login";
    private static final String VEHICLE_URL = "https://api.xchanger.cn/device_platform/user/vehicle";
    
    // API Headers
    private static final String APP_ID = "xiaokanl";
    private static final String ACCEPT = "application/json;responseformat=3";
    
    /**
     * 登录接口
     * @param context 上下文
     * @param username 用户名
     * @param password 密码
     * @return LoginResult 登录结果
     */
    public static LoginResult login(Context context, String username, String password) {
        LoginResult result = new LoginResult();
        
        try {
            // MD5加密密码
            String md5Password = md5(password);
            LogHelper.d(TAG, "🔐 密码MD5: " + md5Password);
            
            // 构建POST请求体
            String postData = "password=" + md5Password + "&username=" + username;
            
            // 发送登录请求
            String response = sendPostRequest(LOGIN_URL, postData);
            LogHelper.d(TAG, "📡 登录接口原始返回: " + response);
            
            if (response == null || response.isEmpty()) {
                result.success = false;
                result.message = "网络请求失败";
                return result;
            }
            
            // 解析JSON响应
            JSONObject jsonResponse = new JSONObject(response);
            boolean success = jsonResponse.optBoolean("success", false);
            
            if (success) {
                JSONObject data = jsonResponse.optJSONObject("data");
                if (data != null) {
                    result.success = true;
                    result.accessToken = data.optString("accessToken", "");
                    result.userId = data.optString("userId", "");
                    result.refreshToken = data.optString("refreshToken", "");
                    result.expiresIn = data.optInt("expiresIn", 0);
                    
                    LogHelper.d(TAG, "✅ 登录成功 - userId: " + result.userId + ", expiresIn: " + result.expiresIn + "秒");
                    
                    // 清除登录失效标记（登录成功）
                    clearLoginInvalidFlag(context);
                    
                    // 保存登录信息到文件（包含时间戳和过期时间）
                    saveLoginInfo(context, response, result.expiresIn);
                    
                    // 获取车辆信息
                    VehicleInfo vehicleInfo = getVehicleInfo(result.accessToken, result.userId);
                    if (vehicleInfo != null) {
                        result.vehicleInfo = vehicleInfo;
                        // 将车辆信息追加到登录信息文件
                        appendVehicleInfo(context, vehicleInfo);
                    }
                } else {
                    result.success = false;
                    result.message = "响应数据格式错误";
                }
            } else {
                result.success = false;
                result.message = jsonResponse.optString("message", "登录失败");
                LogHelper.e(TAG, "❌ 登录失败: " + result.message);
            }
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 登录异常", e);
            result.success = false;
            result.message = "登录异常: " + e.getMessage();
        }
        
        return result;
    }
    
    /**
     * 获取车辆信息
     * @param accessToken 访问令牌
     * @param userId 用户ID
     * @return VehicleInfo 车辆信息
     */
    public static VehicleInfo getVehicleInfo(String accessToken, String userId) {
        try {
            String url = VEHICLE_URL + "?userId=" + userId;
            String response = sendGetRequest(url, accessToken);
            
            if (response == null || response.isEmpty()) {
                LogHelper.e(TAG, "❌ 获取车辆信息失败: 响应为空");
                return null;
            }
            
            LogHelper.dResponsePreview(TAG, "🚗 车辆信息接口返回", response, 512);
            
            // 解析JSON响应
            JSONObject jsonResponse = new JSONObject(response);
            
            // 检查响应是否成功
            boolean success = jsonResponse.optBoolean("success", false);
            if (!success) {
                LogHelper.e(TAG, "❌ 车辆信息接口返回失败");
                return null;
            }
            
            // 从 data.list[0] 中获取车辆信息
            JSONObject data = jsonResponse.optJSONObject("data");
            if (data == null) {
                LogHelper.e(TAG, "❌ 车辆信息 data 为空");
                return null;
            }
            
            org.json.JSONArray list = data.optJSONArray("list");
            if (list == null || list.length() == 0) {
                LogHelper.e(TAG, "❌ 车辆信息 list 为空或长度为0");
                return null;
            }
            
            // 获取第一辆车的信息（通常是最新的或默认的）
            JSONObject vehicleData = list.getJSONObject(0);
            VehicleInfo vehicleInfo = new VehicleInfo();
            vehicleInfo.vin = vehicleData.optString("vin", "");
            vehicleInfo.colorName = vehicleData.optString("colorName", "");
            vehicleInfo.modelName = vehicleData.optString("modelName", "");
            vehicleInfo.plateNo = vehicleData.optString("plateNo", "");
            vehicleInfo.seriesName = vehicleData.optString("seriesName", "");
            vehicleInfo.ihuId = vehicleData.optString("ihuId", "");
            vehicleInfo.iccid = vehicleData.optString("iccid", "");
            vehicleInfo.modelCode = vehicleData.optString("modelCode", "");
            
            LogHelper.d(TAG, "✅ 车辆信息 - 车架号: " + vehicleInfo.vin + 
                      ", 车牌: " + vehicleInfo.plateNo +
                      ", 车系: " + vehicleInfo.seriesName +
                      ", 车型: " + vehicleInfo.modelName +
                      ", 颜色: " + vehicleInfo.colorName +
                      ", 车机ID: " + vehicleInfo.ihuId +
                      ", SIM卡ICCID: " + vehicleInfo.iccid +
                      ", 车型编码: " + vehicleInfo.modelCode);
            
            return vehicleInfo;
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 获取车辆信息异常", e);
            return null;
        }
    }
    
    /**
     * 发送POST请求
     */
    private static String sendPostRequest(String urlString, String postData) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        
        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("x-app-id", APP_ID);
            connection.setRequestProperty("accept", ACCEPT);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            connection.setDoOutput(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            
            // 写入POST数据
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = postData.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
            
            // 读取响应
            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                InputStream is = connection.getInputStream();
                return readInputStream(is);
            } else {
                InputStream is = connection.getErrorStream();
                if (is != null) {
                    return readInputStream(is);
                }
                return null;
            }
        } finally {
            connection.disconnect();
        }
    }
    
    /**
     * 发送GET请求
     * @param urlString 请求URL
     * @param accessToken 访问令牌（authorization header）
     * @return 响应内容，如果HTTP状态码为401/403则抛出IOException
     * @throws IOException 如果请求失败或HTTP状态码为401/403（表示未授权/过期）
     */
    private static String sendGetRequest(String urlString, String accessToken) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        
        try {
            connection.setRequestMethod("GET");
            connection.setRequestProperty("accept", ACCEPT);
            connection.setRequestProperty("authorization", accessToken);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            
            int responseCode = connection.getResponseCode();
            
            // 如果HTTP状态码为401（未授权）或403（禁止访问），说明token已过期
            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED || 
                responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                LogHelper.d(TAG, "⚠️ HTTP状态码 " + responseCode + "（未授权/禁止访问），token已过期");
                throw new IOException("HTTP " + responseCode + " - Token expired or invalid");
            }
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                InputStream is = connection.getInputStream();
                return readInputStream(is);
            } else {
                // 其他HTTP错误，读取错误流内容
                InputStream is = connection.getErrorStream();
                if (is != null) {
                    return readInputStream(is);
                }
                throw new IOException("HTTP " + responseCode + " - Request failed");
            }
        } finally {
            connection.disconnect();
        }
    }
    
    /**
     * 读取输入流
     */
    private static String readInputStream(InputStream is) throws IOException {
        StringBuilder response = new StringBuilder();
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
            response.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
        }
        return response.toString();
    }
    
    /**
     * MD5加密
     */
    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : messageDigest) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ MD5加密失败", e);
            return "";
        }
    }
    
    /**
     * 保存登录信息到应用专属临时文件夹（包含登录时间戳和过期时间，用于过期检查）
     */
    private static void saveLoginInfo(Context context, String jsonResponse, int expiresIn) {
        try {
            // 获取应用专属临时文件夹（应用私有目录，不会丢失）
            File tempDir = context.getFilesDir(); // 使用 files 目录而不是 cache 目录，确保数据持久化
            File loginFile = new File(tempDir, "0.json");
            
            // 解析JSON响应，添加登录时间戳和过期时间
            JSONObject json = new JSONObject(jsonResponse);
            long currentTime = System.currentTimeMillis() / 1000; // 当前时间戳（秒）
            long expireTime = currentTime + expiresIn; // 过期时间戳（秒）
            
            // 添加登录时间和过期时间到data对象
            JSONObject data = json.optJSONObject("data");
            if (data != null) {
                data.put("loginTime", currentTime);
                data.put("expireTime", expireTime);
                data.put("expiresIn", expiresIn);
            }
            
            // 写入JSON响应
            try (FileWriter writer = new FileWriter(loginFile, false)) {
                writer.write(json.toString());
            }
            
            LogHelper.d(TAG, "✅ 登录信息已保存到: " + loginFile.getAbsolutePath() + 
                      " (登录时间: " + currentTime + ", 过期时间: " + expireTime + ")");
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 保存登录信息失败", e);
        }
    }
    
    /**
     * 将车辆信息追加到登录信息文件（按照shell脚本格式：在success字段之前插入）
     * 参考 car/1登入.sh 中的逻辑：sed -i.bak "/\"success\":true/ i\  \"vin\":\"$vin\"..."
     * 为了简化实现，直接将字段添加到根对象中，这样更容易提取（参考 car/提取key.sh）
     */
    private static void appendVehicleInfo(Context context, VehicleInfo vehicleInfo) {
        try {
            // 使用应用私有目录（files），确保数据持久化
            File loginFile = getLoginInfoFile(context);
            
            if (!loginFile.exists()) {
                LogHelper.w(TAG, "⚠️ 登录信息文件不存在，无法追加车辆信息");
                return;
            }
            
            // 读取现有内容
            String existingContent = readFile(loginFile);
            existingContent = existingContent.trim(); // 去除首尾空白
            
            // 解析JSON
            JSONObject json = new JSONObject(existingContent);
            
            // 添加车辆信息字段到根对象（参考shell脚本，直接在根对象添加，方便提取）
            // 这些字段可以在提取key时直接从根对象读取
            // 注意：如果字段已存在，put 会覆盖；如果不存在，会添加
            json.put("vin", vehicleInfo.vin);
            json.put("colorName", vehicleInfo.colorName);
            json.put("modelName", vehicleInfo.modelName);
            json.put("plateNo", vehicleInfo.plateNo);
            json.put("seriesName", vehicleInfo.seriesName);
            json.put("ihuId", vehicleInfo.ihuId);
            json.put("iccid", vehicleInfo.iccid);
            json.put("modelCode", vehicleInfo.modelCode);
            
            // 写入文件
            try (FileWriter writer = new FileWriter(loginFile, false)) {
                writer.write(json.toString());
            }
            
            LogHelper.d(TAG, "✅ 车辆信息已追加到登录文件 - 车架号: " + vehicleInfo.vin + 
                      ", 车牌: " + vehicleInfo.plateNo +
                      ", 车系: " + vehicleInfo.seriesName +
                      ", 车型: " + vehicleInfo.modelName +
                      ", 颜色: " + vehicleInfo.colorName +
                      ", 车机ID: " + vehicleInfo.ihuId +
                      ", SIM卡ICCID: " + vehicleInfo.iccid +
                      ", 车型编码: " + vehicleInfo.modelCode);
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 追加车辆信息失败", e);
        }
    }
    
    /**
     * 读取文件内容（去除换行符，保持原始格式）
     */
    private static String readFile(File file) throws IOException {
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
     * 检查登录信息是否过期（通过实际调用API获取状态信息来判断）
     * 如果状态信息获取失败，则判断为过期，弹出登录界面更新
     * @param context 上下文
     * @return true表示过期或不存在，false表示未过期
     */
    public static boolean isLoginExpired(Context context) {
        try {
            File filesDir = context.getFilesDir();
            File loginFile = new File(filesDir, "0.json");
            
            if (!loginFile.exists()) {
                LogHelper.d(TAG, "⚠️ 登录文件不存在，需要登录");
                return true;
            }
            
            String content = readFile(loginFile);
            content = content.trim();
            
            // 处理可能的格式问题
            int jsonStart = content.indexOf('{');
            if (jsonStart > 0) {
                content = content.substring(jsonStart);
            }
            
            JSONObject json = new JSONObject(content);
            
            // 检查是否有data对象
            JSONObject data = json.optJSONObject("data");
            if (data == null) {
                LogHelper.d(TAG, "⚠️ 登录文件格式错误，需要重新登录");
                return true;
            }
            
            // 检查accessToken和userId是否存在
            String accessToken = data.optString("accessToken", "");
            String userId = data.optString("userId", "");
            if (accessToken.isEmpty() || userId.isEmpty()) {
                LogHelper.d(TAG, "⚠️ accessToken或userId为空，需要登录");
                return true;
            }
            
            // 去掉过期检查：只检查本地是否有登录信息，不进行API验证
            // 实际的API调用（如获取车辆信息）会决定是否需要登录
            // 如果API调用失败（如401/403），用户可以手动登录更新
            LogHelper.d(TAG, "✅ 登录信息存在（accessToken和userId都存在），认为未过期（不进行API验证）");
            return false;
            
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 检查登录过期状态异常（可能是网络错误或token过期）", e);
            return true; // 出错时返回过期，要求重新登录
        }
    }
    
    /**
     * 获取登录信息文件路径（应用私有目录）
     */
    public static File getLoginInfoFile(Context context) {
        File filesDir = context.getFilesDir();
        return new File(filesDir, "0.json");
    }
    
    /**
     * 标记登录失效（当API调用返回401/403时调用）
     * @param context 上下文
     */
    public static void markLoginInvalid(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE);
            prefs.edit().putBoolean("loginInvalid", true).apply();
            LogHelper.d(TAG, "⚠️ 已标记登录失效（API返回401/403）");
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 标记登录失效失败", e);
        }
    }
    
    /**
     * 清除登录失效标记（登录成功后调用）
     * @param context 上下文
     */
    public static void clearLoginInvalidFlag(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE);
            prefs.edit().putBoolean("loginInvalid", false).apply();
            LogHelper.d(TAG, "✅ 已清除登录失效标记");
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 清除登录失效标记失败", e);
        }
    }
    
    /**
     * 检查登录是否被标记为失效（API调用失败时标记）
     * @param context 上下文
     * @return true表示已失效，false表示未失效
     */
    public static boolean isLoginMarkedInvalid(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences("LoginPrefs", Context.MODE_PRIVATE);
            return prefs.getBoolean("loginInvalid", false);
        } catch (Exception e) {
            LogHelper.e(TAG, "❌ 检查登录失效标记失败", e);
            return false;
        }
    }
    
    /**
     * 登录结果类
     */
    public static class LoginResult {
        public boolean success = false;
        public String message = "";
        public String accessToken = "";
        public String userId = "";
        public String refreshToken = "";
        public int expiresIn = 0;
        public VehicleInfo vehicleInfo = null;
    }
    
    /**
     * 车辆信息类（包含所有需要保存到0.json的字段）
     */
    public static class VehicleInfo {
        public String vin = "";           // 车架号
        public String colorName = "";     // 颜色
        public String modelName = "";     // 车型
        public String plateNo = "";       // 车牌
        public String seriesName = "";    // 车系
        public String ihuId = "";         // 车机ID
        public String iccid = "";         // SIM卡ICCID
        public String modelCode = "";     // 车型编码
        
        @Override
        public String toString() {
            return "车架号: " + vin + "\n" +
                   "车牌: " + plateNo + "\n" +
                   "车系: " + seriesName + "\n" +
                   "车型: " + modelName + "\n" +
                   "颜色: " + colorName + "\n" +
                   "车机ID: " + ihuId + "\n" +
                   "SIM卡ICCID: " + iccid + "\n" +
                   "车型编码: " + modelCode;
        }
    }
}

