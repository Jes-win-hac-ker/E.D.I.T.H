package com.nexwork.edith.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.R
import com.nexwork.edith.theme.GlassBorderHighlight
import com.nexwork.edith.theme.GlassSurfaceElevated
import com.nexwork.edith.theme.IceGlowAccent
import com.nexwork.edith.theme.TextMutedSlate
import com.nexwork.edith.theme.TextPureWhite
import com.nexwork.edith.theme.TextSilver
import kotlinx.coroutines.delay

/**
 * Top floating search bar triggered by downward swipe on the home screen.
 * Displays directly at the top of the screen over the grid without full-screen overlay or app results.
 */
@Composable
fun TopSearchBar(
  isOpen: Boolean,
  searchQuery: String,
  onSearchQueryChange: (String) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (!isOpen) return

  BackHandler(enabled = isOpen) {
    onDismiss()
  }

  val focusRequester = remember { FocusRequester() }
  val keyboardController = LocalSoftwareKeyboardController.current

  LaunchedEffect(isOpen) {
    if (isOpen) {
      delay(100L)
      focusRequester.requestFocus()
      keyboardController?.show()
    }
  }

  AnimatedVisibility(
    visible = isOpen,
    enter = fadeIn() + slideInVertically { -it },
    exit = fadeOut() + slideOutVertically { -it },
    modifier = modifier
      .fillMaxWidth()
      .statusBarsPadding(),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Search input pill
      Box(
        modifier = Modifier
          .weight(1f)
          .clip(RoundedCornerShape(22.dp))
          .background(GlassSurfaceElevated.copy(alpha = 0.92f))
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
            modifier = Modifier.size(19.dp),
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
                keyboardController?.hide()
              }
            ),
            modifier = Modifier
              .weight(1f)
              .focusRequester(focusRequester),
            decorationBox = { innerTextField ->
              if (searchQuery.isEmpty()) {
                Text(
                  text = "Search...",
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
  }
}
