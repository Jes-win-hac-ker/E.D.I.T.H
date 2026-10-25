package com.nexwork.edith.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.model.AppEntry
import com.nexwork.edith.model.DeviceTelemetry
import com.nexwork.edith.theme.GlassBorderHighlight
import com.nexwork.edith.theme.GlassBorderSubtle
import com.nexwork.edith.theme.GlassSurface
import com.nexwork.edith.theme.GlassSurfaceElevated
import com.nexwork.edith.theme.IceGlowAccent
import com.nexwork.edith.theme.TextMutedSlate
import com.nexwork.edith.theme.TextPureWhite
import com.nexwork.edith.theme.TextSilver
import com.nexwork.edith.ui.hud.HudPanel

/**
 * Top-left sleek glass section (padded away from the top edge):
 * - Displays subtle status/action pills (Set Default Home / 14-Day Usage Access if needed)
 * - Displays top most-used apps from the past 14 days in a floating frosted glass card.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LeftTopMostUsedSection(
  mostUsedApps: List<AppEntry>,
  telemetry: DeviceTelemetry,
  isDefaultLauncher: Boolean,
  onAppClick: (AppEntry) -> Unit,
  onTogglePinApp: (AppEntry) -> Unit,
  onAppInfoClick: (AppEntry) -> Unit,
  onUninstallClick: (AppEntry) -> Unit,
  onSetDefaultLauncher: () -> Unit,
  onGrantUsageAccess: () -> Unit,
  railPackages: List<String> = emptyList(),
  onToggleRail: (AppEntry) -> Unit = {},
  notificationCounts: Map<String, Int> = emptyMap(),
  hasNotificationAccess: Boolean = true,
  onGrantNotificationAccess: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    // Subtle action pills row if either Default Launcher or 14-day Usage Stats isn't enabled yet
    if (!isDefaultLauncher || !telemetry.hasUsageAccess || !hasNotificationAccess) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (!isDefaultLauncher) {
          Box(
            modifier =
              Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(IceGlowAccent.copy(alpha = 0.16f))
                .border(0.8.dp, IceGlowAccent.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .clickable { onSetDefaultLauncher() }
                .padding(horizontal = 10.dp, vertical = 5.dp)
          ) {
            Text(
              text = "Set Default Home",
              color = IceGlowAccent,
              fontSize = 10.sp,
              fontWeight = FontWeight.Medium,
            )
          }
        }

        if (!telemetry.hasUsageAccess) {
          Box(
            modifier =
              Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(GlassSurfaceElevated.copy(alpha = 0.7f))
                .border(0.8.dp, GlassBorderSubtle, RoundedCornerShape(12.dp))
                .clickable { onGrantUsageAccess() }
                .padding(horizontal = 10.dp, vertical = 5.dp)
          ) {
            Text(
              text = "Enable 14d Usage Sync",
              color = TextSilver,
              fontSize = 10.sp,
              fontWeight = FontWeight.Medium,
            )
          }
        }

        if (!hasNotificationAccess) {
          Box(
            modifier =
              Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(GlassSurfaceElevated.copy(alpha = 0.7f))
                .border(0.8.dp, GlassBorderSubtle, RoundedCornerShape(12.dp))
                .clickable { onGrantNotificationAccess() }
                .padding(horizontal = 10.dp, vertical = 5.dp)
          ) {
            Text(
              text = "Enable Badges",
              color = TextSilver,
              fontSize = 10.sp,
              fontWeight = FontWeight.Medium,
            )
          }
        }
      }
    }

    // Floating Frosted Glass Card for 14-Day Most Used Apps
    if (mostUsedApps.isNotEmpty()) {
      HudPanel(
        title = "FREQ//14D",
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
          horizontalArrangement = Arrangement.SpaceEvenly,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          mostUsedApps.take(4).forEach { app ->
            var menuExpanded by remember { mutableStateOf(false) }
            val iconBmp = remember(app.packageName, app.icon) { app.icon?.toImageBitmap() }

            Box(modifier = Modifier.weight(1f)) {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                  Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .combinedClickable(
                      onClick = { onAppClick(app) },
                      onLongClick = { menuExpanded = true },
                    )
                    .padding(vertical = 4.dp, horizontal = 2.dp),
              ) {
                Box {
                  AppIconBadge(
                    label = app.label,
                    packageName = app.packageName,
                    icon = app.icon,
                    iconBitmap = iconBmp,
                    isMotoApp = app.isMotoApp,
                    size = 44.dp,
                  )
                  if ((notificationCounts[app.packageName] ?: 0) > 0) {
                    NotificationBadgeDot(
                      modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp),
                    )
                  }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = app.label,
                  color = TextPureWhite,
                  fontSize = 10.sp,
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
                isOnRail = app.packageName in railPackages,
                onToggleRail = { onToggleRail(app) },
              )
            }
          }
        }
      }
    }
  }
}

/**
 * Bottom-left Quick Access Dock (padded away from the bottom edge):
 * Houses essential quick-access apps (Phone, GPay, Camera, Messages/Pinned).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LeftBottomQuickAccessDock(
  essentialApps: List<AppEntry>,
  onAppClick: (AppEntry) -> Unit,
  onTogglePinApp: (AppEntry) -> Unit,
  onAppInfoClick: (AppEntry) -> Unit,
  onUninstallClick: (AppEntry) -> Unit,
  railPackages: List<String> = emptyList(),
  onToggleRail: (AppEntry) -> Unit = {},
  notificationCounts: Map<String, Int> = emptyMap(),
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(
      text = "QUICK ACCESS",
      color = TextMutedSlate,
      fontSize = 9.5.sp,
      fontWeight = FontWeight.SemiBold,
      letterSpacing = 1.sp,
      modifier = Modifier.padding(start = 6.dp),
    )

    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(22.dp))
          .background(GlassSurfaceElevated.copy(alpha = 0.62f))
          .border(1.dp, GlassBorderHighlight.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
          .padding(horizontal = 10.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      essentialApps.take(4).forEach { app ->
        var menuExpanded by remember { mutableStateOf(false) }
        val iconBmp = remember(app.packageName, app.icon) { app.icon?.toImageBitmap() }

        Box(modifier = Modifier.weight(1f)) {
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
                .padding(vertical = 4.dp, horizontal = 2.dp),
          ) {
            Box {
              AppIconBadge(
                label = app.label,
                packageName = app.packageName,
                icon = app.icon,
                iconBitmap = iconBmp,
                isMotoApp = app.isMotoApp,
                size = 46.dp,
              )
              if ((notificationCounts[app.packageName] ?: 0) > 0) {
                NotificationBadgeDot(
                  modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp),
                )
              }
            }
            Spacer(modifier = Modifier.height(5.dp))
            Text(
              text = app.label,
              color = TextPureWhite,
              fontSize = 10.5.sp,
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
            onTogglePin = { onTogglePinApp(app) },
            onAppInfo = { onAppInfoClick(app) },
            onUninstall = { onUninstallClick(app) },
            isOnRail = app.packageName in railPackages,
            onToggleRail = { onToggleRail(app) },
          )
        }
      }
    }
  }
}
