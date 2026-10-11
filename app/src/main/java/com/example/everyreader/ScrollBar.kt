package com.example.everyreader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

fun LazyListState.scrollMetrics(): Pair<Float, Float>? {
    val info = layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo
    if (total == 0 || visible.isEmpty()) return null
    val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    val avg = visible.sumOf { it.size }.toFloat() / visible.size
    val content = avg * total
    if (viewport <= 0f || content <= viewport) return null
    val offset = firstVisibleItemIndex * avg + firstVisibleItemScrollOffset
    val thumb = viewport / content
    val pos = (offset / (content - viewport)).coerceIn(0f, 1f)
    return thumb to pos
}

fun ScrollState.scrollMetrics(viewportPx: Int): Pair<Float, Float>? {
    if (viewportPx <= 0 || maxValue <= 0) return null
    val content = viewportPx + maxValue
    return (viewportPx.toFloat() / content) to (value.toFloat() / maxValue)
}

@Composable
fun BoxScope.ScrollIndicator(metrics: () -> Pair<Float, Float>?) {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    Canvas(
        modifier = Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(6.dp)
    ) {
        val (thumb, pos) = metrics() ?: return@Canvas
        val thumbH = (size.height * thumb).coerceAtLeast(24.dp.toPx())
        val y = (size.height - thumbH) * pos
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, y),
            size = Size(size.width, thumbH),
            cornerRadius = CornerRadius(size.width / 2f)
        )
    }
}