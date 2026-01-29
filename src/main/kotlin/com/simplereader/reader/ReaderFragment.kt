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
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.simplereader.R

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

import com.simplereader.databinding.FragmentReaderBinding
import com.simplereader.model.BookData

import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.ExperimentalReadiumApi

abstract class ReaderFragment :  Fragment() {

    interface OnSingleTapListener {
        fun onSingleTap()
    }

    protected var navigator: VisualNavigator? = null

    private var _binding: FragmentReaderBinding? = null
    private val binding get() = _binding!!

    protected val readerViewModel: ReaderViewModel by activityViewModels()

    // abstract function to render the book on the screen
    protected abstract fun publish(data: BookData)

    // icon to display for the "current bookmark"
    private lateinit var bookmarkIcon: View

    // abstract function to get current position in a document
    // for EPUBs this is a percentage   [0..100]
    // for PDFs  this ia a page number  [1..totalPages]
    // return:  null means "I don't know"
    abstract fun progress() : Int?

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReaderBinding.inflate(inflater, container, false)
        return binding.root
    }

    @OptIn(ExperimentalReadiumApi::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        super.onViewCreated(view, savedInstanceState)

        bookmarkIcon = requireView().findViewById<View>(R.id.current_bookmark_icon)

        // watch for a book to be ready to publish
        readerViewModel.bookData.observe(viewLifecycleOwner) { data ->
            data?.let {
                // child publishes to navigator fragment
                publish(data)

                // listen for a single tap and let ReaderActivity know
                listenForSingleTap(navigator)

                // watch for user turning the page
                listenForUserTurningPage(navigator)

                // change the titlebar text
                changeTitleBarText(navigator, data.publication.metadata.title)

                // change the progress menu item
                val progress = data.currentLocation?.locations?.totalProgression ?: 0.0
                (activity as? ReaderActivity)?.updateProgressIndicator(progress)

                // display the "current bookmark" icon (if required)
                updateCurrentBookmarkIcon(bookmarkIcon,it)

                // watch for user selecting a bookmark or search result, then jump to that location
                readerViewModel.gotoLocator.observe(viewLifecycleOwner) { event ->
                    event.getContentIfNotHandled()?.let { locator ->
                        navigator?.go(locator)
                    }
                }

            }
        }

    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // listen for a single tap and let ReaderActivity know
    @OptIn(ExperimentalReadiumApi::class)
    private fun listenForSingleTap(navigator: VisualNavigator?) {

        // when user clicks in top RHS of screen we treat this as a bookmark request
        // when the user clicks anywhere else, it will be treated as a appbar toggle

        // the top RHS is defined as a percentage of the reader content screen
        val topPercent = 0.15f
        val rightPercent = 0.15f

        // get reader touch wrapper (to determine screen dimensions)
        val wrapper = requireView().findViewById<View>(R.id.reader_touch_wrapper)

        val tapListener = object : InputListener {
            override fun onTap(event: TapEvent): Boolean {

                val w = wrapper.width
                val h = wrapper.height

                if (w > 0 && h > 0) {
                    val xRaw = event.point.x
                    val yRaw = event.point.y

                    // Handle both normalized coords (0..1) and pixel coords
                    val x = if (xRaw in 0f..1f) xRaw * w else xRaw
                    val y = if (yRaw in 0f..1f) yRaw * h else yRaw

                    val inTop = y <= h * topPercent
                    val inRight = x >= w * (1f - rightPercent)

                    if (inTop && inRight) {
                        // user clicked the bookmark area, so let's process it
                        readerViewModel.onCurrentBookmarkClick()

                        // and display it...
                        readerViewModel.bookData.value?.let { data ->
                            updateCurrentBookmarkIcon(bookmarkIcon, data)
                        }

                        return true  // we consumed the event, so let's leave
                    }
                }

                // treat this as an appbar toggle event...
                (activity as? OnSingleTapListener)?.onSingleTap()
                return true
            }
        }
        navigator?.addInputListener(tapListener)
    }

    // watch for user turning the page
    private fun listenForUserTurningPage(navigator: VisualNavigator?) {
        navigator?.let {
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    navigator.currentLocator
                        .onEach {
                            readerViewModel.saveReadingProgression(it)

                            // update page indicator
                            val progress = it.locations.totalProgression ?: 0.0
                            (activity as? ReaderActivity)?.updateProgressIndicator(progress)

                            // update current bookmark if required
                            readerViewModel.bookData.value?.let { data ->
                                updateCurrentBookmarkIcon(bookmarkIcon, data)
                            }
                        }
                        .launchIn(this)
                }
            }
        }
    }

    // change the titleBar text
    private fun changeTitleBarText(navigator: VisualNavigator?, title: String?) {
        navigator?.let {
            val toolbarTitle = requireActivity().findViewById<TextView>(R.id.toolbar_title)
            toolbarTitle.text = title
        }
    }

    // toggle the "current bookmark" icon, if we have a bookmark here
    private fun updateCurrentBookmarkIcon(icon: View, data: BookData) {
        val loc = data.currentLocation
        val bm = data.currentBookmark

        val visible =
            (loc != null && bm != null &&
                    loc.toJSON().toString() == bm.toJSON().toString())

        icon.isVisible = visible
    }

}