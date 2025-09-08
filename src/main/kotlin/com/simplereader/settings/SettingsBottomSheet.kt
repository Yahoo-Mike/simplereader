package com.simplereader.settings

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.simplereader.R
import com.simplereader.data.ReaderDatabase
import com.simplereader.databinding.ViewSettingsBinding
import com.simplereader.reader.ReaderViewModel
import kotlinx.coroutines.launch

class SettingsBottomSheet : BottomSheetDialogFragment() {

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
    private fun norm(s: String?): String? =
        s?.trim().takeUnless { it.isNullOrEmpty() }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // Guard: view could already be gone in some edge cases
        val b = _binding ?: return

        val newServer = norm(b.serverTxtBox.text?.toString())
        val newUser   = norm(b.userTxtBox.text?.toString())
        val newPw     = norm(b.passwordTxtBox.text?.toString())

        val serverChanged = newServer != origServer
        val userChanged   = newUser   != origUser
        val passChanged   = newPw     != origPassword

        if (!(serverChanged || userChanged || passChanged)) return

        // Persist changes
        lifecycleScope.launch {
            if (serverChanged || userChanged) {
                repo.updateOrInsertServerAndUser(newServer, newUser)
            }
            if (passChanged) {
                repo.updateOrInsertPassword(newPw) // encrypts & upserts IV/CT
            }
        }
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

            // Populate UI
            binding.serverTxtBox.setText(origServer.orEmpty())
            binding.userTxtBox.setText(origUser.orEmpty())
            binding.passwordTxtBox.setText(origPassword.orEmpty())        }
    }

}