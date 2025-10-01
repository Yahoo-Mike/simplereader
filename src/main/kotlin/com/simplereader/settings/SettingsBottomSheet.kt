package com.simplereader.settings

import android.content.DialogInterface
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

import com.simplereader.R
import com.simplereader.data.ReaderDatabase
import com.simplereader.databinding.ViewSettingsBinding
import com.simplereader.reader.ReaderViewModel
import com.simplereader.sync.SyncManager
import com.simplereader.sync.SyncTickManager
import com.simplereader.util.normaliseServerBase

class SettingsBottomSheet : BottomSheetDialogFragment() {

    private val syncManager: SyncManager by lazy { SyncManager.getInstance(requireContext().applicationContext) }

    private var _binding: ViewSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ReaderViewModel by activityViewModels()

    private val db by lazy { ReaderDatabase.getInstance(requireContext()) }
    private val dao by lazy { db.settingsDao() }
    private val repo by lazy { SettingsRepository(db, dao) } // you already have this

    // Keep the originals to compare when bottomsheet is dismissed
    private var origServer: String? = null
    private var origUser: String? = null
    private var origPassword: String? = null  // decrypted (plain) snapshot
    private var origFrequency: Int = SettingsEntity.NEVER

    companion object {
        @JvmField
        val LOG_TAG: String = SettingsBottomSheet::class.java.simpleName
    }

    override fun onStart() {
        super.onStart()

        // when keyboard starts, resize the bottom sheet (so it's not obscured)
        dialog?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = ViewSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initSettings()
    }

    // Normalize helper: trim & convert "" to null
    private fun norm(s: String?): String? = s?.trim().takeUnless { it.isNullOrEmpty() }

    // if settings have changed, store them in the db
    private fun persistSettings() {
        // Guard: just in case view is gone
        val b = _binding ?: return

        val pendingServer = norm(b.serverTxtBox.text?.toString())
        val pendingUser = norm(b.userTxtBox.text?.toString())
        val pendingPw = norm(b.passwordTxtBox.text?.toString())
        val pendingFreq = norm(b.frequencyTxtBox.text?.toString())?.toInt() ?: SettingsEntity.NEVER

        val serverChanged = pendingServer != origServer
        val userChanged = pendingUser != origUser
        val passChanged = pendingPw != origPassword
        val freqChanged = pendingFreq != origFrequency

        if (!(serverChanged || userChanged || passChanged || freqChanged)) return

        // Persist changes
        lifecycleScope.launch {
            withContext(NonCancellable) { // ensure this gets done despite onDestroy()
                // update db
                db.withTransaction {
                    if (serverChanged || userChanged || freqChanged) {
                        repo.updateOrInsertServerAndUser(pendingServer, pendingUser, pendingFreq)
                    }
                    if (passChanged) {
                        repo.updateOrInsertPassword(pendingPw) // encrypts & upserts IV/CT
                    }
                }

                // inform SyncTickManager (if required)
                if (freqChanged)
                    SyncTickManager.scheduleNextTick(requireContext(), pendingFreq)
            }

            // update the local variables
            origServer = pendingServer
            origUser = pendingUser
            origPassword = pendingPw
            origFrequency = pendingFreq
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        persistSettings()
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

    // set the Theme (based on Theme.MaterialComponents.DayNight.BottomSheetDialog)
    override fun getTheme(): Int = R.style.Theme_SimpleReader_BottomSheetDialog


    private fun initSettings() {
        // Populate fields when sheet starts
        viewLifecycleOwner.lifecycleScope.launch {
            val s = dao.getSettings()                           // suspend call (Room handles threading)
            val pw = repo.readPassword()

            // Snapshot originals (normalize blanks to null)
            origServer = s?.syncServer?.trim().takeUnless { it.isNullOrEmpty() }
            origUser   = s?.syncUser?.trim().takeUnless { it.isNullOrEmpty() }
            origPassword = pw?.trim().takeUnless { it.isNullOrEmpty() }
            origFrequency = s?.syncFrequency ?: SettingsEntity.NEVER

            // Populate UI
            binding.serverTxtBox.setText(origServer.orEmpty())
            binding.userTxtBox.setText(origUser.orEmpty())
            binding.passwordTxtBox.setText(origPassword.orEmpty())
            binding.frequencyTxtBox.setText(origFrequency.toString())

            updateButtonsEnabled() // enable/disable based on fields
        }

        // Re-validate buttons when fields change
        val watcher = { _: CharSequence?, _: Int, _: Int, _: Int -> updateButtonsEnabled() }
        binding.serverTxtBox.doOnTextChanged(watcher)
        binding.userTxtBox.doOnTextChanged(watcher)
        binding.passwordTxtBox.doOnTextChanged(watcher)
        // note: do not need to watch sync frequency (as it doesn't change the buttons)

        // Server Test: POST "/" expecting "server up"
        binding.btnServerTest.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val serverUrl = binding.serverTxtBox.text.toString()
                val ok = serverTest(serverUrl)
                val msg = if (ok) getString(R.string.server_test_ok) else getString(R.string.server_test_fail)
                binding.syncStatus.text = msg
            }
        }

        // Sync Now: guarded by SyncManager’s mutex; enqueue WorkManager
        binding.btnSyncNow.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                // save settings in the dialog first
                persistSettings()

                // try to sync...
                val started = syncManager.syncNow()
                if (started) {
                    binding.syncStatus.text = getString(R.string.server_sync_start)
                } else {
                    val msg = "sync already running"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateButtonsEnabled() {
        val hasServer = binding.serverTxtBox.text?.isNotBlank() == true
        val hasUser   = binding.userTxtBox.text?.isNotBlank() == true
        val hasPass   = binding.passwordTxtBox.text?.isNotBlank() == true
        // note: do not need to test sync frequency (as it doesn't change buttons)

        binding.userTxtBox.isEnabled = hasServer
        binding.passwordTxtBox.isEnabled = hasServer && hasUser
        binding.frequencyTxtBox.isEnabled = hasServer && hasUser && hasPass

        // Server Test only needs server URL; Sync Now needs all three
        binding.btnServerTest.isEnabled = hasServer
        binding.btnSyncNow.isEnabled = hasServer && hasUser && hasPass
    }

    // Health check: POST “/” and look for “server up”. No auth required.
    private suspend fun serverTest(serverUrl: String): Boolean = withContext(Dispatchers.IO) {
        if (serverUrl.isBlank()) return@withContext false
        val url = normaliseServerBase(serverUrl)

        if (url != serverUrl) {
            withContext(Dispatchers.Main) { // ensure we are on the main UI thread
                // update the textbox with our normalised base address
                binding.serverTxtBox.setText(url)
                binding.serverTxtBox.setSelection(binding.serverTxtBox.text.length) // move cursor to end of string
            }
        }

        val client = OkHttpClient.Builder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

        val req = Request.Builder()
            .url(url)
            .get()      // "GET /"
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val text = resp.body?.string()?.trim().orEmpty()

                    val status = try {
                        org.json.JSONObject(text).optString("status")
                    } catch (_: Exception) {
                        text
                    }
                    return@withContext status.equals("server up", ignoreCase = true)
                } else {
                    Log.w(LOG_TAG, "serverTest: http ${resp.code} for $url")
                    return@withContext false
                }
            }
        } catch (e:Exception) {
            Log.e(LOG_TAG,"serverTest failed for $url",e)
            return@withContext false
        }
    } // end serverTest()

}