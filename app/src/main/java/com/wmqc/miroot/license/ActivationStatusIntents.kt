package com.wmqc.miroot.license

/**
 * 外部广播：查询 MiRoot 是否已通过离线激活（与 [OfflineActivationRepository.isActivated] 一致）。
 */
object ActivationStatusIntents {

    /** 查询请求 Action。 */
    const val ACTION_QUERY_ACTIVATION_STATUS =
        "com.wmqc.miroot.license.ACTION_QUERY_ACTIVATION_STATUS"

    /** 查询回执的默认 Action（可通过 Extra [EXTRA_ACTIVATION_REPLY_ACTION] 自定义）。 */
    const val ACTION_ACTIVATION_STATUS_RESULT =
        "com.wmqc.miroot.license.ACTION_ACTIVATION_STATUS_RESULT"

    /**
     * 回执与文档约定：是否已激活。
     * 值为字符串 `"true"` / `"false"`（与音乐投屏 `running`、车控 `success` 同为字符串，便于 MAML 等）。
     */
    const val EXTRA_ACTIVATED = "activated"

    /** 与车控、音乐投屏查询一致：可选，回执中原样带回。 */
    const val EXTRA_REQUEST_ID = "requestId"

    /** 可选；自定义回执 Action，不传则使用 [ACTION_ACTIVATION_STATUS_RESULT]。 */
    const val EXTRA_REPLY_ACTION = "replyAction"
}
