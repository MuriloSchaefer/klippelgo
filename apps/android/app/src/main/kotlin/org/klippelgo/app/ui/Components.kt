package org.klippelgo.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.klippelgo.app.R
import org.klippelgo.app.ui.tags.Tags

/** Names this piece for the tests (see [Tags]). Nothing when there is no name to give. */
fun Modifier.tagged(tag: String?): Modifier = if (tag == null) this else testTag(tag)

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    icon: ImageVector? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = MaterialTheme.colorScheme.outlineVariant,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
        } else if (icon != null) {
            Icon(icon, contentDescription = null, Modifier.size(20.dp))
        }
        Text(
            text,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = if (busy || icon != null) 8.dp else 0.dp),
        )
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp),
        modifier = modifier.heightIn(min = 52.dp),
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

/** A choice among a few: the picked one gets the heavy ink border. */
@Composable
fun Choice(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        selected = selected,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        },
        modifier = modifier.heightIn(min = 44.dp),
    ) {
        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(text, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
        }
    }
}

@Composable
fun BackButton(onClick: () -> Unit, modifier: Modifier = Modifier, onMap: Boolean = false) {
    val button = @Composable {
        IconButton(onClick = onClick, Modifier.size(44.dp).testTag(Tags.BACK)) {
            Icon(KIcons.Back, contentDescription = stringResource(R.string.back), Modifier.size(22.dp))
        }
    }
    if (onMap) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = modifier,
        ) { button() }
    } else {
        Box(modifier) { button() }
    }
}

@Composable
fun KCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    border: BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val body = @Composable { Box(Modifier.padding(padding)) { content() } }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, border = border, modifier = modifier.fillMaxWidth(), content = body)
    } else {
        Surface(shape = shape, border = border, modifier = modifier.fillMaxWidth(), content = body)
    }
}

/** The blue monospace label that says who can see something. */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontFamily = PlexMono,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun Muted(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    align: TextAlign? = null,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Text(text, modifier, color = color, fontSize = size, lineHeight = (size.value + 6).sp, textAlign = align)
}

@Composable
fun Title(text: String, modifier: Modifier = Modifier, size: TextUnit = 28.sp) {
    Text(text, modifier, fontSize = size, lineHeight = (size.value + 6).sp, fontWeight = FontWeight.Bold)
}

/** A white pill floating on the map. */
@Composable
fun Pill(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val body = @Composable {
        Row(
            Modifier.heightIn(min = 40.dp).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = CircleShape, border = border, modifier = modifier, content = body)
    } else {
        Surface(shape = CircleShape, border = border, modifier = modifier, content = body)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).background(color, CircleShape))
}

@Composable
fun Avatar(size: Dp = 48.dp) {
    Box(Modifier.size(size).background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
}

/** A label on the left and its value on the right, with a hairline under it. */
@Composable
fun KeyValue(label: String, value: String, divider: Boolean = true, strong: Boolean = false, tag: String? = null) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Muted(label, Modifier.weight(1f))
            Text(value, Modifier.tagged(tag), fontSize = 14.sp, fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal)
        }
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

/** The bottom sheet that sits over the map. */
@Composable
fun Sheet(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/**
 * The map under every ride screen. With a map pack on the phone it is the real map; without one, the
 * wireframes' grey grid stands in.
 */
@Composable
fun MapSurface(modifier: Modifier = Modifier, scene: MapScene? = null, content: @Composable BoxScope.() -> Unit = {}) {
    val line = MaterialTheme.colorScheme.outlineVariant
    if (scene != null) {
        Box(modifier) {
            PackMap(
                scene,
                ink = MaterialTheme.colorScheme.primary,
                accent = MaterialTheme.colorScheme.tertiary,
                ring = MaterialTheme.colorScheme.surface,
                muted = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxSize(),
            )
            // The map data's licence asks for this credit wherever the map is shown.
            Text(
                stringResource(R.string.map_credit),
                Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 4.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp),
                fontSize = 10.sp,
                lineHeight = 14.sp,
            )
            content()
        }
        return
    }
    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .drawBehind {
                val step = 40.dp.toPx()
                var x = step
                while (x < size.width) {
                    drawLine(line, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                    x += step
                }
                var y = step
                while (y < size.height) {
                    drawLine(line, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    y += step
                }
            },
        content = content,
    )
}

/**
 * A route drawn as a picture, for screens with no real one to show: dotted while it is only planned, solid blue
 * once the trip is under way.
 */
@Composable
fun RouteArt(modifier: Modifier = Modifier, underWay: Boolean = false) {
    val ink = MaterialTheme.colorScheme.primary
    val blue = MaterialTheme.colorScheme.tertiary
    val ring = MaterialTheme.colorScheme.surface
    Canvas(modifier) {
        val points = listOf(0.26f to 0.82f, 0.26f to 0.52f, 0.68f to 0.52f, 0.68f to 0.22f)
            .map { (x, y) -> Offset(x * size.width, y * size.height) }
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        drawPath(
            path,
            color = if (underWay) blue else ink,
            style = Stroke(
                width = 4.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = if (underWay) null else PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 9.dp.toPx())),
            ),
        )
        val start = points.first()
        drawCircle(ring, 11.dp.toPx(), start)
        drawCircle(blue, 8.dp.toPx(), start)
        val end = points.last()
        val outer = 11.dp.toPx()
        val inner = 8.dp.toPx()
        drawRoundRect(ring, end - Offset(outer, outer), Size(outer * 2, outer * 2), CornerRadius(5.dp.toPx()))
        drawRoundRect(ink, end - Offset(inner, inner), Size(inner * 2, inner * 2), CornerRadius(3.dp.toPx()))
    }
}

/** Five stars; tapping the nth picks n. */
@Composable
fun StarPicker(stars: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier, size: Dp = 32.dp, tag: String? = null) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..5).forEach { n ->
            IconButton(onClick = { onPick(n) }, Modifier.size(size + 16.dp).tagged(tag?.let { Tags.star(it, n) })) {
                Icon(
                    if (n <= stars) KIcons.StarFilled else KIcons.Star,
                    contentDescription = stringResource(R.string.stars_n, n),
                    Modifier.size(size),
                )
            }
        }
    }
}

/** PT / EN / ES switch from the welcome screen. */
@Composable
fun LanguagePills(current: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = modifier) {
        Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf("pt-BR" to "PT", "en" to "EN", "es" to "ES").forEach { (tag, label) ->
                val on = current == tag.substringBefore('-')
                Surface(
                    onClick = { onPick(tag) },
                    selected = on,
                    shape = CircleShape,
                    color = if (on) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 44.dp).height(36.dp).testTag(Tags.language(tag.substringBefore('-'))),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/** A full-screen column that scrolls when the content is taller than the screen, and otherwise lets weights fill it. */
@Composable
fun ScrollScreen(tag: String? = null, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().tagged(tag)) {
        Column(Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight), content = content)
    }
}
