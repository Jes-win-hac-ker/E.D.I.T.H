package com.nexwork.edith.theme

import androidx.compose.ui.graphics.Color

// Sleek Dark Glass / Minimalist Obsidian Palette
val ObsidianBgTop = Color(0xFF0B0D12)
val ObsidianBgBottom = Color(0xFF121620)
val GlassSurface = Color(0xFF1A202C)
val GlassSurfaceElevated = Color(0xFF232B3A)
val GlassBorderSubtle = Color(0x33FFFFFF)
val GlassBorderHighlight = Color(0x66FFFFFF)
val GlassPillFill = Color(0x24FFFFFF)

// Typography & Accents
val TextPureWhite = Color(0xFFF8FAFC)
val TextSilver = Color(0xFFCBD5E1)
val TextMutedSlate = Color(0xFF64748B)

// Subtle Ice / Cyan & Warm Glow Accents
val IceGlowAccent = Color(0xFF38BDF8)
val IceGlowSoft = Color(0x3338BDF8)
val WarmSunAccent = Color(0xFFFBBF24)
val DangerRose = Color(0xFFFB7185)

// ===== STARK HUD palette: monochrome wireframe + crimson accent =====
// Mechanically validated (aesthete-agent): 15/20, slop-clean, WCAG-checked.
// USAGE LAW:
//  - Crimson/E01432 is GRAPHIC-ONLY (strokes, brackets, halos — 3:1 non-text bar).
//    Never text: 4.17:1 on void fails AA for small type.
//  - CrimsonBright/FF4D5E is the ONLY crimson allowed as text, badges or active state (6.25:1 AA).
//  - Dim labels use HudLineDim (7.69:1). Wireframe HudLine @85% (17.15:1).
//  - Red-green CVD: crimsons collapse toward silver — never encode meaning by crimson alone,
//    always pair with shape, position or a word.
val HudVoid = Color(0xFF04060B)
val HudInk = Color(0xFF0A0E16)
val HudLine = Color(0xFFE6EDF3)
val HudLineDim = Color(0xFF93A1B0)
val HudFill = Color(0x0FFFFFFF)
val Crimson = Color(0xFFE01432)
val CrimsonBright = Color(0xFFFF4D5E)
val CrimsonDim = Color(0x66E01432)
