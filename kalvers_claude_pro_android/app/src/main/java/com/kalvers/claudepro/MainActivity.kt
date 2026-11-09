package com.kalvers.claudepro

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.io.File
import java.io.InputStreamReader

class MainActivity : AppCompatActivity(), ClaudeAutomationBridge.BridgeListener {

    private lateinit var webView: WebView
    private lateinit var sessionManager: AccountSessionManager
    private lateinit var statusBadge: TextView
    private lateinit var activeAccountSpinner: Spinner
    private lateinit var logConsole: TextView
    private lateinit var progressBar: ProgressBar

    private var currentHandoffSummary: String? = null
    private var currentHandoffCode: String? = null
    private val attemptedAccounts = mutableListOf<Int>()
    private var isTaskRunning = false
    private var isSwitchingAccount = false

    private val incrementalPromptDirective = """
        [CRITICAL EXECUTION DIRECTIVE - IMMEDIATE FILE-BY-FILE OUTPUT]:
        1. START OUTPUT IMMEDIATELY: Output actual production-ready code directly.
        2. FILE-BY-FILE: Label each file clearly with its relative path (e.g., // filename: index.php).
        3. CHECKPOINT ON LIMIT: Output completed files before limit, followed by:
           '### CHECKPOINT: [Completed Files] | Next: [Next File]'
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sessionManager = AccountSessionManager(this)

        webView = findViewById(R.id.webView)
        statusBadge = findViewById(R.id.statusBadge)
        activeAccountSpinner = findViewById(R.id.accountSpinner)
        logConsole = findViewById(R.id.logConsole)
        progressBar = findViewById(R.id.progressBar)

        setupWebView()
        setupAccountSpinner()

        findViewById<Button>(R.id.btnRunTask).setOnClickListener {
            showTaskPromptDialog()
        }

        findViewById<Button>(R.id.btnResetLimits).setOnClickListener {
            sessionManager.resetAllLimits()
            updateUI()
            appendLog("All account limits reset.")
        }

        findViewById<Button>(R.id.btnClearSession).setOnClickListener {
            confirmClearActiveSession()
        }

        findViewById<Button>(R.id.btnTelegram).setOnClickListener {
            showTelegramDialog()
        }

        // Load initial account
        loadInitialAccountSession(sessionManager.activeAccountId)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Disable DevTools inspection & tampering
        WebView.setWebContentsDebuggingEnabled(false)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.setSupportMultipleWindows(false)
        settings.userAgentString = settings.userAgentString.replace("; wv", "")

        webView.addJavascriptInterface(ClaudeAutomationBridge(this), "KalversBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectAutomationScript()

                // Auto-detect and save cookies for current account slot
                val cm = CookieManager.getInstance()
                val cookies = cm.getCookie("https://claude.ai")
                val curUrl = url ?: ""
                if (!cookies.isNullOrEmpty() && (cookies.contains("sessionKey") || curUrl.contains("/chat") || curUrl.contains("/new"))) {
                    sessionManager.saveSession(sessionManager.activeAccountId, cookies)
                    updateUI()
                }

                // Restore saved localStorage if available
                val savedStorage = sessionManager.getSavedStorage(sessionManager.activeAccountId)
                if (!savedStorage.isNullOrEmpty() && savedStorage != "null" && savedStorage != "{}") {
                    val script = """
                        try {
                            var d = $savedStorage;
                            for (var k in d) { window.localStorage.setItem(k, d[k]); }
                        } catch(e) {}
                    """.trimIndent()
                    webView.evaluateJavascript(script, null)
                }
            }
        }
    }

    private fun injectAutomationScript() {
        try {
            val assetStream = assets.open("kalvers_injector.js")
            val script = InputStreamReader(assetStream).readText()
            webView.evaluateJavascript(script, null)
        } catch (e: Exception) {
            appendLog("Injection notice: ${e.message}")
        }
    }

    private fun setupAccountSpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            sessionManager.accounts.map { it.name }
        )
        activeAccountSpinner.adapter = adapter
        activeAccountSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedId = position + 1
                if (selectedId != sessionManager.activeAccountId && !isTaskRunning && !isSwitchingAccount) {
                    switchAccount(selectedId)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadInitialAccountSession(accountId: Int) {
        val cm = CookieManager.getInstance()
        val saved = sessionManager.getSavedCookies(accountId)
        if (!saved.isNullOrEmpty()) {
            for (c in saved.split(";")) {
                val tr = c.trim()
                if (tr.isNotEmpty()) {
                    cm.setCookie("https://claude.ai", tr)
                    cm.setCookie("https://anthropic.com", tr)
                }
            }
            cm.flush()
            appendLog("Session restored for Account #$accountId.")
            webView.loadUrl("https://claude.ai/new")
        } else {
            appendLog("Account #$accountId requires sign in.")
            webView.loadUrl("https://claude.ai/login")
        }
        updateUI()
    }

    private fun switchAccount(newAccountId: Int) {
        if (isSwitchingAccount) return
        isSwitchingAccount = true
        val oldAccountId = sessionManager.activeAccountId
        appendLog("Switching: Account #$oldAccountId -> Account #$newAccountId...")

        val cm = CookieManager.getInstance()
        val currentCookies = cm.getCookie("https://claude.ai")
        if (!currentCookies.isNullOrEmpty()) {
            sessionManager.saveSession(oldAccountId, currentCookies)
        }

        webView.evaluateJavascript("JSON.stringify(window.localStorage);") { storageJson ->
            if (storageJson != null && storageJson != "null" && storageJson != "{}") {
                sessionManager.saveSession(oldAccountId, currentCookies, storageJson)
            }

            runOnUiThread {
                cm.removeAllCookies {
                    cm.flush()
                    WebStorage.getInstance().deleteAllData()
                    webView.clearCache(true)

                    sessionManager.activeAccountId = newAccountId
                    updateUI()

                    val targetCookies = sessionManager.getSavedCookies(newAccountId)
                    if (!targetCookies.isNullOrEmpty()) {
                        for (c in targetCookies.split(";")) {
                            val tr = c.trim()
                            if (tr.isNotEmpty()) {
                                cm.setCookie("https://claude.ai", tr)
                                cm.setCookie("https://anthropic.com", tr)
                            }
                        }
                        cm.flush()
                        appendLog("Account #$newAccountId session restored.")
                        webView.loadUrl("https://claude.ai/new")
                    } else {
                        appendLog("Account #$newAccountId is fresh. Please sign in.")
                        webView.loadUrl("https://claude.ai/login")
                    }
                    isSwitchingAccount = false
                }
            }
        }
    }

    private fun confirmClearActiveSession() {
        val accId = sessionManager.activeAccountId
        AlertDialog.Builder(this)
            .setTitle("Logout Slot #$accId?")
            .setMessage("This will clear saved login credentials for Account #$accId so you can sign in with a different account.")
            .setPositiveButton("Logout Slot") { _, _ ->
                sessionManager.clearSession(accId)
                val cm = CookieManager.getInstance()
                cm.removeAllCookies {
                    cm.flush()
                    WebStorage.getInstance().deleteAllData()
                    webView.clearCache(true)
                    updateUI()
                    webView.loadUrl("https://claude.ai/login")
                    appendLog("Slot #$accId cleared and logged out.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTelegramDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_telegram, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialogView.findViewById<Button>(R.id.btnOpenTelegram).setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/kalverschannel"))
            startActivity(intent)
            dialog.dismiss()
        }

        dialogView.findViewById<Button>(R.id.btnCloseTelegram).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showTaskPromptDialog() {
        val input = EditText(this)
        input.hint = "Enter your project instructions or task..."
        input.setLines(4)

        AlertDialog.Builder(this)
            .setTitle("Kalvers Claude Pro - Run Task")
            .setMessage("Autonomous file-by-file streaming & auto-handoff enabled:")
            .setView(input)
            .setPositiveButton("Start Task") { _, _ ->
                val prompt = input.text.toString().trim()
                if (prompt.isNotEmpty()) {
                    startAutonomousTask(prompt)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startAutonomousTask(originalPrompt: String) {
        isTaskRunning = true
        attemptedAccounts.clear()
        progressBar.visibility = View.VISIBLE

        val account = sessionManager.getNextReadyAccount(attemptedAccounts)
        if (account != null) {
            attemptedAccounts.add(account.id)
            sessionManager.activeAccountId = account.id
            activeAccountSpinner.setSelection(account.id - 1)
            account.status = "Working"
            updateUI()

            val fullPrompt = "$incrementalPromptDirective\n\nTASK:\n$originalPrompt"
            appendLog("Submitting task to ${account.name}...")

            val escaped = fullPrompt.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$").replace("\"", "\\\"")
            webView.evaluateJavascript("window.KalversMobile.sendPrompt(`$escaped`);", null)
        } else {
            appendLog("No ready/logged-in accounts available!")
            isTaskRunning = false
            progressBar.visibility = View.GONE
        }
    }

    override fun onLog(message: String) {
        runOnUiThread { appendLog(message) }
    }

    override fun onLimitDetected(transcript: String, codeJson: String, resetTime: String) {
        runOnUiThread {
            appendLog("100% LIMIT DETECTED on Account #${sessionManager.activeAccountId}! Resets at: $resetTime")
            sessionManager.markLimitReached(sessionManager.activeAccountId, resetTime)
            updateUI()

            currentHandoffSummary = transcript
            currentHandoffCode = codeJson

            val nextAccount = sessionManager.getNextReadyAccount(attemptedAccounts)
            if (nextAccount != null) {
                attemptedAccounts.add(nextAccount.id)
                switchAccount(nextAccount.id)
                nextAccount.status = "Working"
                updateUI()

                appendLog("Auto-switching to ${nextAccount.name} with saved context...")

                webView.postDelayed({
                    val continuationPrompt = """
                        $incrementalPromptDirective
                        ### AUTOMATED HANDOFF FROM PREVIOUS SESSION ###
                        Completed Material:
                        $currentHandoffSummary
                        
                        Continue directly with remaining files without repeating work.
                    """.trimIndent()
                    val escaped = continuationPrompt.replace("`", "\\`").replace("$", "\\$")
                    webView.evaluateJavascript("window.KalversMobile.sendPrompt(`$escaped`);", null)
                }, 6500)
            } else {
                appendLog("All logged-in accounts have reached limits!")
                isTaskRunning = false
                progressBar.visibility = View.GONE
            }
        }
    }

    override fun onResponseReady(fullText: String, codeJson: String) {
        runOnUiThread {
            appendLog("Claude completed response successfully!")
            val currentAcc = sessionManager.accounts.find { it.id == sessionManager.activeAccountId }
            currentAcc?.status = "Ready"
            isTaskRunning = false
            progressBar.visibility = View.GONE
            updateUI()

            saveOutputToDisk(fullText)
        }
    }

    private fun saveOutputToDisk(content: String) {
        try {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Kalvers_Claude_Pro")
            dir.mkdirs()
            val file = File(dir, "task_output_${System.currentTimeMillis()}.txt")
            file.writeText(content)
            appendLog("Output saved to phone: ${file.absolutePath}")
        } catch (e: Exception) {
            appendLog("File save note: ${e.message}")
        }
    }

    private fun appendLog(msg: String) {
        val current = logConsole.text.toString()
        logConsole.text = "$current\n> $msg"
    }

    private fun updateUI() {
        val current = sessionManager.accounts.find { it.id == sessionManager.activeAccountId }
        statusBadge.text = current?.status ?: "Unknown"
        when (current?.status) {
            "Ready" -> statusBadge.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
            "Working" -> statusBadge.setTextColor(ContextCompat.getColor(this, android.R.color.holo_blue_light))
            "Limit Reached" -> statusBadge.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
            else -> statusBadge.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        }
    }
}
