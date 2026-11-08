package com.kalvers.claudepro

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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

        sessionManager = AccountSessionManager()

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

        loadAccountSession(sessionManager.activeAccountId)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.userAgentString = settings.userAgentString.replace("; wv", "") // Real mobile browser UA

        webView.addJavascriptInterface(ClaudeAutomationBridge(this), "KalversBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectAutomationScript()
            }
        }
    }

    private fun injectAutomationScript() {
        try {
            val assetStream = assets.open("kalvers_injector.js")
            val script = InputStreamReader(assetStream).readText()
            webView.evaluateJavascript(script, null)
        } catch (e: Exception) {
            appendLog("Injection error: ${e.message}")
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
                if (selectedId != sessionManager.activeAccountId && !isTaskRunning) {
                    sessionManager.activeAccountId = selectedId
                    loadAccountSession(selectedId)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadAccountSession(accountId: Int) {
        appendLog("Switching to Claude Account #$accountId session...")
        webView.loadUrl("https://claude.ai/new")
        updateUI()
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
        if (account == null) {
            appendLog("❌ No ready accounts available!")
            isTaskRunning = false
            progressBar.visibility = View.GONE
            return
        }

        attemptedAccounts.add(account.id)
        sessionManager.activeAccountId = account.id
        activeAccountSpinner.setSelection(account.id - 1)
        account.status = "Working"
        updateUI()

        val fullPrompt = "$incrementalPromptDirective\n\nTASK:\n$originalPrompt"
        appendLog("🚀 Submitting task to ${account.name}...")

        val escaped = fullPrompt.replace("\\", "\\\\").replace("`", "\\`").replace("$", "\\$").replace("\"", "\\\"")
        webView.evaluateJavascript("window.KalversMobile.sendPrompt(`$escaped`);", null)
    }

    // Bridge Callbacks from JavaScript
    override fun onLog(message: String) {
        runOnUiThread { appendLog(message) }
    }

    override fun onLimitDetected(transcript: String, codeJson: String, resetTime: String) {
        runOnUiThread {
            appendLog("🛑 100% LIMIT DETECTED on Account #${sessionManager.activeAccountId}! Resets at: $resetTime")
            sessionManager.markLimitReached(sessionManager.activeAccountId, resetTime)
            updateUI()

            currentHandoffSummary = transcript
            currentHandoffCode = codeJson

            // Automatically switch to next account
            val nextAccount = sessionManager.getNextReadyAccount(attemptedAccounts)
            if (nextAccount == null) {
                appendLog("❌ All logged-in accounts have reached limits!")
                isTaskRunning = false
                progressBar.visibility = View.GONE
                return
            }

            attemptedAccounts.add(nextAccount.id)
            sessionManager.activeAccountId = nextAccount.id
            activeAccountSpinner.setSelection(nextAccount.id - 1)
            nextAccount.status = "Working"
            updateUI()

            appendLog("🔄 Auto-switching to ${nextAccount.name} with saved context...")
            loadAccountSession(nextAccount.id)

            // Inject continuation prompt once page reloads
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
            }, 6000)
        }
    }

    override fun onResponseReady(fullText: String, codeJson: String) {
        runOnUiThread {
            appendLog("🎉 Claude completed response successfully!")
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
            appendLog("📁 Output saved to phone: ${file.absolutePath}")
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
            "Ready" -> statusBadge.setTextColor(getColor(android.R.color.holo_green_light))
            "Working" -> statusBadge.setTextColor(getColor(android.R.color.holo_blue_light))
            "Limit Reached" -> statusBadge.setTextColor(getColor(android.R.color.holo_orange_light))
            else -> statusBadge.setTextColor(getColor(android.R.color.darker_gray))
        }
    }
}
