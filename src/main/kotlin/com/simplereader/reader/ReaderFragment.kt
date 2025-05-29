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
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle

import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

import com.simplereader.databinding.FragmentReaderBinding
import com.simplereader.model.BookData

import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.ExperimentalReadiumApi
import kotlin.math.roundToInt

abstract class ReaderFragment :  Fragment() {

    interface OnSingleTapListener {
        fun onSingleTap()
    }

    protected var navigator: VisualNavigator? = null

    private var _binding: FragmentReaderBinding? = null
    private val binding get() = _binding!!

    protected val readerViewModel: ReaderViewModel by activityViewModels()


    protected abstract fun publish(data: BookData)

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

        val tapListener = object : InputListener {
            override fun onTap(event: TapEvent): Boolean {
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
                        }
                        .launchIn(this)
                }
            }
        }
    }

    // change the titleBar text
    private fun changeTitleBarText(navigator: VisualNavigator?, title: String?) {
        navigator?.let {
            val displayTitle = title?.takeIf { it.isNotBlank() } ?: "SimpleReader"
            (requireActivity() as AppCompatActivity).supportActionBar?.title = displayTitle
        }
    }

}