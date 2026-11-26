package io.github.santtua.stillreader

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.Collator
import java.util.Date

// The Activity supplies the actions, so this view is constructed in code.
@SuppressLint("ViewConstructor")
internal class LibraryView(
    context: Context,
    private val store: BookLibrary,
    importLink: () -> Unit,
    importCopy: () -> Unit,
    settings: () -> Unit,
    private val open: (Book) -> Unit,
    private val options: (Book) -> Unit
) : LinearLayout(context) {
    private val preferences: SharedPreferences = context.getSharedPreferences("library_view", Context.MODE_PRIVATE)
    private var sortByTitle = preferences.getBoolean("title_sort", false)
    private var showGrid = preferences.getBoolean("grid", false)
    private var books: List<Book> = emptyList()
    private var visibleBooks: List<Book> = emptyList()
    private val covers = LruCache<Long, Bitmap>(48)
    private val collator = Collator.getInstance(resources.configuration.locales[0])
    private val dateFormat = DateFormat.getMediumDateFormat(context)
    private val list = ListView(context).apply { itemsCanFocus = true }
    private val grid = GridView(context).apply {
        numColumns = GridView.AUTO_FIT
        columnWidth = dp(164)
        horizontalSpacing = dp(8)
        verticalSpacing = dp(8)
        stretchMode = GridView.STRETCH_COLUMN_WIDTH
    }
    private val rows = ShelfAdapter()
    private val status = TextView(context).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(16), dp(16), dp(16))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val resume = action(R.string.resume_reading) { books.firstOrNull { !it.needsFile }?.let(open) }
    private val search = EditText(context).apply {
        setHint(R.string.search_library)
        setSingleLine()
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        isSaveEnabled = false
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateRows(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
    private val sort = action(R.string.sort_recent) {
        sortByTitle = !sortByTitle
        preferences.edit().putBoolean("title_sort", sortByTitle).apply()
        updateRows(search.text.toString())
    }
    private val layout = action(R.string.show_grid) {
        showGrid = !showGrid
        preferences.edit().putBoolean("grid", showGrid).apply()
        updateRows(search.text.toString())
    }

    init {
        orientation = VERTICAL
        isFocusableInTouchMode = true
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                setText(R.string.library_title)
                textSize = 26f
                isAccessibilityHeading = true
                setPadding(dp(8), dp(8), dp(8), dp(2))
            }, LayoutParams(0, -2, 1f))
            addView(action(R.string.library_settings, settings))
        })
        addView(buttonRow(action(R.string.open_pdf, importLink), action(R.string.import_copy, importCopy)))
        addView(resume)
        addView(search)
        addView(buttonRow(sort, layout))
        list.adapter = rows
        grid.adapter = rows
        addView(FrameLayout(context).apply {
            addView(list, FrameLayout.LayoutParams(-1, -1))
            addView(grid, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }, LayoutParams(-1, 0, 1f))
        updateRows("")
    }

    fun showBooks(value: List<Book>?) {
        covers.evictAll()
        books = value.orEmpty()
        updateRows(search.text.toString())
        if (value == null) status.setText(R.string.error_library)
    }

    private fun updateRows(query: String) {
        val term = query.trim()
        val matching = books.filter { it.title.contains(term, ignoreCase = true) }
        visibleBooks = if (sortByTitle) matching.sortedWith { a, b -> collator.compare(a.title, b.title) } else matching
        val recent = books.firstOrNull { !it.needsFile }
        resume.visibility = if (recent == null) GONE else VISIBLE
        if (recent != null) resume.text = context.getString(R.string.resume_book, recent.title)
        sort.setText(if (sortByTitle) R.string.sort_title else R.string.sort_recent)
        layout.setText(if (showGrid) R.string.show_list else R.string.show_grid)
        status.setText(if (books.isEmpty()) R.string.library_empty else R.string.no_matching_books)
        status.visibility = if (visibleBooks.isEmpty()) VISIBLE else GONE
        list.visibility = if (!showGrid && visibleBooks.isNotEmpty()) VISIBLE else GONE
        grid.visibility = if (showGrid && visibleBooks.isNotEmpty()) VISIBLE else GONE
        rows.notifyDataSetChanged()
    }

    private inner class ShelfAdapter : BaseAdapter() {
        override fun getCount(): Int = visibleBooks.size
        override fun getItem(position: Int): Book = visibleBooks[position]
        override fun getItemId(position: Int): Long = getItem(position).id
        override fun hasStableIds(): Boolean = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tile = parent is GridView
            val row = (convertView as? ShelfRow)?.takeIf { it.tile == tile } ?: ShelfRow(tile)
            row.bind(getItem(position))
            return row
        }
    }

    private inner class ShelfRow(val tile: Boolean) : LinearLayout(context) {
        private val preview = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        private val title = TextView(context).apply {
            textSize = 17f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        private val details = TextView(context)
        private val date = TextView(context)
        private var current: Book? = null
        private val menu = action(R.string.book_options) { current?.let(options) }

        init {
            orientation = if (tile) VERTICAL else HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(88)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            val value = android.util.TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
                setBackgroundResource(value.resourceId)
            }
            addView(preview, if (tile) LayoutParams(-1, dp(130)) else LayoutParams(dp(56), dp(78)))
            val labels = LinearLayout(context).apply {
                orientation = VERTICAL
                setPaddingRelative(dp(8), 0, dp(8), 0)
                addView(title)
                addView(details)
                addView(date)
            }
            addView(labels, if (tile) LayoutParams(-1, -2) else LayoutParams(0, -2, 1f))
            addView(menu, LayoutParams(if (tile) -1 else -2, -2))
            setOnClickListener { current?.let(open) }
            isFocusable = true
            layoutParams = AbsListView.LayoutParams(-1, -2)
        }

        fun bind(book: Book) {
            current = book
            title.text = book.title
            details.text = if (book.needsFile) context.getString(R.string.book_needs_file) else {
                context.getString(R.string.book_progress, book.position.page + 1, book.pageCount)
            }
            date.text = context.getString(R.string.book_last_opened, dateFormat.format(Date(book.lastOpened)))
            menu.contentDescription = context.getString(R.string.book_options_for, book.title)
            preview.tag = book.id
            val cached = covers.get(book.id)
            preview.setImageBitmap(cached)
            if (book.hasCover && cached == null) {
                store.loadCover(book.id) { image ->
                    if (image != null && preview.tag == book.id) {
                        covers.put(book.id, image)
                        preview.setImageBitmap(image)
                    }
                }
            }
        }
    }

    private fun buttonRow(vararg buttons: Button) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        for (button in buttons) addView(button, LayoutParams(0, -2, 1f))
    }

    private fun action(label: Int, callback: () -> Unit) = Button(context).apply {
        setText(label)
        isAllCaps = false
        minimumWidth = 0
        minWidth = 0
        minHeight = dp(48)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setOnClickListener { callback() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
