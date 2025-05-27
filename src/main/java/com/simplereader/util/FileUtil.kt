package com.simplereader.util

import android.text.TextUtils
import java.util.Locale

/**
 * Created by Mahavir on 12/15/16.
 * converted to kotlin by yahoo mike on 19 May 2025
 */
object FileUtil {
    private val TAG: String = FileUtil::class.java.getSimpleName()

    fun getExtensionUppercase(path: String?): String? {
        if (TextUtils.isEmpty(path)) return null
        val lastIndexOfDot = path!!.lastIndexOf('.')
        if (lastIndexOfDot == -1) return null
        return path.substring(lastIndexOfDot + 1).uppercase(Locale.getDefault())
    }
}
