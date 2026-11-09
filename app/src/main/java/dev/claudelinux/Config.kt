package dev.claudelinux

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** User-editable settings for pointing Claude Code at a custom API endpoint. */
object Config {
    private fun p(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    fun get(c: Context, k: String, d: String = ""): String = p(c).getString(k, d) ?: d
    fun put(c: Context, k: String, v: String) { p(c).edit().putString(k, v).apply() }
    fun getBool(c: Context, k: String, d: Boolean): Boolean = p(c).getBoolean(k, d)
    fun putBool(c: Context, k: String, v: Boolean) { p(c).edit().putBoolean(k, v).apply() }

    /** Environment variables handed to the guest (proot passes the host env through). */
    fun envVars(c: Context): List<String> {
        val out = mutableListOf<String>()
        val url = get(c, "base_url").trim()
        if (url.isNotEmpty()) out += "ANTHROPIC_BASE_URL=$url"
        val key = get(c, "api_key").trim()
        val mode = get(c, "auth_mode", "key")
        // "none" = custom endpoint with no auth. Claude Code still needs *some* credential set
        // or it shows "Not logged in", so hand it a placeholder token (skips /login entirely).
        if (mode == "none" || (key.isEmpty() && url.isNotEmpty())) out += "ANTHROPIC_AUTH_TOKEN=${if (key.isNotEmpty()) key else "no-key-needed"}"
        else if (key.isNotEmpty()) out += if (mode == "token") "ANTHROPIC_AUTH_TOKEN=$key" else "ANTHROPIC_API_KEY=$key"
        val model = get(c, "model").trim()
        if (model.isNotEmpty()) out += "ANTHROPIC_MODEL=$model"
        val fast = get(c, "fast_model").trim()
        if (fast.isNotEmpty()) { out += "ANTHROPIC_SMALL_FAST_MODEL=$fast"; out += "ANTHROPIC_DEFAULT_HAIKU_MODEL=$fast" }
        val maxOut = get(c, "max_output").trim()
        if (maxOut.isNotEmpty()) out += "CLAUDE_CODE_MAX_OUTPUT_TOKENS=$maxOut"
        if (getBool(c, "lean", true)) out += "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1"
        for (line in get(c, "extra_env").lines()) {
            val t = line.trim()
            if (t.isNotEmpty() && !t.startsWith("#") && t.contains("=")) out += t
        }
        return out
    }

    /** Pre-answer Claude Code's first-run login / "use this API key?" prompts so a custom endpoint works immediately. */
    fun seedClaudeJson(c: Context) {
        val f = File(Linux.rootfs(c), "root/.claude.json")
        val o = try { if (f.exists()) JSONObject(f.readText()) else JSONObject() } catch (_: Exception) { JSONObject() }
        o.put("hasCompletedOnboarding", true)
        val key = get(c, "api_key").trim()
        if (key.isNotEmpty() && get(c, "auth_mode", "key") == "key") {
            val r = o.optJSONObject("customApiKeyResponses") ?: JSONObject()
            val approved = r.optJSONArray("approved") ?: JSONArray()
            val tail = key.takeLast(20)
            var has = false
            for (i in 0 until approved.length()) if (approved.optString(i) == tail) has = true
            if (!has) approved.put(tail)
            r.put("approved", approved)
            if (!r.has("rejected")) r.put("rejected", JSONArray())
            o.put("customApiKeyResponses", r)
        }
        f.parentFile?.mkdirs()
        f.writeText(o.toString())
    }
}
