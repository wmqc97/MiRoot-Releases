package com.wmqc.miroot.ui.theme

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * 使用 [Intent.ACTION_OPEN_DOCUMENT] 选择文件（便于无后缀主题包出现），
 * 并请求可持久化的 Uri 权限，以便对同一 [Uri] 执行 [android.content.ContentResolver.openOutputStream] 写回。
 *
 * [writable] 为 true 时同时请求读+写持久授权（主题 zip 写回）；为 false 时仅读（效果图等）。
 */
data class PersistableDocumentPick(val uri: Uri, val resultIntent: Intent)

class OpenPersistableDocumentContract(
    private val writable: Boolean,
) : ActivityResultContract<String, PersistableDocumentPick?>() {

    override fun createIntent(context: Context, input: String): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = input.ifEmpty { "*/*" }
            var f = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            if (writable) {
                f = f or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            }
            addFlags(f)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): PersistableDocumentPick? {
        if (resultCode != Activity.RESULT_OK || intent == null) return null
        val uri = intent.data ?: return null
        return PersistableDocumentPick(uri, intent)
    }
}
