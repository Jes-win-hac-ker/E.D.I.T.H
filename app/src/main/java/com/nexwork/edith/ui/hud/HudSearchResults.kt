package com.nexwork.edith.ui.hud

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.model.AppEntry
import com.nexwork.edith.ui.components.AppIconBadge
import com.nexwork.edith.ui.components.filterMatchingApps
import com.nexwork.edith.ui.components.toImageBitmap

/**
 * Slim results window — a narrow vertical pill docked to the right edge,
 * stacking matching app icons in a LazyColumn so only the visible handful
 * compose (a full Column of 60+ liquid-glass icons janks the frame). Rides the
 * IME inset via imePadding so it never overlaps the keyboard.
 */
@Composable
fun HudSearchResults(
  isOpen: Boolean,
  searchQuery: String,
  apps: List<AppEntry>,
  onLaunchApp: (AppEntry) -> Unit,
  modifier: Modifier = Modifier,
) {
  val matchingApps = remember(searchQuery, apps) { filterMatchingApps(searchQuery, apps) }
  val visible = isOpen && searchQuery.trim().isNotEmpty() && matchingApps.isNotEmpty()

  AnimatedVisibility(
    visible = visible,
    enter = slideInHorizontally(tween(240)) { it } + fadeIn(tween(220)),
    exit = slideOutHorizontally(tween(180)) { it } + fadeOut(tween(140)),
    modifier = modifier,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      modifier = Modifier
        .fillMaxHeight(0.52f)
        .width(78.dp)
        .border(1.5.dp, Color.White, RoundedCornerShape(39.dp))
        .padding(vertical = 10.dp),
    ) {
      LazyColumn(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Top),
        contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        items(matchingApps.take(40), key = { it.packageName }) { app ->
          val bmp = remember(app.packageName, app.icon) { app.icon?.toImageBitmap() }
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
              .width(62.dp)
              .clickable { onLaunchApp(app) },
          ) {
            AppIconBadge(
              label = app.label,
              packageName = app.packageName,
              icon = app.icon,
              iconBitmap = bmp,
              isMotoApp = app.isMotoApp,
              size = 40.dp,
            )
            Text(
              text = app.label,
              color = Color.White.copy(alpha = 0.75f),
              fontSize = 7.5.sp,
              fontFamily = FontFamily.Monospace,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              textAlign = TextAlign.Center,
              modifier = Modifier.padding(top = 3.dp),
            )
          }
        }
      }
    }
  }
}
