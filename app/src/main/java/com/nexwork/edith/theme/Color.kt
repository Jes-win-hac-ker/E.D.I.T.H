package com.nexwork.edith.theme

import androidx.compose.ui.graphics.Color

// Material theme surface roles; the home screen paints its own void.
val ObsidianBgTop = Color(0xFF0B0D12)
val GlassSurface = Color(0xFF1A202C)
val GlassSurfaceElevated = Color(0xFF232B3A)

val TextPureWhite = Color(0xFFF8FAFC)
val TextSilver = Color(0xFFCBD5E1)
val IceGlowAccent = Color(0xFF38BDF8)
val WarmSunAccent = Color(0xFFFBBF24)
val VeridianAccent = Color(0xFF2DD4BF)
val TitaniumAccent = Color(0xFFE2E8F0)

// ===== STARK HUD accent =====
// Mechanically validated (aesthete-agent): slop-clean, WCAG-checked.
// USAGE LAW:
//  - Crimson/E01432 is GRAPHIC-ONLY (strokes, brackets, halos — 3:1 non-text bar).
//    Never text: 4.17:1 on void fails AA for small type.
//  - CrimsonBright/FF4D5E is the ONLY crimson allowed as text, badges or active state (6.25:1 AA).
//  - Red-green CVD: crimsons collapse toward silver — never encode meaning by crimson alone,
//    always pair with shape, position or a word.
val Crimson = Color(0xFFE01432)
val CrimsonBright = Color(0xFFFF4D5E)
val CrimsonDim = Color(0x66E01432)
