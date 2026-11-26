package io.github.santtua.stillreader

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import java.io.File

class MainActivity : Activity() {
    private lateinit var library: BookLibrary
    private val settings by lazy { getSharedPreferences("reader_defaults", MODE_PRIVATE) }
    private var backupBusy = false
    private lateinit var shelf: LibraryView
    private lateinit var root: FrameLayout
    private lateinit var reader: LinearLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var searchButton: Button
    private lateinit var settingsButton: Button
    private lateinit var fullscreenExit: Button
    private lateinit var searchPanel: PdfSearchPanel
    private lateinit var documentStatus: TextView
    private lateinit var pages: PdfPagesView
    private lateinit var navigation: LinearLayout
    private lateinit var zoomControls: LinearLayout
    private lateinit var panControls: LinearLayout
    private lateinit var pageIndicator: Button
    private lateinit var previous: Button
    private lateinit var next: Button
    private lateinit var zoomOut: Button
    private lateinit var zoomIn: Button
    private lateinit var saveCopy: Button

    private var source: PdfDocumentAccess? = null
    private var session: PdfRenderSession? = null
    private var documentOpen: DocumentOpen? = null
    private val documentUri: Uri? get() = documentOpen?.uri
    private var draft: File? = null
    private var book: Book? = null
    private var savedPosition: ReadingPosition? = null
    private var coverScheduled = false
    private var generation = 0
    private var reading = false
    private var fullscreen = false
    private var comfort = ReadingComfort()
    private var pendingReplacement: Long? = null
    private data class DocumentOpen(
        val uri: Uri,
        val retainGrant: Boolean = false,
        val copy: Boolean = false,
        val replaceId: Long? = null,
        val start: ReadingPosition? = null,
        val startComfort: ReadingComfort? = null
    )
    private val handler = Handler(Looper.getMainLooper())
    private val save = Runnable { flushPosition() }
    private val backToLibrary = OnBackInvokedCallback {
        when {
            fullscreen -> setFullscreen(false)
            searchPanel.visibility == View.VISIBLE -> closeSearch()
            else -> showLibrary()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = BookLibrary.get(this)
        buildReader()
        showLibrary()
        pendingReplacement = savedInstanceState?.getLong("replacement")?.takeIf { it > 0 }
        val restored = savedInstanceState?.getParcelable("pdf", Uri::class.java)
        if (restored != null && isContentUri(restored)) {
            val state = savedInstanceState
            val position = if (state.containsKey("readingPage") || state.containsKey("page")) {
                ReadingPosition(
                    state.getInt("readingPage", state.getInt("page")),
                    state.getInt("readingAnchor", state.getInt("page")),
                    state.getFloat("readingOffset"),
                    state.getFloat("zoom", 1f),
                    state.getFloat("readingPan", 0.5f)
                )
            } else null
            val appearance = state.getString("readingTheme")?.let {
                ReadingComfort(
                    ReadingTheme.stored(it), state.getBoolean("readingRecolor"),
                    state.getBoolean("readingFitPage"), state.getBoolean("readingAwake"),
                    state.getInt("readingCrop")
                ).bounded()
            }
            openDocument(DocumentOpen(
                restored, state.getBoolean("retainPdfGrant"), state.getBoolean("copyPdf"),
                state.getLong("openingReplacement").takeIf { it > 0 }, position, appearance
            ))
        } else if (savedInstanceState == null) {
            handleViewIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingReplacement?.let { outState.putLong("replacement", it) }
        documentOpen?.let { request ->
            outState.putParcelable("pdf", request.uri)
            outState.putBoolean("retainPdfGrant", request.retainGrant)
            outState.putBoolean("copyPdf", request.copy)
            request.replaceId?.let { outState.putLong("openingReplacement", it) }
            val opened = pages.pageCount > 0
            val position = if (opened) pages.position() else request.start
            if (position != null) {
                outState.putInt("readingPage", position.page)
                outState.putInt("readingAnchor", position.anchor)
                outState.putFloat("readingOffset", position.offset)
                outState.putFloat("zoom", position.zoom)
                outState.putFloat("readingPan", position.pan)
            }
            val appearance = if (opened) comfort else request.startComfort
            if (appearance != null) {
                outState.putString("readingTheme", appearance.theme.name)
                outState.putBoolean("readingRecolor", appearance.recolor)
                outState.putBoolean("readingFitPage", appearance.fitPage)
                outState.putBoolean("readingAwake", appearance.keepAwake)
                outState.putInt("readingCrop", appearance.crop)
            }
        }
    }

    override fun onStop() {
        flushPosition()
        super.onStop()
    }

    override fun onDestroy() {
        if (reading) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backToLibrary)
        closeDocument()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun pickPdf(request: Int = PICK_PDF, replacement: Long? = null) {
        pendingReplacement = replacement
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = PDF_MIME
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(picker, request)
        } catch (_: ActivityNotFoundException) {
            pendingReplacement = null
            notifyUser(R.string.error_picker)
        }
    }

    @Deprecated("Uses the platform document picker through Activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SAVE_BACKUP || requestCode == OPEN_BACKUP) {
            if (resultCode == RESULT_OK) handleBackupFile(data, requestCode == SAVE_BACKUP)
            return
        }
        if (requestCode !in PICK_PDF..LOCATE_PDF) return
        val replacement = pendingReplacement.takeIf { requestCode == LOCATE_PDF }
        pendingReplacement = null
        if (resultCode == RESULT_OK) openFromIntent(data, requestCode == PICK_COPY, replacement)
    }

    private fun handleViewIntent(incoming: Intent) {
        if (incoming.action == Intent.ACTION_VIEW && incoming.type == PDF_MIME) openFromIntent(incoming)
    }

    private fun openFromIntent(incoming: Intent?, copy: Boolean = false, replacement: Long? = null) {
        val uri = incoming?.data
        if (incoming == null || uri == null || !isContentUri(uri) ||
            incoming.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0
        ) {
            if (documentUri == null) showFailure(R.string.error_document_access)
            else notifyUser(R.string.error_document_access)
            return
        }
        val persistable = incoming.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0
        openDocument(DocumentOpen(uri, retainGrant = persistable, copy = copy, replaceId = replacement))
    }

    private fun isContentUri(uri: Uri): Boolean =
        uri.scheme == "content" && !uri.authority.isNullOrEmpty() && uri.toString().length <= 4096

    private fun openDocument(request: DocumentOpen) {
        closeDocument()
        val ticket = generation
        documentOpen = request
        val (uri, _, copy, replaceId) = request
        showReader()
        pages.visibility = View.GONE
        pages.showDocument(0)
        documentStatus.visibility = View.VISIBLE
        documentStatus.setText(if (copy) R.string.copying_pdf else R.string.opening_pdf)
        library.find(uri) { known ->
            if (ticket != generation) return@find
            val savedCopy = known?.takeUnless { it.id == replaceId }?.let(library::copyFor)
            val makeCopy = copy && savedCopy == null
            val access = PdfDocumentAccess(this, uri, savedCopy, makeCopy) { result ->
                source = null
                when (result) {
                    is PdfDocumentAccess.Result.Failure -> {
                        if (!copy) known?.let { library.markMissing(it.id) }
                        showFailure(result.message)
                    }
                    is PdfDocumentAccess.Result.Open -> bindRenderer(ticket, request, known, result)
                }
            }
            source = access
            access.begin()
        }
    }

    private fun bindRenderer(
        ticket: Int,
        request: DocumentOpen,
        known: Book?,
        result: PdfDocumentAccess.Result.Open
    ) {
        val (uri, retainGrant, copy, replaceId, start, startComfort) = request
        draft = result.draft
        val renderer = PdfRenderSession(
            this,
            onOpened = { count ->
                val imported = draft
                draft = null
                library.remember(uri, result.displayName, count, retainGrant, imported, replaceId, start, defaultComfort()) { saved ->
                    if (ticket != generation) return@remember
                    if (saved == null && (copy || replaceId != null)) {
                        notifyUser(R.string.error_keep_pdf)
                    } else if (replaceId != null && saved?.id != replaceId) {
                        notifyUser(R.string.book_already_in_library)
                    }
                    documentOpen = DocumentOpen(uri, replaceId = replaceId.takeIf { saved == null })
                    book = saved
                    savedPosition = saved?.position
                    coverScheduled = saved?.hasCover ?: false
                    useComfort(startComfort ?: saved?.comfort ?: known?.comfort ?: defaultComfort())
                    if (saved != null && startComfort != null && startComfort != saved.comfort) {
                        library.saveComfort(saved.id, comfort)
                    }
                    documentStatus.visibility = View.GONE
                    pages.visibility = View.VISIBLE
                    pages.showDocument(count, (start ?: saved?.position ?: known?.position ?: ReadingPosition()).bounded(count))
                }
            },
            onPage = { index, frame ->
                val current = book
                if (index == 0 && frame != null && current != null && !coverScheduled) {
                    coverScheduled = true
                    library.saveCover(current.id, frame.bitmap)
                }
                pages.onFrame(index, frame)
                searchPanel.rendererReady()
            },
            onSearchPage = { index, result ->
                searchPanel.onPageResult(index, result)
                pages.rendererReady()
                searchPanel.rendererReady()
            },
            onFailure = { message ->
                if (!copy && (message == R.string.error_invalid_pdf || message == R.string.error_encrypted)) {
                    known?.let { library.markMissing(it.id) }
                }
                showFailure(message)
            }
        )
        session = renderer
        renderer.open(result.descriptor)
    }

    private fun flushPosition() {
        handler.removeCallbacks(save)
        val current = book ?: return
        if (pages.pageCount == 0) return
        val position = pages.position()
        if (position != savedPosition) {
            savedPosition = position
            library.savePosition(current.id, position)
        }
    }

    private fun closeDocument() {
        flushPosition()
        generation++
        source?.close()
        source = null
        session?.close()
        session = null
        draft?.delete()
        draft = null
        book = null
        savedPosition = null
        coverScheduled = false
        documentOpen = null
        searchPanel.reset(documentClosed = true)
        setFullscreen(false)
        comfort = ReadingComfort()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun refreshLibrary() {
        library.list { if (!isDestroyed) shelf.showBooks(it) }
    }

    private fun showLibrary() {
        closeDocument()
        if (reading) {
            reading = false
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backToLibrary)
            if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        pages.visibility = View.GONE
        pages.showDocument(0)
        reader.visibility = View.GONE
        shelf.visibility = View.VISIBLE
        val system = ReadingTheme.SYSTEM.resolve(resources.configuration)
        root.setBackgroundColor(ReaderPalette.from(system).background)
        setSystemBarIcons(system)
        shelf.requestFocus()
        refreshLibrary()
    }

    private fun showReader() {
        if (!reading) {
            reading = true
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backToLibrary)
            val orientation = ORIENTATIONS[settings.getInt("orientation", 0).coerceIn(0, ORIENTATIONS.lastIndex)]
            if (requestedOrientation != orientation) requestedOrientation = orientation
        }
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(shelf.windowToken, 0)
        shelf.visibility = View.GONE
        reader.visibility = View.VISIBLE
        useComfort(comfort)
    }

    private fun showFailure(message: Int) {
        closeDocument()
        showReader()
        pages.visibility = View.GONE
        pages.showDocument(0)
        documentStatus.visibility = View.VISIBLE
        documentStatus.setText(message)
    }

    private fun saveCopy() {
        val request = documentOpen ?: return
        openDocument(request.copy(copy = true, start = pages.position(), startComfort = comfort))
    }

    private fun notifyUser(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun bookOptions(book: Book) {
        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setItems(arrayOf(getString(R.string.locate_pdf), getString(R.string.remove_book))) { _, index ->
                if (index == 0) pickPdf(LOCATE_PDF, book.id)
                else AlertDialog.Builder(this)
                    .setTitle(R.string.remove_book)
                    .setMessage(R.string.remove_book_confirmation)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.remove_action) { _, _ ->
                        library.remove(book) { removed ->
                            if (isDestroyed) return@remove
                            if (!removed) notifyUser(R.string.error_remove_book)
                            refreshLibrary()
                        }
                    }
                    .show()
            }
            .show()
    }

    private fun showLibrarySettings() {
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), 0)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.library_settings)
            .setView(android.widget.ScrollView(this).apply { addView(actions) })
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        fun addAction(label: Int, action: () -> Unit) {
            actions.addView(button(label) {
                dialog.dismiss()
                action()
            }, LinearLayout.LayoutParams(-1, -2))
        }
        addAction(R.string.default_reading_settings) {
            showReadingSettings(this, defaultComfort(), ::saveDefaultComfort)
        }
        addAction(R.string.reading_orientation, ::showOrientationOptions)
        addAction(R.string.export_reading_backup) { pickBackup(save = true) }
        addAction(R.string.restore_reading_backup) { pickBackup(save = false) }
        addAction(R.string.clear_covers) {
            library.clearCovers { completeDataAction(it, R.string.covers_cleared) }
        }
        addAction(R.string.delete_reading_data, ::confirmDeleteReadingData)
        addAction(R.string.about_stillreader, ::showAbout)
        dialog.show()
    }

    private fun defaultComfort() = ReadingComfort(
        ReadingTheme.stored(settings.getString("theme", null)),
        settings.getBoolean("recolor", false), settings.getBoolean("fit_page", false),
        settings.getBoolean("awake", false), settings.getInt("crop", 0)
    ).bounded()

    private fun saveDefaultComfort(value: ReadingComfort) {
        settings.edit().putString("theme", value.theme.name)
            .putBoolean("recolor", value.recolor).putBoolean("fit_page", value.fitPage)
            .putBoolean("awake", value.keepAwake).putInt("crop", value.crop).apply()
    }

    private fun showOrientationOptions() {
        val options = arrayOf(getString(R.string.orientation_device), getString(R.string.orientation_portrait),
            getString(R.string.orientation_landscape))
        AlertDialog.Builder(this)
            .setTitle(R.string.reading_orientation)
            .setSingleChoiceItems(options, settings.getInt("orientation", 0)) { dialog, which ->
                settings.edit().putInt("orientation", which).apply()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAbout() {
        @Suppress("DEPRECATION")
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        AlertDialog.Builder(this)
            .setTitle(R.string.about_stillreader)
            .setMessage(getString(R.string.about_stillreader_description, version))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun pickBackup(save: Boolean) {
        if (backupBusy) return
        val picker = Intent(if (save) Intent.ACTION_CREATE_DOCUMENT else Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = LibraryBackup.MIME
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            if (save) putExtra(Intent.EXTRA_TITLE, "stillreader-backup.json")
            addFlags(if (save) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(picker, if (save) SAVE_BACKUP else OPEN_BACKUP)
        } catch (_: ActivityNotFoundException) {
            notifyUser(R.string.error_picker)
        }
    }

    private fun handleBackupFile(result: Intent?, save: Boolean) {
        val uri = result?.data
        val grant = if (save) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (uri == null || !isContentUri(uri) || result.flags and grant == 0 ||
            !DocumentsContract.isDocumentUri(this, uri)
        ) {
            notifyUser(R.string.error_backup_file)
            return
        }
        if (backupBusy) return
        backupBusy = true
        val file = BackupFile(this, uri)
        if (save) {
            library.backup(uri) { snapshot ->
                if (!isDestroyed) {
                    if (snapshot == null) finishBackup(false, R.string.backup_exported)
                    else file.write(snapshot) { finishBackup(it != null, R.string.backup_exported) }
                }
            }
        } else {
            file.read { books ->
                backupBusy = false
                if (!isDestroyed) {
                    if (books == null) notifyUser(R.string.error_backup_file) else confirmRestore(books)
                }
            }
        }
    }

    private fun confirmRestore(books: List<BackupBook>) {
        AlertDialog.Builder(this)
            .setTitle(R.string.restore_reading_backup)
            .setMessage(R.string.restore_backup_confirmation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.restore_action) { _, _ ->
                backupBusy = true
                library.restore(books) { restored -> finishBackup(restored, R.string.backup_restored) }
            }
            .show()
    }

    private fun finishBackup(success: Boolean, message: Int) {
        backupBusy = false
        if (isDestroyed) return
        notifyUser(if (success) message else R.string.error_backup_file)
        if (success && message == R.string.backup_restored) refreshLibrary()
    }

    private fun confirmDeleteReadingData() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_reading_data)
            .setMessage(R.string.delete_reading_data_confirmation)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_reading_data) { _, _ ->
                library.clearReadingData { completeDataAction(it, R.string.reading_data_deleted) }
            }
            .show()
    }

    private fun completeDataAction(success: Boolean, message: Int) {
        if (isDestroyed) return
        notifyUser(if (success) message else R.string.error_library)
        refreshLibrary()
    }

    private fun buildReader() {
        root = FrameLayout(this)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(safe.left + dp(8), safe.top, safe.right + dp(8), safe.bottom)
            insets
        }
        shelf = LibraryView(this, library, { pickPdf() }, { pickPdf(PICK_COPY) }, ::showLibrarySettings,
            { if (it.needsFile) bookOptions(it) else openDocument(DocumentOpen(it.uri)) }, ::bookOptions)
        root.addView(shelf, FrameLayout.LayoutParams(-1, -1))
        reader = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        saveCopy = button(R.string.save_pdf_copy, ::saveCopy)
        searchButton = button(R.string.open_document_search, ::toggleSearch)
        settingsButton = button(R.string.reading_settings) {
            showReadingSettings(this, comfort, ::changeComfort) { setFullscreen(true) }
        }
        toolbar = row().apply {
            addButtons(this, button(R.string.library_title, ::showLibrary), searchButton, settingsButton, saveCopy)
        }
        reader.addView(toolbar)
        fullscreenExit = button(R.string.exit_fullscreen) { setFullscreen(false) }
        fullscreenExit.visibility = View.GONE
        reader.addView(fullscreenExit)
        navigation = row()
        previous = button(R.string.previous_page) { pages.jumpTo(pages.currentPage - 1) }
        pageIndicator = button(R.string.jump_to_page, ::showPagePicker)
        next = button(R.string.next_page) { pages.jumpTo(pages.currentPage + 1) }
        addButtons(navigation, previous, pageIndicator, next)
        reader.addView(navigation)
        zoomControls = row()
        zoomOut = button(R.string.zoom_out) { pages.changeZoom(pages.zoom / 1.25f) }
        zoomIn = button(R.string.zoom_in) { pages.changeZoom(pages.zoom * 1.25f) }
        addButtons(zoomControls, zoomOut, button(R.string.fit_width) {
            if (comfort.fitPage) changeComfort(comfort.copy(fitPage = false)) else pages.changeZoom(1f)
        }, zoomIn)
        reader.addView(zoomControls)
        panControls = row()
        addButtons(panControls, button(R.string.pan_left) { pages.shiftPan(-0.25f) },
            button(R.string.pan_right) { pages.shiftPan(0.25f) })
        reader.addView(panControls)

        searchPanel = PdfSearchPanel(
            this, { pages.pageCount },
            { index, query, checkText -> session?.search(index, query, checkText) == true },
            { pages.showHit(it) },
            ::closeSearch
        )
        reader.addView(searchPanel)
        pages = PdfPagesView(this, ::updateControls) { index, width -> session?.render(index, width) == true }
        pages.visibility = View.GONE
        documentStatus = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(12))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val readingArea = FrameLayout(this).apply {
            addView(pages, FrameLayout.LayoutParams(-1, -1))
            addView(documentStatus, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        }
        reader.addView(readingArea, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(reader, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        root.requestApplyInsets()
        updateControls()
    }

    private fun updateControls() {
        if (!::pages.isInitialized) return
        val hasPages = pages.pageCount > 0
        toolbar.visibility = if (fullscreen) View.GONE else View.VISIBLE
        fullscreenExit.visibility = if (fullscreen) View.VISIBLE else View.GONE
        searchButton.isEnabled = hasPages
        settingsButton.isEnabled = hasPages
        navigation.visibility = if (hasPages && !fullscreen) View.VISIBLE else View.GONE
        zoomControls.visibility = navigation.visibility
        panControls.visibility = if (hasPages && !fullscreen && pages.zoom > 1f) View.VISIBLE else View.GONE
        saveCopy.visibility = if (hasPages && book?.copyName == null) View.VISIBLE else View.GONE
        if (!hasPages) return
        val page = pages.currentPage
        val position = getString(R.string.page_position, page + 1, pages.pageCount)
        if (pageIndicator.text != position) {
            pageIndicator.text = position
            pageIndicator.contentDescription = getString(R.string.jump_description, position)
        }
        previous.isEnabled = page > 0
        next.isEnabled = page + 1 < pages.pageCount
        zoomOut.isEnabled = pages.zoom > 1f
        zoomIn.isEnabled = pages.zoom < 4f
        if (book != null && !handler.hasCallbacks(save)) handler.postDelayed(save, 1000)
    }

    private fun toggleSearch() {
        if (searchPanel.visibility == View.VISIBLE) closeSearch() else searchPanel.open()
    }

    private fun closeSearch() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(searchPanel.windowToken, 0)
        searchPanel.reset(documentClosed = false)
    }

    private fun changeComfort(value: ReadingComfort) {
        val changedFit = value.fitPage != comfort.fitPage
        useComfort(value)
        if (changedFit) pages.changeZoom(1f)
        book?.let { library.saveComfort(it.id, comfort) }
    }

    private fun useComfort(value: ReadingComfort) {
        comfort = value.bounded()
        val theme = comfort.theme.resolve(resources.configuration)
        val palette = ReaderPalette.from(theme)
        root.setBackgroundColor(palette.background)
        reader.setBackgroundColor(palette.background)
        tintControls(reader, palette)
        setSystemBarIcons(theme)
        pages.setReadingStyle(comfort.fitPage, comfort.crop,
            if (comfort.recolor) pageColorEffect(theme) else null)
        if (comfort.keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun tintControls(view: View, palette: ReaderPalette) {
        when (view) {
            is Button -> {
                view.setTextColor(ColorStateList(
                    arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(palette.muted, palette.text)
                ))
                view.backgroundTintList = ColorStateList.valueOf(palette.control)
            }
            is EditText -> {
                view.setTextColor(palette.text)
                view.setHintTextColor(palette.muted)
                view.backgroundTintList = ColorStateList.valueOf(palette.muted)
            }
            is TextView -> view.setTextColor(palette.text)
            is ViewGroup -> for (index in 0 until view.childCount) tintControls(view.getChildAt(index), palette)
        }
    }

    private fun setSystemBarIcons(theme: ReadingTheme) {
        val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        val darkBackground = theme == ReadingTheme.DARK || theme == ReadingTheme.HIGH_CONTRAST
        window.insetsController?.setSystemBarsAppearance(if (darkBackground) 0 else flags, flags)
    }

    private fun setFullscreen(enabled: Boolean) {
        if (fullscreen == enabled) return
        fullscreen = enabled
        val controller = window.insetsController
        if (enabled) {
            closeSearch()
            controller?.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsets.Type.systemBars())
        } else {
            controller?.show(WindowInsets.Type.systemBars())
        }
        updateControls()
    }

    private fun showPagePicker() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.page_range, pages.pageCount)
            setSingleLine()
            setText(String.format(resources.configuration.locales[0], "%d", pages.currentPage + 1))
            selectAll()
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.jump_to_page)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.go_to_page, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val page = input.text.toString().toIntOrNull()
                if (page == null || page !in 1..pages.pageCount) {
                    input.error = getString(R.string.page_range, pages.pageCount)
                } else {
                    pages.jumpTo(page - 1)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun button(label: Int, action: () -> Unit) = Button(this).apply {
        setText(label)
        isAllCaps = false
        minHeight = dp(48)
        minimumWidth = 0
        minWidth = 0
        setOnClickListener { action() }
    }

    private fun addButtons(row: LinearLayout, vararg buttons: Button) {
        for (button in buttons) row.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val PDF_MIME = "application/pdf"
        const val PICK_PDF = 42
        const val PICK_COPY = 43
        const val LOCATE_PDF = 44
        const val SAVE_BACKUP = 45
        const val OPEN_BACKUP = 46
        val ORIENTATIONS = intArrayOf(
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        )
    }
}
