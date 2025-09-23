package com.simplereader.sync

import com.simplereader.settings.SettingsEntity
import com.simplereader.util.Encrypted
import com.simplereader.util.decryptToString
import com.simplereader.util.encryptString

// Holds server/user and the *encrypted* password (IV + CT). No plaintext is stored.
// Provides helpers to decrypt for use and to re-encrypt when updating.
data class ServerConfig(
    val server: String,
    val user: String,
    val passwordIv: ByteArray,
    val passwordCt: ByteArray,
) {
    companion object {
        // build from SettingsEntity if fully configured
        fun fromSettings(s: SettingsEntity?): ServerConfig? {
            val server = s?.syncServer?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val user = s.syncUser?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val iv = s.syncPasswordIv ?: return null
            val ct = s.syncPasswordCt ?: return null
            return ServerConfig(server, user, iv, ct)
        }
    }

    fun isFullyConfigured(): Boolean =  server.isNotBlank() &&
                                        user.isNotBlank() &&
                                        passwordIv.isNotEmpty() &&
                                        passwordCt.isNotEmpty()

    // decrypts and returns the password as a String (or null on failure)
    fun decryptPassword(secretKey: javax.crypto.SecretKey): String? =
        runCatching { decryptToString(secretKey, Encrypted(passwordIv, passwordCt)) }.getOrNull()

//    // returns a *new* ServerConfig with the password re-encrypted from plaintext
//    fun encryptPassword(secretKey: javax.crypto.SecretKey, plain: String): ServerConfig {
//        val enc: Encrypted = encryptString(secretKey, plain)
//        return copy(passwordIv = enc.iv, passwordCt = enc.ct)
//    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ServerConfig) return false
        return server == other.server &&
                user == other.user &&
                passwordIv.contentEquals(other.passwordIv) &&
                passwordCt.contentEquals(other.passwordCt)
    }

    override fun hashCode(): Int {
        var result = server.hashCode()
        result = 31 * result + user.hashCode()
        result = 31 * result + passwordIv.contentHashCode()
        result = 31 * result + passwordCt.contentHashCode()
        return result
    }
}