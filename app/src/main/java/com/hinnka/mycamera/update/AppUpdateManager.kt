package com.hinnka.mycamera.update

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class AppUpdateRelease(
    val versionName: String?,
    val downloadUrl: String,
    val fileName: String
)

object AppUpdateManager {
    private val _readyApk = MutableStateFlow<File?>(null)
    val readyApk: StateFlow<File?> = _readyApk.asStateFlow()

    suspend fun checkForUpdate(currentVersion: String = "", flavor: String = ""): AppUpdateRelease? = null

    fun startSilentUpdate(context: Context) = Unit

    suspend fun downloadApk(context: Context, release: AppUpdateRelease): File {
        throw UnsupportedOperationException("App updates are disabled in this environment.")
    }

    fun consumeReadyApk(apkFile: File?) {
        if (apkFile == null || _readyApk.value == apkFile) {
            _readyApk.value = null
        }
    }

    fun startInstall(context: Context, apkFile: File): Boolean = false
}
