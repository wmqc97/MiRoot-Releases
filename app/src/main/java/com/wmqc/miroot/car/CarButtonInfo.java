
package com.wmqc.miroot.car;

import androidx.annotation.NonNull;

/**
 * 车控按钮的完整状态描述。
 * 每个按钮绑定一个 FunctionKey，与云端车辆状态对应。
 * 按钮文本 = 当前状态下将要执行的动作（如"解锁"表示按下去会解锁）。
 */
public class CarButtonInfo {

    /** 功能键 — 唯一标识一个车控功能 */
    public enum FunctionKey {
        LOCK(false),               // 解锁/锁车 (toggle)
        WINDOW(false),             // 开窗/关窗 (toggle)
        ENGINE(false),             // 点火/熄火 (toggle)
        AIR_CONDITIONER(false),    // 打开空调/关闭空调 (on/off)
        SEAT_HEATING(false),      // 打开座椅加热/关闭座椅加热 (on/off)
        SEAT_HEAT_DRIVER(false),  // 主驾加热 (one-way on)
        SEAT_HEAT_PASSENGER(false),// 副驾加热 (one-way on)
        TRUNK(true),              // 后备箱 (one-shot)
        FIND_CAR(true),           // 寻车 (one-shot)
        VENTILATE(true),          // 透气 (one-shot)
        NAVIGATE(true);           // 导航到车 (one-shot)

        public final boolean isOneShot;

        FunctionKey(boolean isOneShot) {
            this.isOneShot = isOneShot;
        }

        /** Toggle/ON_OFF 类按钮支持状态翻转和轮询确认 */
        public boolean supportsStatePoll() {
            // 空调和座椅加热无远程 API 状态字段，走本地 pref 不轮询
            return this == LOCK || this == WINDOW || this == ENGINE || this == TRUNK;
        }
    }

    /** 按钮行为分类 */
    public enum Kind {
        TOGGLE_PAIR,    // 双向切换: 解锁↔锁车, 开窗↔关窗, 点火↔熄火
        ON_OFF,         // 开/关: 打开空调↔关闭空调, 座椅加热
        ONE_SHOT,       // 单次触发: 寻车、透气、后备箱（按钮文本不变）
        ONE_WAY_ON      // 单次开启（关闭需走独立操作）: 主驾加热
    }

    /** 功能键 */
    public final FunctionKey key;
    /** 行为类型 */
    public final Kind kind;

    // ── 运行时可变状态 ──

    /** 远程真实状态: true = 已锁/已开/已点火等活跃态 */
    public volatile boolean remoteOn;

    /** 本地是否正在等待远程状态同步（乐观更新后置为true） */
    public volatile boolean localPending;

    /** 本地乐观更新发起时间戳（用于超时判断） */
    public volatile long pendingSinceMs;

    /** 当前轮询重试次数 */
    public volatile int pollRetryCount;

    public CarButtonInfo(FunctionKey key, Kind kind) {
        this.key = key;
        this.kind = kind;
        this.remoteOn = false;
        this.localPending = false;
        this.pendingSinceMs = 0;
        this.pollRetryCount = 0;
    }

    // ── 派生属性 ──

    /** 根据 remoteOn 派生出当前应显示的按钮文本（=按下去会执行的动作） */
    @NonNull
    public String displayText() {
        switch (key) {
            case LOCK:              return remoteOn ? "解锁" : "锁车";
            case WINDOW:            return remoteOn ? "关窗" : "开窗";
            case ENGINE:            return remoteOn ? "熄火" : "点火";
            case AIR_CONDITIONER:   return remoteOn ? "关闭空调" : "打开空调";
            case SEAT_HEATING:      return remoteOn ? "关闭座椅加热" : "打开座椅加热";
            case SEAT_HEAT_DRIVER:  return remoteOn ? "关闭主驾加热" : "主驾加热";
            case SEAT_HEAT_PASSENGER: return remoteOn ? "关闭副驾加热" : "副驾加热";
            case TRUNK:             return "尾箱";
            case FIND_CAR:          return "寻车";
            case VENTILATE:         return "透气";
            case NAVIGATE:          return "导航到车";
            default:                return "";
        }
    }

    /** 按钮文本翻转（TOGGLE_PAIR/ON_OFF/ONE_WAY_ON 有效） */
    @NonNull
    public String toggledText() {
        switch (key) {
            case LOCK:              return remoteOn ? "锁车" : "解锁";
            case WINDOW:            return remoteOn ? "开窗" : "关窗";
            case ENGINE:            return remoteOn ? "点火" : "熄火";
            case AIR_CONDITIONER:   return remoteOn ? "打开空调" : "关闭空调";
            case SEAT_HEATING:      return remoteOn ? "打开座椅加热" : "关闭座椅加热";
            case SEAT_HEAT_DRIVER:  return remoteOn ? "主驾加热" : "关闭主驾加热";
            case SEAT_HEAT_PASSENGER: return remoteOn ? "副驾加热" : "关闭副驾加热";
            default:                return displayText();
        }
    }

    /** 是否可以通过远程 API 轮询确认状态变更 */
    public boolean supportsPolling() {
        return key.supportsStatePoll();
    }

    /** 清除 pending 并重置重试计数 */
    public void clearPending() {
        this.localPending = false;
        this.pendingSinceMs = 0;
        this.pollRetryCount = 0;
    }

    @NonNull
    @Override
    public String toString() {
        return key + "{remoteOn=" + remoteOn + ", pending=" + localPending
                + ", text=" + displayText() + "}";
    }

    // ── 静态工厂（从按钮文本推断 FunctionKey） ──

    /** 根据按钮中文文本查找对应的 FunctionKey */
    public static FunctionKey resolveKey(String buttonText) {
        if (buttonText == null) return null;
        switch (buttonText) {
            case "解锁": case "锁车":                         return FunctionKey.LOCK;
            case "开窗": case "关窗":                         return FunctionKey.WINDOW;
            case "点火": case "熄火":                         return FunctionKey.ENGINE;
            case "打开空调": case "关闭空调":                   return FunctionKey.AIR_CONDITIONER;
            case "打开座椅加热": case "关闭座椅加热":           return FunctionKey.SEAT_HEATING;
            case "主驾加热": case "关闭主驾加热":               return FunctionKey.SEAT_HEAT_DRIVER;
            case "副驾加热": case "关闭副驾加热":               return FunctionKey.SEAT_HEAT_PASSENGER;
            case "尾箱": case "开后备箱": case "打开尾箱": case "打开后备箱": return FunctionKey.TRUNK;
            case "寻车":                                      return FunctionKey.FIND_CAR;
            case "透气":                                      return FunctionKey.VENTILATE;
            case "导航到车":                                  return FunctionKey.NAVIGATE;
            default:                                          return null;
        }
    }

    /** 根据 FunctionKey 创建默认实例（remoteOn=false） */
    public static CarButtonInfo fromKey(FunctionKey key) {
        if (key == null) return null;
        switch (key) {
            case LOCK:    return new CarButtonInfo(key, Kind.TOGGLE_PAIR);
            case WINDOW:  return new CarButtonInfo(key, Kind.TOGGLE_PAIR);
            case ENGINE:  return new CarButtonInfo(key, Kind.TOGGLE_PAIR);
            case AIR_CONDITIONER: return new CarButtonInfo(key, Kind.ON_OFF);
            case SEAT_HEATING:    return new CarButtonInfo(key, Kind.ON_OFF);
            case SEAT_HEAT_DRIVER:  return new CarButtonInfo(key, Kind.ONE_WAY_ON);
            case SEAT_HEAT_PASSENGER: return new CarButtonInfo(key, Kind.ONE_WAY_ON);
            case TRUNK:     return new CarButtonInfo(key, Kind.ONE_SHOT);
            case FIND_CAR:  return new CarButtonInfo(key, Kind.ONE_SHOT);
            case VENTILATE: return new CarButtonInfo(key, Kind.ONE_SHOT);
            case NAVIGATE:  return new CarButtonInfo(key, Kind.ONE_SHOT);
            default:        return null;
        }
    }
}
