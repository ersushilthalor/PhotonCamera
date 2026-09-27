package com.hinnka.mycamera.utils

import android.content.Context

object BuglyHelper {
    fun init(context: Context) {
    }

    fun setUserScene(context: Context, scene: Int) {
    }

    fun putUserData(context: Context, key: String, value: String) {
    }

    fun log(tag: String, msg: String, throwable: Throwable? = null) {
        if (throwable != null) {
            PLog.e(tag, msg, throwable)
        } else {
            PLog.d(tag, msg)
        }
    }

    fun error(throwable: Throwable) {
        PLog.e("BuglyHelper", "Error logged", throwable)
    }
}
