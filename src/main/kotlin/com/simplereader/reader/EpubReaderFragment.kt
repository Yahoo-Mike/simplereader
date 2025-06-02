/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 *
 * modified by yahoo mike 18 May 2025
 *
 */

package com.simplereader.reader

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import android.view.ActionMode
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commitNow
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.simplereader.R
import com.simplereader.data.ReaderDatabase
import kotlinx.coroutines.delay

import com.simplereader.databinding.NavDrawerBinding
import com.simplereader.highlight.Highlight
import com.simplereader.highlight.HighlightRepository
import com.simplereader.highlight.HighlightViewModel
import com.simplereader.highlight.HighlightViewModelFactory
import com.simplereader.model.BookData
import com.simplereader.model.EpubData
import com.simplereader.settings.Settings
import com.simplereader.toc.TocAdapter
import com.simplereader.ui.font.ANDADA
import com.simplereader.ui.font.LATO
import com.simplereader.ui.font.LORA
import com.simplereader.ui.font.RALEWAY
import kotlinx.coroutines.launch
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.SelectableNavigator
import org.readium.r2.navigator.Selection

import org.readium.r2.navigator.epub.*
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.epub.css.FontWeight
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.ExperimentalReadiumApi

class EpubReaderFragment :  ReaderFragment() {

    private lateinit var highlightRepository : HighlightRepository
    private val highlightViewModel: HighlightViewModel by activityViewModels() {
        HighlightViewModelFactory(highlightRepository)
    }

    companion object {
        fun newInstance(): EpubReaderFragment {
            val fragment = EpubReaderFragment()
            return fragment
        }

        val navigatorTag = "EpubNavigatorFragment"
    }

    private val BUBBLE_VERTICAL_OFFSET_DP = 80      // dp above the selection
    private val BUBBLE_HORIZONTAL_OFFSET_DP = 40    // dp right of the selection
    private var offsetBubbleVerticalPx = 0          // px offset (calculated in onViewCreated)
    private var offsetBubbleHorizontalPx = 0

    // callback for when user selects text
    private val selectionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            // Launch highlight bubble
            lifecycleScope.launch {
                delay(1000)     // allow user time to complete selection before showing bubble
                val selection = (navigator as? SelectableNavigator)?.currentSelection()
                selection?.let {
                    showSelectionBubble(it)
                }
            }
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean = false
        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean = false
        override fun onDestroyActionMode(mode: ActionMode?) {
            removeSelectionBubble()
        }
    }

    @ExperimentalReadiumApi
    override fun onCreate(savedInstanceState: Bundle?) {

        // if bookData has been set, restore the NavigatorFactory
        val epubInitData = readerViewModel.bookData.value as? EpubData
        if (epubInitData != null) {
            val navigatorFactory: EpubNavigatorFactory = epubInitData.navigatorFactory

            // You should restore the initial location from your view model.
            val settings = readerViewModel.readerSettings.value ?: Settings.DEFAULT
            childFragmentManager.fragmentFactory =
                navigatorFactory.createFragmentFactory(
                    initialLocator = epubInitData.currentLocation,
                    initialPreferences = EpubPreferences(
                        fontFamily = settings.font,
                        fontSize = settings.fontSize
                    )
                )
        }

        // create the highlight repository (before accessing highlightViewModel
        val daoHighlight =
            ReaderDatabase.Companion.getInstance(requireActivity()).highlightDao()
        highlightRepository = HighlightRepository(daoHighlight)
        highlightViewModel.touch()  // instantiate the highlightViewModel

        // IMPORTANT: Set the `fragmentFactory` before calling `super`
        super.onCreate(savedInstanceState)
    }

    @OptIn(ExperimentalReadiumApi::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // watch for changes in the Settings
        readerViewModel.readerSettings.observe(viewLifecycleOwner) { settings ->
            // user changed settings using SettingsBottomSheet,
            // so now re-render the current publication
            val newPrefs = EpubPreferences(
                fontFamily = settings?.font ?: Settings.DEFAULT_FONT,
                fontSize = settings?.fontSize ?: Settings.DEFAULT_FONT_SIZE
            )
            val epubNavigator = navigator as EpubNavigatorFragment?
            epubNavigator?.submitPreferences(newPrefs)
        }

        // setup offsets for the Selection bubble
        offsetBubbleVerticalPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            BUBBLE_VERTICAL_OFFSET_DP.toFloat(),
            resources.displayMetrics
        ).toInt()
        offsetBubbleHorizontalPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            BUBBLE_HORIZONTAL_OFFSET_DP.toFloat(),
            resources.displayMetrics
        ).toInt()

    }

    @SuppressLint("ClickableViewAccessibility")
    @OptIn(ExperimentalReadiumApi::class)
    override fun publish(data: BookData) {

        val initData = data as? EpubData
            ?: throw IllegalArgumentException("Expected EpubData but got ${data::class.simpleName}")

        if (childFragmentManager.findFragmentByTag(navigatorTag) == null) {

            // setup the factory
            val navigatorFactory: EpubNavigatorFactory = initData.navigatorFactory
            val settings = readerViewModel.readerSettings.value ?: Settings.DEFAULT

            childFragmentManager.fragmentFactory =
                navigatorFactory.createFragmentFactory(
                    initialLocator = initData.currentLocation,
                    initialPreferences = EpubPreferences(
                        fontFamily = settings.font,
                        fontSize = settings.fontSize
                    ),
                    configuration = EpubNavigatorFragment.Configuration {

                        servedAssets += "fonts/.*"

                        // ANDADA
                        addFontFamilyDeclaration(FontFamily.ANDADA) {
                            addFontFace {
                                addSource("fonts/andada_regular.otf", preload = true)
                                setFontStyle(FontStyle.NORMAL)
                                setFontWeight(FontWeight.NORMAL)
                            }
                            addFontFace {
                                addSource("fonts/andada_bold.otf")
                                setFontStyle(FontStyle.NORMAL)
                                setFontWeight(FontWeight.BOLD)
                            }
                            addFontFace {
                                addSource("fonts/andada_italic.otf")
                                setFontStyle(FontStyle.ITALIC)
                                setFontWeight(FontWeight.NORMAL)
                            }
                            addFontFace {
                                addSource("fonts/andada_bold_italic.otf")
                                setFontStyle(FontStyle.ITALIC)
                                setFontWeight(FontWeight.BOLD)
                            }
                        }

                        // LATO
                        addFontFamilyDeclaration(FontFamily.LATO) {
                            addFontFace {
                                addSource("fonts/lato_regular.ttf", preload = true)
                                setFontStyle(FontStyle.NORMAL)
                                setFontWeight(FontWeight.NORMAL)
                            }
                            addFontFace {
                                addSource("fonts/lato_bold.ttf")
                                setFontStyle(FontStyle.NORMAL)
                                setFontWeight(FontWeight.BOLD)
                            }
                            addFontFace {
                                addSource("fonts/lato_italic.ttf")
                                setFontStyle(FontStyle.ITALIC)
                                setFontWeight(FontWeight.NORMAL)
                            }
                            addFontFace {
                                addSource("fonts/lato_bold_italic.ttf")
                                setFontStyle(FontStyle.ITALIC)
                                setFontWeight(FontWeight.BOLD)
                            }
                        }

                        // LORA
                        addFontFamilyDeclaration(FontFamily.LORA) {
                            addFontFace {
                                addSource("fonts/lora_regular.ttf", preload = true)
                                setFontStyle(FontStyle.NORMAL)
                                setFontWeight(FontWeight.NORMAL)
                            }
                            addFontFace {
                                addSource("fonts/lora_bold.ttf")
                                setFontStyle(FontStyle.NORMAL)
                                setFontWeight(FontWeight.BOLD)
                            }
                            addFontFace {
                                addSource("fonts/lora_italic.ttf")
                                setFontStyle(FontStyle.ITALIC)
                                setFontWeight(FontWeight.NORMAL)
                            }
                            addFontFace {
                                addSource("fonts/lora_bold_italic.ttf")
                                setFontStyle(FontStyle.ITALIC)
                                setFontWeight(FontWeight.BOLD)
                            }
                        }

                        // raleway
                        addFontFamilyDeclaration(FontFamily.RALEWAY) {
                            addFontFace {
                                addSource("fonts/raleway_regular.ttf", preload = true)
                                setFontStyle(FontStyle.NORMAL)
                                setFontWeight(FontWeight.NORMAL)
                            }
                            addFontFace {
                                addSource("fonts/raleway_bold.ttf")
                                setFontStyle(FontStyle.NORMAL)
                                setFontWeight(FontWeight.BOLD)
                            }
                            addFontFace {
                                addSource("fonts/raleway_italic.ttf")
                                setFontStyle(FontStyle.ITALIC)
                                setFontWeight(FontWeight.NORMAL)
                            }
                            addFontFace {
                                addSource("fonts/raleway_bold_italic.ttf")
                                setFontStyle(FontStyle.ITALIC)
                                setFontWeight(FontWeight.BOLD)
                            }
                        }

                        // hook into user selecting text
                        selectionActionModeCallback = selectionModeCallback
                    }
                )

            // add this fragment...
            childFragmentManager.commitNow {
                add(R.id.fragment_reader_container, EpubNavigatorFragment::class.java, null, navigatorTag)
            }
        }

        navigator = childFragmentManager.findFragmentByTag(navigatorTag) as? EpubNavigatorFragment

        // setup the TableOfContents in the nav drawer
        navigator?.let {
            val activityBinding = (requireActivity() as ReaderActivity).binding
            val drawerLayout = activityBinding.drawerLayout
            val navigationView = activityBinding.navigationView
            val navDrawerBinding = NavDrawerBinding.inflate(layoutInflater, navigationView, false)
            navigationView.addView(navDrawerBinding.root)

            // setup the Home item
            navDrawerBinding.navHomeItem.setOnClickListener {
                drawerLayout.closeDrawers()
            }

            // setup the ToC recyclerView
            navDrawerBinding.tocRecyclerView.layoutManager = LinearLayoutManager(requireContext())
            navDrawerBinding.tocRecyclerView.adapter =
                TocAdapter(initData.publication.tableOfContents) { link ->
                    navigator!!.go(link, false)
                    drawerLayout.closeDrawers()
                }
        }

        // observe any highlights for changes & then load them from db
        highlightViewModel.highlights.observe(viewLifecycleOwner) { highlightsList ->
            if (highlightsList != null) {
                applyHighlightsToPage(highlightsList)
            }
        }
        highlightViewModel.loadHighlightsFromDb(initData.bookId())
    }

    fun showSelectionBubble(selection: Selection) {
        // bubble container lives in the parent reader_activity
        val container = requireActivity().findViewById<FrameLayout>(R.id.highlight_bubble_container)

        // Inflate the highlight bubble layout
        val bubble = layoutInflater.inflate(R.layout.highlight_bubble, container, false)

        // Position the bubble near the selection (using selection.rect)
        container.removeAllViews()
        container.addView(bubble)

        // Position the bubble based on selection.rect
        selection.rect?.let { rect ->
            val params = bubble.layoutParams as FrameLayout.LayoutParams
            params.leftMargin = (rect.left + offsetBubbleHorizontalPx).toInt() // slightly further right
            params.topMargin = (rect.top - offsetBubbleVerticalPx).toInt().coerceAtLeast(0) // slightly higher
            bubble.layoutParams = params
        }

        // Handle color selections
        bubble.findViewById<ImageView>(R.id.color_yellow).setOnClickListener {
            saveHighlight(selection, "yellow")
            removeSelectionBubble()
        }

        bubble.findViewById<ImageView>(R.id.color_blue).setOnClickListener {
            saveHighlight(selection, "blue")
            removeSelectionBubble()
        }

        bubble.findViewById<ImageView>(R.id.color_green).setOnClickListener {
            saveHighlight(selection, "green")
            removeSelectionBubble()
        }

        bubble.findViewById<ImageView>(R.id.color_pink).setOnClickListener {
            saveHighlight(selection, "pink")
            removeSelectionBubble()
        }

        // Define button (on highlight bubble)
        bubble.findViewById<TextView>(R.id.btn_define).setOnClickListener {
            // TODO for definitions from dictionary
            Toast.makeText(requireContext(), "Define clicked", Toast.LENGTH_SHORT).show()
        }

        // Copy button (on highlight bubble)
        bubble.findViewById<TextView>(R.id.btn_copy).setOnClickListener {
            // TODO to copy the selected text to the clipboard
            Toast.makeText(requireContext(), "Copy clicked", Toast.LENGTH_SHORT).show()
        }
    }

    fun removeSelectionBubble() {
        val container = requireActivity().findViewById<FrameLayout>(R.id.highlight_bubble_container)
        container.removeAllViews()
    }

    fun saveHighlight(selection: Selection, color: String) {
        val bookId = readerViewModel.bookData.value?.bookId() ?: return

        val highlight = Highlight(
            bookId = bookId,
            id = 0,              // HighlightRepository will update this for us to next available
            label = null,        // HighlightRespository will workout the best label
            selection = selection.locator,
            color = color
        )

        highlightViewModel.insertHighlight(highlight)
    }

    private fun applyHighlightsToPage(highlights: List<Highlight>) {
        val epubNavigator = childFragmentManager
            .findFragmentByTag(navigatorTag) as? DecorableNavigator

        epubNavigator?.let { navigator ->
            val decorations = highlights.map { highlight ->
                Decoration(
                    id = highlight.id.toString(),
                    locator = highlight.selection,
                    style = Decoration.Style.Highlight(
                        tint = highlight.getHexColor(requireContext()),
                        isActive = false
                    )
                )
            }

            lifecycleScope.launch {
                navigator.applyDecorations(decorations, "highlights")
            }
        }
    }

}