package io.github.santtua.stillreader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

// Callbacks share the reader's isolated session, with visible renders given priority.
@SuppressLint("ViewConstructor")
internal class PdfSearchPanel(
    context: Context,
    private val pageCount: () -> Int,
    private val requestPage: (Int, String, Boolean) -> Boolean,
    private val showHit: (DocumentHit?) -> Unit,
    closePanel: () -> Unit
) : LinearLayout(context) {
    private class Search(val query: String, val pages: Int) {
        val matches = mutableListOf<DocumentHit>()
        var page = 0
        var selected = -1
        var foundText = false
        var skippedPage = false
        var limited = false
        var running = true
        var cancelled = false
    }

    private var search: Search? = null
    private var pending: Search? = null
    private var lastProgress = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val pump = Runnable { nextPage() }
    private val field = EditText(context).apply {
        setHint(R.string.document_search_hint)
        setSingleLine()
        filters = arrayOf(InputFilter.LengthFilter(PdfRenderProtocol.MAX_QUERY_LENGTH))
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        isSaveEnabled = false
        setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { start(); true } else false
        }
    }
    private val status = TextView(context).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        setPadding(dp(8), dp(2), dp(8), dp(2))
    }
    private val previous = control(R.string.previous_match) { navigate(-1) }
    private val next = control(R.string.next_match) { navigate(1) }
    private val stop = control(R.string.cancel_search, ::cancel)

    init {
        orientation = VERTICAL
        addView(field)
        addView(line(control(R.string.find_matches, ::start), stop, control(R.string.close_search, closePanel)))
        addView(status)
        addView(line(previous, next))
        visibility = GONE
        updateStatus()
    }

    fun open() {
        visibility = VISIBLE
        field.requestFocus()
        updateStatus()
    }

    fun reset(documentClosed: Boolean) {
        search = null
        if (documentClosed) pending = null
        handler.removeCallbacks(pump)
        showHit(null)
        field.text.clear()
        visibility = GONE
        updateStatus()
    }

    fun rendererReady() {
        handler.removeCallbacks(pump)
        if (search?.running == true) handler.post(pump)
    }

    fun onPageResult(index: Int, result: PageSearchResult?) {
        val running = pending
        pending = null
        if (running == null || running !== search || !running.running || running.page != index) return
        running.page++
        if (result == null) {
            running.skippedPage = true
        } else {
            val free = MAX_MATCHES - running.matches.size
            running.matches.addAll(result.hits.take(free))
            running.foundText = running.foundText || result.hasText
            running.limited = result.truncated || result.hits.size > free ||
                (running.matches.size == MAX_MATCHES && running.page < running.pages)
            if (running.selected == -1 && running.matches.isNotEmpty()) {
                running.selected = 0
                showHit(running.matches[0])
            }
        }
        running.running = !running.limited && running.page < running.pages
        val now = SystemClock.uptimeMillis()
        if (!running.running || now - lastProgress >= 150) {
            updateStatus()
            lastProgress = now
        }
    }

    private fun start() {
        val query = field.text.toString().trim()
        val count = pageCount()
        if (query.isEmpty() || count == 0) {
            field.error = context.getString(R.string.document_search_hint)
            return
        }
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(field.windowToken, 0)
        field.clearFocus()
        search = Search(query, count)
        lastProgress = 0
        showHit(null)
        updateStatus()
        rendererReady()
    }

    private fun nextPage() {
        val active = search ?: return
        if (active.running && pending == null && requestPage(active.page, active.query, !active.foundText)) {
            pending = active
        }
    }

    private fun cancel() {
        val active = search ?: return
        active.running = false
        active.cancelled = true
        handler.removeCallbacks(pump)
        updateStatus()
    }

    private fun navigate(by: Int) {
        val active = search ?: return
        val count = active.matches.size
        if (count < 2) return
        active.selected = Math.floorMod(active.selected + by, count)
        showHit(active.matches[active.selected])
        updateStatus()
    }

    private fun updateStatus() {
        val active = search
        val count = active?.matches?.size ?: 0
        previous.isEnabled = count > 1
        next.isEnabled = count > 1
        stop.isEnabled = active?.running == true
        status.text = when {
            active == null -> context.getString(R.string.document_search_hint)
            active.cancelled -> resources.getQuantityString(R.plurals.search_cancelled, count, count)
            active.running -> resources.getQuantityString(R.plurals.search_running, count, active.page + 1, active.pages, count)
            active.limited -> context.getString(R.string.search_limit, active.selected + 1, count)
            active.skippedPage -> resources.getQuantityString(R.plurals.search_incomplete, count, count)
            count > 0 -> context.getString(R.string.search_matches, active.selected + 1, count)
            !active.foundText -> context.getString(R.string.search_no_text)
            else -> context.getString(R.string.search_no_matches)
        }
    }

    private fun line(vararg buttons: Button) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        for (button in buttons) addView(button, LayoutParams(0, -2, 1f))
    }

    private fun control(label: Int, action: () -> Unit) = Button(context).apply {
        setText(label)
        isAllCaps = false
        minimumWidth = 0
        minWidth = 0
        minHeight = dp(48)
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val MAX_MATCHES = 1000
    }
}
