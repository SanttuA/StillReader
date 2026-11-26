package io.github.santtua.stillreader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.util.LruCache
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.ListView
import kotlin.math.abs
import kotlin.math.roundToInt

// Created by MainActivity with callbacks for visible pages and navigation state.
@SuppressLint("ViewConstructor")
internal class PdfPagesView(
    context: Context,
    private val onChange: () -> Unit,
    private val renderPage: (Int, Int) -> Boolean
) : ListView(context), AbsListView.OnScrollListener {
    private val frames = object : LruCache<Int, PageFrame>(32 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: PageFrame): Int = value.bitmap.allocationByteCount
    }
    private val pageRatios = LruCache<Int, Float>(256)
    private val failed = LruCache<Int, Boolean>(256)
    private val pages = PagesAdapter()
    private var rendering = false
    private var scrollState = OnScrollListener.SCROLL_STATE_IDLE
    private var selectedPage: Int? = null
    private var pendingRestore: ReadingPosition? = null
    private var hit: DocumentHit? = null
    private var fitPage = false
    private var margin = 0f
    private var pan = 0.5f
    private var interceptGesture = false
    private var scaleGesture = false
    private val dividerSize = (context.resources.displayMetrics.density * 8).roundToInt()

    var pageCount = 0
        private set
    var zoom = 1f
        private set

    val firstPage: Int get() = firstVisiblePosition.coerceAtLeast(0)
    val offset: Int get() = getChildAt(0)?.top ?: 0
    val currentPage: Int
        get() {
            selectedPage?.let { return it }
            val first = getChildAt(0)
            val next = if (first != null && childCount > 1 && first.bottom < height / 3) 1 else 0
            return (firstPage + next).coerceAtMost((pageCount - 1).coerceAtLeast(0))
        }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaleGesture = true
            takeGesture()
            return pageCount > 0
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            changeZoom(zoom * detector.scaleFactor, detector.focusY)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaleGesture = false
            schedule()
        }
    }).apply { isQuickScaleEnabled = false }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (pageCount == 0) return false
            takeGesture()
            changeZoom(if (zoom > 1f) 1f else 2f, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaleGesture || zoom <= 1f) return false
            if (!interceptGesture && (e1 == null || abs(e2.x - e1.x) <= abs(e2.y - e1.y))) return false
            takeGesture()
            shiftPan(distanceX / (width * (zoom - 1f)).coerceAtLeast(1f))
            return true
        }
    })

    init {
        adapter = pages
        divider = null
        dividerHeight = dividerSize
        setOnScrollListener(this)
        isVerticalFadingEdgeEnabled = false
        isFocusable = true
    }

    fun showDocument(count: Int, start: ReadingPosition = ReadingPosition()) {
        val initial = start.bounded(count)
        rendering = false
        frames.evictAll()
        pageRatios.evictAll()
        failed.evictAll()
        pageCount = count
        selectedPage = if (count > 0) initial.page else null
        pendingRestore = initial.takeIf { count > 0 && it.offset > 0f }
        hit = null
        scrollState = OnScrollListener.SCROLL_STATE_IDLE
        zoom = initial.zoom
        pan = initial.pan
        pages.notifyDataSetChanged()
        if (count > 0) setSelectionFromTop(initial.anchor, 0)
        onChange()
        post(::schedule)
    }

    fun position(): ReadingPosition {
        pendingRestore?.let { return it.copy(zoom = zoom, pan = pan) }
        val selected = selectedPage
        val anchor = if (selected != null && selected !in firstVisiblePosition..lastVisiblePosition) selected else firstPage
        val rowHeight = getChildAt(0)?.height ?: 0
        val fraction = if (anchor == firstPage && rowHeight > 0) -offset.toFloat() / rowHeight else 0f
        return ReadingPosition(currentPage, anchor, fraction, zoom, pan).bounded(pageCount)
    }

    fun rendererReady() { post(::schedule) }

    fun setReadingStyle(fit: Boolean, crop: Int, effect: RenderEffect?) {
        setRenderEffect(effect)
        val newMargin = crop / 100f
        if (fit != fitPage || margin != newMargin) {
            val anchor = position()
            fitPage = fit
            margin = newMargin
            if (pageCount > 0) {
                pages.notifyDataSetChanged()
                setSelectionFromTop(anchor.anchor, -(rowSize(anchor.anchor).second * anchor.offset).roundToInt())
            }
            post(::schedule)
        }
        for (i in 0 until childCount) getChildAt(i).invalidate()
    }

    fun showHit(match: DocumentHit?) {
        hit = match
        if (match != null && match.page in 0 until pageCount) {
            pageRatios.put(match.page, match.pageHeight.toFloat() / match.pageWidth)
            pendingRestore = null
            selectedPage = match.page
            val (pageWidth, pageHeight) = rowSize(match.page)
            val crop = cropFor(match.page)
            val visible = 1f - 2f * crop
            val first = match.rectangles.firstOrNull()
            val scroll = if (first == null) 0 else {
                val left = ((first.left + first.right) / 2f - crop) / visible
                val overhang = pageWidth - width
                if (overhang > 0) pan = ((left * pageWidth - width / 2f) / overhang).coerceIn(0f, 1f)
                (height / 3f - (first.top - crop) / visible * pageHeight).roundToInt().coerceAtMost(0)
            }
            pages.notifyDataSetChanged()
            setSelectionFromTop(match.page, scroll)
            onChange()
        }
        for (i in 0 until childCount) getChildAt(i).invalidate()
        post(::schedule)
    }

    private fun cropFor(index: Int): Float {
        val match = hit
        if (match != null && match.page == index && match.rectangles.any {
                it.left < margin || it.top < margin || it.right > 1f - margin || it.bottom > 1f - margin
            }
        ) return 0f
        return margin
    }

    fun jumpTo(index: Int) {
        if (index in 0 until pageCount) {
            pendingRestore = null
            selectedPage = index
            setSelectionFromTop(index, 0)
            onChange()
            post(::schedule)
        }
    }

    fun changeZoom(value: Float) = changeZoom(value, height / 2f)

    private fun changeZoom(value: Float, focusY: Float) {
        if (!value.isFinite() || pageCount == 0) return
        val newZoom = value.coerceIn(1f, 4f)
        if (abs(newZoom - zoom) < 0.005f) return
        pendingRestore = null
        val anchor = firstPage
        val start = offset
        val fraction = (focusY - start) / rowSize(anchor).second
        zoom = newZoom
        if (zoom == 1f) pan = 0.5f
        val newOffset = (focusY - fraction * rowSize(anchor).second).roundToInt().coerceAtMost(0)
        pages.notifyDataSetChanged()
        setSelectionFromTop(anchor, newOffset)
        onChange()
        post(::schedule)
    }

    fun shiftPan(delta: Float) {
        pan = (pan + delta).coerceIn(0f, 1f)
        for (i in 0 until childCount) getChildAt(i).invalidate()
        onChange()
    }

    fun onFrame(index: Int, frame: PageFrame?) {
        rendering = false
        if (index !in 0 until pageCount) {
            frame?.bitmap?.recycle()
            return
        }
        val anchor = selectedPage?.takeUnless { it in firstVisiblePosition..lastVisiblePosition } ?: firstPage
        val start = if (anchor == firstPage) offset else 0
        val previousHeight = rowSize(index).second
        if (frame != null) {
            frames.put(index, frame)
            pageRatios.put(index, frame.pageHeight.toFloat() / frame.pageWidth)
        } else {
            failed.put(index, true)
        }
        val restore = pendingRestore?.takeIf { it.anchor == index }
        if (restore != null) {
            pages.notifyDataSetChanged()
            setSelectionFromTop(restore.anchor, -(rowSize(index).second * restore.offset).roundToInt())
            post {
                if (pendingRestore === restore) pendingRestore = null
                onChange()
            }
        } else if (index in firstVisiblePosition..lastVisiblePosition) {
            if (previousHeight != rowSize(index).second) {
                pages.notifyDataSetChanged()
                setSelectionFromTop(anchor, start)
            } else {
                val row = getChildAt(index - firstVisiblePosition)
                row?.contentDescription = description(index)
                row?.invalidate()
            }
        }
        post(::schedule)
    }

    override fun onScroll(view: AbsListView, firstVisibleItem: Int, visibleItemCount: Int, totalItemCount: Int) {
        onChange()
        post(::schedule)
    }

    override fun onScrollStateChanged(view: AbsListView, state: Int) {
        scrollState = state
        if (state != OnScrollListener.SCROLL_STATE_IDLE) {
            selectedPage = null
            pendingRestore = null
        }
        onChange()
        post(::schedule)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw > 0 && pageCount > 0 && (w != oldw || fitPage && h != oldh)) {
            val anchor = firstPage
            val start = offset
            post {
                pages.notifyDataSetChanged()
                setSelectionFromTop(anchor, start)
                schedule()
            }
        }
    }

    // ListView handles item and accessibility scrolling. The toolbar also provides zoom and pan actions.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) interceptGesture = false
        scaleDetector.onTouchEvent(event)
        if (!scaleGesture && event.pointerCount == 1) gestures.onTouchEvent(event)
        if (interceptGesture || scaleGesture) return true
        return super.onTouchEvent(event)
    }

    private fun takeGesture() {
        if (interceptGesture) return
        interceptGesture = true
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        try {
            super.onTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
    }

    private fun schedule() {
        if (rendering || scaleGesture || scrollState == OnScrollListener.SCROLL_STATE_FLING ||
            width <= 0 || pageCount == 0 || childCount == 0 || visibility != VISIBLE
        ) return
        val from = firstVisiblePosition.coerceAtLeast(0)
        val to = lastVisiblePosition.coerceAtMost(pageCount - 1)
        for (index in from..to) {
            val requested = (rowSize(index).first / (1f - 2f * cropFor(index)))
                .roundToInt().coerceIn(1, PdfRenderProtocol.MAX_EDGE)
            if (failed.get(index) == true || (frames.get(index)?.requestedWidth ?: 0) >= requested) continue
            rendering = renderPage(index, requested)
            return
        }
    }

    private fun rowSize(index: Int): Pair<Float, Int> {
        val ratio = pageRatios.get(index) ?: 1.414f
        val available = width.coerceAtLeast(1).toFloat()
        val fit = if (fitPage) minOf(available, height.coerceAtLeast(1) / ratio) else available
        val pageWidth = (fit * zoom).coerceAtMost(12_000f / ratio)
        return pageWidth to (pageWidth * ratio).roundToInt().coerceIn(1, 12_000)
    }

    private fun description(index: Int): String = context.getString(
        if (failed.get(index) == true) R.string.page_unavailable_description else R.string.page_description,
        index + 1
    )

    private inner class PagesAdapter : BaseAdapter() {
        override fun getCount(): Int = pageCount
        override fun getItem(position: Int): Int = position
        override fun getItemId(position: Int): Long = position.toLong()
        override fun isEnabled(position: Int): Boolean = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? PageView ?: PageView()
            row.index = position
            row.contentDescription = description(position)
            val height = rowSize(position).second
            val current = row.layoutParams
            if (current == null || current.height != height) {
                row.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
            }
            row.invalidate()
            return row
        }
    }

    private inner class PageView : View(context) {
        var index = 0
        private val brush = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val bounds = RectF()
        private val source = Rect()

        override fun onDraw(canvas: Canvas) {
            val pageWidth = rowSize(index).first
            val left = if (pageWidth > width) -(pageWidth - width) * pan else (width - pageWidth) / 2
            bounds.set(left, 0f, left + pageWidth, height.toFloat())
            brush.color = Color.WHITE
            canvas.drawRect(bounds, brush)
            val bitmap = frames.get(index)?.bitmap
            if (bitmap != null) {
                val crop = cropFor(index)
                val insetX = (bitmap.width * crop).toInt()
                val insetY = (bitmap.height * crop).toInt()
                source.set(insetX, insetY, bitmap.width - insetX, bitmap.height - insetY)
                canvas.drawBitmap(bitmap, source, bounds, brush)
                drawMatch(canvas, crop)
            } else if (failed.get(index) == true) {
                brush.color = Color.DKGRAY
                brush.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
                brush.textAlign = Paint.Align.CENTER
                canvas.drawText(context.getString(R.string.page_unavailable), width / 2f, height / 2f, brush)
            }
        }

        private fun drawMatch(canvas: Canvas, crop: Float) {
            val match = hit?.takeIf { it.page == index } ?: return
            val visible = 1f - 2f * crop
            val saved = canvas.save()
            canvas.clipRect(bounds)
            brush.color = 0x99ffac29.toInt()
            for (rect in match.rectangles) {
                canvas.drawRect(
                    bounds.left + (rect.left - crop) / visible * bounds.width(),
                    (rect.top - crop) / visible * bounds.height(),
                    bounds.left + (rect.right - crop) / visible * bounds.width(),
                    (rect.bottom - crop) / visible * bounds.height(),
                    brush
                )
            }
            canvas.restoreToCount(saved)
        }
    }
}
