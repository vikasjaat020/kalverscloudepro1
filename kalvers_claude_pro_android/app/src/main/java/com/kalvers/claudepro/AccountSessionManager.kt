package com.kalvers.claudepro

import android.content.Context
import android.content.SharedPreferences

data class ClaudeAccount(
    val id: Int,
    val name: String,
    var isLoggedIn: Boolean = false,
    var status: String = "Sign In Required", // "Ready", "Working", "Limit Reached", "Sign In Required"
    var resetTime: String? = null,
    var messageCount: Int = 0
)

class AccountSessionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("kalvers_claude_pro_sessions", Context.MODE_PRIVATE)
    val accounts = mutableListOf<ClaudeAccount>()
    var activeAccountId = 1

    init {
        for (i in 1..15) {
            val savedCookies = prefs.getString("account_${i}_cookies", null)
            val isLogged = !savedCookies.isNullOrEmpty()
            val savedStatus = if (isLogged) "Ready" else "Sign In Required"
            accounts.add(
                ClaudeAccount(
                    id = i,
                    name = "Claude Account #$i",
                    isLoggedIn = isLogged,
                    status = savedStatus
                )
            )
        }
    }

    fun saveSession(accountId: Int, cookies: String?, localStorage: String? = null) {
        if (!cookies.isNullOrEmpty()) {
            val editor = prefs.edit().putString("account_${accountId}_cookies", cookies)
            if (!localStorage.isNullOrEmpty() && localStorage != "{}" && localStorage != "null") {
                editor.putString("account_${accountId}_storage", localStorage)
            }
            editor.apply()

            val acc = accounts.find { it.id == accountId }
            acc?.let {
                it.isLoggedIn = true
                if (it.status == "Sign In Required") {
                    it.status = "Ready"
                }
            }
        }
    }

    fun getSavedCookies(accountId: Int): String? {
        return prefs.getString("account_${accountId}_cookies", null)
    }

    fun getSavedStorage(accountId: Int): String? {
        return prefs.getString("account_${accountId}_storage", null)
    }

    fun clearSession(accountId: Int) {
        prefs.edit()
            .remove("account_${accountId}_cookies")
            .remove("account_${accountId}_storage")
            .apply()

        val acc = accounts.find { it.id == accountId }
        acc?.let {
            it.isLoggedIn = false
            it.status = "Sign In Required"
            it.resetTime = null
            it.messageCount = 0
        }
    }

    fun getNextReadyAccount(excludeIds: List<Int>): ClaudeAccount? {
        return accounts.firstOrNull { it.isLoggedIn && it.status != "Limit Reached" && it.id !in excludeIds }
    }

    fun markLimitReached(accountId: Int, resetTime: String) {
        val acc = accounts.find { it.id == accountId }
        acc?.let {
            it.status = "Limit Reached"
            it.resetTime = resetTime
        }
    }

    fun resetAllLimits() {
        accounts.forEach {
            it.messageCount = 0
            if (it.status == "Limit Reached") {
                it.status = if (it.isLoggedIn) "Ready" else "Sign In Required"
            }
            it.resetTime = null
        }
    }

    fun updateAccountStatus(accountId: Int, status: String) {
        val acc = accounts.find { it.id == accountId }
        acc?.status = status
    }
}
