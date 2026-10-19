package com.nexwork.edith.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.R
import com.nexwork.edith.model.AppEntry
import com.nexwork.edith.theme.GlassBorderHighlight
import com.nexwork.edith.theme.GlassBorderSubtle
import com.nexwork.edith.theme.GlassSurface
import com.nexwork.edith.theme.GlassSurfaceElevated
import com.nexwork.edith.theme.IceGlowAccent
import com.nexwork.edith.theme.ObsidianBgBottom
import com.nexwork.edith.theme.ObsidianBgTop
import com.nexwork.edith.theme.TextMutedSlate
import com.nexwork.edith.theme.TextPureWhite
import com.nexwork.edith.theme.TextSilver
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * iOS Spotlight-style search overlay in dark glassmorphism:
 * - Opened by swiping down on the home screen.
 * - Search bar positioned at the top with auto-focus and clear action.
 * - Matching apps filtered beneath the search bar.
 * - Auto-launches if exactly 1 app matches.
 * - When multiple apps match, detects 1s typing inactivity:
 *   automatically dismisses the keyboard and brings up a minimalist vertical slider to slide vertically.
 */
@Composable
fun SpotlightSearchOverlay(
  isOpen: Boolean,
  apps: List<AppEntry>,
  essentialApps: List<AppEntry>,
  recentApps: List<AppEntry>,
  searchQuery: String,
  onSearchQueryChange: (String) -> Unit,
  onLaunchApp: (AppEntry) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (!isOpen) return

  BackHandler(enabled = isOpen) {
    onDismiss()
  }

  val focusRequester = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val listState = rememberLazyListState()
  val coroutineScope = rememberCoroutineScope()

  // Auto-focus and open keyboard on open
  LaunchedEffect(isOpen) {
    if (isOpen) {
      delay(120L)
      focusRequester.requestFocus()
      keyboardController?.show()
    }
  }

  // Filter and sort apps
  val matchingApps = remember(apps, searchQuery, essentialApps, recentApps) {
    val q = searchQuery.trim()
    if (q.isEmpty()) {
      // Top Suggestions when no query is typed
      val suggestions = mutableListOf<AppEntry>()
      suggestions.addAll(essentialApps)
      suggestions.addAll(recentApps)
      suggestions.addAll(apps)
      suggestions.distinctBy { it.packageName }.take(12)
    } else {
      apps
        .filter {
          it.label.contains(q, ignoreCase = true) ||
            it.packageName.contains(q, ignoreCase = true)
        }
        .sortedWith(
          compareByDescending<AppEntry> { it.label.startsWith(q, ignoreCase = true) }
            .thenByDescending { it.usageTimeMillis14d }
            .thenBy { it.label.lowercase() }
        )
    }
  }

  // 1. Auto-launch when exactly 1 app matches while actively searching
  LaunchedEffect(searchQuery, matchingApps) {
    val q = searchQuery.trim()
    if (isOpen && q.isNotEmpty() && matchingApps.size == 1) {
      delay(350L) // Graceful debounce so the single result is visible before launching
      if (isOpen && searchQuery.trim() == q && matchingApps.size == 1) {
        val soleApp = matchingApps.first()
        onLaunchApp(soleApp)
        onDismiss()
      }
    }
  }

  // 2. Inactivity Detection (1 second): Auto-dismiss keyboard and reveal minimalist vertical slider
  var isSliderActive by remember { mutableStateOf(false) }

  LaunchedEffect(searchQuery, matchingApps.size, isOpen) {
    if (!isOpen) {
      isSliderActive = false
      return@LaunchedEffect
    }
    val q = searchQuery.trim()
    if (q.isNotEmpty() && matchingApps.size > 1) {
      isSliderActive = false
      // Sensed inactivity timer: 1 second
      delay(1000L)
      keyboardController?.hide()
      focusManager.clearFocus()
      isSliderActive = true
    } else {
      // If query is empty and list is long enough, keep slider available for comfortable vertical sliding
      isSliderActive = matchingApps.size > 4 && q.isEmpty()
    }
  }

  AnimatedVisibility(
    visible = isOpen,
    enter = fadeIn() + slideInVertically { -it / 4 },
    exit = fadeOut() + slideOutVertically { -it / 4 },
    modifier = modifier.fillMaxSize(),
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            colors = listOf(
              ObsidianBgTop.copy(alpha = 0.94f),
              ObsidianBgBottom.copy(alpha = 0.98f),
            )
          )
        )
        .statusBarsPadding()
        .navigationBarsPadding()
        .pointerInput(Unit) {
          detectTapGestures(
            onTap = {
              keyboardController?.hide()
              focusManager.clearFocus()
            }
          )
        }
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .padding(top = 12.dp, start = 16.dp, end = 16.dp, bottom = 8.dp)
      ) {
        // Spotlight Search Bar at the Top (iPhone-style)
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          // Glass pill search container
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(22.dp))
              .background(GlassSurfaceElevated.copy(alpha = 0.90f))
              .border(1.2.dp, GlassBorderHighlight.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
              .padding(horizontal = 14.dp, vertical = 12.dp)
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Icon(
                painter = painterResource(id = R.drawable.ic_custom_search),
                contentDescription = "Search",
                tint = IceGlowAccent,
                modifier = Modifier
                  .size(19.dp)
                  .padding(end = 4.dp),
              )

              Spacer(modifier = Modifier.width(8.dp))

              BasicTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                  color = TextPureWhite,
                  fontSize = 15.sp,
                  fontFamily = FontFamily.SansSerif,
                  fontWeight = FontWeight.Normal,
                ),
                cursorBrush = SolidColor(IceGlowAccent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                  onSearch = {
                    if (matchingApps.isNotEmpty()) {
                      onLaunchApp(matchingApps.first())
                      onDismiss()
                    }
                  }
                ),
                modifier = Modifier
                  .weight(1f)
                  .focusRequester(focusRequester),
                decorationBox = { innerTextField ->
                  if (searchQuery.isEmpty()) {
                    Text(
                      text = "Search apps...",
                      color = TextMutedSlate.copy(alpha = 0.85f),
                      fontSize = 15.sp,
                      fontFamily = FontFamily.SansSerif,
                    )
                  }
                  innerTextField()
                },
              )

              if (searchQuery.isNotEmpty()) {
                Box(
                  modifier = Modifier
                    .clip(CircleShape)
                    .clickable { onSearchQueryChange("") }
                    .padding(4.dp),
                  contentAlignment = Alignment.Center,
                ) {
                  Text(
                    text = "✕",
                    color = TextSilver,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                  )
                }
              }
            }
          }

          // Cancel button
          Text(
            text = "Cancel",
            color = IceGlowAccent,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
              .clickable {
                keyboardController?.hide()
                onDismiss()
              }
              .padding(horizontal = 6.dp, vertical = 8.dp),
          )
        }

        // Section Header (e.g. "TOP RESULTS (4)" or "SUGGESTIONS")
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = if (searchQuery.isBlank()) "SUGGESTED APPS" else "MATCHING APPS (${matchingApps.size})",
            color = TextMutedSlate,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
          )

          if (isSliderActive && matchingApps.size > 1) {
            Text(
              text = "Slide to scrub",
              color = IceGlowAccent.copy(alpha = 0.8f),
              fontSize = 10.5.sp,
              fontWeight = FontWeight.Medium,
            )
          }
        }

        // Search Results + Minimalist Vertical Slider
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
        ) {
          if (matchingApps.isEmpty()) {
            Box(
              modifier = Modifier.fillMaxSize(),
              contentAlignment = Alignment.Center,
            ) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                  text = "No apps found",
                  color = TextSilver,
                  fontSize = 16.sp,
                  fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = "Try searching with a different name",
                  color = TextMutedSlate,
                  fontSize = 12.5.sp,
                )
              }
            }
          } else {
            LazyColumn(
              state = listState,
              contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier
                .fillMaxSize()
                .padding(end = if (isSliderActive) 24.dp else 0.dp),
            ) {
              itemsIndexed(matchingApps, key = { _, app -> app.packageName }) { _, app ->
                SpotlightAppRowItem(
                  app = app,
                  onClick = {
                    onLaunchApp(app)
                    onDismiss()
                  },
                )
              }
            }

            // Minimalist Vertical Slider (appears on inactivity / multi-match)
            if (isSliderActive && matchingApps.size > 1) {
              Box(
                modifier = Modifier
                  .align(Alignment.CenterEnd)
                  .fillMaxHeight()
                  .width(22.dp)
                  .padding(vertical = 12.dp)
              ) {
                MinimalistVerticalSlider(
                  itemCount = matchingApps.size,
                  firstVisibleIndex = listState.firstVisibleItemIndex,
                  onScrollToFraction = { fraction ->
                    val targetIdx = (fraction * (matchingApps.size - 1)).roundToInt().coerceIn(0, matchingApps.size - 1)
                    coroutineScope.launch {
                      listState.scrollToItem(targetIdx)
                    }
                  },
                  modifier = Modifier.fillMaxSize(),
                )
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Minimalist vertical scrubber/slider:
 * Provides a sleek frosted glass vertical track with an ice-blue glowing thumb
 * that smoothly scrubs through matching apps.
 */
@Composable
private fun MinimalistVerticalSlider(
  itemCount: Int,
  firstVisibleIndex: Int,
  onScrollToFraction: (Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  var trackHeightPx by remember { mutableFloatStateOf(1f) }
  val thumbHeightDp = 36.dp

  val scrollFraction by remember(firstVisibleIndex, itemCount) {
    derivedStateOf {
      if (itemCount <= 1) 0f
      else (firstVisibleIndex.toFloat() / (itemCount - 1).toFloat()).coerceIn(0f, 1f)
    }
  }

  Box(
    modifier = modifier
      .onGloballyPositioned { coords ->
        trackHeightPx = coords.size.height.toFloat().coerceAtLeast(1f)
      }
      .pointerInput(itemCount) {
        detectVerticalDragGestures(
          onDragStart = { offset ->
            val fraction = (offset.y / trackHeightPx).coerceIn(0f, 1f)
            onScrollToFraction(fraction)
          },
          onVerticalDrag = { change, _ ->
            change.consume()
            val fraction = (change.position.y / trackHeightPx).coerceIn(0f, 1f)
            onScrollToFraction(fraction)
          }
        )
      }
      .pointerInput(itemCount) {
        detectTapGestures { offset ->
          val fraction = (offset.y / trackHeightPx).coerceIn(0f, 1f)
          onScrollToFraction(fraction)
        }
      },
    contentAlignment = Alignment.TopCenter,
  ) {
    // Translucent vertical track line
    Box(
      modifier = Modifier
        .fillMaxHeight()
        .width(2.dp)
        .clip(RoundedCornerShape(1.dp))
        .background(GlassBorderSubtle.copy(alpha = 0.45f))
    )

    // Glowing ice-blue draggable thumb
    val thumbOffsetPx = scrollFraction * (trackHeightPx - (thumbHeightDp.value * 2.5f)).coerceAtLeast(0f)

    Box(
      modifier = Modifier
        .offset { IntOffset(x = 0, y = thumbOffsetPx.roundToInt()) }
        .width(5.dp)
        .height(thumbHeightDp)
        .clip(RoundedCornerShape(3.dp))
        .background(
          Brush.verticalGradient(
            colors = listOf(
              IceGlowAccent,
              IceGlowAccent.copy(alpha = 0.7f),
            )
          )
        )
        .border(0.6.dp, GlassBorderHighlight, RoundedCornerShape(3.dp))
    )
  }
}

/**
 * Individual app row in the spotlight search overlay:
 * Displays custom vector icon or app icon badge with app label and package name.
 */
@Composable
private fun SpotlightAppRowItem(
  app: AppEntry,
  onClick: () -> Unit,
) {
  val customIconRes = remember(app.packageName) {
    when {
      app.packageName == "com.whatsapp" -> R.drawable.ic_custom_whatsapp
      app.packageName.contains("camera") -> R.drawable.ic_custom_camera
      app.packageName.contains("dialer") -> R.drawable.ic_custom_phone
      app.packageName.contains("paisa") || app.packageName.contains("wallet") -> R.drawable.ic_custom_gpay
      app.packageName.contains("googlequicksearchbox") -> R.drawable.ic_custom_google
      app.packageName.contains("bard") -> R.drawable.ic_custom_gemini
      app.packageName.contains("qwen") -> R.drawable.ic_custom_qwen
      else -> null
    }
  }

  val iconBitmap = remember(app.packageName, app.icon) {
    if (customIconRes == null) app.icon?.toImageBitmapSafe() else null
  }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .background(GlassSurface.copy(alpha = 0.58f))
      .border(0.8.dp, GlassBorderSubtle, RoundedCornerShape(16.dp))
      .clickable { onClick() }
      .padding(horizontal = 12.dp, vertical = 9.dp),
  ) {
    // App Icon Container
    Box(
      modifier = Modifier
        .size(42.dp)
        .clip(RoundedCornerShape(13.dp))
        .background(GlassSurfaceElevated.copy(alpha = 0.75f))
        .border(0.8.dp, GlassBorderHighlight.copy(alpha = 0.35f), RoundedCornerShape(13.dp)),
      contentAlignment = Alignment.Center,
    ) {
      if (customIconRes != null) {
        Icon(
          painter = painterResource(id = customIconRes),
          contentDescription = app.label,
          tint = TextPureWhite,
          modifier = Modifier.size(24.dp),
        )
      } else if (iconBitmap != null) {
        Image(
          bitmap = iconBitmap,
          contentDescription = app.label,
          modifier = Modifier.size(28.dp),
        )
      } else {
        Text(
          text = app.label.take(1).uppercase(),
          color = IceGlowAccent,
          fontSize = 16.sp,
          fontWeight = FontWeight.Bold,
        )
      }
    }

    Spacer(modifier = Modifier.width(14.dp))

    // App Label and Package details
    Column(
      modifier = Modifier.weight(1f)
    ) {
      Text(
        text = app.label,
        color = TextPureWhite,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.SansSerif,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Spacer(modifier = Modifier.height(2.dp))
      Text(
        text = app.packageName,
        color = TextMutedSlate.copy(alpha = 0.8f),
        fontSize = 11.5.sp,
        fontFamily = FontFamily.SansSerif,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }

    // Subtle launch indicator
    Text(
      text = "›",
      color = IceGlowAccent.copy(alpha = 0.65f),
      fontSize = 20.sp,
      fontWeight = FontWeight.Light,
      modifier = Modifier.padding(end = 4.dp),
    )
  }
}

private fun Drawable.toImageBitmapSafe(): ImageBitmap? {
  return try {
    if (this is BitmapDrawable && bitmap != null) {
      return bitmap.asImageBitmap()
    }
    val w = intrinsicWidth.coerceAtLeast(1).coerceAtMost(192)
    val h = intrinsicHeight.coerceAtLeast(1).coerceAtMost(192)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    bmp.asImageBitmap()
  } catch (_: Exception) {
    null
  }
}
