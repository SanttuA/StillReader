package io.github.santtua.stillreader

import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver

    @Test
    fun viewIntentRendersPagesAndProvidesNavigationAndZoomControls() = withDocument(true) { uri ->
        val original = read(uri)
        withActivity(viewIntent(uri)) { activity ->
            val reader = awaitReader(activity)
            expect("The first page was not rendered") { sampleFirstRow(reader) == Color.BLACK }
            click(activity, R.string.next_page)
            expect("The second page was not rendered") {
                reader.currentPage == 1 && reader.firstPage == 1 && sampleFirstRow(reader) == Color.BLUE
            }
            click(activity, R.string.zoom_in)
            expect("The zoom button did not enlarge the page") { reader.zoom > 1f }
            click(activity, R.string.fit_width)
            expect("Fit width did not reset zoom") { reader.zoom == 1f }
            click(activity, R.string.previous_page)
            expect("Previous did not return to the first page") { reader.currentPage == 0 }
            tapPage(reader)
            SystemClock.sleep(70)
            tapPage(reader)
            expect("Double tap did not zoom") { reader.zoom > 1f }
            click(activity, R.string.fit_width)
            pinchPage(reader)
            expect("Pinch did not zoom") { reader.zoom > 1f }
        }
        assertArrayEquals(original, read(uri))
    }

    @Test
    fun pickerResultOpensAndDamagedReplacementReportsAnError() = withDocument(true) { valid ->
        withDocument(false) { invalid ->
            val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType(PDF_MIME)
            }
            val monitor = instrumentation.addMonitor(
                filter,
                Instrumentation.ActivityResult(
                    Activity.RESULT_OK,
                    Intent().setData(valid).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ),
                true
            )
            try {
                withActivity(Intent(Intent.ACTION_MAIN)) { activity ->
                    click(activity, R.string.open_pdf)
                    assertEquals(1, monitor.hits)
                    val reader = awaitReader(activity)
                    expect("The picker result did not reach the renderer") { sampleFirstRow(reader) == Color.BLACK }
                    instrumentation.runOnMainSync { activity.startActivity(viewIntent(invalid)) }
                    expect("The reader did not report the damaged document") {
                        visibleText(activity, R.string.error_invalid_pdf)
                    }
                    instrumentation.runOnMainSync { activity.startActivity(viewIntent(valid)) }
                    awaitReader(activity)
                }
            } finally {
                instrumentation.removeMonitor(monitor)
            }
        }
    }

    @Test
    fun viewIntentRejectsNonContentUris() {
        withActivity(viewIntent(Uri.parse("https://example.invalid/test.pdf"))) { activity ->
            expect("A non-content URI was accepted") { visibleText(activity, R.string.error_document_access) }
        }
    }

    @Test
    fun copiedBookResumesWithSavedPosition() {
        val stage = InstrumentationRegistry.getArguments().getString("library_restart")
        val title = if (stage == null) "stillreader-library-${SystemClock.uptimeMillis()}.pdf" else "stillreader-restart.pdf"
        if (stage == "resume") {
            assertResumedCopy(title)
            removeTestBook(title)
            return
        }

        withDocument(true, name = title) { uri ->
            val original = read(uri)
            val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                addDataType(PDF_MIME)
            }
            val monitor = instrumentation.addMonitor(
                filter,
                Instrumentation.ActivityResult(
                    Activity.RESULT_OK,
                    Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ),
                true
            )
            try {
                withActivity(Intent(Intent.ACTION_MAIN)) { activity ->
                    click(activity, R.string.import_copy)
                    val reader = awaitReader(activity)
                    expect("The first page was not rendered") { sampleFirstRow(reader) == Color.BLACK }
                    click(activity, R.string.next_page)
                    expect("Page two did not render") { reader.firstPage == 1 && sampleFirstRow(reader) == Color.BLUE }
                    click(activity, R.string.zoom_in)
                    expect("The reader did not zoom") { reader.zoom > 1f }
                    instrumentation.runOnMainSync {
                        reader.setSelectionFromTop(1, -(reader.getChildAt(0).height / 4))
                    }
                    expect("The reader did not scroll within the page") { reader.position().offset > 0.18f }
                    click(activity, R.string.library_title)
                    expect("The library did not show reading progress") {
                        views(activity.window.decorView).filterIsInstance<TextView>().any {
                            it.isShown && it.text.toString() == context.getString(R.string.book_progress, 2, 3)
                        }
                    }
                    click(activity, R.string.import_copy)
                    val restored = awaitReader(activity)
                    expect("Importing the same document did not resume it") {
                        restored.currentPage == 1 && restored.zoom > 1f && restored.position().offset > 0.18f
                    }
                    click(activity, R.string.library_title)
                }
                val rows = libraryBooks().filter { it.uri == uri }
                assertEquals(1, rows.size)
                assertTrue(rows.single().hasCover)
                assertTrue(rows.single().copyName != null)
                assertTrue(rows.single().position.offset > 0.18f)
                assertArrayEquals(original, read(uri))
                if (stage == null) {
                    assertResumedCopy(title)
                    removeTestBook(title)
                    assertArrayEquals(original, read(uri))
                }
            } finally {
                instrumentation.removeMonitor(monitor)
            }
        }
    }

    @Test
    fun recreatingWhileImportingStillSavesAPrivateCopy() = withDocument(true) { uri ->
        val original = read(uri)
        val filter = IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType(PDF_MIME)
        }
        val monitor = instrumentation.addMonitor(
            filter,
            Instrumentation.ActivityResult(
                Activity.RESULT_OK,
                Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ),
            true
        )
        try {
            withActivity(Intent(Intent.ACTION_MAIN)) { activity ->
                whileRecreatingOnOpen(activity, R.string.copying_pdf, { click(activity, R.string.import_copy) }) { recreated ->
                    val reader = awaitReader(recreated)
                    expect("The recreated Activity did not render the imported copy") { sampleFirstRow(reader) == Color.BLACK }
                    click(recreated, R.string.library_title)
                }
            }
            assertEquals(1, monitor.hits)
            val restored = libraryBooks().single { it.uri == uri }
            val copy = checkNotNull(BookLibrary.get(context).copyFor(restored))
            assertArrayEquals(original, copy.readBytes())
            removeTestBook(restored.title)
            assertArrayEquals(original, read(uri))
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun failedPrivateCopyDeletionLeavesTheBookAvailableForRetry() = withDocument(true) { uri ->
        val original = read(uri)
        withActivity(viewIntent(uri)) { activity ->
            awaitReader(activity)
            click(activity, R.string.save_pdf_copy)
            awaitReader(activity)
            click(activity, R.string.library_title)
        }
        val store = BookLibrary.get(context)
        val book = libraryBooks().single { it.uri == uri }
        val copy = checkNotNull(store.copyFor(book))
        val held = copy.resolveSibling("${copy.name}.held")
        assertTrue(copy.renameTo(held))
        assertTrue(copy.mkdir())
        val obstruction = copy.resolve("fixture")
        try {
            assertTrue(obstruction.createNewFile())
            val result = ArrayBlockingQueue<Boolean>(1)
            store.remove(book) { result.offer(it) }
            assertEquals(false, result.poll(5, TimeUnit.SECONDS))
            assertEquals(book.copyName, libraryBooks().single { it.id == book.id }.copyName)
            assertTrue(obstruction.isFile)
        } finally {
            obstruction.delete()
            assertTrue(copy.delete())
            assertTrue(held.renameTo(copy))
        }
        removeTestBook(book.title)
        assertArrayEquals(original, read(uri))
    }

    @Test
    fun systemPickerRetainsReadAccessAndRelinksAMissingBook() {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("verify_system_picker") == "true"
        )
        val title = "stillreader-link-${SystemClock.uptimeMillis()}.pdf"
        var selected = 0L
        withDocument(true, name = title) { uri ->
            withActivity(Intent(Intent.ACTION_MAIN)) { activity ->
                click(activity, R.string.open_pdf)
                whileRecreatingOnOpen(activity, R.string.opening_pdf, { selectSystemFile(title) }) { recreated ->
                    val reader = awaitReader(recreated)
                    expect("A PDF from the system picker did not render") { sampleFirstRow(reader) == Color.BLACK }
                    click(recreated, R.string.next_page)
                    click(recreated, R.string.zoom_in)
                    expect("The original book did not reach the saved position") { reader.currentPage == 1 && reader.zoom > 1f }
                    click(recreated, R.string.library_title)
                }
            }
            val row = libraryBooks().single { it.title == title }
            selected = row.id
            assertTrue(row.copyName == null)
            assertTrue(resolver.persistedUriPermissions.any {
                it.uri == row.uri && it.isReadPermission && !it.isWritePermission
            })
            assertTrue(read(uri).isNotEmpty())
        }

        val replacement = "stillreader-relink-${SystemClock.uptimeMillis()}.pdf"
        withDocument(true, name = replacement) {
            withActivity(Intent(Intent.ACTION_MAIN)) { activity ->
                val resume = context.getString(R.string.resume_book, title)
                expect("The linked book did not appear on the shelf") {
                    views(activity.window.decorView).filterIsInstance<Button>().any {
                        it.isShown && it.text.toString() == resume
                    }
                }
                clickText(activity, resume)
                expect("A missing source did not show an access error") {
                    visibleText(activity, R.string.error_document_access)
                }
                click(activity, R.string.library_title)
                expect("The library did not mark the source as unavailable") {
                    visibleText(activity, R.string.book_needs_file)
                }
                click(activity, R.string.book_options)
                clickAccessibilityText(context.getString(R.string.locate_pdf), 5_000)
                whileRecreatingOnOpen(activity, R.string.opening_pdf, { selectSystemFile(replacement) }) { recreated ->
                    val reader = awaitReader(recreated)
                    expect("The relinked book did not retain the saved position") { reader.currentPage == 1 && reader.zoom > 1f }
                    click(recreated, R.string.library_title)
                }
            }
            val row = libraryBooks().single { it.id == selected }
            assertEquals(replacement, row.title)
            assertTrue(!row.needsFile)
            assertTrue(row.copyName == null)
            assertTrue(resolver.persistedUriPermissions.any { it.uri == row.uri && it.isReadPermission })
            val reply = java.util.concurrent.ArrayBlockingQueue<Boolean>(1)
            BookLibrary.get(context).remove(row) { reply.offer(it) }
            assertTrue(reply.poll(5, java.util.concurrent.TimeUnit.SECONDS) == true)
            assertTrue(resolver.persistedUriPermissions.none { it.uri == row.uri })
        }
    }

    @Test
    fun exportsAndRestoresReadingMetadataThroughTheSystemPicker() {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("verify_system_backup") == "true"
        )
        assertTrue("The backup test requires an empty test library", libraryBooks().isEmpty())
        val title = "stillreader-backup-fixture-${SystemClock.uptimeMillis()}.pdf"
        withDocument(true, name = title) { uri ->
            val original = read(uri)
            withActivity(viewIntent(uri)) { activity ->
                awaitReader(activity)
                click(activity, R.string.save_pdf_copy)
                val reader = awaitReader(activity)
                expect("The copy was not ready") { sampleFirstRow(reader) == Color.BLACK }
                click(activity, R.string.next_page)
                click(activity, R.string.zoom_in)
                expect("The backup fixture did not reach the intended position") {
                    reader.currentPage == 1 && reader.zoom > 1f
                }
                click(activity, R.string.library_title)
            }
            val before = libraryBooks().single()
            val backup = "stillreader-backup.json"
            withActivity(Intent(Intent.ACTION_MAIN)) { activity ->
                click(activity, R.string.library_settings)
                pressSetting(R.string.export_reading_backup)
                clickAccessibilityText("Save", 15_000)
                expect("The backup picker did not return to StillReader") {
                    activity.hasWindowFocus()
                }
                click(activity, R.string.library_settings)
                pressSetting(R.string.delete_reading_data)
                pressSetting(R.string.delete_reading_data)
                expect("The delete action did not clear the library") {
                    visibleText(activity, R.string.library_empty)
                }
                assertTrue(!checkNotNull(BookLibrary.get(context).copyFor(before)).exists())
                assertArrayEquals(original, read(uri))

                click(activity, R.string.library_settings)
                pressSetting(R.string.restore_reading_backup)
                selectSystemFile(backup)
                pressSetting(R.string.restore_action)
                expect("The imported book did not request its original PDF") {
                    visibleText(activity, R.string.book_needs_file)
                }
            }
            val restored = libraryBooks().single()
            try {
                assertEquals(before.backupKey, restored.backupKey)
                assertEquals(before.position, restored.position)
                assertEquals(before.comfort, restored.comfort)
                assertTrue(restored.needsFile)
                assertArrayEquals(original, read(uri))
            } finally {
                val reply = java.util.concurrent.ArrayBlockingQueue<Boolean>(1)
                BookLibrary.get(context).remove(restored) { reply.offer(it) }
                assertTrue(reply.poll(5, java.util.concurrent.TimeUnit.SECONDS) == true)
            }
        }
    }

    @Test
    fun rendersAndNavigatesALargeDocumentWithMixedPageSizes() =
        withDocument(true, pageTotal = 301) { uri ->
            withActivity(viewIntent(uri)) { activity ->
                val reader = awaitReader(activity, 301)
                expect("The first page of the long PDF did not render") { sampleFirstRow(reader) == Color.BLACK }
                clickText(activity, context.getString(R.string.page_position, 1, 301))
                enterDialogText("301")
                pressSetting(R.string.go_to_page)
                expect("Jumping to the final page did not change the page indicator") {
                    reader.currentPage == 300
                }
                expect("The final page of the long PDF did not render") {
                    samplePage(reader, 300) == Color.BLACK
                }
                click(activity, R.string.previous_page)
                expect("The previous page with a different aspect ratio was unavailable") {
                    reader.currentPage == 299 && samplePage(reader, 299) == Color.RED
                }
            }
        }

    @Test
    fun rejectsAnExternalViewWithoutAReadGrant() = withDocument(true) { uri ->
        withActivity(viewIntent(uri).setFlags(0)) { activity ->
            expect("A PDF launch without a read grant was accepted") {
                visibleText(activity, R.string.error_document_access)
            }
        }
    }

    @Test
    fun exposesPageNavigationAndZoomWithTalkBackEnabled() {
        org.junit.Assume.assumeTrue(
            InstrumentationRegistry.getArguments().getString("verify_talkback") == "true"
        )
        val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val accessibility = checkNotNull(context.getSystemService(android.view.accessibility.AccessibilityManager::class.java))
        expect("TalkBack did not enable touch exploration") {
            accessibility.isTouchExplorationEnabled && accessibility.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
            ).any { it.resolveInfo.serviceInfo.packageName == "com.google.android.marvin.talkback" }
        }
        withDocument(true) { uri ->
            withActivity(viewIntent(uri)) { activity ->
                val reader = awaitReader(activity)
                val button = awaitAccessibilityNode(automation) {
                    it.text?.toString() == context.getString(R.string.zoom_in)
                }
                val focus = android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
                assertTrue(button.isAccessibilityFocused || button.performAction(focus))
                assertTrue(button.refresh() && button.isAccessibilityFocused)
                assertTrue(clickNode(button))
                expect("Accessibility zoom did not enlarge the page") { reader.zoom > 1f }
                val description = context.getString(R.string.jump_description, context.getString(R.string.page_position, 1, 3))
                awaitAccessibilityNode(automation) { it.contentDescription?.toString() == description }
            }
        }
    }

    private fun awaitAccessibilityNode(
        automation: android.app.UiAutomation,
        matches: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean
    ): android.view.accessibility.AccessibilityNodeInfo {
        val until = SystemClock.uptimeMillis() + 10_000L
        do {
            val active = automation.rootInActiveWindow
            if (active?.packageName?.toString()?.endsWith(".permissioncontroller") == true) {
                findNode(active) {
                    it.viewIdResourceName?.endsWith(":id/permission_deny_button") == true ||
                        it.text?.toString() == "Don’t allow" || it.text?.toString() == "Don't allow"
                }?.let(::clickNode)
            }
            for (window in automation.windows) {
                val root = window.root ?: continue
                if (root.packageName?.toString() != context.packageName) continue
                findNode(root) { it.isVisibleToUser && matches(it) }?.let { return it }
            }
            SystemClock.sleep(75)
        } while (SystemClock.uptimeMillis() < until)
        val active = automation.rootInActiveWindow?.packageName
        throw AssertionError("The reader did not expose the expected accessibility control. Active window: $active")
    }

    private fun enterDialogText(value: String) {
        val action = android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT
        val args = android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val until = SystemClock.uptimeMillis() + 10_000L
        do {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val field = root?.let { findNode(it) { node -> node.isEditable } }
            if (field?.performAction(action, args) == true) return
            SystemClock.sleep(75)
        } while (SystemClock.uptimeMillis() < until)
        throw AssertionError("The page dialog did not expose an editable field.")
    }

    private fun selectSystemFile(title: String) {
        val automation = instrumentation.uiAutomation
        val until = SystemClock.uptimeMillis() + 20_000L
        val browseAfter = SystemClock.uptimeMillis() + 2500L
        var openedRoots = false
        var openedDownloads = false
        do {
            val root = automation.rootInActiveWindow
            if (root != null && root.packageName?.toString()?.contains("documentsui") == true) {
                val file = root.findAccessibilityNodeInfosByText(title).firstOrNull {
                    it.text?.toString() == title
                }
                if (file != null && clickNode(file)) return
                if (SystemClock.uptimeMillis() >= browseAfter && !openedRoots) {
                    val menu = findNode(root) { node ->
                        node.contentDescription?.toString() in setOf("Show roots", "Open navigation drawer")
                    }
                    if (menu != null) openedRoots = clickNode(menu)
                }
                if (openedRoots && !openedDownloads) {
                    val downloads = root.findAccessibilityNodeInfosByText("Downloads").firstOrNull {
                        it.text?.toString() == "Downloads"
                    }
                    if (downloads != null) openedDownloads = clickNode(downloads)
                }
            }
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < until)
        throw AssertionError("The system picker did not expose the synthetic file.")
    }

    private fun clickAccessibilityText(label: String, timeout: Long) {
        val until = SystemClock.uptimeMillis() + timeout
        do {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val items = root?.findAccessibilityNodeInfosByText(label).orEmpty()
            if (items.any { it.text?.toString().equals(label, ignoreCase = true) && clickNode(it) }) return
            SystemClock.sleep(75L)
        } while (SystemClock.uptimeMillis() < until)
        throw AssertionError("The window did not expose the action \"$label\".")
    }

    private fun clickNode(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        var item: android.view.accessibility.AccessibilityNodeInfo? = node
        while (item != null) {
            if (item.isClickable && item.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            item = item.parent
        }
        return false
    }

    private fun findNode(
        node: android.view.accessibility.AccessibilityNodeInfo,
        matches: (android.view.accessibility.AccessibilityNodeInfo) -> Boolean
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (matches(node)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findNode(child, matches)?.let { return it }
        }
        return null
    }

    @Test
    fun findsPdfTextAndHighlightsTheSelectedMatch() = withDocument(true, searchable = true) { uri ->
        withActivity(viewIntent(uri)) { activity ->
            val reader = awaitReader(activity)
            click(activity, R.string.open_document_search)
            enterSearch(activity, "needle")
            expect("Two searchable pages did not produce two matches") {
                visibleTextValue(activity, context.getString(R.string.search_matches, 1, 2))
            }
            expect("The highlight did not overlay the text line") { amberAtTextLine(reader) }
            click(activity, R.string.next_match)
            expect("Next match did not navigate to the second result") {
                reader.currentPage == 1 && visibleTextValue(activity, context.getString(R.string.search_matches, 2, 2))
            }
            click(activity, R.string.previous_match)
            expect("Previous match did not return to the first result") { reader.currentPage == 0 }
            enterSearch(activity, "unmatched phrase")
            expect("A text PDF with no matches was reported incorrectly") {
                visibleText(activity, R.string.search_no_matches)
            }
        }
    }

    @Test
    fun canCancelSearchAndExplainsImageOnlyDocuments() = withDocument(true, imageOnly = true) { uri ->
        withActivity(viewIntent(uri)) { activity ->
            awaitReader(activity)
            click(activity, R.string.open_document_search)
            instrumentation.runOnMainSync {
                val field = views(activity.window.decorView).filterIsInstance<android.widget.EditText>().first { it.isShown }
                field.setText("needle")
                val buttons = views(activity.window.decorView).filterIsInstance<Button>().filter { it.isShown }.toList()
                buttons.first { it.text.toString() == context.getString(R.string.find_matches) }.performClick()
                buttons.first { it.text.toString() == context.getString(R.string.cancel_search) }.performClick()
            }
            expect("Cancellation was not acknowledged") {
                visibleTextValue(activity, context.resources.getQuantityString(R.plurals.search_cancelled, 0, 0))
            }
            enterSearch(activity, "needle")
            expect("The image-only PDF did not report missing searchable text") {
                visibleText(activity, R.string.search_no_text)
            }
            click(activity, R.string.close_search)
            expect("The reader did not resume after search") { !visibleText(activity, R.string.search_no_text) }
        }
    }

    @Test
    fun savesBookAppearanceAndAppliesTheReadingControls() {
        val title = "stillreader-appearance-${SystemClock.uptimeMillis()}.pdf"
        withDocument(true, name = title) { uri ->
            val original = read(uri)
            withActivity(viewIntent(uri)) { activity ->
                awaitReader(activity)
                click(activity, R.string.save_pdf_copy)
                val reader = awaitReader(activity)
                expect("The copy did not render") { sampleFirstRow(reader) == Color.BLACK }
                click(activity, R.string.reading_settings)
                chooseSetting(R.string.theme_system, R.string.theme_sepia)
                pressSetting(R.string.recolor_pdf)
                pressSetting(R.string.fit_page)
                pressSetting(R.string.keep_screen_awake)
                chooseSetting(R.string.crop_off, R.string.crop_five)
                pressSetting(R.string.enter_fullscreen)
                expect("Full screen did not offer a button to exit") { visibleText(activity, R.string.exit_fullscreen) }
                expect("The reader did not keep the screen awake") {
                    activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
                }
                assertTrue("Sepia did not tint the PDF paper in the composed screen", showsSepiaPaper(reader))
                click(activity, R.string.exit_fullscreen)
                click(activity, R.string.library_title)
            }
            val stored = libraryBooks().single { it.title == title }
            assertEquals(ReadingComfort(ReadingTheme.SEPIA, recolor = true, fitPage = true, keepAwake = true, crop = 5), stored.comfort)
            withActivity(Intent(Intent.ACTION_MAIN)) { activity ->
                val resume = context.getString(R.string.resume_book, title)
                expect("The book could not be resumed") { visibleTextValue(activity, resume) }
                clickText(activity, resume)
                val reader = awaitReader(activity)
                assertTrue("The saved appearance was not used on reopening", showsSepiaPaper(reader))
                click(activity, R.string.fit_width)
                click(activity, R.string.library_title)
            }
            val afterFitWidth = libraryBooks().single { it.title == title }
            assertTrue(!afterFitWidth.comfort.fitPage)
            removeTestBook(title)
            assertArrayEquals(original, read(uri))
        }
    }

    private fun enterSearch(activity: Activity, query: String) {
        instrumentation.runOnMainSync {
            views(activity.window.decorView).filterIsInstance<android.widget.EditText>().first { it.isShown }.setText(query)
        }
        click(activity, R.string.find_matches)
    }

    private fun visibleTextValue(activity: Activity, value: String): Boolean =
        views(activity.window.decorView).filterIsInstance<TextView>().any {
            it.isShown && it.text.toString() == value
        }

    private fun amberAtTextLine(reader: PdfPagesView): Boolean {
        val row = reader.getChildAt(0) ?: return false
        if (row.width == 0 || row.height == 0) return false
        val image = Bitmap.createBitmap(96, 192, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(image)
            canvas.scale(96f / row.width, 192f / row.height)
            row.draw(canvas)
            (105..123).any { y ->
                (12..40).any { x ->
                    val pixel = image.getPixel(x, y)
                    Color.red(pixel) > 195 && Color.green(pixel) in 100..235 && Color.blue(pixel) < 190
                }
            }
        } finally {
            image.recycle()
        }
    }

    private fun chooseSetting(from: Int, to: Int) {
        pressSetting(from)
        pressSetting(to)
    }

    private fun pressSetting(label: Int) {
        clickAccessibilityText(context.getString(label), 5_000)
    }

    private fun showsSepiaPaper(reader: PdfPagesView): Boolean {
        repeat(24) {
            var x = -1
            var y = -1
            instrumentation.runOnMainSync {
                reader.getChildAt(0)?.let { child ->
                    val point = IntArray(2)
                    child.getLocationOnScreen(point)
                    x = point[0] + child.width / 2
                    y = point[1] + minOf(child.height / 2, reader.height / 3)
                }
            }
            if (x >= 0 && y >= 0) {
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                if (screenshot != null) {
                    try {
                        if (x < screenshot.width && y < screenshot.height) {
                            val pixel = screenshot.getPixel(x, y)
                            if (Color.red(pixel) > 220 && Color.red(pixel) > Color.green(pixel) + 5 &&
                                Color.green(pixel) > Color.blue(pixel) + 10
                            ) return true
                        }
                    } finally {
                        screenshot.recycle()
                    }
                }
            }
            SystemClock.sleep(80)
        }
        return false
    }

    private fun assertResumedCopy(title: String) {
        withActivity(Intent(Intent.ACTION_MAIN)) { activity ->
            val resume = context.getString(R.string.resume_book, title)
            expect("The library did not reopen the saved book") {
                views(activity.window.decorView).filterIsInstance<Button>().any {
                    it.isShown && it.text.toString() == resume
                }
            }
            clickText(activity, resume)
            val reader = awaitReader(activity)
            expect("The saved page, zoom, and scroll were not restored") {
                reader.currentPage == 1 && reader.zoom > 1f &&
                    reader.position().offset > 0.18f && reader.firstPage == 1 &&
                    sampleFirstRow(reader) != null
            }
        }
    }

    private fun libraryBooks(): List<Book> {
        val reply = java.util.concurrent.ArrayBlockingQueue<List<Book>>(1)
        BookLibrary.get(context).list { reply.offer(it.orEmpty()) }
        return checkNotNull(reply.poll(5, java.util.concurrent.TimeUnit.SECONDS))
    }

    private fun removeTestBook(title: String) {
        val book = libraryBooks().single { it.title == title }
        val store = BookLibrary.get(context)
        val file = checkNotNull(store.copyFor(book))
        assertTrue(file.isFile)
        val reply = java.util.concurrent.ArrayBlockingQueue<Boolean>(1)
        store.remove(book) { reply.offer(it) }
        assertTrue(reply.poll(5, java.util.concurrent.TimeUnit.SECONDS) == true)
        assertTrue(libraryBooks().none { it.id == book.id })
        assertTrue(!file.exists())
    }

    private fun awaitReader(activity: MainActivity, pageCount: Int = 3): PdfPagesView {
        lateinit var reader: PdfPagesView
        expect("The reader did not open the expected document") {
            reader = checkNotNull(views(activity.window.decorView).filterIsInstance<PdfPagesView>().firstOrNull())
            reader.visibility == View.VISIBLE && reader.pageCount == pageCount
        }
        return reader
    }

    private fun visibleText(activity: Activity, label: Int): Boolean =
        views(activity.window.decorView).filterIsInstance<TextView>().any {
            it.isShown && it.text.toString() == context.getString(label)
        }

    private fun click(activity: Activity, label: Int) = clickText(activity, context.getString(label))

    private fun clickText(activity: Activity, text: String) {
        instrumentation.runOnMainSync {
            val button = views(activity.window.decorView).filterIsInstance<Button>()
                .first { it.isShown && it.text.toString() == text }
            assertTrue(button.isEnabled)
            assertTrue(button.performClick())
        }
    }

    private fun tapPage(reader: PdfPagesView) {
        instrumentation.runOnMainSync {
            val time = SystemClock.uptimeMillis()
            for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, time, action, reader.width / 2f, reader.height / 2f, 0)
                try {
                    reader.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }
        }
    }

    private fun pinchPage(reader: PdfPagesView) {
        instrumentation.runOnMainSync {
            val start = SystemClock.uptimeMillis() - 100
            val properties = Array(2) { index ->
                MotionEvent.PointerProperties().apply {
                    id = index
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }

            fun send(action: Int, time: Long, vararg positions: Float) {
                val coordinates = Array(positions.size) { index ->
                    MotionEvent.PointerCoords().apply {
                        x = reader.width * positions[index]
                        y = reader.height / 2f
                        pressure = 1f
                        size = 1f
                    }
                }
                val event = MotionEvent.obtain(
                    start, start + time, action, positions.size, properties, coordinates,
                    0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
                )
                try {
                    reader.dispatchTouchEvent(event)
                } finally {
                    event.recycle()
                }
            }

            val pointerIndex = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
            send(MotionEvent.ACTION_DOWN, 0, 0.35f)
            send(MotionEvent.ACTION_POINTER_DOWN or pointerIndex, 10, 0.35f, 0.65f)
            send(MotionEvent.ACTION_MOVE, 30, 0.25f, 0.75f)
            send(MotionEvent.ACTION_MOVE, 50, 0.15f, 0.85f)
            send(MotionEvent.ACTION_POINTER_UP or pointerIndex, 70, 0.15f, 0.85f)
            send(MotionEvent.ACTION_UP, 80, 0.15f)
        }
    }

    private fun views(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i)))
        }
    }

    private fun expect(message: String, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + 20_000L
        do {
            var matched = false
            instrumentation.runOnMainSync { matched = predicate() }
            if (matched) return
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < until)
        throw AssertionError(message)
    }

    private fun sampleFirstRow(reader: PdfPagesView): Int? = samplePage(reader, reader.firstVisiblePosition)

    private fun samplePage(reader: PdfPagesView, page: Int): Int? {
        val index = page - reader.firstVisiblePosition
        if (index !in 0 until reader.childCount) return null
        val row = reader.getChildAt(index) ?: return null
        if (row.width <= 0 || row.height <= 0) return null
        val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(pixel)
            canvas.translate(-row.width / 16f, -row.height / 8f)
            row.draw(canvas)
            pixel.getPixel(0, 0)
        } finally {
            pixel.recycle()
        }
    }

    private fun viewIntent(uri: Uri): Intent = Intent(Intent.ACTION_VIEW)
        .setClass(context, MainActivity::class.java)
        .setDataAndType(uri, PDF_MIME)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun withActivity(intent: Intent, block: (MainActivity) -> Unit) {
        val activity = instrumentation.startActivitySync(
            intent.setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as MainActivity
        try {
            block(activity)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun whileRecreatingOnOpen(
        activity: MainActivity,
        status: Int,
        open: () -> Unit,
        verify: (MainActivity) -> Unit
    ) {
        val instances = ArrayBlockingQueue<MainActivity>(1)
        val registry = ActivityLifecycleMonitorRegistry.getInstance()
        val callback = ActivityLifecycleCallback { candidate, stage ->
            if (candidate is MainActivity && candidate !== activity && stage == Stage.RESUMED) instances.offer(candidate)
        }
        val loading = context.getString(status)
        var requested = false
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!requested && s?.toString() == loading) {
                    requested = true
                    activity.recreate()
                }
            }
        }
        lateinit var watched: List<TextView>
        instrumentation.runOnMainSync {
            registry.addLifecycleCallback(callback)
            watched = views(activity.window.decorView).filterIsInstance<TextView>().toList()
            watched.forEach { it.addTextChangedListener(watcher) }
        }
        var recreated: MainActivity? = null
        try {
            open()
            recreated = checkNotNull(instances.poll(15, TimeUnit.SECONDS)) { "The Activity did not recreate while opening." }
            verify(recreated)
        } finally {
            instrumentation.runOnMainSync {
                registry.removeLifecycleCallback(callback)
                watched.forEach { it.removeTextChangedListener(watcher) }
                (recreated ?: instances.poll())?.finish()
            }
            instrumentation.waitForIdleSync()
        }
    }

    private fun withDocument(
        valid: Boolean,
        name: String = "stillreader-test-${SystemClock.uptimeMillis()}.pdf",
        searchable: Boolean = false,
        imageOnly: Boolean = false,
        pageTotal: Int = 3,
        block: (Uri) -> Unit
    ) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, PDF_MIME)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { output ->
                if (valid) {
                    val document = PdfDocument()
                    try {
                        val colors = listOf(Color.BLACK, Color.BLUE, Color.RED)
                        for (index in 0 until pageTotal) {
                            val color = colors[index % colors.size]
                            val width = if (pageTotal > 3 && index % 2 == 1) 192 else 96
                            val page = document.startPage(PdfDocument.PageInfo.Builder(width, 192, index + 1).create())
                            val paint = Paint().apply { this.color = color }
                            if (imageOnly) {
                                val image = Bitmap.createBitmap(22, 46, Bitmap.Config.ARGB_8888)
                                try {
                                    image.eraseColor(color)
                                    page.canvas.drawBitmap(image, 2f, 2f, null)
                                } finally {
                                    image.recycle()
                                }
                            } else {
                                page.canvas.drawRect(2f, 2f, 24f, 48f, paint)
                            }
                            if (searchable && index < 2) {
                                paint.color = Color.BLACK
                                paint.textSize = 14f
                                page.canvas.drawText("needle", 12f, 120f + index * 26f, paint)
                            }
                            document.finishPage(page)
                        }
                        document.writeTo(output)
                    } finally {
                        document.close()
                    }
                } else {
                    output.write("Synthetic damaged PDF".toByteArray())
                }
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            block(uri)
        } finally {
            resolver.delete(uri, null, null)
        }
    }

    private fun read(uri: Uri): ByteArray =
        checkNotNull(resolver.openInputStream(uri)).use { it.readBytes() }

    private companion object {
        const val PDF_MIME = "application/pdf"
    }
}
