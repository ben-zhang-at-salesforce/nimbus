//
// Copyright (c) 2019, Salesforce.com, inc.
// All rights reserved.
// SPDX-License-Identifier: BSD-3-Clause
// For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause
//

package com.salesforce.nimbus.bridge.webview

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.salesforce.nimbus.Binder
import com.salesforce.nimbus.Bridge
import com.salesforce.nimbus.JavascriptSerializable
import com.salesforce.nimbus.PrimitiveJSONSerializable
import com.salesforce.nimbus.Runtime
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val BRIDGE_NAME = "_nimbus"
private typealias Promise = ((String?, Any?) -> Unit)

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
class WebViewBridge : Bridge<WebView, String>,
    Runtime<WebView, String> {

    private var bridgeWebView: WebView? = null
    private val binders = mutableListOf<Binder<WebView, String>>()
    private val promises: ConcurrentHashMap<String, Promise> = ConcurrentHashMap()

    override fun add(vararg binder: Binder<WebView, String>) {
        binders.addAll(binder)
    }

    override fun attach(javascriptEngine: WebView) {
        this.bridgeWebView = javascriptEngine
        if (!javascriptEngine.settings.javaScriptEnabled) {
            javascriptEngine.settings.javaScriptEnabled = true
        }
        javascriptEngine.addJavascriptInterface(this,
            BRIDGE_NAME
        )
        initialize(javascriptEngine, binders)
    }

    override fun detach() {
        bridgeWebView?.let { webView ->
            webView.removeJavascriptInterface(BRIDGE_NAME)
            cleanup(webView, binders)
        }
        binders.clear()
        bridgeWebView = null
    }

    override fun invoke(
        functionName: String,
        args: Array<JavascriptSerializable<String>?>,
        callback: ((String?, Any?) -> Unit)?
    ) {
        invokeInternal(functionName.split('.').toTypedArray(), args, callback)
    }

    override fun getJavascriptEngine(): WebView? {
        return bridgeWebView
    }

    private fun invokeInternal(
        identifierSegments: Array<String>,
        args: Array<JavascriptSerializable<String>?> = emptyArray(),
        callback: Promise?
    ) {
        val promiseId = UUID.randomUUID().toString()
        callback?.let { promises[promiseId] = it }

        val segmentArray = JSONArray(identifierSegments)
        val segmentString = segmentArray.toString()

        val jsonArray = JSONArray()
        args.forEachIndexed { _, jsonSerializable ->
            val asPrimitive = jsonSerializable as? PrimitiveJSONSerializable
            if (asPrimitive != null) {
                jsonArray.put(asPrimitive.value)
            } else {
                jsonArray.put(if (jsonSerializable == null) JSONObject.NULL
                else JSONObject(jsonSerializable.serialize()))
            }
        }
        val jsonString = jsonArray.toString()
        val script = """
        {
            let idSegments = $segmentString;
            let args = $jsonString;
            let promise = undefined;
            try {
                let fn = idSegments.reduce((state, key) => {
                    return state[key];
                }, window);
                promise = Promise.resolve(fn(...args));
            } catch (error) {
                promise = Promise.reject(error);
            }
            promise.then((value) => {
                _nimbus.resolvePromise("$promiseId", JSON.stringify({value: value}));
            }).catch((err) => {
                _nimbus.rejectPromise("$promiseId", err.toString());
            });
        }
        null;
        """.trimIndent()

        bridgeWebView?.handler?.post {
            bridgeWebView?.evaluateJavascript(script, null)
        }
    }

    @Suppress("unused")
    @JavascriptInterface
    fun resolvePromise(promiseId: String, json: String?) {
        val value = json?.let { JSONObject(it).get("value") }
        promises.remove(promiseId)?.invoke(null, value)
    }

    @Suppress("unused")
    @JavascriptInterface
    fun rejectPromise(promiseId: String, error: String) {
        promises.remove(promiseId)?.invoke(error, null)
    }

    @Suppress("unused")
    @JavascriptInterface
    fun pageUnloaded() {
        val canceledPromises = ConcurrentHashMap(promises)
        promises.clear()
        for (promise in canceledPromises.values) {
            promise("ERROR_PAGE_UNLOADED", null)
        }
    }

    /**
     * Creates and returns a Callback object that can be passed as an argument to
     * a subsequent JavascriptInterface bound method.
     */
    @Suppress("unused")
    @JavascriptInterface
    fun makeCallback(callbackId: String): Callback? {
        return bridgeWebView?.let { return Callback(it, callbackId) }
    }

    /**
     * Return the names of all connected extensions so they can be processed by the
     * JavaScript runtime code.
     */
    @Suppress("unused")
    @JavascriptInterface
    fun nativePluginNames(): String {
        val names = binders.map { it.getPluginName() }
        val result = JSONArray(names)
        return result.toString()
    }

    private fun initialize(webView: WebView, binders: Collection<Binder<WebView, String>>) {
        binders.forEach { binder ->

            // customize web view if needed
            binder.getPlugin().customize(this)

            // bind web view to binder
            binder.bind(this)

            // add the javascript interface for the binder
            val plugin = binder.getPluginName()
            webView.addJavascriptInterface(binder, "_$plugin")
        }
    }

    private fun cleanup(webView: WebView, binders: Collection<Binder<WebView, String>>) {
        binders.forEach { binder ->

            // cleanup web view if needed
            binder.getPlugin().cleanup(this)

            // unbind web view from binder
            binder.unbind(this)

            // remove the javascript interface for the binder
            val plugin = binder.getPluginName()
            webView.removeJavascriptInterface("_$plugin")
        }
    }

    protected fun finalize() {
        promises.values.forEach { it.invoke("Canceled", null) }
        promises.clear()
    }
}
