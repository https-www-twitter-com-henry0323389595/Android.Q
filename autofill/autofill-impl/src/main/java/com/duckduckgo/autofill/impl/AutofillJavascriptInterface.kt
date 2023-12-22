/*
 * Copyright (c) 2022 DuckDuckGo
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

package com.duckduckgo.autofill.impl

import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.duckduckgo.app.di.AppCoroutineScope
import com.duckduckgo.autofill.api.AutofillCapabilityChecker
import com.duckduckgo.autofill.api.Callback
import com.duckduckgo.autofill.api.EmailProtectionInContextSignupFlowListener
import com.duckduckgo.autofill.api.EmailProtectionUserPromptListener
import com.duckduckgo.autofill.api.domain.app.LoginCredentials
import com.duckduckgo.autofill.api.domain.app.LoginTriggerType
import com.duckduckgo.autofill.api.email.EmailManager
import com.duckduckgo.autofill.api.passwordgeneration.AutomaticSavedLoginsMonitor
import com.duckduckgo.autofill.api.store.AutofillStore
import com.duckduckgo.autofill.impl.deduper.AutofillLoginDeduplicator
import com.duckduckgo.autofill.impl.domain.javascript.JavascriptCredentials
import com.duckduckgo.autofill.impl.email.incontext.availability.EmailProtectionInContextRecentInstallChecker
import com.duckduckgo.autofill.impl.email.incontext.store.EmailProtectionInContextDataStore
import com.duckduckgo.autofill.impl.jsbridge.AutofillMessagePoster
import com.duckduckgo.autofill.impl.jsbridge.request.AutofillDataRequest
import com.duckduckgo.autofill.impl.jsbridge.request.AutofillRequestParser
import com.duckduckgo.autofill.impl.jsbridge.request.AutofillStoreFormDataRequest
import com.duckduckgo.autofill.impl.jsbridge.request.SupportedAutofillInputMainType.CREDENTIALS
import com.duckduckgo.autofill.impl.jsbridge.request.SupportedAutofillInputSubType.PASSWORD
import com.duckduckgo.autofill.impl.jsbridge.request.SupportedAutofillInputSubType.USERNAME
import com.duckduckgo.autofill.impl.jsbridge.request.SupportedAutofillTriggerType
import com.duckduckgo.autofill.impl.jsbridge.request.SupportedAutofillTriggerType.AUTOPROMPT
import com.duckduckgo.autofill.impl.jsbridge.request.SupportedAutofillTriggerType.USER_INITIATED
import com.duckduckgo.autofill.impl.jsbridge.response.AutofillResponseWriter
import com.duckduckgo.autofill.impl.sharedcreds.ShareableCredentials
import com.duckduckgo.autofill.impl.store.NeverSavedSiteRepository
import com.duckduckgo.autofill.impl.systemautofill.SystemAutofillServiceSuppressor
import com.duckduckgo.autofill.impl.ui.credential.passwordgeneration.Actions
import com.duckduckgo.autofill.impl.ui.credential.passwordgeneration.Actions.DeleteAutoLogin
import com.duckduckgo.autofill.impl.ui.credential.passwordgeneration.Actions.DiscardAutoLoginId
import com.duckduckgo.autofill.impl.ui.credential.passwordgeneration.Actions.PromptToSave
import com.duckduckgo.autofill.impl.ui.credential.passwordgeneration.Actions.UpdateSavedAutoLogin
import com.duckduckgo.autofill.impl.ui.credential.passwordgeneration.AutogeneratedPasswordEventResolver
import com.duckduckgo.common.utils.ConflatedJob
import com.duckduckgo.common.utils.DefaultDispatcherProvider
import com.duckduckgo.common.utils.DispatcherProvider
import com.duckduckgo.di.scopes.AppScope
import com.squareup.anvil.annotations.ContributesBinding
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

interface AutofillJavascriptInterface {

    @JavascriptInterface
    fun getAutofillData(requestString: String)

    @JavascriptInterface
    fun getIncontextSignupDismissedAt(data: String)

    fun injectCredentials(credentials: LoginCredentials)
    fun injectNoCredentials()

    fun cancelRetrievingStoredLogins()

    fun acceptGeneratedPassword()
    fun rejectGeneratedPassword()

    fun inContextEmailProtectionFlowFinished()

    var callback: Callback?
    var emailProtectionInContextCallback: EmailProtectionUserPromptListener?
    var emailProtectionInContextSignupFlowCallback: EmailProtectionInContextSignupFlowListener?
    var webView: WebView?
    var autoSavedLoginsMonitor: AutomaticSavedLoginsMonitor?
    var tabId: String?

    companion object {
        const val INTERFACE_NAME = "BrowserAutofill"
    }

    @JavascriptInterface
    fun closeEmailProtectionTab(data: String)
}

@ContributesBinding(AppScope::class)
class AutofillStoredBackJavascriptInterface @Inject constructor(
    private val requestParser: AutofillRequestParser,
    private val autofillStore: AutofillStore,
    private val shareableCredentials: ShareableCredentials,
    private val autofillMessagePoster: AutofillMessagePoster,
    private val autofillResponseWriter: AutofillResponseWriter,
    @AppCoroutineScope private val coroutineScope: CoroutineScope,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    private val currentUrlProvider: UrlProvider = WebViewUrlProvider(dispatcherProvider),
    private val autofillCapabilityChecker: AutofillCapabilityChecker,
    private val passwordEventResolver: AutogeneratedPasswordEventResolver,
    private val emailManager: EmailManager,
    private val inContextDataStore: EmailProtectionInContextDataStore,
    private val recentInstallChecker: EmailProtectionInContextRecentInstallChecker,
    private val loginDeduplicator: AutofillLoginDeduplicator,
    private val systemAutofillServiceSuppressor: SystemAutofillServiceSuppressor,
    private val neverSavedSiteRepository: NeverSavedSiteRepository,
) : AutofillJavascriptInterface {

    override var callback: Callback? = null
    override var emailProtectionInContextCallback: EmailProtectionUserPromptListener? = null
    override var emailProtectionInContextSignupFlowCallback: EmailProtectionInContextSignupFlowListener? = null
    override var webView: WebView? = null
    override var autoSavedLoginsMonitor: AutomaticSavedLoginsMonitor? = null
    override var tabId: String? = null

    // coroutine jobs tracked for supporting cancellation
    private val getAutofillDataJob = ConflatedJob()
    private val storeFormDataJob = ConflatedJob()
    private val injectCredentialsJob = ConflatedJob()
    private val emailProtectionInContextSignupJob = ConflatedJob()

    @JavascriptInterface
    override fun getAutofillData(requestString: String) {
        Timber.v("BrowserAutofill: getAutofillData called:\n%s", requestString)
        getAutofillDataJob += coroutineScope.launch(dispatcherProvider.io()) {
            val url = currentUrlProvider.currentUrl(webView)
            if (url == null) {
                Timber.w("Can't autofill as can't retrieve current URL")
                return@launch
            }

            if (!autofillCapabilityChecker.canInjectCredentialsToWebView(url)) {
                Timber.v("BrowserAutofill: getAutofillData called but feature is disabled")
                return@launch
            }

            val parseResult = requestParser.parseAutofillDataRequest(requestString)
            val request = parseResult.getOrElse {
                Timber.w(it, "Unable to parse getAutofillData request")
                return@launch
            }

            val triggerType = convertTriggerType(request.trigger)

            if (request.mainType != CREDENTIALS) {
                handleUnknownRequestMainType(request, url)
                return@launch
            }

            if (request.isGeneratedPasswordAvailable()) {
                handleRequestForPasswordGeneration(url, request)
            } else if (request.isAutofillCredentialsRequest()) {
                handleRequestForAutofillingCredentials(url, request, triggerType)
            } else {
                Timber.w("Unable to process request; don't know how to handle request %s", requestString)
            }
        }
    }

    @JavascriptInterface
    override fun getIncontextSignupDismissedAt(data: String) {
        emailProtectionInContextSignupJob += coroutineScope.launch(dispatcherProvider.io()) {
            val permanentDismissalTime = inContextDataStore.timestampUserChoseNeverAskAgain()
            val installedRecently = recentInstallChecker.isRecentInstall()
            val jsonResponse = autofillResponseWriter.generateResponseForEmailProtectionInContextSignup(installedRecently, permanentDismissalTime)
            autofillMessagePoster.postMessage(webView, jsonResponse)
        }
    }

    @JavascriptInterface
    override fun closeEmailProtectionTab(data: String) {
        emailProtectionInContextSignupFlowCallback?.closeInContextSignup()
    }

    @JavascriptInterface
    fun showInContextEmailProtectionSignupPrompt(data: String) {
        coroutineScope.launch(dispatcherProvider.io()) {
            currentUrlProvider.currentUrl(webView)?.let {
                val isSignedIn = emailManager.isSignedIn()

                withContext(dispatcherProvider.main()) {
                    if (isSignedIn) {
                        emailProtectionInContextCallback?.showNativeChooseEmailAddressPrompt()
                    } else {
                        emailProtectionInContextCallback?.showNativeInContextEmailProtectionSignupPrompt()
                    }
                }
            }
        }
    }

    private suspend fun handleRequestForPasswordGeneration(
        url: String,
        request: AutofillDataRequest,
    ) {
        callback?.onGeneratedPasswordAvailableToUse(url, request.generatedPassword?.username, request.generatedPassword?.value!!)
    }

    private suspend fun handleRequestForAutofillingCredentials(
        url: String,
        request: AutofillDataRequest,
        triggerType: LoginTriggerType,
    ) {
        val matches = mutableListOf<LoginCredentials>()
        val directMatches = autofillStore.getCredentials(url)
        val shareableMatches = shareableCredentials.shareableCredentials(url)
        Timber.v("Direct matches: %d, shareable matches: %d for %s", directMatches.size, shareableMatches.size, url)
        matches.addAll(directMatches)
        matches.addAll(shareableMatches)

        val credentials = filterRequestedSubtypes(request, matches)

        val dedupedCredentials = loginDeduplicator.deduplicate(url, credentials)
        Timber.v("Original autofill credentials list size: %d, after de-duping: %d", credentials.size, dedupedCredentials.size)

        if (dedupedCredentials.isEmpty()) {
            callback?.noCredentialsAvailable(url)
        } else {
            callback?.onCredentialsAvailableToInject(url, dedupedCredentials, triggerType)
        }
    }

    private fun convertTriggerType(trigger: SupportedAutofillTriggerType): LoginTriggerType {
        return when (trigger) {
            USER_INITIATED -> LoginTriggerType.USER_INITIATED
            AUTOPROMPT -> LoginTriggerType.AUTOPROMPT
        }
    }

    private fun filterRequestedSubtypes(
        request: AutofillDataRequest,
        credentials: List<LoginCredentials>,
    ): List<LoginCredentials> {
        return when (request.subType) {
            USERNAME -> credentials.filterNot { it.username.isNullOrBlank() }
            PASSWORD -> credentials.filterNot { it.password.isNullOrBlank() }
        }
    }

    private fun handleUnknownRequestMainType(
        request: AutofillDataRequest,
        url: String,
    ) {
        Timber.w("Autofill type %s unsupported", request.mainType)
        callback?.noCredentialsAvailable(url)
    }

    @JavascriptInterface
    fun storeFormData(data: String) {
        // important to call suppressor as soon as possible
        systemAutofillServiceSuppressor.suppressAutofill(webView)

        Timber.i("storeFormData called, credentials provided to be persisted")

        storeFormDataJob += coroutineScope.launch(dispatcherProvider.io()) {
            val currentUrl = currentUrlProvider.currentUrl(webView) ?: return@launch

            if (!autofillCapabilityChecker.canSaveCredentialsFromWebView(currentUrl)) {
                Timber.v("BrowserAutofill: storeFormData called but feature is disabled")
                return@launch
            }

            if (neverSavedSiteRepository.isInNeverSaveList(currentUrl)) {
                Timber.v("BrowserAutofill: storeFormData called but site is in never save list")
                return@launch
            }

            val parseResult = requestParser.parseStoreFormDataRequest(data)
            val request = parseResult.getOrElse {
                Timber.w(it, "Unable to parse storeFormData request")
                return@launch
            }

            if (!request.isValid()) {
                Timber.w("Invalid data from storeFormData")
                return@launch
            }

            val jsCredentials = JavascriptCredentials(request.credentials!!.username, request.credentials.password)
            val credentials = jsCredentials.asLoginCredentials(currentUrl)

            val autologinId = autoSavedLoginsMonitor?.getAutoSavedLoginId(tabId)
            Timber.i("Autogenerated? %s, Previous autostored login ID: %s", request.credentials.autogenerated, autologinId)
            val autosavedLogin = autologinId?.let { autofillStore.getCredentialsWithId(it) }

            val autogenerated = request.credentials.autogenerated
            val actions = passwordEventResolver.decideActions(autosavedLogin, autogenerated)
            processStoreFormDataActions(actions, currentUrl, credentials)
        }
    }

    private suspend fun processStoreFormDataActions(
        actions: List<Actions>,
        currentUrl: String,
        credentials: LoginCredentials,
    ) {
        Timber.d("%d actions to take: %s", actions.size, actions.joinToString())
        actions.forEach {
            when (it) {
                is DeleteAutoLogin -> {
                    autofillStore.deleteCredentials(it.autologinId)
                }

                is DiscardAutoLoginId -> {
                    autoSavedLoginsMonitor?.clearAutoSavedLoginId(tabId)
                }

                is PromptToSave -> {
                    callback?.onCredentialsAvailableToSave(currentUrl, credentials)
                }

                is UpdateSavedAutoLogin -> {
                    autofillStore.getCredentialsWithId(it.autologinId)?.let { existingCredentials ->
                        if (isUpdateRequired(existingCredentials, credentials)) {
                            Timber.v("Update required as not identical to what is already stored. id=%s", it.autologinId)
                            val toSave = existingCredentials.copy(username = credentials.username, password = credentials.password)
                            autofillStore.updateCredentials(toSave)?.let { savedCredentials ->
                                callback?.onCredentialsSaved(savedCredentials)
                            }
                        } else {
                            Timber.v("Update not required as identical to what is already stored. id=%s", it.autologinId)
                            callback?.onCredentialsSaved(existingCredentials)
                        }
                    }
                }
            }
        }
    }

    private fun isUpdateRequired(
        existingCredentials: LoginCredentials,
        credentials: LoginCredentials,
    ): Boolean {
        return existingCredentials.username != credentials.username || existingCredentials.password != credentials.password
    }

    private fun AutofillStoreFormDataRequest?.isValid(): Boolean {
        if (this == null || credentials == null) return false
        return !(credentials.username.isNullOrBlank() && credentials.password.isNullOrBlank())
    }

    override fun injectCredentials(credentials: LoginCredentials) {
        Timber.v("Informing JS layer with credentials selected")
        injectCredentialsJob += coroutineScope.launch(dispatcherProvider.io()) {
            val jsCredentials = credentials.asJsCredentials()
            val jsonResponse = autofillResponseWriter.generateResponseGetAutofillData(jsCredentials)
            Timber.i("Injecting credentials: %s", jsonResponse)
            autofillMessagePoster.postMessage(webView, jsonResponse)
        }
    }

    override fun injectNoCredentials() {
        Timber.v("No credentials selected; informing JS layer")
        injectCredentialsJob += coroutineScope.launch(dispatcherProvider.io()) {
            autofillMessagePoster.postMessage(webView, autofillResponseWriter.generateEmptyResponseGetAutofillData())
        }
    }

    private fun LoginCredentials.asJsCredentials(): JavascriptCredentials {
        return JavascriptCredentials(
            username = username,
            password = password,
        )
    }

    override fun cancelRetrievingStoredLogins() {
        getAutofillDataJob.cancel()
    }

    override fun acceptGeneratedPassword() {
        Timber.v("Accepting generated password")
        injectCredentialsJob += coroutineScope.launch(dispatcherProvider.io()) {
            autofillMessagePoster.postMessage(webView, autofillResponseWriter.generateResponseForAcceptingGeneratedPassword())
        }
    }

    override fun rejectGeneratedPassword() {
        Timber.v("Rejecting generated password")
        injectCredentialsJob += coroutineScope.launch(dispatcherProvider.io()) {
            autofillMessagePoster.postMessage(webView, autofillResponseWriter.generateResponseForRejectingGeneratedPassword())
        }
    }

    override fun inContextEmailProtectionFlowFinished() {
        emailProtectionInContextSignupJob += coroutineScope.launch(dispatcherProvider.io()) {
            val json = autofillResponseWriter.generateResponseForEmailProtectionEndOfFlow(emailManager.isSignedIn())
            autofillMessagePoster.postMessage(webView, json)
        }
    }

    private fun JavascriptCredentials.asLoginCredentials(
        url: String,
    ): LoginCredentials {
        return LoginCredentials(
            id = null,
            domain = url,
            username = username,
            password = password,
            domainTitle = null,
        )
    }

    interface UrlProvider {
        suspend fun currentUrl(webView: WebView?): String?
    }

    @ContributesBinding(AppScope::class)
    class WebViewUrlProvider @Inject constructor(val dispatcherProvider: DispatcherProvider) : UrlProvider {
        override suspend fun currentUrl(webView: WebView?): String? {
            return withContext(dispatcherProvider.main()) {
                webView?.url
            }
        }
    }
}
