package com.nexwork.edith.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.R
import com.nexwork.edith.theme.GlassBorderHighlight
import com.nexwork.edith.theme.GlassBorderSubtle
import com.nexwork.edith.theme.GlassSurface
import com.nexwork.edith.theme.IceGlowAccent
import com.nexwork.edith.theme.TextPureWhite

data class RightRailAppItem(
  val label: String,
  val packageName: String,
  @param:DrawableRes val iconRes: Int,
  val launchFallback: (Context) -> Unit,
)

/**
 * Custom right-side app column positioned in the 4-square right margin:
 * Arranged from bottom to top:
 * - Google (at the bottom)
 * - GPay (above Google)
 * - Phone (above GPay)
 * - Camera (above Phone)
 * - WhatsApp (above Camera)
 */
@Composable
fun RightAppRail(
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current

  // From top to bottom (so Google is at the bottom):
  // Qwen Code -> Gemini -> WhatsApp -> Camera -> Phone -> GPay -> Google
  val apps = listOf(
    RightRailAppItem(
      label = "Qwen Code",
      packageName = "ai.qwenlm.chat.android",
      iconRes = R.drawable.ic_custom_qwen,
      launchFallback = { ctx ->
        launchOrFallback(ctx, "ai.qwenlm.chat.android") {
          launchOrFallback(ctx, "com.tongyi.intl") {
            runCatching {
              ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://chat.qwenlm.ai/"))
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              )
            }
          }
        }
      },
    ),
    RightRailAppItem(
      label = "Gemini",
      packageName = "com.google.android.apps.bard",
      iconRes = R.drawable.ic_custom_gemini,
      launchFallback = { ctx ->
        launchOrFallback(ctx, "com.google.android.apps.bard") {
          launchOrFallback(ctx, "com.google.android.apps.googleassistant") {
            runCatching {
              ctx.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://gemini.google.com/"))
                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              )
            }
          }
        }
      },
    ),
    RightRailAppItem(
      label = "WhatsApp",
      packageName = "com.whatsapp",
      iconRes = R.drawable.ic_custom_whatsapp,
      launchFallback = { ctx ->
        launchOrFallback(ctx, "com.whatsapp") {
          runCatching {
            ctx.startActivity(
              Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
          }
        }
      },
    ),
    RightRailAppItem(
      label = "Camera",
      packageName = "com.motorola.camera3",
      iconRes = R.drawable.ic_custom_camera,
      launchFallback = { ctx ->
        launchOrFallback(ctx, "com.motorola.camera3") {
          runCatching {
            ctx.startActivity(
              Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
          }
        }
      },
    ),
    RightRailAppItem(
      label = "Phone",
      packageName = "com.google.android.dialer",
      iconRes = R.drawable.ic_custom_phone,
      launchFallback = { ctx ->
        launchOrFallback(ctx, "com.google.android.dialer") {
          runCatching {
            ctx.startActivity(
              Intent(Intent.ACTION_DIAL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
          }
        }
      },
    ),
    RightRailAppItem(
      label = "GPay",
      packageName = "com.google.android.apps.nbu.paisa.user",
      iconRes = R.drawable.ic_custom_gpay,
      launchFallback = { ctx ->
        launchOrFallback(ctx, "com.google.android.apps.nbu.paisa.user") {
          launchOrFallback(ctx, "com.google.android.apps.walletnfcrel")
        }
      },
    ),
    RightRailAppItem(
      label = "Google",
      packageName = "com.google.android.googlequicksearchbox",
      iconRes = R.drawable.ic_custom_google,
      launchFallback = { ctx ->
        launchOrFallback(ctx, "com.google.android.googlequicksearchbox") {
          runCatching {
            ctx.startActivity(
              Intent(Intent.ACTION_WEB_SEARCH)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
          }
        }
      },
    ),
  )

  Column(
    modifier =
      modifier
        .fillMaxHeight()
        .statusBarsPadding()
        .navigationBarsPadding()
        .padding(top = 56.dp, start = 8.dp, end = 8.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.Top,
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    apps.forEachIndexed { index, app ->
      CustomAppItem(
        app = app,
        onClick = { app.launchFallback(context) },
      )
      if (index < apps.lastIndex) {
        Spacer(modifier = Modifier.height(12.dp))
      }
    }
  }
}

@Composable
private fun CustomAppItem(
  app: RightRailAppItem,
  onClick: () -> Unit,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier =
      Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .clickable { onClick() }
        .padding(vertical = 4.dp),
  ) {
    // Custom Minimalist Glass Icon Squircle
    Box(
      modifier =
        Modifier
          .size(52.dp)
          .clip(RoundedCornerShape(16.dp))
          .background(GlassSurface.copy(alpha = 0.75f))
          .border(1.dp, GlassBorderHighlight.copy(alpha = 0.35f), RoundedCornerShape(16.dp)),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        painter = painterResource(id = app.iconRes),
        contentDescription = app.label,
        tint = TextPureWhite,
        modifier = Modifier.size(26.dp),
      )
    }

    Spacer(modifier = Modifier.height(5.dp))

    Text(
      text = app.label,
      color = TextPureWhite.copy(alpha = 0.92f),
      fontSize = 11.sp,
      fontWeight = FontWeight.Medium,
      fontFamily = FontFamily.SansSerif,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center,
    )
  }
}

private fun launchOrFallback(
  context: Context,
  packageName: String,
  onFallback: () -> Unit = {},
) {
  val pm = context.packageManager
  val launchIntent = pm.getLaunchIntentForPackage(packageName)
  if (launchIntent != null) {
    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(launchIntent)
  } else {
    onFallback()
  }
}
