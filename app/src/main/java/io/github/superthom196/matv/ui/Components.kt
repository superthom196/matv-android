@file:OptIn(ExperimentalTvMaterial3Api::class)

package io.github.superthom196.matv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.superthom196.matv.R
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.github.superthom196.matv.ma.MediaItem

/** A focusable, clickable panel with the app's focus treatment: white ring, slight grow. */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(14.dp),
    container: Color = HiFiColors.Surface,
    focusedContainer: Color = HiFiColors.SurfaceHigh,
    scale: Float = 1.04f,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            focusedContainerColor = focusedContainer,
            pressedContainerColor = focusedContainer,
            contentColor = HiFiColors.Text,
            focusedContentColor = HiFiColors.Text,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, HiFiColors.Focus), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = scale),
    ) { content() }
}

/** Primary / secondary pill buttons. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    FocusSurface(
        onClick = { if (enabled) onClick() },
        modifier = modifier,
        shape = RoundedCornerShape(50),
        container = if (primary) HiFiColors.Accent else HiFiColors.Surface,
        focusedContainer = if (primary) HiFiColors.AccentBright else HiFiColors.SurfaceHigh,
        scale = 1.06f,
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val tint = if (primary) HiFiColors.OnAccent else HiFiColors.Text
            if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) tint else HiFiColors.Muted)
        }
    }
}

/** Round icon-only transport button. */
@Composable
fun RoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 72.dp,
    primary: Boolean = false,
) {
    FocusSurface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = RoundedCornerShape(50),
        container = if (primary) HiFiColors.Accent else HiFiColors.SurfaceHigh,
        focusedContainer = if (primary) HiFiColors.AccentBright else Color(0xFF3A3A3A),
        scale = 1.1f,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, tint = if (primary) HiFiColors.OnAccent else HiFiColors.Text, modifier = Modifier.size(size * 0.5f))
        }
    }
}

/** Square artwork with a placeholder. */
@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, corner: androidx.compose.ui.unit.Dp = 10.dp) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(Color(0xFF2A2A2A))) {
        if (url != null) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(HiFiColors.Accent.copy(alpha = 0.5f)))
            }
        }
    }
}

/** Library grid tile: artwork, name, subtitle. */
@Composable
fun MediaCard(item: MediaItem, imageUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier, round: Boolean = false, onLongClick: (() -> Unit)? = null, hiRes: Boolean = false) {
    FocusSurface(onClick = onClick, onLongClick = onLongClick, modifier = modifier, container = Color.Transparent, focusedContainer = HiFiColors.SurfaceHigh, scale = 1.05f) {
        // Square artwork fills the column, so its captions line up left. A circle is inset from the
        // column edges, which leaves left-aligned text looking detached from it — centre those.
        val align = if (round) TextAlign.Center else TextAlign.Start
        Column(Modifier.padding(7.dp)) {
            Box(Modifier.fillMaxWidth()) {
                Artwork(imageUrl, Modifier.fillMaxWidth().aspectRatio(1f), corner = if (round) 200.dp else 10.dp)
                if (hiRes) HiResBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            Spacer(Modifier.height(6.dp))
            Text(item.name, style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp, lineHeight = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align, modifier = Modifier.fillMaxWidth())
            val sub = when (item.mediaType) {
                "album" -> listOfNotNull(item.artistLine.takeIf { it.isNotBlank() }, item.year?.toString()).joinToString(" · ")
                "playlist" -> item.owner ?: "Playlist"
                "track" -> item.artistLine
                else -> ""
            }
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 14.sp), color = HiFiColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align, modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * The app's wordmark: the Music Assistant house followed by "TV". The logo already reads MA, so
 * spelling out "MATV" beside it would say it twice. One definition, used by the header, the splash
 * and the connect screen.
 */
@Composable
fun Wordmark(logoSize: Dp = 30.dp, fontSize: TextUnit = 30.sp, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.logo_ma), contentDescription = "MATV", modifier = Modifier.size(logoSize))
        HSpace(logoSize * 0.23f)
        Text(
            "TV",
            style = MaterialTheme.typography.headlineLarge.copy(fontSize = fontSize, fontWeight = FontWeight.Bold),
            color = HiFiColors.Accent,
        )
    }
}

/** Gold "HR" corner mark: this album's tracks are better than CD off the source file. */
@Composable
fun HiResBadge(modifier: Modifier = Modifier) {
    Text(
        "HR",
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
        color = Color(0xFF3A2A00),
        modifier = modifier
            .background(HiFiColors.HiRes, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * Text entry that works with a D-pad: focus ring, OK opens the on-screen keyboard.
 * (tv-material has no TextField; this is a styled BasicTextField.)
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier
            .background(if (focused) HiFiColors.SurfaceHigh else HiFiColors.Surface, shape)
            .then(if (focused) Modifier.border(2.dp, HiFiColors.Focus, shape) else Modifier)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = HiFiColors.Muted)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = HiFiColors.Text, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
            cursorBrush = SolidColor(HiFiColors.Accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
                imeAction = imeAction,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); onDone() }, onNext = { }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { ev ->
                    if (ev.type == KeyEventType.KeyUp && (ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter)) {
                        keyboard?.show(); true
                    } else false
                },
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = HiFiColors.Muted, modifier = modifier)
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(12.dp).clip(CircleShape).background(color))
}

fun formatTime(seconds: Double?): String {
    if (seconds == null || seconds < 0) return "–:––"
    val s = seconds.toInt()
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
fun HSpace(w: androidx.compose.ui.unit.Dp) = Spacer(Modifier.width(w))
@Composable
fun VSpace(h: androidx.compose.ui.unit.Dp) = Spacer(Modifier.height(h))
