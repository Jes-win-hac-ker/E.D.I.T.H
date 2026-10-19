package com.nexwork.edith.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.model.AppEntry
import com.nexwork.edith.theme.DangerRose
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
import kotlin.math.hypot
import kotlinx.coroutines.launch

/**
 * Sleek Dark Glass Right Rail:
 * - Separated by a delicate translucent glass divider line that fades out at top and bottom.
 * - Medium vertical padding (88.dp top & bottom) so apps never touch the top or bottom edges.
 * - Corner swipe zones (top-right and bottom-right corners) that progressively blur the home screen
 *   and unlock the Full App List Glass Overlay.
 */
@Composable
fun AppListRail(
  quickRailApps: List<AppEntry>,
  onAppClick: (AppEntry) -> Unit,
  onTogglePinApp: (AppEntry) -> Unit,
  onAppInfoClick: (AppEntry) -> Unit,
  onUninstallClick: (AppEntry) -> Unit,
  onCornerSwipeProgress: (Float) -> Unit,
  onCornerSwipeFinish: (Boolean) -> Unit,
  onUnlockFullAppList: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(modifier = modifier.fillMaxHeight()) {
    // Sleek 1dp translucent gradient divider (replaces the raw 3dp sketch line)
    Box(
      modifier =
        Modifier
          .fillMaxHeight()
          .padding(vertical = 48.dp)
          .width(1.dp)
          .background(
            Brush.verticalGradient(
              colors =
                listOf(
                  Color.Transparent,
                  GlassBorderHighlight,
                  IceGlowAccent.copy(alpha = 0.45f),
                  GlassBorderHighlight,
                  Color.Transparent,
                )
            )
          )
    )

    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .statusBarsPadding()
          .navigationBarsPadding(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.SpaceBetween,
    ) {
      // Top-Right Corner Swipe Zone (medium top spacing before apps start)
      CornerUnlockTrigger(
        label = "APPS",
        isTopCorner = true,
        onSwipeProgress = onCornerSwipeProgress,
        onSwipeFinish = onCornerSwipeFinish,
        onTap = onUnlockFullAppList,
        modifier =
          Modifier
            .fillMaxWidth()
            .height(78.dp),
      )

      // Center Vertical Strip of Quick/Frequent Apps (padded away from top and bottom)
      LazyColumn(
        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
          Modifier
            .weight(1f)
            .fillMaxWidth(),
      ) {
        items(
          items = quickRailApps,
          key = { "${it.packageName}/${it.activityName}" },
        ) { app ->
          GlassRailAppItem(
            app = app,
            onClick = { onAppClick(app) },
            onTogglePin = { onTogglePinApp(app) },
            onAppInfo = { onAppInfoClick(app) },
            onUninstall = { onUninstallClick(app) },
          )
        }
      }

      // Bottom-Right Corner Swipe Zone (primary one-handed corner swipe to unlock full App List)
      CornerUnlockTrigger(
        label = "SWIPE ↖",
        isTopCorner = false,
        onSwipeProgress = onCornerSwipeProgress,
        onSwipeFinish = onCornerSwipeFinish,
        onTap = onUnlockFullAppList,
        modifier =
          Modifier
            .fillMaxWidth()
            .height(84.dp),
      )
    }
  }
}

/**
 * Corner gesture area that tracks diagonal/inward swipes from the corner to unlock the full App List
 * while progressively blurring the background screen.
 */
@Composable
private fun CornerUnlockTrigger(
  label: String,
  isTopCorner: Boolean,
  onSwipeProgress: (Float) -> Unit,
  onSwipeFinish: (Boolean) -> Unit,
  onTap: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var dragDistance by remember { mutableStateOf(0f) }

  Box(
    modifier =
      modifier
        .pointerInput(isTopCorner) {
          detectDragGestures(
            onDragStart = {
              dragDistance = 0f
              onSwipeProgress(0.08f)
            },
            onDrag = { change, dragAmount ->
              change.consume()
              // Swiping inward (left) or away from the corner increases unlock progress
              val inwardX = -dragAmount.x
              val inwardY = if (isTopCorner) dragAmount.y else -dragAmount.y
              val delta =
                if (inwardX > 0f || inwardY > 0f) {
                  hypot(inwardX.coerceAtLeast(0f), inwardY.coerceAtLeast(0f))
                } else {
                  -hypot((-inwardX).coerceAtLeast(0f), (-inwardY).coerceAtLeast(0f)) * 0.5f
                }
              dragDistance = (dragDistance + delta).coerceAtLeast(0f)
              val progress = (dragDistance / 160f).coerceIn(0f, 1f)
              onSwipeProgress(progress)
            },
            onDragEnd = {
              val shouldUnlock = dragDistance >= 65f
              dragDistance = 0f
              onSwipeFinish(shouldUnlock)
            },
            onDragCancel = {
              dragDistance = 0f
              onSwipeFinish(false)
            },
          )
        }
        .clickable { onTap() },
    contentAlignment = Alignment.Center,
  ) {
    // Minimalist frosted glass corner pill indicator
    Box(
      modifier =
        Modifier
          .padding(horizontal = 10.dp)
          .clip(RoundedCornerShape(16.dp))
          .background(GlassSurfaceElevated.copy(alpha = 0.55f))
          .border(0.8.dp, GlassBorderSubtle, RoundedCornerShape(16.dp))
          .padding(horizontal = 10.dp, vertical = 6.dp),
      contentAlignment = Alignment.Center,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Box(
          modifier =
            Modifier
              .size(5.dp)
              .clip(CircleShape)
              .background(IceGlowAccent.copy(alpha = 0.8f))
        )
        Text(
          text = label,
          color = TextSilver,
          fontSize = 9.sp,
          fontWeight = FontWeight.Medium,
          letterSpacing = 0.8.sp,
        )
      }
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GlassRailAppItem(
  app: AppEntry,
  onClick: () -> Unit,
  onTogglePin: () -> Unit,
  onAppInfo: () -> Unit,
  onUninstall: () -> Unit,
) {
  var menuExpanded by remember { mutableStateOf(false) }
  val iconBitmap = remember(app.packageName, app.icon) { app.icon?.toImageBitmap() }

  Box {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier =
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(14.dp))
          .background(GlassSurface.copy(alpha = 0.48f))
          .border(0.8.dp, GlassBorderSubtle, RoundedCornerShape(14.dp))
          .combinedClickable(
            onClick = onClick,
            onLongClick = { menuExpanded = true },
          )
          .padding(horizontal = 6.dp, vertical = 8.dp),
    ) {
      AppIconBadge(
        label = app.label,
        iconBitmap = iconBitmap,
        isMotoApp = app.isMotoApp,
        modifier = Modifier.size(36.dp),
      )
      Spacer(modifier = Modifier.height(5.dp))
      Text(
        text = app.label,
        color = TextPureWhite,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
      )
    }

    AppContextDropdown(
      app = app,
      expanded = menuExpanded,
      onDismiss = { menuExpanded = false },
      onTogglePin = onTogglePin,
      onAppInfo = onAppInfo,
      onUninstall = onUninstall,
    )
  }
}

/**
 * Full-Screen Dark Glass App List Overlay unlocked via corner swipe (while the underlying
 * home screen is blurred).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FullAppListGlassOverlay(
  apps: List<AppEntry>,
  recentMostUsedApps: List<AppEntry>,
  searchQuery: String,
  onSearchQueryChange: (String) -> Unit,
  onAppClick: (AppEntry) -> Unit,
  onTogglePinApp: (AppEntry) -> Unit,
  onAppInfoClick: (AppEntry) -> Unit,
  onUninstallClick: (AppEntry) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val gridState = rememberLazyGridState()
  val coroutineScope = rememberCoroutineScope()

  val filteredApps =
    remember(apps, searchQuery) {
      if (searchQuery.isBlank()) apps
      else {
        apps.filter {
          it.label.contains(searchQuery.trim(), ignoreCase = true) ||
            it.packageName.contains(searchQuery.trim(), ignoreCase = true)
        }
      }
    }

  val initials =
    remember(filteredApps) {
      filteredApps.map { it.initial }.distinct()
    }

  Box(
    modifier =
      modifier
        .fillMaxSize()
        .background(
          Brush.verticalGradient(
            colors =
              listOf(
                ObsidianBgTop.copy(alpha = 0.86f),
                ObsidianBgBottom.copy(alpha = 0.92f),
              )
          )
        )
        .statusBarsPadding()
        .navigationBarsPadding()
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxSize()
          .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
      // Top Search & Close Header
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Box(
          modifier =
            Modifier
              .weight(1f)
              .clip(RoundedCornerShape(16.dp))
              .background(GlassSurfaceElevated.copy(alpha = 0.85f))
              .border(1.dp, GlassBorderHighlight, RoundedCornerShape(16.dp))
              .padding(horizontal = 14.dp, vertical = 11.dp)
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = "⌕",
              color = IceGlowAccent,
              fontSize = 15.sp,
              modifier = Modifier.padding(end = 8.dp),
            )
            BasicTextField(
              value = searchQuery,
              onValueChange = onSearchQueryChange,
              singleLine = true,
              textStyle =
                TextStyle(
                  color = TextPureWhite,
                  fontSize = 14.sp,
                ),
              cursorBrush = SolidColor(IceGlowAccent),
              modifier = Modifier.weight(1f),
              decorationBox = { innerTextField ->
                if (searchQuery.isEmpty()) {
                  Text(
                    text = "Search ${apps.size} apps...",
                    color = TextMutedSlate,
                    fontSize = 13.5.sp,
                  )
                }
                innerTextField()
              },
            )
            if (searchQuery.isNotEmpty()) {
              Text(
                text = "✕",
                color = TextSilver,
                fontSize = 13.sp,
                modifier =
                  Modifier
                    .padding(start = 8.dp)
                    .clickable { onSearchQueryChange("") },
              )
            }
          }
        }

        // Close overlay button
        Box(
          modifier =
            Modifier
              .clip(RoundedCornerShape(14.dp))
              .background(GlassSurfaceElevated.copy(alpha = 0.75f))
              .border(1.dp, GlassBorderSubtle, RoundedCornerShape(14.dp))
              .clickable { onDismiss() }
              .padding(horizontal = 13.dp, vertical = 11.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = "CLOSE",
            color = TextSilver,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
          )
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // Past 14 Days Most Used Highlight Strip (when not searching)
      if (searchQuery.isBlank() && recentMostUsedApps.isNotEmpty()) {
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(18.dp))
              .background(GlassSurface.copy(alpha = 0.65f))
              .border(0.8.dp, GlassBorderSubtle, RoundedCornerShape(18.dp))
              .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
          Text(
            text = "MOST USED · PAST 14 DAYS",
            color = IceGlowAccent,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
          )
          Spacer(modifier = Modifier.height(6.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
          ) {
            recentMostUsedApps.take(5).forEach { app ->
              val bmp = remember(app.packageName, app.icon) { app.icon?.toImageBitmap() }
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                  Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onAppClick(app) }
                    .padding(vertical = 4.dp, horizontal = 2.dp),
              ) {
                AppIconBadge(
                  label = app.label,
                  iconBitmap = bmp,
                  isMotoApp = app.isMotoApp,
                  modifier = Modifier.size(40.dp),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = app.label,
                  color = TextPureWhite,
                  fontSize = 10.5.sp,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }
        }
        Spacer(modifier = Modifier.height(12.dp))
      }

      // Main 4-Column App Grid + Right-Edge A-Z Fast Scrubber
      Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
        LazyVerticalGrid(
          columns = GridCells.Fixed(4),
          state = gridState,
          contentPadding = PaddingValues(vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.weight(1f).fillMaxHeight(),
        ) {
          items(
            items = filteredApps,
            key = { "${it.packageName}/${it.activityName}" },
          ) { app ->
            var menuExpanded by remember { mutableStateOf(false) }
            val bmp = remember(app.packageName, app.icon) { app.icon?.toImageBitmap() }

            Box {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .combinedClickable(
                      onClick = { onAppClick(app) },
                      onLongClick = { menuExpanded = true },
                    )
                    .padding(vertical = 6.dp, horizontal = 4.dp),
              ) {
                AppIconBadge(
                  label = app.label,
                  iconBitmap = bmp,
                  isMotoApp = app.isMotoApp,
                  modifier = Modifier.size(46.dp),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                  text = app.label,
                  color = TextPureWhite,
                  fontSize = 11.5.sp,
                  fontWeight = FontWeight.Normal,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  textAlign = TextAlign.Center,
                )
              }

              AppContextDropdown(
                app = app,
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                onTogglePin = { onTogglePinApp(app) },
                onAppInfo = { onAppInfoClick(app) },
                onUninstall = { onUninstallClick(app) },
              )
            }
          }
        }

        if (initials.size > 2) {
          AlphabetScrubber(
            initials = initials,
            onSelectInitial = { ch ->
              val idx = filteredApps.indexOfFirst { it.initial == ch }
              if (idx >= 0) {
                coroutineScope.launch { gridState.scrollToItem(idx) }
              }
            },
            modifier =
              Modifier
                .fillMaxHeight()
                .width(20.dp)
                .padding(vertical = 8.dp),
          )
        }
      }
    }
  }
}

@Composable
fun AppContextDropdown(
  app: AppEntry,
  expanded: Boolean,
  onDismiss: () -> Unit,
  onTogglePin: () -> Unit,
  onAppInfo: () -> Unit,
  onUninstall: () -> Unit,
) {
  DropdownMenu(
    expanded = expanded,
    onDismissRequest = onDismiss,
    modifier = Modifier.background(GlassSurfaceElevated),
  ) {
    DropdownMenuItem(
      text = {
        Text(
          text = if (app.isPinned) "Remove from Quick Access" else "Pin to Quick Access",
          color = TextPureWhite,
          fontSize = 12.sp,
        )
      },
      onClick = {
        onDismiss()
        onTogglePin()
      },
    )
    DropdownMenuItem(
      text = { Text(text = "App Info", color = TextPureWhite, fontSize = 12.sp) },
      onClick = {
        onDismiss()
        onAppInfo()
      },
    )
    if (!app.isSystemApp) {
      DropdownMenuItem(
        text = { Text(text = "Uninstall", color = DangerRose, fontSize = 12.sp) },
        onClick = {
          onDismiss()
          onUninstall()
        },
      )
    }
  }
}

@Composable
fun AppIconBadge(
  label: String,
  iconBitmap: androidx.compose.ui.graphics.ImageBitmap?,
  isMotoApp: Boolean,
  modifier: Modifier = Modifier,
) {
  if (iconBitmap != null) {
    Image(
      bitmap = iconBitmap,
      contentDescription = label,
      modifier = modifier.clip(CircleShape),
    )
  } else {
    Surface(
      color = GlassSurfaceElevated,
      shape = CircleShape,
      modifier =
        modifier.border(
          width = 1.dp,
          color = if (isMotoApp) IceGlowAccent.copy(alpha = 0.6f) else GlassBorderHighlight,
          shape = CircleShape,
        ),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Text(
          text = label.take(2).uppercase(),
          color = if (isMotoApp) IceGlowAccent else TextPureWhite,
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
        )
      }
    }
  }
}

@Composable
private fun AlphabetScrubber(
  initials: List<Char>,
  onSelectInitial: (Char) -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier.pointerInput(initials) {
        detectVerticalDragGestures { change, _ ->
          change.consume()
          if (size.height > 0 && initials.isNotEmpty()) {
            val fraction = (change.position.y / size.height.toFloat()).coerceIn(0f, 0.999f)
            val idx = (fraction * initials.size).toInt().coerceIn(0, initials.lastIndex)
            onSelectInitial(initials[idx])
          }
        }
      },
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.SpaceEvenly,
  ) {
    initials.forEach { ch ->
      Text(
        text = ch.toString(),
        color = TextSilver,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clickable { onSelectInitial(ch) },
      )
    }
  }
}

fun Drawable.toImageBitmap(): androidx.compose.ui.graphics.ImageBitmap? {
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
