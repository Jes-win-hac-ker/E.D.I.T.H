# AGENTS.md — E.D.I.T.H Home Launcher (Motorola Moto G45 5G)

This document defines the architectural context, target device constraints, design invariants, and coding conventions for AI agents working on the **E.D.I.T.H** Android Home Launcher repository.

---

## 1. Target Device Profile: Motorola Moto G45 5G

All UI/UX decisions, performance targets, and system integrations in this repository are tailored for the **Motorola Moto G45 5G**:

- **Display**: 6.5-inch IPS LCD, `720 × 1600` px (HD+, 20:9 aspect ratio, ~269 ppi).
- **Refresh Rate**: **120Hz** adaptive refresh rate. Animations, rotary dial dragging, corner-swipe blur transitions, and app scrolling must avoid main-thread jank.
- **SoC & Memory**: Qualcomm **Snapdragon 6s Gen 3** (6nm) with 4GB or 8GB LPDDR4X RAM. Load app icons and 14-day usage statistics asynchronously on `Dispatchers.IO`.
- **OS & Ecosystem**: Android 14+ with Motorola **Hello UI / My UX**. First-class essential quick-access hooks target:
  - **Phone / Dialer** (`com.google.android.dialer` / `com.android.dialer`)
  - **GPay / Google Wallet** (`com.google.android.apps.nbu.paisa.user` / `com.google.android.apps.walletnfcrel`)
  - **Moto Camera** (`com.motorola.camera3` / `com.motorola.camera2`)

---

## 2. Design & Interaction Invariants

1. **Crafted Sleek Dark Glass UI (No Raw Sketch Wireframes)**:
   - `docs/design/Android Compact - 1.svg` is a spatial layout guide only. Never render literal sketch wireframe lines or giant literal `A P P L I S T` letters.
   - Use the **Sleek Dark Glass / Minimalist** palette (`theme/Color.kt`): deep obsidian background (`ObsidianBgTop` `#0B0D12` to `ObsidianBgBottom` `#121620`), translucent frosted glass surfaces (`GlassSurface`, `GlassSurfaceElevated`), delicate `0.8dp–1.2dp` specular borders (`GlassBorderSubtle`, `GlassBorderHighlight`), and ice-blue accents (`IceGlowAccent` `#38BDF8`).
2. **Medium Top & Bottom Padding for Apps**:
   - Apps on both the left deck and the right quick rail must **never** touch the top or bottom edges of the screen. Maintain generous medium vertical padding (`42dp–84dp`) at the top and bottom.
3. **Quick Access + Past 14 Days Most Used Apps (`data/DataRepository.kt`)**:
   - Essential apps (**Phone, GPay, Camera**) and the user's most-used apps over the **past 14 days** (queried via `UsageStatsManager.queryAndAggregateUsageStats`) are surfaced on the home screen for instant one-tap launch.
4. **Corner-Swipe Gesture with Background Blur (`ui/components/AppListRail.kt` & `ui/main/MainScreen.kt`)**:
   - Starting a swipe from the right corner progressively blurs the underlying home screen (`Modifier.blur`) and unlocks `FullAppListGlassOverlay` (searchable 4-column grid, 14-day frequent strip, and A–Z fast scrubber).
5. **Proactive Feature Suggestions & Plausibility Consultation**:
   - When asked to add or modify features, controls, or gestures, proactively pitch 1–2 practical enhancements or ergonomic options (e.g., natural dismissal mechanics, tactile compression, timing thresholds, haptic confirmation, or GPU optimization) to ensure maximum utility and 120Hz smooth plausibility before implementing.

---

## 3. Launcher Engineering Rules

- **Manifest & Permissions (`AndroidManifest.xml`)**:
  - Retain `CATEGORY_HOME` and `CATEGORY_DEFAULT` intent filters, `launchMode="singleTask"`, `stateNotNeeded="true"`, `QUERY_ALL_PACKAGES`, and `PACKAGE_USAGE_STATS`.
- **Back Navigation**:
  - Pressing Back when `FullAppListGlassOverlay` is open closes the overlay and clears search; pressing Back on the root home screen must never exit the launcher.
- **Verification**:
  - Run `./gradlew testDebugUnitTest assembleDebug` to verify compilation and unit tests after code changes.
