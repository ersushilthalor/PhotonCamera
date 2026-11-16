package com.hinnka.mycamera

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Crash UI hosted in its own process so it can remain visible after the main app process dies.
 * The report is persisted and reopened on the next normal app launch if Android blocks this UI.
 */
class CrashReportActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showReport()
    }

    private fun showReport() {
        val report = CrashLogStore.read(this)
        if (report.isNullOrBlank()) {
            finish()
            return
        }

        val density = resources.displayMetrics.density
        val reportText = TextView(this).apply {
            text = report
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(
                (16 * density).toInt(),
                (12 * density).toInt(),
                (16 * density).toInt(),
                (12 * density).toInt(),
            )
        }
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.58f).toInt(),
            )
            addView(reportText)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.crash_report_title)
            .setView(scrollView)
            .setPositiveButton(R.string.crash_report_copy, null)
            .setNegativeButton(R.string.crash_report_close) { _, _ ->
                CrashLogStore.clear(this)
                finish()
            }
            .setCancelable(false)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                    ClipData.newPlainText(getString(R.string.crash_report_title), report),
                )
                Toast.makeText(this, R.string.crash_report_copied, Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    companion object {
        /** Recover the report if Android prevented the crash-time activity launch. */
        fun showPendingReport(context: Context) {
            if (!CrashLogStore.hasReport(context)) return
            runCatching {
                context.startActivity(
                    Intent(context, CrashReportActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
                )
            }
        }
    }
}

internal object CrashLogger {
    fun install(application: Application) {
        // This activity runs in :crash_report. Do not initialize camera services there.
        if (Application.getProcessName() != application.packageName) return

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                CrashLogStore.save(
                    application,
                    CrashLogStore.createReport(application, thread, error),
                )
            }
            // The separate process keeps this screen alive while Android terminates the crashed
            // app process. The saved file is the fallback if Android blocks activity launches.
            runCatching {
                application.startActivity(
                    Intent(application, CrashReportActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
                    ),
                )
            }

            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}

private object CrashLogStore {
    private const val REPORT_FILE = "last_crash_report.txt"
    private const val MAX_STACKTRACE_CHARS = 300_000

    fun createReport(context: Context, thread: Thread, error: Throwable): String {
        val stackTrace = StringWriter().also { writer ->
            error.printStackTrace(PrintWriter(writer))
        }.toString()
        val boundedStackTrace = if (stackTrace.length > MAX_STACKTRACE_CHARS) {
            stackTrace.take(MAX_STACKTRACE_CHARS) + "\n[Stack trace truncated]"
        } else {
            stackTrace
        }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US).format(Date())
        return buildString {
            appendLine("PhotonCamera crash report")
            appendLine("Time: $timestamp")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Process: ${Application.getProcessName()}")
            appendLine("Thread: ${thread.name} (id=${thread.id})")
            appendLine("Files directory: ${context.filesDir.absolutePath}")
            appendLine()
            append(boundedStackTrace)
        }
    }

    fun save(context: Context, report: String) {
        val target = File(context.filesDir, REPORT_FILE)
        val temporary = File(context.filesDir, "$REPORT_FILE.tmp")
        val bytes = report.toByteArray(Charsets.UTF_8)
        FileOutputStream(temporary).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            FileOutputStream(target).use { stream ->
                stream.write(bytes)
                stream.fd.sync()
            }
            temporary.delete()
        }
    }

    fun hasReport(context: Context): Boolean {
        val file = File(context.filesDir, REPORT_FILE)
        return file.isFile && file.length() > 0L
    }

    fun read(context: Context): String? = runCatching {
        File(context.filesDir, REPORT_FILE).takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { File(context.filesDir, REPORT_FILE).delete() }
    }
}
