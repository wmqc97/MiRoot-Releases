package com.wmqc.miroot.rear

/** 外部广播：将主屏前台应用迁到背屏（与「切应用到背屏」磁贴一致）。 */
object AppProjectionIntents {
    const val ACTION_APP_PROJECTION = "com.wmqc.miroot.rear.ACTION_APP_PROJECTION"
    const val EXTRA_APP_PROJECTION_OP = "com.wmqc.miroot.rear.EXTRA_APP_PROJECTION_OP"
    const val OP_START = "start"
    const val OP_STOP = "stop"
}
