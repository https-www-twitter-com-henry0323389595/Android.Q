/*
 * Copyright (c) 2023 DuckDuckGo
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

package com.duckduckgo.contentscopescripts.impl.messaging

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.net.toUri
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.contentscopescripts.impl.CoreContentScopeScripts
import com.duckduckgo.di.scopes.FragmentScope
import com.duckduckgo.js.messaging.api.JsMessage
import com.duckduckgo.js.messaging.api.JsMessageCallback
import com.duckduckgo.js.messaging.api.JsMessageHandler
import com.duckduckgo.js.messaging.api.JsMessageHelper
import com.duckduckgo.js.messaging.api.JsMessaging
import com.squareup.anvil.annotations.ContributesBinding
import com.squareup.moshi.Moshi
import java.util.*
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.runBlocking
import logcat.LogPriority.DEBUG
import logcat.logcat

@ContributesBinding(FragmentScope::class)
@Named("ContentScopeScripts")
class ContentScopeScriptsJsMessaging @Inject constructor(
    private val jsMessageHelper: JsMessageHelper,
    private val dispatcherProvider: DispatcherProvider,
    private val coreContentScopeScripts: CoreContentScopeScripts,
) : JsMessaging {

    private val moshi = Moshi.Builder().add(JSONObjectAdapter()).build()

    private lateinit var webView: WebView
    private lateinit var jsMessageCallback: JsMessageCallback

    override val context: String = "contentScopeScripts"
    override val callbackName: String = coreContentScopeScripts.callbackName
    override val secret: String = coreContentScopeScripts.secret
    override val allowedDomains: List<String> = emptyList()

    private val handlers: List<JsMessageHandler> = listOf()

    @JavascriptInterface
    override fun process(message: String, secret: String) {
        try {
            logcat(DEBUG) { "Message received $message" }
            val adapter = moshi.adapter(JsMessage::class.java)
            val jsMessage = adapter.fromJson(message)
            val domain = runBlocking(dispatcherProvider.main()) {
                webView.url?.toUri()?.host
            }
            jsMessage?.let {
                if (context == jsMessage.context && (allowedDomains.isEmpty() || allowedDomains.contains(domain))) {
                    handlers.firstOrNull {
                        it.method == jsMessage.method && it.featureName == jsMessage.featureName
                    }?.let {
                        val response = it.process(jsMessage, secret, webView, jsMessageCallback)
                        response?.let {
                            jsMessageHelper.sendJsResponse(response, callbackName, secret, webView)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logcat { "Exception is ${e.message}" }
        }
    }

    override fun register(webView: WebView, jsMessageCallback: JsMessageCallback?) {
        if (jsMessageCallback == null) throw Exception("Callback cannot be null")
        this.webView = webView
        this.jsMessageCallback = jsMessageCallback
        this.webView.addJavascriptInterface(this, coreContentScopeScripts.javascriptInterface)
    }

    override fun sendSubscriptionEvent() {
        // NOOP
    }
}