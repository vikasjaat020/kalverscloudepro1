/**
 * Kalvers Claude Pro - Mobile Autonomous Bridge & Observer
 * Runs natively inside Android WebViews to handle automated task entry,
 * streaming checkpoints, and seamless handoff on limit detection.
 */

(function() {
    console.log("[Kalvers Mobile] Injected automation script initialized.");

    const LIMIT_KEYWORDS = [
        "you've reached your usage limit",
        "free message limit reached",
        "you have reached your limit",
        "you are out of free messages",
        "resets at",
        "limit will reset",
        "wait until",
        "try again in",
        "upgrade to pro to continue"
    ];

    const QUEUED_KEYWORDS = [
        "checking for a response to your message",
        "if claude is still working on it"
    ];

    function extractResetTime(text) {
        const patterns = [
            /(?:until|at|after|resets?\s+at)\s+([0-9]{1,2}(?::[0-9]{2})?\s*(?:am|pm|AM|PM))/i,
            /([0-9]{1,2}(?::[0-9]{2})?\s*(?:am|pm|AM|PM))\s*(?:tomorrow|today)?/i,
            /(?:in\s+)([0-9]+\s+(?:hours?|minutes?|hrs?|mins?))/i
        ];
        for (let p of patterns) {
            let m = text.match(p);
            if (m) return m[1].trim();
        }
        return "";
    }

    function extractCodeBlocks(text) {
        const blocks = [];
        const regex = /```([a-zA-Z0-9_\-\.\+]*)\n([\s\S]*?)```/g;
        let match;
        while ((match = regex.exec(text)) !== null) {
            blocks.push({
                language: match[1].trim() || "text",
                code: match[2].trim()
            });
        }
        return blocks;
    }

    function getLatestAssistantResponse() {
        const msgs = document.querySelectorAll('.font-claude-message, [data-is-streaming], div[class*="font-claude"], .standard-markdown');
        if (msgs.length > 0) {
            return msgs[msgs.length - 1].innerText;
        }
        const turns = document.querySelectorAll('div[class*="grid-cols-1"]');
        if (turns.length > 0) {
            return turns[turns.length - 1].innerText;
        }
        return "";
    }

    function getFullTranscript() {
        const nodes = document.querySelectorAll('.font-user-message, .font-claude-message, .standard-markdown');
        let arr = [];
        nodes.forEach(n => arr.push(n.innerText));
        return arr.join("\n\n--- NEXT TURN ---\n\n");
    }

    // Public API exposed to native Android code
    window.KalversMobile = {
        sendPrompt: function(promptText) {
            console.log("[Kalvers Mobile] Sending prompt...");
            const inputEl = document.querySelector('div[contenteditable="true"], textarea, p[data-placeholder]');
            if (!inputEl) {
                if (window.KalversBridge) {
                    window.KalversBridge.onLog("Message input box not found. Make sure you are logged in.");
                }
                return false;
            }

            inputEl.focus();
            document.execCommand('insertText', false, promptText);
            inputEl.dispatchEvent(new Event('input', { bubbles: true }));

            setTimeout(function() {
                const sendBtn = document.querySelector('button[aria-label*="Send"], button[aria-label*="send"]');
                if (sendBtn) {
                    sendBtn.click();
                } else {
                    const enterEvent = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, key: 'Enter', keyCode: 13 });
                    inputEl.dispatchEvent(enterEvent);
                }

                if (window.KalversBridge) {
                    window.KalversBridge.onLog("Prompt submitted. Starting streaming and limit observer...");
                }

                KalversMobile.startObserver();
            }, 800);

            return true;
        },

        startObserver: function() {
            let lastLen = 0;
            let stableCount = 0;

            const interval = setInterval(function() {
                const bodyText = document.body.innerText.toLowerCase();

                // 1. Check for 100% capacity limit
                for (let kw of LIMIT_KEYWORDS) {
                    if (bodyText.includes(kw)) {
                        clearInterval(interval);
                        const resetTime = extractResetTime(bodyText) || "Later";
                        const transcript = getFullTranscript();
                        const latestText = getLatestAssistantResponse();
                        const codeBlocks = extractCodeBlocks(latestText);

                        if (window.KalversBridge) {
                            window.KalversBridge.onLimitDetected(
                                transcript,
                                JSON.stringify(codeBlocks),
                                resetTime
                            );
                        }
                        return;
                    }
                }

                // 2. Check for Queued message ("Checking for a response...")
                for (let qkw of QUEUED_KEYWORDS) {
                    if (bodyText.includes(qkw)) {
                        if (window.KalversBridge) {
                            window.KalversBridge.onLog("Claude is processing in background. Auto-refreshing in 2 minutes...");
                        }
                        // Android host will handle 2-minute refresh
                        return;
                    }
                }

                // 3. Check for completed response
                const stopBtn = document.querySelector('button[aria-label*="Stop"], [data-testid="stop-button"]');
                const isBusy = stopBtn && stopBtn.offsetParent !== null;
                const currentText = getLatestAssistantResponse();

                if (currentText.length > 20 && currentText.length === lastLen && !isBusy) {
                    stableCount++;
                    if (stableCount >= 3) {
                        clearInterval(interval);
                        const codeBlocks = extractCodeBlocks(currentText);
                        if (window.KalversBridge) {
                            window.KalversBridge.onResponseReady(
                                currentText,
                                JSON.stringify(codeBlocks)
                            );
                        }
                    }
                } else {
                    stableCount = 0;
                    lastLen = currentText.length;
                }

            }, 2500);
        }
    };

    console.log("[Kalvers Mobile] Bridge ready. Window.KalversMobile exposed.");
})();
