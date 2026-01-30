package com.simplereader.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

private const val KEY_ALIAS = "simplereader.sync_password_key"

data class Encrypted(val iv: ByteArray, val ct: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Encrypted) return false
        return iv.contentEquals(other.iv) && ct.contentEquals(other.ct)
    }

    override fun hashCode(): Int {
        var result = iv.contentHashCode()
        result = 31 * result + ct.contentHashCode()
        return result
    }
}

fun getOrCreateSecretKey(): SecretKey {
    val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    ks.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }

    val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
    val spec = KeyGenParameterSpec.Builder(
        KEY_ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true)
        .build()
    kg.init(spec)
    return kg.generateKey()
}

fun encryptString(secretKey: SecretKey, plain: String): Encrypted {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, secretKey)
    val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    return Encrypted(iv = cipher.iv, ct = ct)
}

fun decryptToString(secretKey: SecretKey, enc: Encrypted): String {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, enc.iv))
    val bytes = cipher.doFinal(enc.ct)
    return String(bytes, Charsets.UTF_8)
}

// ensure that server is in form:  "https://my.domain:port/"
// returns normalised base address.  If there's an error, it just returns "base"
fun normaliseServerBase(base: String): String {
    var s = base.trim()
    if (s.isEmpty()) return base

    // add/force HTTPS scheme
    s = when {
        "://" !in s -> "https://$s"
        s.startsWith("http://", ignoreCase = true) ->
            "https://${s.substringAfter("://")}"
        else -> s
    }

    // parse & rebuild as scheme + host +  port + root path
    val url = s.toHttpUrlOrNull() ?: return base
    val normalized = url.newBuilder()
        .encodedPath("/")   // drop any user-entered path
        .query(null)
        .fragment(null)
        .build()
        .toString()

    return normalized.trimEnd('/') + "/"  // ensure trailing slash
}

fun sha256Hex(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { ins ->
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = ins.read(buf); if (n <= 0) break
            md.update(buf, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

fun JSONObject.getNullableString(key: String): String? {
    return this.opt(key)
        ?.takeIf { it != JSONObject.NULL }
        ?.toString()
        ?.takeUnless { it.isBlank() }
}
