package com.matrixlab.focustime.filter

import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Detects pornographic content by checking:
 *  1. Known porn-app package names  (instant)
 *  2. Browser URL-bar domain match  (O(1) HashSet lookup)
 *  3. Explicit-keyword scan on URL + page title text
 *
 * All checks are text-based — no image processing, no ML, near-zero CPU.
 */
class ContentFilterManager {

    companion object {
        private const val TAG = "ContentFilter"
        private const val MAX_TREE_DEPTH = 15
    }

    // ── public API ────────────────────────────────────────────────────

    fun isPornApp(packageName: String): Boolean =
        packageName in BlockLists.pornAppPackages

    fun isBrowserApp(packageName: String): Boolean =
        packageName in BlockLists.browserPackages

    /**
     * Main entry-point for browsers.
     * Returns `true` when the visible page is likely adult content.
     */
    fun shouldBlockBrowserContent(root: AccessibilityNodeInfo): Boolean {
        val urlInfo = extractBrowserInfo(root)

        // 1. Domain check (fastest path)
        val url = urlInfo.url
        if (url != null && isPornDomain(url)) {
            Log.d(TAG, "Domain blocked: $url")
            return true
        }

        // 2. Keyword check on combined URL + title text
        val combined = buildString {
            if (url != null) append(url).append(' ')
            if (urlInfo.title != null) append(urlInfo.title)
        }
        if (combined.isNotBlank() && hasExplicitKeywords(combined)) {
            Log.d(TAG, "Keyword blocked – text: ${combined.take(120)}")
            return true
        }

        return false
    }

    // ── domain matching ───────────────────────────────────────────────

    fun isPornDomain(url: String): Boolean {
        val domain = extractDomain(url) ?: return false
        // Exact match
        if (domain in BlockLists.pornDomains) return true
        // Subdomain match (e.g. m.pornhub.com → pornhub.com)
        for (blocked in BlockLists.pornDomains) {
            if (domain.endsWith(".$blocked")) return true
        }
        return false
    }

    // ── keyword matching ──────────────────────────────────────────────

    fun hasExplicitKeywords(text: String): Boolean {
        val lower = text.lowercase()
        for (keyword in BlockLists.explicitKeywords) {
            if (keyword in lower) return true
        }
        return false
    }

    // ── URL extraction from browser accessibility tree ────────────────

    private data class BrowserInfo(val url: String?, val title: String?)

    private fun extractBrowserInfo(root: AccessibilityNodeInfo): BrowserInfo {
        var url: String? = null
        var title: String? = null

        // Try to get page title from root content description
        val rootDesc = root.contentDescription?.toString()
        if (!rootDesc.isNullOrBlank()) {
            title = rootDesc
        }

        // Search for URL bar node
        url = findUrlBarText(root, 0)

        return BrowserInfo(url, title)
    }

    /**
     * Recursively search for the URL bar in the accessibility tree.
     * Matches on known browser URL-bar view IDs first, then falls back
     * to any EditText whose content looks like a URL.
     */
    private fun findUrlBarText(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > MAX_TREE_DEPTH) {
            node.recycle()
            return null
        }

        val viewId = node.viewIdResourceName ?: ""
        val text = node.text?.toString() ?: ""

        // Fast path: known URL bar view IDs
        for (barId in BlockLists.browserUrlBarIds) {
            if (viewId == barId && text.isNotBlank()) {
                val result = text
                node.recycle()
                return result
            }
        }

        // Fallback: EditText that looks like a URL
        if (node.className?.toString() == "android.widget.EditText" && looksLikeUrl(text)) {
            val result = text
            node.recycle()
            return result
        }

        // Recurse children
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) ?: continue } catch (_: Exception) { continue }
            val found = findUrlBarText(child, depth + 1)
            if (found != null) {
                node.recycle()
                return found
            }
        }

        node.recycle()
        return null
    }

    // ── helpers ───────────────────────────────────────────────────────

    private fun extractDomain(url: String): String? {
        return try {
            val cleaned = if ("://" in url) url else "https://$url"
            Uri.parse(cleaned).host
                ?.lowercase()
                ?.removePrefix("www.")
        } catch (_: Exception) {
            null
        }
    }

    private fun looksLikeUrl(text: String): Boolean {
        if (text.isBlank()) return false
        return "://" in text || (text.contains('.') && !text.contains(' '))
    }
}
