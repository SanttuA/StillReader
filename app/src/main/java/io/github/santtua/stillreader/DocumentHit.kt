package io.github.santtua.stillreader

internal data class MatchRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

internal data class DocumentHit(
    val page: Int,
    val pageWidth: Int,
    val pageHeight: Int,
    val rectangles: List<MatchRect>
)

internal data class PageSearchResult(
    val hasText: Boolean,
    val truncated: Boolean,
    val hits: List<DocumentHit>
)
