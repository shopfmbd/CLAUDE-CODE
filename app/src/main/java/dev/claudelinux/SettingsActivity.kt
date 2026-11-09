package dev.claudelinux

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private class M(val id: String, val free: Boolean)

    private fun modelsUrl(base: String): String {
        val b = base.trim().trimEnd('/')
        return if (b.endsWith("/v1")) "$b/models" else "$b/v1/models"
    }

    private fun httpGet(u: String, key: String): Pair<Int, String> {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 30000
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        if (key.isNotEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("x-api-key", key)
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText() ?: ""
        return Pair(code, body)
    }

    /** GET {base}/v1/models, following pagination (has_more/last_id) so ALL models load. Free models sorted first. */
    private fun fetchModels(base: String, key: String): List<M> {
        fun zero(v: String) = v.toDoubleOrNull() == 0.0
        val out = LinkedHashMap<String, M>()
        var afterId: String? = null
        var useLimit = true
        for (page in 0 until 50) {
            val q = mutableListOf<String>()
            if (useLimit) q += "limit=1000"
            if (afterId != null) q += "after_id=" + URLEncoder.encode(afterId, "UTF-8")
            val u = modelsUrl(base) + (if (q.isNotEmpty()) "?" + q.joinToString("&") else "")
            var (code, body) = httpGet(u, key)
            if (code in 400..499 && useLimit && page == 0) { // some gateways reject ?limit=
                useLimit = false
                val r = httpGet(modelsUrl(base), key); code = r.first; body = r.second
            }
            if (code !in 200..299) throw Exception("HTTP $code: ${body.take(200)}")
            val t = body.trim()
            var more = false; var last = ""
            val arr: JSONArray = if (t.startsWith("[")) JSONArray(t) else {
                val o = JSONObject(t)
                more = o.optBoolean("has_more", false); last = o.optString("last_id", "")
                o.optJSONArray("data") ?: o.optJSONArray("models") ?: JSONArray()
            }
            for (i in 0 until arr.length()) {
                val item = arr.opt(i)
                val id: String; var free: Boolean
                if (item is JSONObject) {
                    id = item.optString("id", item.optString("name", ""))
                    val pr = item.optJSONObject("pricing")
                    free = pr != null && pr.has("prompt") && zero(pr.optString("prompt")) && zero(pr.optString("completion", "0"))
                } else { id = item?.toString() ?: ""; free = false }
                if (id.isEmpty()) continue
                if (id.contains("free", ignoreCase = true)) free = true
                out[id] = M(id, free)
            }
            if (!more || last.isEmpty() || last == afterId) break
            afterId = last
        }
        return out.values.sortedWith(compareByDescending<M> { it.free }.thenBy { it.id.lowercase() })
    }

    private fun doFetch(base: String, key: String, target: EditText) {
        if (base.isBlank()) { Toast.makeText(this, "Enter the Base URL first", Toast.LENGTH_SHORT).show(); return }
        Toast.makeText(this, "Fetching models…", Toast.LENGTH_SHORT).show()
        thread {
            try {
                val list = fetchModels(base, key.trim())
                runOnUiThread {
                    if (list.isEmpty()) Toast.makeText(this, "No models returned", Toast.LENGTH_LONG).show()
                    else showPicker(list, target)
                }
            } catch (e: Exception) {
                runOnUiThread { AlertDialog.Builder(this).setTitle("Could not fetch models").setMessage(e.message ?: e.toString()).setPositiveButton("OK", null).show() }
            }
        }
    }

    private fun showPicker(models: List<M>, target: EditText) {
        val search = EditText(this).apply { hint = "Search…"; inputType = InputType.TYPE_CLASS_TEXT }
        val lv = ListView(this)
        val adapter = ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, mutableListOf())
        var shown: List<M> = models
        fun refresh(q: String) {
            shown = models.filter { it.id.contains(q.trim(), ignoreCase = true) } // order kept: free stays on top
            adapter.clear()
            adapter.addAll(shown.map { if (it.free) "★ FREE   ${it.id}" else it.id })
            adapter.notifyDataSetChanged()
        }
        lv.adapter = adapter
        refresh("")
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { refresh(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), 0) }
        box.addView(search, LinearLayout.LayoutParams(-1, -2))
        box.addView(lv, LinearLayout.LayoutParams(-1, dp(360)))
        val dlg = AlertDialog.Builder(this).setTitle("${models.size} models (${models.count { it.free }} free)").setView(box)
            .setNegativeButton("Cancel", null).create()
        lv.setOnItemClickListener { _, _, i, _ -> target.setText(shown[i].id); dlg.dismiss() }
        dlg.show()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        title = "API settings"
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }

        fun label(t: String) = col.addView(TextView(this).apply { text = t; setPadding(0, dp(12), 0, 0) })
        fun field(lbl: String, key: String, hint: String, secret: Boolean = false, lines: Int = 1): EditText {
            label(lbl)
            val e = EditText(this).apply {
                setText(Config.get(this@SettingsActivity, key)); this.hint = hint
                if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                else if (lines > 1) { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; minLines = lines }
                else inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            col.addView(e, LinearLayout.LayoutParams(-1, -2))
            return e
        }

        val url = field("Base URL (ANTHROPIC_BASE_URL)", "base_url", "https://my-gateway.example.com")
        val key = field("API key / token", "api_key", "optional if your endpoint needs no key", secret = true)
        fun pickBtn(label: String, target: EditText) = Button(this).apply {
            text = label; isAllCaps = false
            setOnClickListener { doFetch(url.text.toString(), key.text.toString(), target) }
        }

        label("Send the key as")
        val rg = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val rKey = RadioButton(this).apply { text = "ANTHROPIC_API_KEY  (x-api-key header)"; id = 101 }
        val rTok = RadioButton(this).apply { text = "ANTHROPIC_AUTH_TOKEN  (Bearer header)"; id = 102 }
        val rNone = RadioButton(this).apply { text = "No key needed (skip login, my endpoint has no auth)"; id = 103 }
        rg.addView(rKey); rg.addView(rTok); rg.addView(rNone)
        rg.check(when (Config.get(this, "auth_mode", "key")) { "token" -> 102; "none" -> 103; else -> 101 })
        col.addView(rg)

        val model = field("Model (ANTHROPIC_MODEL)", "model", "e.g. your-gateway-model-id")
        col.addView(pickBtn("Fetch model list (free on top)", model))
        val fast = field("Small / fast model (optional)", "fast_model", "used for background tasks")
        col.addView(pickBtn("Fetch model list for small model", fast))
        val maxOut = field("Max output tokens per reply (CLAUDE_CODE_MAX_OUTPUT_TOKENS)", "max_output", "e.g. 32000 (empty = default)")
        val extra = field("Extra environment variables (one KEY=VALUE per line)", "extra_env", "API_TIMEOUT_MS=600000", lines = 3)

        val lean = CheckBox(this).apply {
            text = "Disable non-essential traffic (telemetry, update checks)"
            isChecked = Config.getBool(this@SettingsActivity, "lean", true)
        }
        col.addView(lean)
        col.addView(TextView(this).apply { text = "Changes apply to sessions you open afterwards."; setPadding(0, dp(8), 0, dp(8)) })

        col.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                Config.put(context, "base_url", url.text.toString())
                Config.put(context, "api_key", key.text.toString())
                Config.put(context, "auth_mode", when (rg.checkedRadioButtonId) { 102 -> "token"; 103 -> "none"; else -> "key" })
                Config.put(context, "model", model.text.toString())
                Config.put(context, "fast_model", fast.text.toString())
                Config.put(context, "max_output", maxOut.text.toString().filter { it.isDigit() })
                Config.put(context, "extra_env", extra.text.toString())
                Config.putBool(context, "lean", lean.isChecked)
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                finish()
            }
        })
        setContentView(ScrollView(this).apply { addView(col, ViewGroup.LayoutParams(-1, -2)) })
    }
}
