# E.D.I.T.H — Sleek Dark Glass Home Launcher for Motorola Moto G45 5G

**E.D.I.T.H** is a custom Android Home Launcher built from scratch in a **Sleek Dark Glass / Minimalist** aesthetic, inspired by the spatial layout of [`Android Compact - 1.svg`](docs/design/Android%20Compact%20-%201.svg) and engineered specifically for the **Motorola Moto G45 5G** (`720 × 1600` HD+ 20:9 IPS LCD, **120Hz** adaptive refresh rate, **Snapdragon 6s Gen 3**).

---

## 1. Design & Spatial Architecture

Rather than rendering raw wireframe lines from the sketch, all UI elements are crafted from scratch using deep obsidian gradients, frosted glass surfaces, specular borders, and real-time background blur:

| Spatial Zone | Implementation | Key Features |
| :--- | :--- | :--- |
| **Left-Edge Glass Rotary Clock & Weather Dial** | [`RotaryClockDial.kt`](app/src/main/java/com/nexwork/edith/ui/components/RotaryClockDial.kt) | Frosted obsidian semi-circular hour track (`R=156dp` / `R=104dp`), fine degree micro-ticks, glowing horizontal time capsule (`:MM SS` + active hour window), crafted sun-and-cloud weather readout, and interactive vertical drag scrubbing |
| **Top-Left 14-Day Frequent Apps** | [`LeftTopMostUsedSection`](app/src/main/java/com/nexwork/edith/ui/components/MotoQuickDeck.kt) | Queries Android `UsageStatsManager` over the **past 14 days** to surface your most-used apps inside a floating glass card with medium top padding so apps never touch the top edge |
| **Bottom-Left Essential Quick Access** | [`LeftBottomQuickAccessDock`](app/src/main/java/com/nexwork/edith/ui/components/MotoQuickDeck.kt) | Prioritizes **Phone, GPay / Wallet, and Camera (Moto Camera)** with medium bottom padding so apps sit comfortably in the thumb zone without touching the bottom edge |
| **Right Quick App Rail & Corner Swipe Trigger** | [`AppListRail.kt`](app/src/main/java/com/nexwork/edith/ui/components/AppListRail.kt) | Separated by a delicate `1dp` gradient glass divider; displays quick-access apps with generous top/bottom padding (`78dp` / `84dp`) and corner unlock triggers |
| **Corner-Swipe Blurred Full App List Overlay** | [`FullAppListGlassOverlay`](app/src/main/java/com/nexwork/edith/ui/components/AppListRail.kt) | Starting a swipe inward from the corner progressively **blurs the entire home screen** (`Modifier.blur`) and unlocks a full-screen dark glass App List with search, 14-day most-used strip, 4-column grid, and A–Z fast scrubber |

---

## 2. Motorola Moto G45 5G Customizations

1. **20:9 Display & 120Hz Ergonomics**:
   - Proportional `0.73f : 0.27f` split tailored for the Moto G45's 6.5" `720 × 1600` (20:9) display.
   - Medium top and bottom vertical padding ensures no app icons touch the top status area or bottom gesture bar, keeping all quick-access targets within natural one-handed reach.
2. **14-Day Usage Intelligence (`UsageStatsManager`)**:
   - [`LauncherRepository.loadInstalledApps`](app/src/main/java/com/nexwork/edith/data/DataRepository.kt) aggregates foreground usage over the past 14 days (`TimeUnit.DAYS.toMillis(14)`) and automatically ranks **Phone**, **GPay** (`com.google.android.apps.nbu.paisa.user` / Google Wallet), and **Moto Camera** (`com.motorola.camera3`) alongside your most-used apps.
3. **Real-Time RenderEffect Blur**:
   - Uses Android 12+ hardware-accelerated `Modifier.blur` on the Moto G45 (Android 14) to smoothly blur the home screen up to `18dp` as you swipe from the corner to unlock the full App List overlay.

---

## 3. Building & Installing on Motorola Moto G45 5G

```bash
# Build debug APK and run unit tests
./gradlew testDebugUnitTest assembleDebug

# Install and launch on connected Moto G45 5G
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.nexwork.edith/.MainActivity
```
