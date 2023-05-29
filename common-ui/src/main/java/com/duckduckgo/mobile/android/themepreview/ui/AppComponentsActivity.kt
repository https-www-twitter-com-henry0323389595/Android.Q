/*
 * Copyright (c) 2021 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.mobile.android.themepreview.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.viewpager.widget.ViewPager
import com.duckduckgo.mobile.android.R
import com.duckduckgo.mobile.android.ui.DuckDuckGoTheme
import com.duckduckgo.mobile.android.ui.applyTheme
import com.duckduckgo.mobile.android.ui.view.listitem.OneLineListItem
import com.google.android.material.tabs.TabLayout

class AppComponentsActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager
    private lateinit var tabLayout: TabLayout
    private lateinit var darkThemeSwitch: OneLineListItem

    override fun onCreate(savedInstanceState: Bundle?) {
        val themePreferences = AppComponentsSharedPreferences(this)
        val selectedTheme = themePreferences.selectedTheme
        applyTheme(selectedTheme)

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_components)
        viewPager = findViewById(R.id.view_pager)
        tabLayout = findViewById(R.id.tab_layout)
        darkThemeSwitch = findViewById(R.id.dark_theme_switch)

        tabLayout.setupWithViewPager(viewPager)
        val adapter = AppComponentsPagerAdapter(this, supportFragmentManager)
        viewPager.adapter = adapter

        darkThemeSwitch.quietlySetIsChecked(selectedTheme == DuckDuckGoTheme.DARK) { _, enabled ->
            themePreferences.selectedTheme =
                if (enabled) {
                    DuckDuckGoTheme.DARK
                } else {
                    DuckDuckGoTheme.LIGHT
                }
            startActivity(intent(this))
            finish()
        }
    }

    companion object {
        fun intent(context: Context): Intent {
            return Intent(context, AppComponentsActivity::class.java)
        }
    }
}

class AppComponentsSharedPreferences(private val context: Context) {
    var selectedTheme: DuckDuckGoTheme
        get() {
            return if (preferences.getBoolean(KEY_SELECTED_DARK_THEME, false)) {
                DuckDuckGoTheme.DARK
            } else {
                DuckDuckGoTheme.LIGHT
            }
        }
        set(theme) =
            preferences.edit { putBoolean(KEY_SELECTED_DARK_THEME, theme == DuckDuckGoTheme.DARK) }

    private val preferences: SharedPreferences by lazy { context.getSharedPreferences(FILENAME, Context.MODE_PRIVATE) }

    companion object {
        const val FILENAME = "com.duckduckgo.app.dev_settings_activity.theme_settings"
        const val KEY_SELECTED_DARK_THEME = "KEY_SELECTED_DARK_THEME"
    }
}
