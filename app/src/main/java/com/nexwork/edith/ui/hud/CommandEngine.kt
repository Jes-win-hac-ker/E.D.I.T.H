package com.nexwork.edith.ui.hud

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.pow

/**
 * Parsed tactical HUD command or inline arithmetic result produced from spotlight search input.
 */
sealed interface HudCommand {
  data class MathResult(val expression: String, val formatted: String) : HudCommand
  data class OtaCheck(val customFeedUrl: String? = null) : HudCommand
  data object MonetStatus : HudCommand
  data object MonetApply : HudCommand
  data object MonetReset : HudCommand
  data object GrantUsage : HudCommand
  data object GrantNotifications : HudCommand
  data object OpenHomeChooser : HudCommand
  data class PinToggle(val query: String) : HudCommand
  data object ShowBattery : HudCommand
  data object ShowSystem : HudCommand
  data object ShowWeather : HudCommand
  data object ToggleWifi : HudCommand
  data object ToggleBluetooth : HudCommand
  data object ToggleDnd : HudCommand
  data class SetRefreshRate(val hz: Int?) : HudCommand
  data object ShowHelp : HudCommand
  data class WebSearch(val query: String) : HudCommand
  data class Unknown(val raw: String) : HudCommand
}

/**
 * Parses spotlight search input into a tactical [HudCommand], or returns `null` when the query
 * should be treated as a normal app search.
 */
fun parseHudCommand(rawQuery: String): HudCommand? {
  val trimmed = rawQuery.trim()
  if (trimmed.isEmpty()) return null

  if (trimmed.startsWith("?")) {
    return HudCommand.WebSearch(trimmed.drop(1).trim())
  }

  if (trimmed.startsWith("!")) {
    return parseBangCommand(trimmed)
  }

  if (trimmed.startsWith("=")) {
    val expr = trimmed.drop(1).trim()
    if (expr.isEmpty()) return null
    val evaluated = evaluateArithmetic(expr, allowBareNumber = true) ?: return null
    return HudCommand.MathResult(expr, evaluated)
  }

  val evaluated = evaluateArithmetic(trimmed, allowBareNumber = false) ?: return null
  return HudCommand.MathResult(trimmed, evaluated)
}

private fun parseBangCommand(trimmed: String): HudCommand {
  val body = trimmed.drop(1).trim()
  if (body.isEmpty()) return HudCommand.ShowHelp

  val spaceIdx = body.indexOfFirst { it.isWhitespace() }
  val verb = (if (spaceIdx == -1) body else body.substring(0, spaceIdx)).lowercase()
  val arg = if (spaceIdx == -1) "" else body.substring(spaceIdx + 1).trim()

  return when (verb) {
    "help", "cmd" -> if (arg.isEmpty()) HudCommand.ShowHelp else HudCommand.Unknown(trimmed)
    "ota" -> HudCommand.OtaCheck(arg.takeIf { it.isNotEmpty() })
    "monet" -> when (arg.lowercase()) {
      "", "status" -> HudCommand.MonetStatus
      "apply" -> HudCommand.MonetApply
      "reset" -> HudCommand.MonetReset
      else -> HudCommand.Unknown(trimmed)
    }
    "theme" -> if (arg.isEmpty()) HudCommand.MonetStatus else HudCommand.Unknown(trimmed)
    "reset" -> if (arg.isEmpty()) HudCommand.MonetReset else HudCommand.Unknown(trimmed)
    "usage" -> if (arg.isEmpty()) HudCommand.GrantUsage else HudCommand.Unknown(trimmed)
    "notif" -> if (arg.isEmpty()) HudCommand.GrantNotifications else HudCommand.Unknown(trimmed)
    "home" -> if (arg.isEmpty()) HudCommand.OpenHomeChooser else HudCommand.Unknown(trimmed)
    "pin", "unpin" -> HudCommand.PinToggle(arg)
    "battery", "bat" -> if (arg.isEmpty()) HudCommand.ShowBattery else HudCommand.Unknown(trimmed)
    "ram", "sys" -> if (arg.isEmpty()) HudCommand.ShowSystem else HudCommand.Unknown(trimmed)
    "weather", "wx" -> if (arg.isEmpty()) HudCommand.ShowWeather else HudCommand.Unknown(trimmed)
    "wifi" -> if (arg.isEmpty()) HudCommand.ToggleWifi else HudCommand.Unknown(trimmed)
    "bt", "bluetooth" -> if (arg.isEmpty()) HudCommand.ToggleBluetooth else HudCommand.Unknown(trimmed)
    "dnd" -> if (arg.isEmpty()) HudCommand.ToggleDnd else HudCommand.Unknown(trimmed)
    "refresh" -> when {
      arg.isEmpty() || arg.equals("auto", ignoreCase = true) -> HudCommand.SetRefreshRate(null)
      else -> arg.toIntOrNull()?.takeIf { it > 0 }?.let { HudCommand.SetRefreshRate(it) }
        ?: HudCommand.Unknown(trimmed)
    }
    "g" -> HudCommand.WebSearch(arg)
    else -> HudCommand.Unknown(trimmed)
  }
}

private fun evaluateArithmetic(expression: String, allowBareNumber: Boolean): String? {
  val parser = ArithmeticParser(expression)
  val value = parser.parse() ?: return null
  if (!allowBareNumber && !parser.hasBinaryOperator) return null
  return formatMathNumber(value)
}

private fun formatMathNumber(value: Double): String {
  if (abs(value) < 1e-12) return "0"
  val scaled = BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP)
  if (scaled.compareTo(BigDecimal.ZERO) == 0) return "0"
  return scaled.stripTrailingZeros().toPlainString()
}

private class ArithmeticParser(private val input: String) {
  private var pos = 0
  var hasBinaryOperator = false
    private set

  fun parse(): Double? {
    val result = parseExpression() ?: return null
    skipWhitespace()
    if (pos != input.length) return null
    if (!result.isFinite()) return null
    return result
  }

  private fun parseExpression(): Double? {
    var left = parseTerm() ?: return null
    while (true) {
      skipWhitespace()
      if (pos >= input.length) break
      val op = input[pos]
      if (op != '+' && op != '-') break
      pos++
      hasBinaryOperator = true
      val right = parseTerm() ?: return null
      left = if (op == '+') left + right else left - right
    }
    return left
  }

  private fun parseTerm(): Double? {
    var left = parseUnary() ?: return null
    while (true) {
      skipWhitespace()
      if (pos >= input.length) break
      val op = input[pos]
      if (op != '*' && op != '/' && op != '%') break
      pos++
      hasBinaryOperator = true
      val right = parseUnary() ?: return null
      left = when (op) {
        '*' -> left * right
        '/' -> if (right == 0.0) return null else left / right
        else -> if (right == 0.0) return null else left % right
      }
    }
    return left
  }

  private fun parseUnary(): Double? {
    skipWhitespace()
    if (pos < input.length) {
      val op = input[pos]
      if (op == '+' || op == '-') {
        pos++
        val operand = parseUnary() ?: return null
        return if (op == '-') -operand else operand
      }
    }
    return parsePower()
  }

  private fun parsePower(): Double? {
    val base = parsePrimary() ?: return null
    skipWhitespace()
    if (pos < input.length && input[pos] == '^') {
      pos++
      hasBinaryOperator = true
      val exp = parseUnary() ?: return null
      val result = base.pow(exp)
      if (!result.isFinite()) return null
      return result
    }
    return base
  }

  private fun parsePrimary(): Double? {
    skipWhitespace()
    if (pos < input.length && input[pos] == '(') {
      pos++
      val inner = parseExpression() ?: return null
      skipWhitespace()
      if (pos >= input.length || input[pos] != ')') return null
      pos++
      return inner
    }
    return parseNumber()
  }

  private fun parseNumber(): Double? {
    skipWhitespace()
    val start = pos
    var sawDigit = false
    var sawDot = false
    while (pos < input.length) {
      val c = input[pos]
      when {
        c in '0'..'9' -> {
          sawDigit = true
          pos++
        }
        c == '.' && !sawDot -> {
          sawDot = true
          pos++
        }
        else -> break
      }
    }
    if (!sawDigit) return null
    return input.substring(start, pos).toDoubleOrNull()
  }

  private fun skipWhitespace() {
    while (pos < input.length && input[pos].isWhitespace()) {
      pos++
    }
  }
}
