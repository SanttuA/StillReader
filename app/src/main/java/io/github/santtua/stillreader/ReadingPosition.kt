package io.github.santtua.stillreader

internal data class ReadingPosition(
    val page: Int = 0,
    val anchor: Int = 0,
    val offset: Float = 0f,
    val zoom: Float = 1f,
    val pan: Float = 0.5f
) {
    fun bounded(pageCount: Int): ReadingPosition {
        val last = (pageCount - 1).coerceAtLeast(0)
        return ReadingPosition(
            page.coerceIn(0, last),
            anchor.coerceIn(0, last),
            if (offset.isFinite()) offset.coerceIn(0f, 0.99f) else 0f,
            if (zoom.isFinite()) zoom.coerceIn(1f, 4f) else 1f,
            if (pan.isFinite()) pan.coerceIn(0f, 1f) else 0.5f
        )
    }
}

internal fun cleanBookTitle(value: String?): String? = value
    ?.asSequence()
    ?.filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
    ?.take(160)
    ?.joinToString("")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
