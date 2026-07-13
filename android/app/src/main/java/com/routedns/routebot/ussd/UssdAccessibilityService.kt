package com.routedns.routebot.ussd

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.routedns.routebot.common.RouteBotLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Accessibility helper for USSD dialogs: capture text and type interactive replies.
 *
 * Used when [android.telephony.TelephonyManager.sendUssdRequest] is blocked by the OEM
 * (common on Oppo/ColorOS). Mirrors the dial + a11y approach of reliable USSD agents.
 */
class UssdAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 50
        }
        RouteBotLog.i("ussd_a11y_connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val waiter = pending.get() ?: return
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) {
            return
        }

        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty() && !isPhonePackage(pkg)) return

        val prompt = readPhoneDialogText() ?: return
        if (!looksLikeUssdResponse(prompt)) return
        val normalized = normalizePrompt(prompt)
        if (normalized.isBlank()) return
        if (normalized == lastCompletedPrompt) return

        val hasInput = hasInputField()
        if (requireInputField && !hasInput && !looksLikeTerminalResult(normalized)) {
            RouteBotLog.i(
                "ussd_a11y_wait_input",
                mapOf("chars" to normalized.length, "pkg" to pkg)
            )
            return
        }

        RouteBotLog.i(
            "ussd_a11y_captured",
            mapOf("chars" to normalized.length, "pkg" to pkg, "has_input" to hasInput)
        )
        if (pending.compareAndSet(waiter, null)) {
            lastCompletedPrompt = normalized
            waiter.complete(normalized)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        pending.getAndSet(null)?.cancel()
        super.onDestroy()
        RouteBotLog.i("ussd_a11y_destroyed")
    }

    private fun readPhoneDialogText(): String? {
        val texts = linkedSetOf<String>()
        windows?.forEach { window ->
            val root = window.root ?: return@forEach
            try {
                val winPkg = root.packageName?.toString().orEmpty()
                if (winPkg.isEmpty() || isPhonePackage(winPkg)) {
                    collectText(root, texts)
                }
            } finally {
                root.recycle()
            }
        }
        if (texts.isEmpty()) {
            rootInActiveWindow?.let { collectText(it, texts) }
        }
        val body = texts
            .asSequence()
            .map { it.trim() }
            .filter { it.length >= 2 }
            .filterNot { IGNORE_LABELS.contains(it.lowercase()) }
            .filterNot { isNoiseLine(it) }
            .distinct()
            .toList()
        if (body.isEmpty()) return null
        return body.joinToString("\n")
    }

    private fun isNoiseLine(line: String): Boolean {
        val lower = line.lowercase().trim()
        if (NOISE_PREFIXES.any { line.startsWith(it, ignoreCase = true) }) return true
        if (line.contains("notification:", ignoreCase = true)) return true
        if (line.contains("Status Bar", ignoreCase = true)) return true
        if (Regex("""^\d{1,2}:\d{2}""").containsMatchIn(line)) return true // clock
        if (lower == "kb/s" || lower == "kb/s:" || lower.endsWith(" kb/s")) return true
        if (Regex("""(?i)^\d+(\.\d+)?\s*KB/S$""").matches(line.trim())) return true
        // Bare rate left when "KB/S" is a separate node (e.g. "4.00" + "KB/S")
        if (Regex("""^\d+(\.\d+)?$""").matches(line.trim()) && line.length <= 6) return true
        if (lower == "roaming" || lower == "am" || lower == "pm") return true
        if (Regex("""^\d{1,3}%$""").matches(line.trim())) return true
        return false
    }

    private fun collectText(node: AccessibilityNodeInfo, out: MutableSet<String>) {
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, out)
            child.recycle()
        }
    }

    private fun hasInputField(): Boolean {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        windows?.forEach { w -> w.root?.let { roots.add(it) } }
        rootInActiveWindow?.let { roots.add(it) }
        try {
            return roots.any { findEditable(it) != null }
        } finally {
            roots.forEach { it.recycle() }
        }
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun clickButton(labels: Collection<String>): Boolean {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        windows?.forEach { w -> w.root?.let { roots.add(it) } }
        rootInActiveWindow?.let { roots.add(it) }
        try {
            for (root in roots) {
                if (clickButtonIn(root, labels)) return true
            }
        } finally {
            roots.forEach { it.recycle() }
        }
        return false
    }

    private fun clickButtonIn(node: AccessibilityNodeInfo, labels: Collection<String>): Boolean {
        val label = (node.text?.toString() ?: node.contentDescription?.toString() ?: "")
            .trim()
            .lowercase()
        if (label in labels && (node.isClickable || node.isEnabled)) {
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) {
                val parent = target.parent
                if (target !== node) target.recycle()
                target = parent
            }
            val clicked = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            if (target != null && target !== node) target.recycle()
            if (clicked) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val clicked = clickButtonIn(child, labels)
            child.recycle()
            if (clicked) return true
        }
        return false
    }

    private fun typeIntoUssdField(value: String): Boolean {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        windows?.forEach { w -> w.root?.let { roots.add(it) } }
        rootInActiveWindow?.let { roots.add(it) }
        try {
            for (root in roots) {
                val edit = findEditable(root) ?: continue
                try {
                    edit.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                    }
                    val set = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    if (set) return true
                } finally {
                    edit.recycle()
                }
            }
        } finally {
            roots.forEach { it.recycle() }
        }
        return false
    }

    companion object {
        @Volatile
        private var instance: UssdAccessibilityService? = null

        @Volatile
        private var lastCompletedPrompt: String? = null

        /** When true, ignore USSD text until an editable reply field is present. */
        @Volatile
        private var requireInputField: Boolean = false

        private val pending = AtomicReference<CompletableDeferred<String>?>(null)

        private val IGNORE_LABELS = setOf(
            "ok", "okay", "cancel", "dismiss", "close", "done", "send", "ussd",
            "phone", "dial", "call", "message", "messages", "send instructions"
        )

        private val NOISE_PREFIXES = listOf(
            "Device:", "Server:", "WebSocket:", "Last heartbeat:", "Pending offline",
            "Battery:", "Network:", "Permissions", "SMS ", "Call Phone", "Phone State",
            "Notifications", "Camera", "Notification access", "Open Notification",
            "RouteBot", "Wifi signal", "Phone three", "Battery Status", "Network Speed",
            "Overview", "Back", "Home", "Telegram", "System UI", "Salatuk"
        )

        private val SEND_LABELS = listOf("send", "ok", "okay", "submit", "next", "continue")
        private val DISMISS_LABELS = listOf("ok", "okay", "dismiss", "close", "done", "cancel")

        fun isConnected(): Boolean = instance != null

        fun isEnabledInSettings(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val pkg = context.packageName
            val shortName = "$pkg/.ussd.UssdAccessibilityService"
            val fullName = "$pkg/${UssdAccessibilityService::class.java.name}"
            return enabled.split(':').any {
                it.equals(shortName, ignoreCase = true) ||
                    it.equals(fullName, ignoreCase = true) ||
                    it.contains("UssdAccessibilityService", ignoreCase = true)
            }
        }

        fun armCapture(
            resetLast: Boolean = true,
            requireInput: Boolean = false
        ): CompletableDeferred<String> {
            pending.getAndSet(null)?.cancel()
            if (resetLast) lastCompletedPrompt = null
            requireInputField = requireInput
            val deferred = CompletableDeferred<String>()
            pending.set(deferred)
            return deferred
        }

        fun cancelCapture() {
            pending.getAndSet(null)?.cancel()
        }

        suspend fun awaitCapture(timeoutMs: Long): String? {
            val deferred = pending.get() ?: return null
            return withTimeoutOrNull(timeoutMs) { deferred.await() }.also {
                pending.compareAndSet(deferred, null)
            }
        }

        /**
         * Type [value] into the active USSD input dialog and press Send.
         * Returns false if no editable field / send button was found.
         */
        suspend fun reply(value: String): Boolean {
            val svc = instance ?: return false
            // Wait briefly for the input dialog to be fully drawn.
            repeat(15) {
                if (svc.hasInputField()) return@repeat
                delay(200)
            }
            val typed = svc.typeIntoUssdField(value)
            if (!typed) {
                RouteBotLog.w("ussd_reply_no_field", mapOf("value" to value))
                return false
            }
            delay(300)
            val sent = svc.clickButton(SEND_LABELS)
            // Keep lastCompletedPrompt so the same menu cannot complete a waiter
            // before the carrier refreshes the dialog.
            RouteBotLog.i("ussd_reply", mapOf("value" to value, "sent" to sent))
            return sent
        }

        fun dismissDialog() {
            instance?.clickButton(DISMISS_LABELS)
        }

        /** Normalize prompt text for equality checks (drop status-bar fluff). */
        fun normalizePrompt(text: String): String {
            return text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filterNot { line ->
                    val lower = line.lowercase()
                    Regex("""^\d{1,2}:\d{2}""").containsMatchIn(line) ||
                        Regex("""(?i)^\d+(\.\d+)?\s*KB/S$""").matches(line) ||
                        lower == "kb/s" ||
                        Regex("""^\d+(\.\d+)?$""").matches(line) && line.length <= 6 ||
                        lower == "roaming" || lower == "am" || lower == "pm" ||
                        Regex("""^\d{1,3}%$""").matches(line)
                }
                .joinToString("\n")
        }

        private fun isPhonePackage(pkg: String): Boolean {
            return pkg == "com.android.phone" ||
                pkg == "com.android.systemui" ||
                pkg.contains("telephony", ignoreCase = true) ||
                pkg.contains("dialer", ignoreCase = true)
        }

        private fun looksLikeTerminalResult(joined: String): Boolean {
            val lower = joined.lowercase()
            return lower.contains("balance") ||
                Regex("""(?i)\btk\s*[\d.,]+""").containsMatchIn(joined) ||
                lower.contains("success") ||
                lower.contains("successful") ||
                lower.contains("invalid") ||
                lower.contains("incorrect") ||
                lower.contains("failed") ||
                lower.contains("thank")
        }

        private fun looksLikeUssdResponse(joined: String): Boolean {
            val lower = joined.lowercase()
            if (lower.contains("ussd code running") || lower == "ussd code running…") return false
            if (lower.contains("running ussd") || lower.contains("please wait")) return false
            val parts = joined.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (joined.contains('*') && joined.contains('#') && parts.size <= 2) {
                if (parts.all { it.matches(Regex("""[\d*#\s]+""")) }) return false
            }
            if (Regex("""\d\)""").containsMatchIn(joined)) return true
            if (lower.contains("enter pin") || lower.contains("enter your pin") ||
                lower.contains("pin") && lower.contains("enter")
            ) {
                return true
            }
            if (lower.contains("balance") || lower.contains("invalid") ||
                lower.contains("success") || lower.contains("error") ||
                lower.contains("menu") || lower.contains("press") ||
                lower.contains("my number") || lower.contains("offers") ||
                lower.contains("loan") || lower.contains("package") ||
                lower.contains("reply") || lower.contains("option")
            ) {
                return true
            }
            if (joined.length >= 20 && parts.any { it.length >= 12 }) return true
            return false
        }
    }
}
