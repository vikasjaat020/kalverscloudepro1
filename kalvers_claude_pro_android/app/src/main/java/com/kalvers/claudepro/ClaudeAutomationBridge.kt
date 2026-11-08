package com.kalvers.claudepro

import android.webkit.JavascriptInterface

class ClaudeAutomationBridge(private val listener: BridgeListener) {

    interface BridgeListener {
        fun onLog(message: String)
        fun onLimitDetected(transcript: String, codeJson: String, resetTime: String)
        fun onResponseReady(fullText: String, codeJson: String)
    }

    @JavascriptInterface
    fun onLog(message: String) {
        listener.onLog(message)
    }

    @JavascriptInterface
    fun onLimitDetected(transcript: String, codeJson: String, resetTime: String) {
        listener.onLimitDetected(transcript, codeJson, resetTime)
    }

    @JavascriptInterface
    fun onResponseReady(fullText: String, codeJson: String) {
        listener.onResponseReady(fullText, codeJson)
    }
}
