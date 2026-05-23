package com.wmqc.miroot.car;

/**
 * 车控外部广播 Action。投屏仅使用 {@code com.wmqc.miroot.car.*}；指令与状态查询可与外部主题共用 tgwgroup 命名空间（兼容历史集成）。
 */
public final class CarControlIntents {
    private CarControlIntents() {}

    public static final String ACTION_OPEN_CAR_CONTROL_PROJECTION =
            "com.wmqc.miroot.car.ACTION_OPEN_CAR_CONTROL_PROJECTION";

    public static final String ACTION_STOP_CAR_CONTROL_PROJECTION =
            "com.wmqc.miroot.car.ACTION_STOP_CAR_CONTROL_PROJECTION";

    /** 与 {@link #VALUE_CAR_PROJECTION_OP_START} / {@link #VALUE_CAR_PROJECTION_OP_STOP} 配合；仅对 {@link #ACTION_OPEN_CAR_CONTROL_PROJECTION} 有效。 */
    public static final String EXTRA_CAR_PROJECTION_OP =
            "com.wmqc.miroot.car.EXTRA_CAR_PROJECTION_OP";

    public static final String VALUE_CAR_PROJECTION_OP_START = "start";

    public static final String VALUE_CAR_PROJECTION_OP_STOP = "stop";

    public static final String ACTION_CAR_CONTROL_COMMAND =
            "com.tgwgroup.MiRearScreenSwitchers.ACTION_CAR_CONTROL_COMMAND";

    public static final String ACTION_CAR_CONTROL_COMMAND_RESULT =
            "com.tgwgroup.MiRearScreenSwitchers.ACTION_CAR_CONTROL_COMMAND_RESULT";

    public static final String ACTION_QUERY_VEHICLE_STATUS =
            "com.tgwgroup.MiRearScreenSwitchers.ACTION_QUERY_VEHICLE_STATUS";

    public static final String ACTION_VEHICLE_STATUS_RESULT =
            "com.tgwgroup.MiRearScreenSwitchers.ACTION_VEHICLE_STATUS_RESULT";

    public static final String ACTION_CAR_CONTROL_COMMAND_MIROOT =
            "com.wmqc.miroot.car.ACTION_CAR_CONTROL_COMMAND";

    public static final String ACTION_CAR_CONTROL_COMMAND_RESULT_MIROOT =
            "com.wmqc.miroot.car.ACTION_CAR_CONTROL_COMMAND_RESULT";

    public static final String ACTION_QUERY_VEHICLE_STATUS_MIROOT =
            "com.wmqc.miroot.car.ACTION_QUERY_VEHICLE_STATUS";

    public static final String ACTION_VEHICLE_STATUS_RESULT_MIROOT =
            "com.wmqc.miroot.car.ACTION_VEHICLE_STATUS_RESULT";

    /** {@link CarControlProjectionService} 可处理的 Intent action。 */
    public static boolean isCarControlProjectionServiceAction(String action) {
        if (action == null) {
            return false;
        }
        return ACTION_OPEN_CAR_CONTROL_PROJECTION.equals(action)
                || ACTION_STOP_CAR_CONTROL_PROJECTION.equals(action);
    }

    /** 是否为「仅停止投屏」类 action（无需再读 extra）。 */
    public static boolean isCarProjectionStopAction(String action) {
        return ACTION_STOP_CAR_CONTROL_PROJECTION.equals(action);
    }

    /** {@link CarControlCommandService} 可处理的 Intent action。 */
    public static boolean isCarControlCommandServiceAction(String action) {
        return ACTION_CAR_CONTROL_COMMAND.equals(action)
                || ACTION_CAR_CONTROL_COMMAND_MIROOT.equals(action);
    }

    /** {@link VehicleStatusQueryService} 可处理的 Intent action。 */
    public static boolean isQueryVehicleStatusServiceAction(String action) {
        return ACTION_QUERY_VEHICLE_STATUS.equals(action)
                || ACTION_QUERY_VEHICLE_STATUS_MIROOT.equals(action);
    }
}
