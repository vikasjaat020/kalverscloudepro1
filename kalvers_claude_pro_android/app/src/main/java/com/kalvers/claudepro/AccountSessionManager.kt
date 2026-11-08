package com.kalvers.claudepro

data class ClaudeAccount(
    val id: Int,
    val name: String,
    var isLoggedIn: Boolean = false,
    var status: String = "Sign In Required", // "Ready", "Working", "Limit Reached", "Sign In Required"
    var resetTime: String? = null,
    var messageCount: Int = 0
)

class AccountSessionManager {
    val accounts = mutableListOf<ClaudeAccount>()
    var activeAccountId = 1

    init {
        for (i in 1..15) {
            accounts.add(
                ClaudeAccount(
                    id = i,
                    name = "Claude Account #$i",
                    isLoggedIn = (i == 1 || i == 2),
                    status = if (i == 1 || i == 2) "Ready" else "Sign In Required"
                )
            )
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
}
