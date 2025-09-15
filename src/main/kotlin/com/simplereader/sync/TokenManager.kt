package com.simplereader.sync

import android.content.Context
import android.util.Log
import com.simplereader.data.ReaderDatabase
import com.simplereader.settings.Settings.Companion.DEFAULT_FONT
import com.simplereader.settings.Settings.Companion.DEFAULT_FONT_SIZE
import com.simplereader.settings.SettingsEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

import com.simplereader.util.MiscUtil
import com.simplereader.util.getOrCreateSecretKey

// keeps a track of token sent back from the sync server
// note: is a kotlin "object" not a class, which makes it a threadsafe singleton
object TokenManager {
    @Volatile private var currentConfig: ServerConfig? = null
    @Volatile private var authToken: String? = null
    @Volatile private var tokenExpiry: Long = 0L

    private val mutex = Mutex()  // to protect vars while updating

    private val cryptoKey by lazy { getOrCreateSecretKey() }
    private val deviceName by lazy { getThisDeviceName() }
    private const val libVersion = com.simplereader.BuildConfig.SIMPLEREADER_VERSION

    private val TAG: String = MiscUtil::class.java.simpleName
    private const val DEVICE_NOT_FOUND = "android"

    fun getServerName() = currentConfig?.server

    // RETURNS: true if server config was changed, false if unchanged
    suspend fun updateServerConfig (ctx: Context, newCfg: ServerConfig?) : Boolean {
        mutex.withLock {
            if (currentConfig != newCfg) {

                currentConfig = newCfg
                authToken = null            // invalidate the token
                tokenExpiry = 0L

                // persist the config changes in Settings table
                val dao = ReaderDatabase.getInstance(ctx).settingsDao()
                dao.insertIfMissing(SettingsEntity(
                    id = 1,
                    font = DEFAULT_FONT.name,
                    fontSize = DEFAULT_FONT_SIZE,
                    syncServer = null,
                    syncUser = null,
                    syncPasswordIv = null,
                    syncPasswordCt = null
                ))
                dao.updateSyncServerAndUser(currentConfig?.server, currentConfig?.user)
                dao.updateSyncPassword(currentConfig?.passwordIv, currentConfig?.passwordCt)

                return true
            }
        }
        return false
    }

    fun isConnected(): Boolean =
        authToken != null && tokenExpiry > System.currentTimeMillis()

    // disconnects current server session  (clears token)
    // note: does not server credentials - use updateServerConfig(ctx,null) to clear credentials
    suspend fun clearToken() {
        mutex.withLock {
            authToken = null
            tokenExpiry = 0L

            Log.i(TAG, "disconnected from simplereaderd server")
        }
    }

    //
    // Ensure a token that won’t expire within [minTtlMs].
    // Re-login if needed; returns the usable token or null (not configured / login failed).
    //
    suspend fun getToken(ctx: Context): String? {
        val leeway = 120000   // 2 minutes leeway before token expires (in msecs)

        if (authToken != null) {
            if (tokenExpiry - System.currentTimeMillis() > leeway)
                return authToken
        }

        // serialize refresh & double-check inside
        return mutex.withLock {

            // check again, because we might have been waiting for the mutex and someone else rereshed the token
            if (authToken != null) {
                if (tokenExpiry - System.currentTimeMillis() > leeway)
                    return@withLock authToken
            }

            // try to refresh the token
            if (currentConfig==null) {
                // try and get server config from the Settings db table
                val daoSettings = ReaderDatabase.getInstance(ctx).settingsDao()

                currentConfig = ServerConfig.fromSettings(daoSettings.getSettings())
                if (currentConfig == null) { // still no credentials to use to login
                    return@withLock null     // no server config, so don't bother trying
                }
            }

            val result = login(currentConfig!!)
            if (result.ok) {
                authToken = result.token
                tokenExpiry = result.expiresAt

                Log.i(TAG, "connected to simplereaderd server")
            } else {
                // could not login with these credentials
                authToken = null
                tokenExpiry = 0
            }
            return@withLock authToken
        }
    }

    // find name of his device
    private fun getThisDeviceName() : String {
        val manufacturer = android.os.Build.MANUFACTURER?.trim().orEmpty()
        val model = android.os.Build.MODEL?.trim().orEmpty()
        return when {
            model.isNotEmpty() && manufacturer.isNotEmpty() ->
                if (model.startsWith(
                        manufacturer,
                        ignoreCase = true
                    )
                ) model else "$manufacturer $model"

            model.isNotEmpty() -> model
            else -> android.os.Build.DEVICE?.trim().takeUnless { it.isNullOrEmpty() } ?: DEVICE_NOT_FOUND
        }
    }

    /////////////////////////////////////////////////////////////////////////////////////
    // POST /login {username,password,version,device}
    // login to server with user/pw
    //      version:    simplereader library version number (server checks for compatability)
    //      device:     name of device we are running on (optional)
    // RETURNS: true on success
    //////////////////////////////////////////////////////////////////
    private data class LoginResult(val ok: Boolean, val token: String?, val expiresAt: Long)
    private fun login(config: ServerConfig) : LoginResult {

        val base = config.server.trimEnd('/')
        val url = "$base/login"

        // build the POST /login request
        val client = OkHttpClient.Builder()
            .callTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val payload = org.json.JSONObject().apply {
            put("username", config.user)            // use "username" if your server expects that
            put("password", config.decryptPassword(cryptoKey))
            put("version", libVersion)
            put("device",  deviceName)
        }.toString()
        val req = okhttp3.Request.Builder()
            .url(url)
            .post(
                payload.toRequestBody("application/json".toMediaTypeOrNull())
            )
            .build()

        // post request and await response
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) IOException("HTTP ${resp.code}")
                val respText = resp.body?.string()?.trim().orEmpty()
                if (respText.isEmpty()) throw IOException("empty message from server")

                var token: String?
                var expiry = 0L
                try { // try JSON first
                    val json = org.json.JSONObject(respText)

                    val ok = json.getBoolean("ok")
                    if (!ok) {
                        val err = json.optString("error","unspecified error")
                        val reason = json.optString("reason")
                        var msg = "login failed: $err"
                        if (!reason.isNullOrEmpty())
                            msg = "$msg reason: $reason"
                        Log.e(TAG,msg)

                        return LoginResult(false,null,0)
                    }

                    token = json.optString("token").takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException("No token in JSON")
                    expiry = json.optLong("expiresAt", 0L)
                } catch (e: Exception) {
                    Log.e(TAG,"login failed: $e")
                    return LoginResult(false,null,0)
                }

                return LoginResult(true,token,expiry)
            }
        } catch ( e: Exception) {
            Log.e(TAG,"login failed: $e")
            return LoginResult(false,null,0)
        }
        // unreachable
    }

}
