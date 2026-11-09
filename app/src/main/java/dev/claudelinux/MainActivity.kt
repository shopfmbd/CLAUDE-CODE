package dev.claudelinux

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlin.concurrent.thread

object Sessions {
    val list = mutableListOf<TerminalSession>()
    val names = HashMap<TerminalSession, String>()
    var current: TerminalSession? = null
    var setup: TerminalSession? = null
}

/** One shared client so sessions survive the Activity being recreated. */
object SessionClient : TerminalSessionClient {
    var view: TerminalView? = null
    var ctx: Context? = null
    var onFinished: ((TerminalSession) -> Unit)? = null

    override fun onTextChanged(changedSession: TerminalSession) { view?.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) { onFinished?.invoke(finishedSession) }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        val c = ctx ?: return
        c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("terminal", text ?: ""))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val c = ctx ?: return
        val t = c.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(c)?.toString() ?: return
        (session ?: view?.currentSession)?.emulator?.paste(t)
    }
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}

/** TerminalView that forces a "raw text" input mode: no suggestions, no autocorrect, no auto-caps, no composing text. */
class Term(c: Context) : TerminalView(c, null) {
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs)
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = outAttrs.imeOptions or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        outAttrs.privateImeOptions = "nm"
        return ic
    }
}

class MainActivity : Activity() {
    private lateinit var terminal: TerminalView
    private lateinit var panel: LinearLayout
    private lateinit var panelText: TextView
    private lateinit var panelBtn: Button
    private lateinit var panelBar: ProgressBar
    private lateinit var ctrlBtn: Button
    private lateinit var altBtn: Button
    private var ctrlOn = false
    private var altOn = false
    private var fontSp = 14f

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun px(sp: Float) = (sp * resources.displayMetrics.scaledDensity).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Claude Linux"
        installCrashLogger()
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val rootCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val stack = FrameLayout(this)
        terminal = Term(this).apply {
            setTerminalViewClient(VC())
            setTextSize(px(fontSp))
            isFocusable = true; isFocusableInTouchMode = true
            keepScreenOn = true
        }
        stack.addView(terminal, FrameLayout.LayoutParams(-1, -1))

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#E6000000")); setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        panelText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER }
        panelBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        panelBtn = Button(this)
        panel.addView(panelText); panel.addView(panelBar, LinearLayout.LayoutParams(-1, -2)); panel.addView(panelBtn)
        stack.addView(panel, FrameLayout.LayoutParams(-1, -1))
        rootCol.addView(stack, LinearLayout.LayoutParams(-1, 0, 1f))
        rootCol.addView(buildKeyRow(), LinearLayout.LayoutParams(-1, -2))
        setContentView(rootCol)

        SessionClient.view = terminal
        SessionClient.ctx = applicationContext
        SessionClient.onFinished = { s -> handleFinished(s) }

        val cur = Sessions.current
        if (cur != null && Sessions.list.contains(cur)) attach(cur) else route()
    }

    override fun onDestroy() {
        super.onDestroy()
        SessionClient.view = null
        SessionClient.onFinished = null
    }

    private fun installCrashLogger() {
        val f = java.io.File(filesDir, "crash.txt")
        if (f.exists()) {
            val t = f.readText(); f.delete()
            AlertDialog.Builder(this).setTitle("Last crash").setMessage(t.take(3000))
                .setPositiveButton("OK", null).show()
        }
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { th, e ->
            try { f.writeText(android.util.Log.getStackTraceString(e)) } catch (_: Throwable) {}
            old?.uncaughtException(th, e)
        }
    }

    // ---------- flow ----------
    private fun route() {
        when {
            !Linux.extracted(this) -> showSetup()
            !Linux.ready(this) -> startBootstrap()
            Sessions.list.isEmpty() -> showIdle()
            else -> attach(Sessions.list.last())
        }
    }

    private fun showPanel(text: String, btn: String?, onClick: (() -> Unit)?) {
        panel.visibility = View.VISIBLE
        panelText.text = text
        panelBar.visibility = View.GONE
        if (btn == null) panelBtn.visibility = View.GONE
        else { panelBtn.visibility = View.VISIBLE; panelBtn.text = btn; panelBtn.setOnClickListener { onClick?.invoke() } }
    }

    private fun showSetup() {
        showPanel("Welcome.\n\nThis installs a small Alpine Linux and Claude Code inside the app. It needs internet and a few hundred MB.", "Install Linux + Claude Code") { runInstall() }
    }

    private fun showIdle() {
        showPanel("No open sessions.", "Open Claude Code") { newClaude() }
    }

    private fun runInstall() {
        panelBtn.visibility = View.GONE
        panelBar.visibility = View.VISIBLE
        thread {
            try {
                Linux.install(this) { msg, pct ->
                    runOnUiThread {
                        panelText.text = msg
                        panelBar.isIndeterminate = pct < 0
                        if (pct >= 0) panelBar.progress = pct
                    }
                }
                runOnUiThread { route() }
            } catch (e: Exception) {
                runOnUiThread { showPanel("Install failed:\n${e.message}", "Retry") { runInstall() } }
            }
        }
    }

    private fun startBootstrap() {
        Sessions.setup?.let { Sessions.list.remove(it); it.finishIfRunning() }
        newSession("Setup", listOf("/bin/sh", "/root/bootstrap.sh"), isSetup = true)
    }

    private fun newClaude() {
        Config.seedClaudeJson(this)
        newSession(
            "Claude Code",
            listOf("/bin/bash", "-lc", "cd /root/work 2>/dev/null; claude; echo; echo '[claude exited. Type claude to restart]'; exec bash -l")
        )
    }

    private fun newShell() = newSession("Shell", listOf("/bin/bash", "-l"))

    private fun newSession(title: String, guest: List<String>, isSetup: Boolean = false) {
        Linux.prepare(this)
        val argv = Linux.argv(this, guest)
        val s = TerminalSession(Linux.prootPath(this), filesDir.absolutePath, argv, Linux.env(this), 4000, SessionClient)
        Sessions.list.add(s); Sessions.names[s] = title
        if (isSetup) Sessions.setup = s
        attach(s)
        startForegroundService(Intent(this, KeepAliveService::class.java))
    }

    private fun attach(s: TerminalSession) {
        Sessions.current = s
        panel.visibility = View.GONE
        terminal.attachSession(s)
        terminal.onScreenUpdated()
        terminal.requestFocus()
        title = "Claude Linux · " + (Sessions.names[s] ?: "")
    }

    private fun handleFinished(s: TerminalSession) {
        if (s === Sessions.setup) {
            Sessions.setup = null
            if (Linux.ready(this)) {
                Sessions.list.remove(s)
                Toast.makeText(this, "Setup complete", Toast.LENGTH_SHORT).show()
                newClaude()
            } else {
                Toast.makeText(this, "Setup did not finish. Read the log, then Menu > Re-run setup.", Toast.LENGTH_LONG).show()
            }
            return
        }
        Sessions.list.remove(s)
        if (Sessions.current === s) Sessions.current = null
        val next = Sessions.list.lastOrNull()
        if (next != null) attach(next)
        else { stopService(Intent(this, KeepAliveService::class.java)); showIdle() }
    }

    // ---------- menu ----------
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "New Claude Code session")
        menu.add(0, 2, 1, "New Linux shell")
        menu.add(0, 3, 2, "Switch session…")
        menu.add(0, 4, 3, "Close this session")
        menu.add(0, 5, 4, "API settings")
        menu.add(0, 6, 5, "Re-run setup")
        menu.add(0, 7, 6, "Reset Linux (delete all)")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val ready = Linux.ready(this)
        when (item.itemId) {
            1 -> if (ready) newClaude() else toastNotReady()
            2 -> if (ready) newShell() else toastNotReady()
            3 -> switchDialog()
            4 -> Sessions.current?.finishIfRunning()
            5 -> startActivity(Intent(this, SettingsActivity::class.java))
            6 -> if (Linux.extracted(this)) startBootstrap() else toastNotReady()
            7 -> AlertDialog.Builder(this).setTitle("Delete the Linux system?")
                .setMessage("Everything under /root and every installed package is erased.")
                .setPositiveButton("Delete") { _, _ ->
                    Sessions.list.toList().forEach { it.finishIfRunning() }
                    Sessions.list.clear(); Sessions.current = null
                    thread { Linux.rootfs(this).deleteRecursively(); runOnUiThread { route() } }
                }.setNegativeButton("Cancel", null).show()
        }
        return true
    }

    private fun toastNotReady() = Toast.makeText(this, "Finish setup first", Toast.LENGTH_SHORT).show()

    private fun switchDialog() {
        val names = Sessions.list.map { Sessions.names[it] ?: "Session" }.toTypedArray()
        if (names.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Sessions").setItems(names) { _, i -> attach(Sessions.list[i]) }.show()
    }

    /** Fallback input box: works with any keyboard (autocorrect is fine here), then sends the text to the terminal. */
    private fun typeDialog() {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2; hint = "Type or dictate here…"
        }
        AlertDialog.Builder(this).setTitle("Type to terminal").setView(et)
            .setPositiveButton("Send + Enter") { _, _ -> send(et.text.toString().replace("\n", " ") + "\r") }
            .setNeutralButton("Send") { _, _ -> send(et.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- extra keys ----------
    private fun send(s: String) { Sessions.current?.write(s) }

    private fun keyBtn(label: String, onClick: (Button) -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; minWidth = 0; minimumWidth = 0; textSize = 13f
        setPadding(dp(10), 0, dp(10), 0)
        isFocusable = false; isFocusableInTouchMode = false
        setOnClickListener { onClick(this) }
    }

    private fun refreshMods() {
        ctrlBtn.alpha = if (ctrlOn) 1f else 0.6f; ctrlBtn.setTextColor(if (ctrlOn) Color.YELLOW else Color.WHITE)
        altBtn.alpha = if (altOn) 1f else 0.6f; altBtn.setTextColor(if (altOn) Color.YELLOW else Color.WHITE)
    }

    private fun buildKeyRow(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.parseColor("#202020")) }
        fun add(b: Button) = row.addView(b, LinearLayout.LayoutParams(-2, dp(40)))
        add(keyBtn("Esc") { send("\u001b") })
        add(keyBtn("Tab") { send("\t") })
        ctrlBtn = keyBtn("Ctrl") { ctrlOn = !ctrlOn; refreshMods() }; add(ctrlBtn)
        altBtn = keyBtn("Alt") { altOn = !altOn; refreshMods() }; add(altBtn)
        add(keyBtn("↑") { send("\u001b[A") }); add(keyBtn("↓") { send("\u001b[B") })
        add(keyBtn("←") { send("\u001b[D") }); add(keyBtn("→") { send("\u001b[C") })
        add(keyBtn("^C") { send("\u0003") }); add(keyBtn("^D") { send("\u0004") })
        add(keyBtn("-") { send("-") }); add(keyBtn("/") { send("/") }); add(keyBtn("|") { send("|") }); add(keyBtn("~") { send("~") })
        add(keyBtn("Paste") { SessionClient.onPasteTextFromClipboard(Sessions.current) })
        add(keyBtn("Type…") { typeDialog() })
        val sv = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        refreshMods()
        return sv
    }

    // ---------- TerminalView callbacks ----------
    inner class VC : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                fontSp = (fontSp * (if (scale > 1f) 1.1f else 0.9f)).coerceIn(8f, 32f)
                terminal.setTextSize(px(fontSp))
                return 1.0f
            }
            return scale
        }
        override fun onSingleTapUp(e: MotionEvent?) {
            terminal.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(terminal, InputMethodManager.SHOW_IMPLICIT)
        }
        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = true
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
        override fun onLongPress(event: MotionEvent?) = false
        override fun readControlKey() = ctrlOn
        override fun readAltKey() = altOn
        override fun readShiftKey() = false
        override fun readFnKey() = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
            if (ctrlOn || altOn) { ctrlOn = false; altOn = false; refreshMods() }
            return false
        }
        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) {}
        override fun logWarn(tag: String?, message: String?) {}
        override fun logInfo(tag: String?, message: String?) {}
        override fun logDebug(tag: String?, message: String?) {}
        override fun logVerbose(tag: String?, message: String?) {}
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
        override fun logStackTrace(tag: String?, e: Exception?) {}
    }
}
