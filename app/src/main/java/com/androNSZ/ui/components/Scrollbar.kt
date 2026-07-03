package com.androNSZ.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draws a thin vertical scrollbar thumb on the right edge of a [LazyColumn].
 *
 * The thumb is drawn only when the list overflows its viewport, and — unlike the
 * fade-on-scroll variant from the Compose docs — it stays visible the whole time
 * so the user can see at a glance that the list is scrollable.
 *
 * Thumb position and height are derived from [LazyListState.layoutInfo] assuming
 * roughly uniform item heights (queue rows are close enough), so it is a visual
 * approximation rather than a pixel-exact indicator.
 */
fun Modifier.simpleVerticalScrollbar(
   state: LazyListState,
   width: Dp = 4.dp,
   color: Color = Color(0x66888888)
): Modifier = drawWithContent {
   drawContent()

   val layoutInfo = state.layoutInfo
   val totalItems = layoutInfo.totalItemsCount
   val visibleItems = layoutInfo.visibleItemsInfo
   if (totalItems == 0 || visibleItems.isEmpty()) return@drawWithContent

   val firstVisibleIndex = visibleItems.first().index
   val hasOverflow = totalItems > visibleItems.size ||
      firstVisibleIndex > 0 ||
      visibleItems.first().offset < 0
   if (!hasOverflow) return@drawWithContent

   val elementHeight = size.height / totalItems
   val thumbOffsetY = firstVisibleIndex * elementHeight
   val thumbHeight = visibleItems.size * elementHeight
   val widthPx = width.toPx()

   drawRoundRect(
      color = color,
      topLeft = Offset(size.width - widthPx, thumbOffsetY),
      size = Size(widthPx, thumbHeight),
      cornerRadius = CornerRadius(widthPx / 2f, widthPx / 2f)
   )
}
