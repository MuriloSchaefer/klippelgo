package org.klippelgo.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** The wireframes' line icons: 24 x 24, 2 px round strokes. `Icon` tints them. */
object KIcons {
    val Lock = icon(
        "M7 11h10a2 2 0 0 1 2 2v6a2 2 0 0 1 -2 2H7a2 2 0 0 1 -2 -2v-6a2 2 0 0 1 2 -2z",
        "M8 11V7a4 4 0 0 1 8 0v4",
    )
    val Phone = icon(
        "M9 2h6a2 2 0 0 1 2 2v16a2 2 0 0 1 -2 2H9a2 2 0 0 1 -2 -2V4a2 2 0 0 1 2 -2z",
        "M11 18h2",
    )
    val Rules = icon("M3 21h18", "M5 21V10l7 -5 7 5v11", "M10 21v-6h4v6")
    val Back = icon("M15 18l-6 -6 6 -6")
    val Search = icon("M4 11a7 7 0 1 0 14 0a7 7 0 1 0 -14 0", "M20 20l-3.5 -3.5")
    val Pin = icon(
        "M12 21s-7 -6.2 -7 -11a7 7 0 0 1 14 0c0 4.8 -7 11 -7 11z",
        "M9.5 10a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0 -5 0",
    )
    val Wallet = icon(
        "M5 6h14a2 2 0 0 1 2 2v9a2 2 0 0 1 -2 2H5a2 2 0 0 1 -2 -2V8a2 2 0 0 1 2 -2z",
        "M16 12.5h2",
    )
    val Person = icon("M8 8a4 4 0 1 0 8 0a4 4 0 1 0 -8 0", "M4 21c1.5 -4 4.5 -6 8 -6s6.5 2 8 6")
    val Check = icon("M5 12l5 5L20 7", width = 2.4f)
    val Star = icon(STAR, width = 1.5f)
    val StarFilled = icon(STAR, width = 1.5f, filled = true)
}

private const val STAR = "M12 3l2.8 5.7 6.2 0.9 -4.5 4.4 1 6.2L12 17.3 6.5 20.2l1 -6.2L3 9.6l6.2 -0.9z"

private fun icon(vararg paths: String, width: Float = 2f, filled: Boolean = false): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        paths.forEach {
            addPath(
                pathData = addPathNodes(it),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
