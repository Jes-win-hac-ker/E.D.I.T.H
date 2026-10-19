package com.nexwork.edith.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.R
import com.nexwork.edith.model.AppEntry
import com.nexwork.edith.theme.IceGlowAccent
import com.nexwork.edith.theme.TextPureWhite
import kotlinx.coroutines.delay

/**
 * Filters apps matching the search query by label or packageName,
 * ordered with most-used apps first (by 14-day foreground usage, essential status, and pinned status).
 */
fun filterMatchingApps(query: String, apps: List<AppEntry>): List<AppEntry> {
  val q = query.trim()
  if (q.isEmpty()) return emptyList()
  return apps.filter {
    it.label.contains(q, ignoreCase = true) ||
      it.packageName.contains(q, ignoreCase = true)
  }.sortedWith(
    compareByDescending<AppEntry> { it.usageTimeMillis14d }
      .thenByDescending { it.isEssentialQuickAccess }
      .thenByDescending { it.isPinned }
      .thenBy { it.label.lowercase() }
  )
}

/**
 * Top floating search bar triggered by downward swipe on the home screen:
 * - Liquid glass pill layout with slide-in physics animation.
 * - Matching apps appear in a horizontal liquid glass tab directly below the search bar (icons only).
 * - Auto-launches if there is only 1 matching app (after 320ms).
 * - Auto-launches the most used app after 3s of inactivity if multiple apps match.
 * - Auto-dismisses keyboard after 1s of inactivity if multiple apps match.
 * - Auto-dismisses search bar after 2s of inactivity if query is empty.
 * - Complete immunity to slide/gesture typing (composition != null).
 */
@Composable
fun TopSearchBar(
  isOpen: Boolean,
  searchQuery: String,
  onSearchQueryChange: (String) -> Unit,
  onDismiss: () -> Unit,
  apps: List<AppEntry> = emptyList(),
  onLaunchApp: (AppEntry) -> Unit = {},
  onSearch: ((String) -> Unit)? = null,
  singleAppAutoLaunchDelayMs: Long = 320L,
  multipleAppsAutoLaunchDelayMs: Long = 3000L,
  modifier: Modifier = Modifier,
) {
  BackHandler(enabled = isOpen) {
    onDismiss()
  }

  val focusRequester = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current

  var textFieldValue by remember {
    mutableStateOf(TextFieldValue(text = searchQuery, selection = TextRange(searchQuery.length)))
  }

  // Synchronize external query changes (e.g. on reset/clear)
  LaunchedEffect(searchQuery) {
    if (searchQuery != textFieldValue.text) {
      textFieldValue = TextFieldValue(text = searchQuery, selection = TextRange(searchQuery.length))
    }
  }

  var isFocused by remember { mutableStateOf(false) }
  var lastInteractionTimestamp by remember { mutableLongStateOf(0L) }

  // Filter matching apps based on current query (sorted by most-used first)
  val matchingApps = remember(searchQuery, apps) {
    filterMatchingApps(searchQuery, apps)
  }

  // Auto-focus and open keyboard after slide-in begins
  LaunchedEffect(isOpen) {
    if (isOpen) {
      delay(180L)
      focusRequester.requestFocus()
      keyboardController?.show()
      lastInteractionTimestamp = System.currentTimeMillis()
    }
  }

  // Auto-launch if exactly 1 matching app is found (with slide typing immunity)
  LaunchedEffect(searchQuery, matchingApps.size, textFieldValue.composition) {
    val q = searchQuery.trim()
    if (q.isNotEmpty() && matchingApps.size == 1 && textFieldValue.composition == null) {
      delay(singleAppAutoLaunchDelayMs)
      if (isOpen && searchQuery.trim().isNotEmpty() && matchingApps.size == 1 && textFieldValue.composition == null) {
        onLaunchApp(matchingApps.first())
        onDismiss()
      }
    }
  }

  // Auto-launch the most used app after 3 seconds of inactivity if multiple apps match
  LaunchedEffect(
    isOpen,
    searchQuery,
    matchingApps.size,
    textFieldValue.composition,
    lastInteractionTimestamp,
  ) {
    if (!isOpen) return@LaunchedEffect

    val isSlideTyping = textFieldValue.composition != null
    if (isSlideTyping) return@LaunchedEffect

    val q = searchQuery.trim()
    if (q.isNotEmpty() && matchingApps.size > 1) {
      val targetApp = matchingApps.first()
      delay(multipleAppsAutoLaunchDelayMs)
      if (isOpen && searchQuery.trim().isNotEmpty() && matchingApps.size > 1 && textFieldValue.composition == null) {
        onLaunchApp(targetApp)
        onDismiss()
      }
    }
  }

  // Inactivity tracking:
  // - 2 seconds inactivity when query is empty -> auto-dismiss search bar
  // - 1 second inactivity when multiple apps match -> auto-dismiss keyboard
  // - Slide typing immunity: active composition inhibits auto-actions
  LaunchedEffect(isOpen, textFieldValue, lastInteractionTimestamp, matchingApps.size) {
    if (!isOpen) return@LaunchedEffect

    val isSlideTyping = textFieldValue.composition != null
    if (isSlideTyping) {
      return@LaunchedEffect
    }

    val isTextEmpty = textFieldValue.text.isEmpty()

    if (isTextEmpty) {
      delay(2000L)
      if (isOpen && textFieldValue.text.isEmpty() && textFieldValue.composition == null) {
        keyboardController?.hide()
        focusManager.clearFocus()
        onDismiss()
      }
    } else if (matchingApps.size > 1) {
      delay(1000L)
      if (isOpen && textFieldValue.text.isNotEmpty() && textFieldValue.composition == null) {
        keyboardController?.hide()
        focusManager.clearFocus()
      }
    }
  }

  AnimatedVisibility(
    visible = isOpen,
    enter = slideInVertically(
      animationSpec = spring(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessMediumLow,
      )
    ) { -it - 150 } + fadeIn(animationSpec = tween(240)),
    exit = slideOutVertically(
      animationSpec = tween(durationMillis = 220, easing = FastOutLinearInEasing)
    ) { -it - 150 } + fadeOut(animationSpec = tween(180)),
    modifier = modifier
      .fillMaxWidth()
      .displayCutoutPadding(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 14.dp),
    ) {
      // 1. Search Bar Liquid Glass Pill Container
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(26.dp))
          .background(
            Brush.linearGradient(
              colors = listOf(
                Color(0x38FFFFFF),
                Color(0x14FFFFFF),
                Color(0x0E0F172A),
                Color(0x2438BDF8),
              ),
              start = Offset(0f, 0f),
              end = Offset.Infinite,
            )
          )
          .clickable {
            focusRequester.requestFocus()
            keyboardController?.show()
            lastInteractionTimestamp = System.currentTimeMillis()
          }
      ) {
        // Specular meniscus highlight at top of liquid glass
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(
              Brush.verticalGradient(
                colors = listOf(
                  Color(0x52FFFFFF),
                  Color(0x18FFFFFF),
                  Color.Transparent,
                )
              )
            )
        )

        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 13.dp),
        ) {
          BasicTextField(
            value = textFieldValue,
            onValueChange = { newVal ->
              lastInteractionTimestamp = System.currentTimeMillis()
              textFieldValue = newVal
              if (newVal.text != searchQuery) {
                onSearchQueryChange(newVal.text)
              }
            },
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
                keyboardController?.hide()
                focusManager.clearFocus()
                onSearch?.invoke(textFieldValue.text)
              }
            ),
            modifier = Modifier
              .weight(1f)
              .focusRequester(focusRequester)
              .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
                if (focusState.isFocused) {
                  lastInteractionTimestamp = System.currentTimeMillis()
                  keyboardController?.show()
                }
              },
            decorationBox = { innerTextField ->
              if (textFieldValue.text.isEmpty()) {
                Text(
                  text = "Search...",
                  color = Color(0x99FFFFFF),
                  fontSize = 15.sp,
                  fontFamily = FontFamily.SansSerif,
                )
              }
              innerTextField()
            },
          )

          if (textFieldValue.text.isNotEmpty()) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
              modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Color(0x26FFFFFF))
                .border(0.8.dp, Color(0x66FFFFFF), CircleShape)
                .clickable {
                  lastInteractionTimestamp = System.currentTimeMillis()
                  textFieldValue = TextFieldValue("")
                  onSearchQueryChange("")
                },
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = "✕",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
              )
            }
          }
        }
      }

      // 2. Liquid Glass Tab directly underneath showing matching apps in horizontal row (only icons)
      AnimatedVisibility(
        visible = searchQuery.trim().isNotEmpty() && matchingApps.isNotEmpty(),
        enter = fadeIn(tween(200)) + expandVertically(tween(220)),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(180)),
      ) {
        Column {
          Spacer(modifier = Modifier.height(10.dp))

          Box(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(22.dp))
              .background(Color(0xF20B0D12))
              .background(
                Brush.linearGradient(
                  colors = listOf(
                    Color(0x28FFFFFF),
                    Color(0x05FFFFFF),
                    Color(0x2038BDF8),
                  ),
                  start = Offset(0f, 0f),
                  end = Offset.Infinite,
                )
              )
              .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                  colors = listOf(
                    Color(0x88FFFFFF),
                    Color(0x3538BDF8),
                    Color(0x15FFFFFF),
                    Color(0x3038BDF8),
                    Color(0x60FFFFFF),
                  ),
                  start = Offset(0f, 0f),
                  end = Offset.Infinite,
                ),
                shape = RoundedCornerShape(22.dp),
              )
          ) {
            // Specular meniscus highlight at top of glass tab
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(
                  Brush.verticalGradient(
                    colors = listOf(
                      Color(0x45FFFFFF),
                      Color(0x12FFFFFF),
                      Color.Transparent,
                    )
                  )
                )
            )

            val lazyListState = rememberLazyListState()
            LaunchedEffect(lazyListState.isScrollInProgress) {
              if (lazyListState.isScrollInProgress) {
                lastInteractionTimestamp = System.currentTimeMillis()
              }
            }

            // Horizontal row with ONLY icons
            LazyRow(
              state = lazyListState,
              horizontalArrangement = Arrangement.spacedBy(14.dp),
              contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              itemsIndexed(matchingApps, key = { _, it -> it.packageName }) { index, app ->
                SearchAppIconItem(
                  app = app,
                  isPrimaryCandidate = (index == 0 && matchingApps.size > 1),
                  onClick = {
                    onLaunchApp(app)
                    onDismiss()
                  },
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
 * Liquid glass squircle icon for horizontal search results:
 * Displays only the icon with the translucent liquid glass aesthetic (no label text).
 * If isPrimaryCandidate is true, highlights the app as the prioritized target that will auto-launch in 3s.
 */
@Composable
private fun SearchAppIconItem(
  app: AppEntry,
  isPrimaryCandidate: Boolean = false,
  onClick: () -> Unit,
) {
  LiquidGlassAppIcon(
    packageName = app.packageName,
    label = app.label,
    icon = app.icon,
    size = 52.dp,
    isPrimaryCandidate = isPrimaryCandidate,
    modifier = Modifier.clickable { onClick() },
  )
}

