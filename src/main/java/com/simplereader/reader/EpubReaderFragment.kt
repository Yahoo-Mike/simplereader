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
import android.view.View
import androidx.fragment.app.commitNow
import androidx.recyclerview.widget.LinearLayoutManager
import com.simplereader.R

import com.simplereader.databinding.NavDrawerBinding
import com.simplereader.model.BookData
import com.simplereader.model.EpubData
import com.simplereader.settings.Settings
import com.simplereader.toc.TocAdapter
import com.simplereader.ui.ANDADA
import com.simplereader.ui.LATO
import com.simplereader.ui.LORA
import com.simplereader.ui.RALEWAY

import org.readium.r2.navigator.epub.*
import org.readium.r2.navigator.epub.css.FontStyle
import org.readium.r2.navigator.epub.css.FontWeight
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.shared.ExperimentalReadiumApi

class EpubReaderFragment :  ReaderFragment() {

    companion object {
        fun newInstance(): EpubReaderFragment {
            val fragment = EpubReaderFragment()
            return fragment
        }
    }

    @ExperimentalReadiumApi
    override fun onCreate(savedInstanceState: Bundle?) {

        // if bookData has been set, restore the NavigatorFactory
        val epubInitData = viewModel.bookData.value as? EpubData
        if (epubInitData != null) {
            val navigatorFactory: EpubNavigatorFactory = epubInitData.navigatorFactory

            // You should restore the initial location from your view model.
            val settings = viewModel.readerSettings.value ?: Settings.DEFAULT
            childFragmentManager.fragmentFactory =
                navigatorFactory.createFragmentFactory(
                    initialLocator = epubInitData.currentLocation,
                    initialPreferences = EpubPreferences(
                        fontFamily = settings.font,
                        fontSize = settings.fontSize
                    )
                )
        }

        // IMPORTANT: Set the `fragmentFactory` before calling `super`.
        super.onCreate(savedInstanceState)
    }

    @OptIn(ExperimentalReadiumApi::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        super.onViewCreated(view, savedInstanceState)

        // watch for changes in the Settings
        viewModel.readerSettings.observe(viewLifecycleOwner) { settings ->
            // user changed settings using SettingsBottomSheet,
            // so now re-render the current publication
            val newPrefs = EpubPreferences(
                fontFamily = settings?.font ?: Settings.DEFAULT_FONT,
                fontSize = settings?.fontSize ?: Settings.DEFAULT_FONT_SIZE
            )
            val epubNavigator = navigator as EpubNavigatorFragment?
            epubNavigator?.submitPreferences(newPrefs)
        }

    }

    @SuppressLint("ClickableViewAccessibility")
    @OptIn(ExperimentalReadiumApi::class)
    override fun publish(data: BookData) {

        val initData = data as? EpubData
            ?: throw IllegalArgumentException("Expected EpubData but got ${data::class.simpleName}")

        val tag = "EpubNavigatorFragment"
        if (childFragmentManager.findFragmentByTag(tag) == null) {

            // setup the factory
            val navigatorFactory: EpubNavigatorFactory = initData.navigatorFactory
            val settings = viewModel.readerSettings.value ?: Settings.DEFAULT

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
                    }
                )

            // add this fragment...
            childFragmentManager.commitNow {
                add(R.id.fragment_reader_container, EpubNavigatorFragment::class.java, null, tag)
            }
        }

        navigator = childFragmentManager.findFragmentByTag(tag) as? EpubNavigatorFragment

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

    }

}