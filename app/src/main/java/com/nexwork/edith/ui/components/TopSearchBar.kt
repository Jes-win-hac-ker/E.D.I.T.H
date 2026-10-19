package com.nexwork.edith.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.SolidColor
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
import com.nexwork.edith.theme.GlassBorderHighlight
import com.nexwork.edith.theme.GlassSurfaceElevated
import com.nexwork.edith.theme.IceGlowAccent
import com.nexwork.edith.theme.TextMutedSlate
import com.nexwork.edith.theme.TextPureWhite
import com.nexwork.edith.theme.TextSilver
import kotlinx.coroutines.delay

/**
 * Top floating search bar triggered by downward swipe on the home screen:
 * - Full width pill layout without cancel button.
 * - Auto-dismisses the search bar after 1 second of inactivity if nothing is typed.
 * - Grants complete immunity to slide typing: while the user is gesture/slide-typing
 *   (composition != null), auto-dismissal is completely inhibited.
 * - Clicking or typing in the search bar resets the inactivity timer.
 */
@Composable
fun TopSearchBar(
  isOpen: Boolean,
  searchQuery: String,
  onSearchQueryChange: (String) -> Unit,
  onDismiss: () -> Unit,
  onSearch: ((String) -> Unit)? = null,
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

  // Auto-focus and open keyboard after slide-in begins
  LaunchedEffect(isOpen) {
    if (isOpen) {
      delay(180L)
      focusRequester.requestFocus()
      keyboardController?.show()
      lastInteractionTimestamp = System.currentTimeMillis()
    }
  }

  // Auto-dismiss the search bar after 2 seconds of inactivity if nothing is typed,
  // granting complete immunity to slide typing (composition != null)
  LaunchedEffect(isOpen, textFieldValue, lastInteractionTimestamp) {
    if (!isOpen) return@LaunchedEffect

    val isSlideTyping = textFieldValue.composition != null
    val isTextEmpty = textFieldValue.text.isEmpty()

    // 1. Slide typing immunity: if slide/gesture typing is active, DO NOT auto-dismiss
    if (isSlideTyping) {
      return@LaunchedEffect
    }

    // 2. Dismiss the searchbar in this scenario (nothing typed on the search bar)
    if (isTextEmpty) {
      delay(2000L) // 2 seconds of inactivity with nothing typed
      // Verify condition still holds after the 2 second delay
      if (isOpen && textFieldValue.text.isEmpty() && textFieldValue.composition == null) {
        keyboardController?.hide()
        focusManager.clearFocus()
        onDismiss()
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
      .statusBarsPadding()
      .displayCutoutPadding(),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
      // Liquid glass pill container
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
    }
  }
}
