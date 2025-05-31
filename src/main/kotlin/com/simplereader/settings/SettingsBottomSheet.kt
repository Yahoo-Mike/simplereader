package com.simplereader.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.simplereader.R
import com.simplereader.databinding.ViewSettingsBinding
import com.simplereader.reader.ReaderViewModel
import com.simplereader.ui.font.FontPreviewAdapter
import org.readium.r2.navigator.preferences.FontFamily

/**
 * Created by mobisys2 on 11/16/2016.
 * updated by yahoo mike on 22 May 2025
 */
class SettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: ViewSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ReaderViewModel by activityViewModels()

    companion object {
        @JvmField
        val LOG_TAG: String = SettingsBottomSheet::class.java.simpleName
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        _binding = ViewSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews()
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

    // set the Theme (based on Theme.MaterialComponents.DayNight.BottomSheetDialog)
    override fun getTheme(): Int = R.style.Theme_SimpleReader_BottomSheetDialog

    // class to encapsulate the relationship between position on SeekBar slider and
    // the corresponding font_size
    private class FontSizer(initialFontSize: Double = Settings.DEFAULT_FONT_SIZE) {

        companion object {
            private const val MIN_POSITION = 0
            private const val MAX_POSITION = 4
            private const val FONT_SIZE_STEP = 0.25
            private const val FONT_SIZE_BASE = 1.0
        }

        var position: Int = fontSizeToPosition(initialFontSize)
            private set

        val fontSize: Double
            get() = FONT_SIZE_BASE + ( position * FONT_SIZE_STEP )

        fun setFromProgress(progress: Int) {
            position = progress.coerceIn(MIN_POSITION, MAX_POSITION)
        }

        private fun fontSizeToPosition(size: Double): Int {
            val pos = ((size - FONT_SIZE_BASE) / FONT_SIZE_STEP).toInt()
            return pos.coerceIn(MIN_POSITION, MAX_POSITION)
        }
    }

    private fun initViews() {

        // font
        viewModel.readerSettings.observe(viewLifecycleOwner) { settings ->
            val currentFont = settings?.font ?: FontFamily.Companion.SERIF
            val adapter =
                FontPreviewAdapter(Settings.supportedFonts, currentFont) { selectedFont ->
                    viewModel.setFont(selectedFont)
                }
            binding.fontListRecyclerView.adapter = adapter
            binding.fontListRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        }

        // font size
        var fontsizeSeekBar = binding.viewSettingsFontSizeSeekBar

        val fontSize = viewModel.readerSettings.value?.fontSize ?: Settings.DEFAULT_FONT_SIZE
        val fontSizer = FontSizer(fontSize)
        fontsizeSeekBar.progress = fontSizer.position
        configSeekBar(fontSizer)
    }

    private fun configSeekBar(fontSizer: FontSizer) {
       val thumbDrawable = ContextCompat.getDrawable(requireActivity(), R.drawable.seekbar_thumb)
        var fontsizeSeekBar = binding.viewSettingsFontSizeSeekBar

       fontsizeSeekBar.thumb = thumbDrawable

       fontsizeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
           override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
               fontSizer.setFromProgress(progress)
               viewModel.setFontSize(fontSizer.fontSize)
           }

           override fun onStartTrackingTouch(seekBar: SeekBar) {}
           override fun onStopTrackingTouch(seekBar: SeekBar) {}
       })
    }
}