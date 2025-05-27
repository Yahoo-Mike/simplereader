package com.simplereader.util

import java.security.MessageDigest

/**
 * Created by yahoo mike on 22 May 2025
 */
object MiscUtil {
    private val TAG: String = MiscUtil::class.java.getSimpleName()

    fun hashIdentifier(url: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(url.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
