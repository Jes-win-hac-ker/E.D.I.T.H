package com.nexwork.edith.ui.hud

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Constraints
import com.nexwork.edith.theme.Crimson
import com.nexwork.edith.theme.CrimsonBright
import com.nexwork.edith.theme.CrimsonDim
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nexwork.edith.model.AppEntry
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Search console — the same 312-unit sheet as `HudAppCard` at 64 tall, so its hairline grid,
 * right column and corner brackets register exactly with the deck below it. It initialises the
 * same way the cards do: the keyline constructs itself with a tracing head, the brackets snap on
 * as it passes each corner, the status label decrypts, the hit count rolls up and the prompt
 * blinks in last. Nothing slides.
 * Logic unchanged:
 *  - tiered label matching via filterMatchingApps
 *  - the deck below owns the pick, and the single-match auto-launch charge with it
 *  - empty query for 4s auto-dismisses
 *  - IME search action launches the top match
 */
@Composable
fun HudSearchBar(
  isOpen: Boolean,
  searchQuery: String,
  onSearchQueryChange: (String) -> Unit,
  onDismiss: () -> Unit,
  apps: List<AppEntry>,
  onLaunchApp: (AppEntry) -> Unit,
  modifier: Modifier = Modifier,
  autoFocus: Boolean = true,
  idleDismiss: Boolean = true,
  scopeLabel: String? = null,
  scopeCount: Int = 0,
  onExitGroup: () -> Unit = {},
) {
  val density = LocalDensity.current
  val focusRequester = remember { FocusRequester() }
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current

  var textFieldValue by remember {
    mutableStateOf(TextFieldValue(text = searchQuery, selection = TextRange(searchQuery.length)))
  }
  LaunchedEffect(searchQuery) {
    if (searchQuery != textFieldValue.text) {
      textFieldValue = TextFieldValue(text = searchQuery, selection = TextRange(searchQuery.length))
    }
  }

  var lastInteractionTimestamp by remember { mutableLongStateOf(0L) }

  // Dismissing clears the query, which would flip the console back to its idle readout
  // mid-retraction, so hold the last typed value until the frame has unwound.
  var frozen by remember { mutableStateOf(TextFieldValue()) }
  LaunchedEffect(isOpen, textFieldValue) {
    if (isOpen) frozen = textFieldValue
  }
  val shownQuery = if (isOpen) searchQuery else frozen.text
  val shownText = if (isOpen) textFieldValue.text else frozen.text

  val matchingApps = remember(shownQuery, apps) {
    filterMatchingApps(shownQuery, apps)
  }

  val typing = shownQuery.trim().isNotEmpty()
  // A drill-down reads the category in the status slot; a query always reports the flat index,
  // because typing searches everything regardless of which level is underneath.
  val scope = scopeLabel?.takeIf { !typing }
  val statusText = when {
    typing -> "QUERY"
    scope != null -> "◀ $scope"
    else -> "STANDBY"
  }
  val countValue = when {
    typing -> matchingApps.size
    scope != null -> scopeCount
    else -> apps.size
  }
  val captionText = when {
    typing -> "HITS"
    scope != null -> "APPS"
    else -> "IN INDEX"
  }

  val statusPaint = remember(density) {
    monoPaint(density, 7.5f, 1.1f, Paint.Align.LEFT, 107)
  }
  val countPaint = remember(density) {
    monoPaint(density, 16f, 0.5f, Paint.Align.LEFT, 230)
  }
  val captionPaint = remember(density) {
    monoPaint(density, 7.5f, 1.1f, Paint.Align.LEFT, 107)
  }
  val promptPaint = remember(density) {
    monoPaint(density, 18f, 0f, Paint.Align.CENTER, 46)
  }
  val bgBrush = remember { Brush.verticalGradient(listOf(Color(0xFF050505), Color(0xFF111111))) }

  val revealAnim = remember { Animatable(0f) }
  var mounted by remember { mutableStateOf(isOpen) }
  LaunchedEffect(isOpen) {
    if (isOpen) {
      mounted = true
      revealAnim.animateTo(1f, tween(1200, easing = LinearEasing))
    } else {
      revealAnim.animateTo(0f, tween(1000, easing = LinearEasing))
      mounted = false
    }
  }

  LaunchedEffect(isOpen) {
    if (isOpen && autoFocus) {
      delay(160L)
      focusRequester.requestFocus()
      keyboardController?.show()
      lastInteractionTimestamp = System.currentTimeMillis()
    }
  }

  // Empty query 4s -> dismiss; multi-match idle 1s -> hide keyboard
  LaunchedEffect(isOpen, textFieldValue, lastInteractionTimestamp, matchingApps.size) {
    if (!isOpen) return@LaunchedEffect
    if (textFieldValue.composition != null) return@LaunchedEffect
    if (textFieldValue.text.isEmpty()) {
      if (!idleDismiss) return@LaunchedEffect
      delay(4000L)
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

  if (!mounted) return

  val transition = rememberInfiniteTransition(label = "searchConsole")
  val sweepAnim = transition.animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(6400, easing = LinearEasing)),
    label = "searchSweep",
  )

  Box(
    modifier
      .fillMaxWidth()
      .padding(horizontal = 18.dp, vertical = 84.dp)
      .height(64.dp)
      .graphicsLayer { alpha = phase(revealAnim.value, 0f, 0.22f) }
      // The plate itself is the hit target: in browse mode there is no keyboard yet, so a tap
      // on the ruled field has to summon the cursor instead of falling through to the dismiss
      // layer behind it.
      .pointerInput(Unit) {
        detectTapGestures {
          focusRequester.requestFocus()
          keyboardController?.show()
        }
      }
      .background(bgBrush),
  ) {
    Box(
      Modifier
        .fillMaxSize()
        .drawWithCache {
          val ux = size.width / 312f
          val uy = size.height / 64f
          fun X(sx: Float) = sx * ux
          fun Y(sy: Float) = sy * uy
          val hair = 0.6f.dp.toPx()

          val gridBitmap = cachedHudGridBitmap(size.width.roundToInt(), size.height.roundToInt(), 64f, hair)
          val platePath = Path().apply {
            moveTo(X(260f), Y(2f))
            lineTo(size.width - 8f * ux, Y(2f))
            lineTo(size.width, Y(2f) + 8f * uy)
            lineTo(size.width, Y(62f))
            lineTo(X(260f), Y(62f))
            close()
          }
          val bar = 1.2f.dp.toPx()
          val plateStroke = Stroke(width = 0.8f.dp.toPx())
          val perimeter = 2f * (size.width + size.height)
          val cornerAt = floatArrayOf(
            0f,
            size.width / perimeter,
            (size.width + size.height) / perimeter,
            (2f * size.width + size.height) / perimeter,
          )

          val crossArm = 3f.dp.toPx()
          val crossStroke = 1.2f.dp.toPx()
          val stroke = 1f.dp.toPx()

          val statusBaseline = centerBaseline(statusPaint, Y(18f))
          val countBaseline = centerBaseline(countPaint, Y(26f))
          val captionBaseline = centerBaseline(captionPaint, Y(46f))
          val promptBaseline = centerBaseline(promptPaint, Y(38f))
          val countAdvance = countPaint.measureText("MM") - countPaint.measureText("M")
          val countRight = X(248f)
          val captionRight = X(248f) - captionPaint.measureText(captionText)
          val statusX = X(33f)
          val countText = countValue.toString()
          val seed = statusText.hashCode()

          onDrawBehind {
            val reveal = revealAnim.value
            if (reveal <= 0.001f) return@onDrawBehind

            val trace = phase(reveal, 0f, 0.6f)
            val statusP = phase(reveal, 0.34f, 0.68f)
            val rollP = phase(reveal, 0.4f, 0.8f)
            val captionP = phase(reveal, 0.56f, 0.78f)
            val crossP = phase(reveal, 0.42f, 0.6f)
            val promptP = phase(reveal, 0.72f, 0.95f)

            fun drawPlateAndGrid() {
              drawPath(platePath, Color(0xFF0F141E))
              drawPath(
                platePath,
                if (typing) CrimsonBright.copy(alpha = 0.6f) else CrimsonDim,
                style = plateStroke,
              )
              gridBitmap?.let { drawImage(it) }
            }

            val plateClip = phase(trace, 0f, cornerAt[1])
            if (plateClip < 0.999f) {
              clipRect(right = size.width * plateClip) { drawPlateAndGrid() }
            } else {
              drawPlateAndGrid()
            }

            if (crossP > 0f) {
              drawCross(X(9.5f), Y(16.5f), crossArm, crossStroke, CrimsonBright.copy(alpha = 0.7f * crossP))
              drawCross(X(9.5f), Y(56.5f), crossArm, crossStroke, Color.White.copy(alpha = 0.4f * crossP))
            }

            if (statusP > 0f) {
              drawDecrypt(statusText, statusP, seed, statusPaint, statusX, statusBaseline)
              drawCircle(
                color = if (typing) CrimsonBright else CrimsonDim,
                radius = 2.2f * ux,
                center = Offset(X(18f), Y(18f)),
              )
            }
            if (rollP > 0f) {
              val shown = if (rollP >= 1f) countText else ((countValue * rollP).roundToInt()).toString()
              countPaint.color = if (typing) {
                android.graphics.Color.argb((255 * rollP).toInt(), 255, 77, 94)
              } else {
                android.graphics.Color.argb((230 * rollP).toInt(), 255, 255, 255)
              }
              drawContext.canvas.nativeCanvas.drawText(
                shown,
                countRight - shown.length * countAdvance,
                countBaseline + (1f - rollP) * 3f.dp.toPx(),
                countPaint,
              )
            }
            if (captionP > 0f) {
              captionPaint.alpha = (120 * captionP).toInt()
              if (captionP < 0.999f) {
                clipRect(right = captionRight + captionPaint.measureText(captionText) * captionP) {
                  drawContext.canvas.nativeCanvas.drawText(captionText, captionRight, captionBaseline, captionPaint)
                }
              } else {
                drawContext.canvas.nativeCanvas.drawText(captionText, captionRight, captionBaseline, captionPaint)
              }
            }
            if (promptP > 0f) {
              val blink = if (promptP < 1f && (promptP * 6f).toInt() % 2 == 0) 0.25f else 1f
              if (typing) {
                promptPaint.color = android.graphics.Color.argb((220f * promptP * blink).toInt().coerceAtMost(255), 255, 77, 94)
              } else {
                promptPaint.color = android.graphics.Color.argb((46f * promptP * blink).toInt().coerceAtMost(255), 255, 255, 255)
              }
              drawContext.canvas.nativeCanvas.drawText(">>", X(282f), promptBaseline, promptPaint)
            }

            val bracketTint = Color.White
            drawHudCornerBrackets(
              trace = trace,
              cornerAt = cornerAt,
              barPx = bar,
              tint = bracketTint,
              trTint = CrimsonBright.copy(alpha = 0.85f),
              blTint = bracketTint,
            )

            drawRect(
              color = CrimsonBright.copy(alpha = 0.75f * phase(reveal, 0.85f, 1f)),
              topLeft = Offset(X(283.5f), Y(50f)),
              size = Size(3f * ux, 3f * uy),
            )

            drawTracedRect(
              width = size.width - stroke,
              height = size.height - stroke,
              progress = trace,
              strokeWidth = stroke,
              color = Color.White.copy(alpha = 0.85f),
              head = if (trace in 0.02f..0.98f) 1f else 0f,
            )
          }
        },
    )

    // Isolate the 90Hz scanner beam in its own draw layer so the static plate, grid, and text don't re-record every frame.
    Box(
      Modifier
        .fillMaxSize()
        .drawWithCache {
          val ux = size.width / 312f
          val uy = size.height / 64f
          val bandW = 15f.dp.toPx()
          val bandBrush = Brush.horizontalGradient(
            0f to Color.Transparent,
            bandW to (if (typing) CrimsonBright.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.11f)),
            bandW * 2f to Color.Transparent,
          )
          val fieldLeft = 2f * ux
          val fieldRight = 259f * ux
          val bandTop = 2f * uy
          val bandH = 60f * uy
          val lineTop = 4f * uy
          val lineBottom = 60f * uy
          val lineStroke = (if (typing) 1.5f else 1f).dp.toPx()

          onDrawBehind {
            if (revealAnim.value <= 0.001f) return@onDrawBehind
            val sweep = sweepAnim.value
            val edge = 1f - abs(2f * sweep - 1f)
            val sweepX = fieldLeft + sweep * (fieldRight - fieldLeft)
            withTransform({ translate(sweepX - bandW, bandTop) }) {
              drawRect(
                brush = bandBrush,
                size = Size(bandW * 2f, bandH),
                blendMode = BlendMode.Plus,
              )
            }
            drawLine(
              color = if (typing) CrimsonBright.copy(alpha = 0.45f * edge * edge) else Color.White.copy(alpha = 0.22f * edge * edge),
              start = Offset(sweepX, lineTop),
              end = Offset(sweepX, lineBottom),
              strokeWidth = lineStroke,
              blendMode = BlendMode.Plus,
            )
          }
        },
    )

    Layout(
      modifier = Modifier.fillMaxSize(),
      content = {
        if (scope != null) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .pointerInput(scope) {
                detectTapGestures { onExitGroup() }
              }
          )
        }
        Box(modifier = Modifier.fillMaxSize()) {
          BasicTextField(
            value = if (isOpen) textFieldValue else frozen,
            onValueChange = { newVal ->
              lastInteractionTimestamp = System.currentTimeMillis()
              textFieldValue = newVal
              if (newVal.text != searchQuery) onSearchQueryChange(newVal.text)
            },
            singleLine = true,
            textStyle = TextStyle(
              color = Color.White,
              fontSize = 20.sp,
              fontFamily = FontFamily.Monospace,
              fontWeight = FontWeight.Medium,
              letterSpacing = 2.sp,
            ),
            cursorBrush = SolidColor(CrimsonBright),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
              onSearch = {
                keyboardController?.hide()
                focusManager.clearFocus()
                val top = matchingApps.firstOrNull()
                if (top != null) {
                  onLaunchApp(top)
                  onDismiss()
                }
              }
            ),
            modifier = Modifier
              .focusRequester(focusRequester)
              .graphicsLayer { alpha = contentProgress(revealAnim.value) }
              .fillMaxWidth()
              .fillMaxHeight(),
            decorationBox = { innerTextField ->
              Box {
                if (shownText.isEmpty()) {
                  Text(
                    text = "SEARCH TELEMETRY",
                    color = Color.White.copy(alpha = 0.28f),
                    fontSize = 20.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 2.sp,
                  )
                }
                innerTextField()
              }
            },
          )
        }
      },
    ) { measurables, constraints ->
      val ux = constraints.maxWidth / 312f
      val uy = constraints.maxHeight / 64f
      val scopePlaceable = if (scope != null && measurables.size > 1) {
        measurables[0].measure(
          Constraints.fixed((130f * ux).roundToInt().coerceAtLeast(1), (26f * uy).roundToInt().coerceAtLeast(1)),
        )
      } else {
        null
      }
      val fieldPlaceable = measurables.last().measure(
        Constraints.fixed((190f * ux).roundToInt().coerceAtLeast(1), (26f * uy).roundToInt().coerceAtLeast(1)),
      )
      layout(constraints.maxWidth, constraints.maxHeight) {
        scopePlaceable?.placeRelative(0, 0)
        fieldPlaceable.placeRelative((33f * ux).roundToInt(), (26f * uy).roundToInt())
      }
    }
  }
}
