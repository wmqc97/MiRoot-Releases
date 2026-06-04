package com.wmqc.miroot.record

/**
 * 背屏录屏路径约定：
 * - **视频**：shell 写公共目录（screenrecord 需 shell 可直接创建文件）
 * - **PID**：`/data/local/tmp`（shell 可写，不涉及分区存储权限）
 *
 * Shizuku 约束见 docs/Shizuku权限注意事项.md
 */
object RecordPaths {

    /** screenrecord 原始 MP4（特权 shell 写入）。 */
    const val SHELL_CAPTURE_DIR = "/sdcard/MiRoot/record_capture"

    /** screenrecord PID，仅 shell 读写。 */
    const val SHELL_WORK_DIR = "/data/local/tmp/miroot_record"
    const val SHELL_PID_FILE = "$SHELL_WORK_DIR/screenrecord.pid"
}
