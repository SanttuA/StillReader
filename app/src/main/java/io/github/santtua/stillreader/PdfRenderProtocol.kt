package io.github.santtua.stillreader

import kotlin.math.sqrt

internal object PdfRenderProtocol {
    const val OPEN = 1
    const val RENDER = 2
    const val RESULT = 3
    const val SEARCH_PAGE = 4

    const val DESCRIPTOR = "descriptor"
    const val MEMORY = "memory"
    const val STATUS = "status"
    const val COUNT = "count"
    const val PAGE = "page"
    const val REQUEST_WIDTH = "requestWidth"
    const val PAGE_WIDTH = "pageWidth"
    const val PAGE_HEIGHT = "pageHeight"
    const val WIDTH = "width"
    const val HEIGHT = "height"
    const val QUERY = "query"
    const val CHECK_TEXT = "checkText"
    const val HAS_TEXT = "hasText"
    const val TRUNCATED = "truncated"
    const val RECT_COUNTS = "rectCounts"
    const val RECTS = "rects"

    const val MAX_QUERY_LENGTH = 128
    const val MAX_HITS_PER_PAGE = 256
    const val MAX_RECTS_PER_HIT = 8

    const val OK = 1
    const val UNREADABLE = 2
    const val ENCRYPTED = 3
    const val UNRENDERABLE_PAGE = 4

    const val MAX_PAGES = 100_000
    const val MAX_EDGE = 2048
    private const val MAX_PIXELS = 2_097_152
    private const val MAX_PAGE_POINTS = 200_000

    fun acceptsPageSize(width: Int, height: Int): Boolean =
        width in 1..MAX_PAGE_POINTS && height in 1..MAX_PAGE_POINTS

    fun rasterSize(pageWidth: Int, pageHeight: Int, requestWidth: Int): RasterSize {
        require(acceptsPageSize(pageWidth, pageHeight))
        require(requestWidth in 1..MAX_EDGE)
        val scale = minOf(
            requestWidth.toDouble() / pageWidth,
            MAX_EDGE.toDouble() / pageHeight,
            sqrt(MAX_PIXELS.toDouble() / (pageWidth.toDouble() * pageHeight))
        )
        return RasterSize(
            (pageWidth * scale).toInt().coerceAtLeast(1),
            (pageHeight * scale).toInt().coerceAtLeast(1)
        )
    }

    fun byteCount(width: Int, height: Int): Int? {
        if (width !in 1..MAX_EDGE || height !in 1..MAX_EDGE) return null
        val pixels = width * height
        return if (pixels <= MAX_PIXELS) pixels * 4 else null
    }
}

internal data class RasterSize(val width: Int, val height: Int)
