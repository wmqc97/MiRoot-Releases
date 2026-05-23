package com.wmqc.miroot.lsp

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * Modern Xposed (LSPosed / libxposed) module entry (API 101).
 * 当前无进程内 Hook；汽水等歌词走网络 API（qsgc）与背屏投屏常规链路。
 */
class MiRootLspModule : XposedModule() {

    companion object {
        private const val TAG = "MiRoot-LSP"
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(Log.INFO, TAG, "module loaded: process=${param.processName}")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (!param.isFirstPackage) return
        log(Log.INFO, TAG, "package ready: ${param.packageName}")
    }
}
