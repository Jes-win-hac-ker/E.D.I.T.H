package com.nexwork.edith.ui.main

import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexwork.edith.data.DataRepository
import com.nexwork.edith.data.DefaultDataRepository
import com.nexwork.edith.data.LauncherRepository
import com.nexwork.edith.data.MonetThemeManager
import com.nexwork.edith.data.MonetUiState
import com.nexwork.edith.data.OtaState
import com.nexwork.edith.data.OtaUpdater
import com.nexwork.edith.data.ShizukuControlManager
import com.nexwork.edith.data.WeatherRepository
import com.nexwork.edith.model.AppEntry
import com.nexwork.edith.model.AppFootprint
import com.nexwork.edith.model.DeviceTelemetry
import com.nexwork.edith.model.HudIntensityPreset
import com.nexwork.edith.model.LauncherAccentPreset
import com.nexwork.edith.model.LauncherFontStyle
import com.nexwork.edith.model.LauncherSettingsConfig
import com.nexwork.edith.model.LauncherSettingsSection
import com.nexwork.edith.model.LiveWeather
import com.nexwork.edith.model.SettingsStage
import com.nexwork.edith.model.WeatherCondition
import com.nexwork.edith.notifications.EdithNotificationListener
import com.nexwork.edith.ui.hud.HudCommand
import com.nexwork.edith.ui.hud.filterMatchingApps
import com.nexwork.edith.ui.hud.parseHudCommand
import com.nexwork.edith.ui.hud.warmAppIcons
import com.nexwork.edith.ui.main.MainScreenUiState.Success
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HudFeedbackEvent(
  val message: String,
  val clipboardText: String? = null,
)

data class LauncherHomeState(
  val apps: List<AppEntry> = LauncherRepository.sampleApps(),
  val essentialApps: List<AppEntry> = LauncherRepository.sampleApps().take(4),
  val mostUsed14dApps: List<AppEntry> = LauncherRepository.sampleApps().drop(3).take(4),
  val quickRailApps: List<AppEntry> = LauncherRepository.sampleApps().take(7),
  val searchQuery: String = "",
  val isAppBrowseOpen: Boolean = false,
  val browseGroup: String? = null,
  val isSpotlightSearchOpen: Boolean = false,
  val isAmbientCentered: Boolean = false,
  val use24Hour: Boolean = false,
  val isDefaultLauncher: Boolean = true,
  val weather: WeatherCondition = WeatherCondition.PARTLY_CLOUDY,
  val liveWeather: LiveWeather? = null,
  val commandHint: String? = null,
  val shizukuControlAuthorized: Boolean = false,
  val telemetry: DeviceTelemetry = DeviceTelemetry(),
  val notificationCounts: Map<String, Int> = emptyMap(),
  val hasNotificationAccess: Boolean = false,
  val otaState: OtaState = OtaState.Idle,
  val monetUiState: MonetUiState = MonetUiState(),
  val settingsStage: SettingsStage = SettingsStage.CLOSED,
  val selectedSettingsSection: LauncherSettingsSection? = null,
  val launcherSettings: LauncherSettingsConfig = LauncherSettingsConfig(),
)

class MainScreenViewModel(
  dataRepository: DataRepository = DefaultDataRepository(),
  private val launcherRepository: LauncherRepository = LauncherRepository(null),
  private val weatherRepository: WeatherRepository = WeatherRepository(null),
) : ViewModel() {

  val uiState: StateFlow<MainScreenUiState> =
    dataRepository.data
      .map<List<String>, MainScreenUiState>(::Success)
      .catch { emit(MainScreenUiState.Error(it)) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MainScreenUiState.Loading)

  private val _launcherState = MutableStateFlow(LauncherHomeState())
  val launcherState: StateFlow<LauncherHomeState> = _launcherState.asStateFlow()

  private val _feedbackEvents = MutableSharedFlow<HudFeedbackEvent>(extraBufferCapacity = 8)
  val feedbackEvents: SharedFlow<HudFeedbackEvent> = _feedbackEvents.asSharedFlow()

  private var appContext: Context? = null
  private var telemetryObservationJob: Job? = null
  private var otaUpdater: OtaUpdater? = null
  private var otaJob: Job? = null
  private var monetThemeManager: MonetThemeManager? = null
  private var monetJob: Job? = null
  private var monetEnsureJob: Job? = null
  private var shizukuControlManager: ShizukuControlManager? = null
  private var weatherJob: Job? = null
  private var controlJob: Job? = null

  init {
    refreshLauncherData()
    if (launcherRepository.hasContext()) {
      startTelemetryObservation()
    }
    viewModelScope.launch {
      EdithNotificationListener.activeCounts.collect { counts ->
        _launcherState.update { it.copy(notificationCounts = counts) }
      }
    }
  }

  fun initContext(context: android.content.Context) {
    val appCtx = context.applicationContext
    appContext = appCtx
    launcherRepository.setContext(context)
    weatherRepository.setContext(appCtx)
    weatherRepository.getCachedWeather()?.let { cached ->
      _launcherState.update { it.copy(liveWeather = cached) }
    }
    if (shizukuControlManager == null) {
      shizukuControlManager = ShizukuControlManager(appCtx)
    }
    if (otaUpdater == null) {
      otaUpdater = OtaUpdater(appCtx).also {
        it.requestShizukuPermissionUpfront()
      }

    }
    if (monetThemeManager == null) {
      val manager = MonetThemeManager(appCtx)
      monetThemeManager = manager
      viewModelScope.launch {
        manager.uiState.collect { monetState ->
          _launcherState.update { it.copy(monetUiState = monetState) }
        }
      }
      manager.refreshShizukuState()
      ensureMonetPersisted()
    }
    refreshLauncherData()
    startTelemetryObservation()
    refreshWeather(forceRefresh = false)
    syncShizukuControlAndPermissions()
    checkForUpdates(force = false)
  }

  private fun syncShizukuControlAndPermissions() {
    val manager = shizukuControlManager ?: return
    viewModelScope.launch {
      val authorized = manager.isShizukuAuthorized()
      _launcherState.update { it.copy(shizukuControlAuthorized = authorized) }
      if (authorized &&
        (!launcherRepository.hasUsageStatsPermission() || !launcherRepository.hasNotificationListenerAccess())
      ) {
        manager.autoGrantPermissionsIfReady()
        refreshLauncherData()
      }
    }
  }

  fun refreshWeather(forceRefresh: Boolean = false, announceResult: Boolean = false) {
    weatherJob?.cancel()
    weatherJob = viewModelScope.launch {
      val live = weatherRepository.fetchWeather(forceRefresh = forceRefresh)
      if (live != null) {
        _launcherState.update { it.copy(liveWeather = live) }
      }
      if (announceResult) {
        val current = live ?: _launcherState.value.liveWeather
        val text = formatWeatherFeedback(current)
        _launcherState.update { it.copy(commandHint = formatWeatherHint(current)) }
        _feedbackEvents.tryEmit(HudFeedbackEvent(text))
      }
    }
  }

  private fun startTelemetryObservation() {
    telemetryObservationJob?.cancel()
    telemetryObservationJob = viewModelScope.launch {
      launcherRepository.observeTelemetry().collect { telemetry ->
        _launcherState.update { it.copy(telemetry = telemetry) }
      }
    }
  }

  fun refreshLauncherData(
    invalidatedPackage: String? = null,
    clearAllFootprints: Boolean = false,
  ) {
    if (clearAllFootprints) {
      launcherRepository.invalidateFootprintCache(null)
    } else if (invalidatedPackage != null) {
      launcherRepository.invalidateFootprintCache(invalidatedPackage)
    }
    viewModelScope.launch {
      val apps = launcherRepository.loadInstalledApps()
      val telemetry = launcherRepository.readTelemetry()
      val isDefault = launcherRepository.isDefaultLauncher()
      val notifAccess = launcherRepository.hasNotificationListenerAccess()
      val savedSettings = if (launcherRepository.hasContext()) launcherRepository.getLauncherSettings() else null
      val quickAll = launcherRepository.selectQuickAccessApps(apps, maxCount = 20)
      val essentials = quickAll.take(4)
      val essentialPkgs = essentials.map { it.packageName }.toSet()

      val mostUsed14d =
        apps
          .filterNot { it.packageName in essentialPkgs }
          .sortedWith(
            compareByDescending<AppEntry> { it.usageTimeMillis14d }
              .thenByDescending { it.isPinned }
              .thenBy { it.label.lowercase() }
          )
          .take(4)
          .ifEmpty { quickAll.drop(4).take(4).ifEmpty { essentials } }

      val leftShownPkgs = essentialPkgs + mostUsed14d.map { it.packageName }.toSet()
      val railApps =
        apps
          .filterNot { it.packageName in leftShownPkgs }
          .sortedWith(
            compareByDescending<AppEntry> { it.usageTimeMillis14d }
              .thenBy { it.label.lowercase() }
          )
          .ifEmpty { apps }

      _launcherState.update { state ->
        state.copy(
          apps = apps,
          essentialApps = essentials,
          mostUsed14dApps = mostUsed14d,
          quickRailApps = railApps,
          telemetry = telemetry,
          isDefaultLauncher = isDefault,
          hasNotificationAccess = notifAccess,
          launcherSettings = savedSettings ?: state.launcherSettings,
          use24Hour = savedSettings?.use24Hour ?: state.use24Hour,
        )
      }
      withContext(Dispatchers.Default) { warmAppIcons(apps) }
    }
  }

  fun onSearchQueryChange(query: String) {
    val trimmed = query.trim()
    if (trimmed.equals("!wx", ignoreCase = true) || trimmed.equals("!weather", ignoreCase = true)) {
      refreshWeather(forceRefresh = false)
    }
    val parsed = parseHudCommand(query)
    val hint = parsed?.let { previewCommandHint(it) }
    _launcherState.update { state ->
      state.copy(searchQuery = query, commandHint = hint)
    }
  }

  fun submitConsoleQuery(): Boolean {
    val query = _launcherState.value.searchQuery.trim()
    if (query.isEmpty()) return false
    val command = parseHudCommand(query)
    if (command == null) {
      val matches = filterMatchingApps(query, _launcherState.value.apps)
      if (matches.isNotEmpty()) return false
      launcherRepository.openWebSearch(query)
      _feedbackEvents.tryEmit(HudFeedbackEvent("Searching web for \"$query\""))
      closeSpotlightSearch()
      return true
    }

    when (command) {
      is HudCommand.MathResult -> {
        _launcherState.update { it.copy(commandHint = "= ${command.formatted}  ·  COPIED") }
        _feedbackEvents.tryEmit(
          HudFeedbackEvent(
            message = "${command.expression} = ${command.formatted} (copied)",
            clipboardText = command.formatted,
          )
        )
      }
      is HudCommand.OtaCheck -> {
        command.customFeedUrl?.let { otaUpdater?.setFeedUrl(it) }
        closeSpotlightSearch()
        checkForUpdates(force = true)
      }
      HudCommand.GrantUsage -> {
        handleShizukuOrSettingsPermission(
          alreadyGranted = launcherRepository.hasUsageStatsPermission(),
          alreadyMessage = "Usage access already granted",
          shizukuGrantedMessage = "Usage access granted via Shizuku",
          openFallback = launcherRepository::openUsageAccessSettings,
        )
      }
      HudCommand.GrantNotifications -> {
        handleShizukuOrSettingsPermission(
          alreadyGranted = launcherRepository.hasNotificationListenerAccess(),
          alreadyMessage = "Notification access already granted",
          shizukuGrantedMessage = "Notification access granted via Shizuku",
          openFallback = launcherRepository::openNotificationListenerSettings,
        )
      }
      HudCommand.OpenHomeChooser -> {
        launcherRepository.openHomeSettings()
        closeSpotlightSearch()
      }
      is HudCommand.PinToggle -> {
        val target = if (command.query.isBlank()) {
          null
        } else {
          filterMatchingApps(command.query, _launcherState.value.apps).firstOrNull()
        }
        if (target == null) {
          _feedbackEvents.tryEmit(HudFeedbackEvent("No app matched \"${command.query}\""))
        } else {
          val willPin = !target.isPinned
          togglePinApp(target)
          _feedbackEvents.tryEmit(
            HudFeedbackEvent("${if (willPin) "Pinned" else "Unpinned"} ${target.label}")
          )
          closeSpotlightSearch()
        }
      }
      HudCommand.ShowBattery -> {
        val t = _launcherState.value.telemetry
        val msg = "Battery ${t.batteryPct}% (${if (t.isCharging) "charging" else "discharging"})"
        _launcherState.update { it.copy(commandHint = msg.uppercase()) }
        _feedbackEvents.tryEmit(HudFeedbackEvent(msg))
      }
      HudCommand.ShowSystem -> {
        val t = _launcherState.value.telemetry
        val msg = "RAM ${t.availableRamGb}/${t.totalRamGb} GB · ${t.refreshRateHz} Hz"
        _launcherState.update { it.copy(commandHint = msg.uppercase()) }
        _feedbackEvents.tryEmit(HudFeedbackEvent(msg))
      }
      HudCommand.ShowWeather -> {
        refreshWeather(forceRefresh = true, announceResult = true)
      }
      HudCommand.ToggleWifi -> {
        executeShizukuToggle(
          currentState = readWifiEnabled() == true,
          shizukuAction = { manager, next -> manager.toggleWifi(next) },
          settingsFallback = launcherRepository::openWifiSettings,
        )
      }
      HudCommand.ToggleBluetooth -> {
        executeShizukuToggle(
          currentState = readBluetoothEnabled() == true,
          shizukuAction = { manager, next -> manager.toggleBluetooth(next) },
          settingsFallback = launcherRepository::openBluetoothSettings,
        )
      }
      HudCommand.ToggleDnd -> {
        executeShizukuToggle(
          currentState = readDndEnabled() == true,
          shizukuAction = { manager, next -> manager.toggleDnd(next) },
          settingsFallback = launcherRepository::openDndSettings,
        )
      }
      is HudCommand.SetRefreshRate -> {
        setRefreshRatePreset(command.hz, closeConsole = true)
      }
      HudCommand.ShowHelp -> {
        _launcherState.update {
          it.copy(commandHint = "!PIN !WIFI !BT !DND !BAT !RAM !WX !OTA")
        }
        _feedbackEvents.tryEmit(
          HudFeedbackEvent("Commands: !pin !wifi !bt !dnd !refresh !bat !ram !wx !ota !usage !notif !home ?web =math")
        )
      }
      is HudCommand.WebSearch -> {
        if (command.query.isBlank()) {
          _feedbackEvents.tryEmit(HudFeedbackEvent("Type a search query after ? or !g"))
        } else {
          launcherRepository.openWebSearch(command.query)
          closeSpotlightSearch()
        }
      }
      is HudCommand.Unknown -> {
        _feedbackEvents.tryEmit(HudFeedbackEvent("Unknown command: ${command.raw}. Type !help"))
      }
    }
    return true
  }

  private fun handleShizukuOrSettingsPermission(
    alreadyGranted: Boolean,
    alreadyMessage: String,
    shizukuGrantedMessage: String,
    openFallback: () -> Unit,
  ) {
    if (alreadyGranted) {
      _feedbackEvents.tryEmit(HudFeedbackEvent(alreadyMessage))
      closeSpotlightSearch()
      return
    }
    val manager = shizukuControlManager
    controlJob?.cancel()
    controlJob = viewModelScope.launch {
      if (manager != null && manager.isShizukuAuthorized()) {
        val grantResult = manager.autoGrantPermissionsIfReady()
        refreshLauncherData()
        if (grantResult.ok) {
          _feedbackEvents.tryEmit(HudFeedbackEvent(shizukuGrantedMessage))
          closeSpotlightSearch()
          return@launch
        }
      }
      openFallback()
      closeSpotlightSearch()
    }
  }

  private fun executeShizukuToggle(
    currentState: Boolean,
    shizukuAction: suspend (ShizukuControlManager, Boolean) -> com.nexwork.edith.data.ShizukuActionResult,
    settingsFallback: () -> Unit,
  ) {
    val manager = shizukuControlManager
    controlJob?.cancel()
    controlJob = viewModelScope.launch {
      if (manager != null && manager.isShizukuAuthorized()) {
        val result = shizukuAction(manager, !currentState)
        _launcherState.update { it.copy(shizukuControlAuthorized = true) }
        _feedbackEvents.tryEmit(HudFeedbackEvent(result.formatStatusMessage()))
        if (!result.ok) {
          settingsFallback()
        }
      } else {
        settingsFallback()
      }
      closeSpotlightSearch()
    }
  }

  private fun readWifiEnabled(): Boolean? {
    val ctx = appContext ?: return null
    return runCatching {
      Settings.Global.getInt(ctx.contentResolver, "wifi_on", 0) == 1
    }.getOrNull()
  }

  private fun readBluetoothEnabled(): Boolean? {
    val ctx = appContext ?: return null
    return runCatching {
      Settings.Global.getInt(ctx.contentResolver, "bluetooth_on", 0) == 1
    }.getOrNull()
  }

  private fun readDndEnabled(): Boolean? {
    val ctx = appContext ?: return null
    return runCatching {
      val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
      nm != null && nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }.getOrNull()
  }

  private fun previewCommandHint(command: HudCommand): String {
    val state = _launcherState.value
    val shizukuReady = state.shizukuControlAuthorized
    return when (command) {
      is HudCommand.MathResult -> "= ${command.formatted}"
      is HudCommand.OtaCheck ->
        if (command.customFeedUrl != null) "OTA  ·  CUSTOM FEED" else "OTA  ·  CHECK RELEASE"
      HudCommand.GrantUsage ->
        if (state.telemetry.hasUsageAccess) "USAGE  ·  GRANTED" else "USAGE  ·  GRANT ACCESS"
      HudCommand.GrantNotifications ->
        if (state.hasNotificationAccess) "NOTIF  ·  GRANTED" else "NOTIF  ·  GRANT ACCESS"
      HudCommand.OpenHomeChooser -> "HOME  ·  DEFAULT APP"
      is HudCommand.PinToggle -> {
        if (command.query.isBlank()) {
          "PIN  ·  TYPE APP NAME"
        } else {
          val match = filterMatchingApps(command.query, state.apps).firstOrNull()
          if (match == null) {
            "PIN  ·  NO MATCH"
          } else {
            "${if (match.isPinned) "UNPIN" else "PIN"}  ·  ${match.label.uppercase()}"
          }
        }
      }
      HudCommand.ShowBattery -> {
        val t = state.telemetry
        "BAT ${t.batteryPct}%${if (t.isCharging) " CHARGING" else ""}"
      }
      HudCommand.ShowSystem -> {
        val t = state.telemetry
        "RAM ${t.availableRamGb}/${t.totalRamGb}G  ·  ${t.refreshRateHz}HZ"
      }
      HudCommand.ShowWeather -> formatWeatherHint(state.liveWeather)
      HudCommand.ToggleWifi -> if (shizukuReady) "WIFI  ·  TOGGLE" else "WIFI  ·  SETTINGS"
      HudCommand.ToggleBluetooth -> if (shizukuReady) "BT  ·  TOGGLE" else "BT  ·  SETTINGS"
      HudCommand.ToggleDnd -> if (shizukuReady) "DND  ·  TOGGLE" else "DND  ·  SETTINGS"
      is HudCommand.SetRefreshRate ->
        if (command.hz == null) "HZ  ·  AUTO" else "HZ  ·  ${command.hz}HZ"
      HudCommand.ShowHelp -> "!PIN !WIFI !BT !DND !BAT !RAM !WX !OTA"
      is HudCommand.WebSearch ->
        if (command.query.isBlank()) "WEB  ·  TYPE QUERY" else "WEB  ·  ${command.query.take(18).uppercase()}"
      is HudCommand.Unknown -> "UNKNOWN CMD  ·  !HELP"
    }
  }

  private fun formatWeatherHint(weather: LiveWeather?): String {
    if (weather == null) return "WX  ·  FETCHING..."
    val cityTag = weather.city.takeIf { it.isNotBlank() }?.let { " · ${it.uppercase()}" } ?: ""
    return "${weather.tempCelsius}C  ·  ${weather.label.uppercase()}$cityTag"
  }

  private fun formatWeatherFeedback(weather: LiveWeather?): String {
    if (weather == null) return "Weather unavailable right now"
    val place = if (weather.city.isNotBlank()) " in ${weather.city}" else ""
    return "${weather.tempCelsius}°C · ${weather.label}$place"
  }

  fun applyCrimsonMonet() {
    val manager = monetThemeManager ?: return
    if (monetJob?.isActive == true) return
    monetEnsureJob?.cancel()
    monetJob = viewModelScope.launch {
      manager.applyCrimsonMonet()
    }
  }

  fun resetMonetToDefault() {
    val manager = monetThemeManager ?: return
    if (monetJob?.isActive == true) return
    monetEnsureJob?.cancel()
    monetJob = viewModelScope.launch {
      manager.resetToDefault()
    }
  }

  fun ensureMonetPersisted() {
    val manager = monetThemeManager ?: return
    manager.refreshShizukuState()
    if (monetJob?.isActive == true || monetEnsureJob?.isActive == true) return
    monetEnsureJob = viewModelScope.launch {
      manager.ensureOverlayPersistedIfEnabled()
    }
  }

  /** Checks the configured OTA endpoint; auto-downloads and caches APKs without duplicate fetches. */
  fun checkForUpdates(force: Boolean) {
    val updater = otaUpdater ?: return
    val current = _launcherState.value.otaState
    if (otaJob?.isActive == true || current is OtaState.Downloading || current is OtaState.Installing) {
      return
    }
    if (current is OtaState.ReadyToInstall) {
      if (force) {
        otaJob = viewModelScope.launch {
          installReleaseApk(updater, current.release, current.apkFile)
        }
      }
      return
    }
    if (!force && !updater.shouldAutoCheck()) return

    otaJob = viewModelScope.launch {
      if (force) {
        _launcherState.update { it.copy(otaState = OtaState.Checking) }
      }
      val result = updater.checkForUpdate()
      result.fold(
        onSuccess = { release ->
          if (release == null) {
            if (force) {
              _launcherState.update { it.copy(otaState = OtaState.UpToDate) }
              delay(2_000L)
              _launcherState.update { state ->
                if (state.otaState is OtaState.UpToDate) state.copy(otaState = OtaState.Idle) else state
              }
            } else {
              _launcherState.update { it.copy(otaState = OtaState.Idle) }
            }
            return@fold
          }

          val cached = updater.getCachedValidApk(release)
          if (cached != null) {
            if (force) {
              installReleaseApk(updater, release, cached)
            } else {
              _launcherState.update { it.copy(otaState = OtaState.ReadyToInstall(release, cached)) }
            }
            return@fold
          }

          if (force || updater.isUnmeteredWifi()) {
            downloadRelease(updater, release, installOnComplete = force || updater.isShizukuReady())
          } else {
            _launcherState.update { it.copy(otaState = OtaState.Available(release)) }
          }
        },
        onFailure = { err ->
          _launcherState.update {
            it.copy(otaState = if (force) OtaState.Error(err.message ?: "Check failed") else OtaState.Idle)
          }
        },
      )
    }
  }

  fun onOtaPillClick() {
    val updater = otaUpdater ?: return
    when (val current = _launcherState.value.otaState) {
      is OtaState.Available -> {
        if (otaJob?.isActive == true) return
        otaJob = viewModelScope.launch {
          downloadRelease(updater, current.release, installOnComplete = true)
        }
      }
      is OtaState.ReadyToInstall -> {
        if (otaJob?.isActive == true) return
        otaJob = viewModelScope.launch {
          installReleaseApk(updater, current.release, current.apkFile)
        }
      }
      is OtaState.Error -> checkForUpdates(force = true)
      OtaState.UpToDate -> dismissOtaPill()
      else -> Unit
    }
  }

  fun dismissOtaPill() {
    val wasBusy = _launcherState.value.otaState is OtaState.Downloading ||
      _launcherState.value.otaState is OtaState.Checking
    if (wasBusy) {
      otaJob?.cancel()
      otaJob = null
    }
    _launcherState.update { it.copy(otaState = OtaState.Idle) }
  }

  private suspend fun downloadRelease(
    updater: OtaUpdater,
    release: com.nexwork.edith.data.OtaRelease,
    installOnComplete: Boolean,
  ) {
    _launcherState.update { it.copy(otaState = OtaState.Downloading(release, 0)) }
    val download = updater.downloadAndVerify(release) { pct ->
      _launcherState.update { it.copy(otaState = OtaState.Downloading(release, pct)) }
    }
    download.fold(
      onSuccess = { file ->
        if (installOnComplete) {
          installReleaseApk(updater, release, file)
        } else {
          _launcherState.update { it.copy(otaState = OtaState.ReadyToInstall(release, file)) }
        }
      },
      onFailure = { err ->
        if (err is CancellationException) throw err
        _launcherState.update { it.copy(otaState = OtaState.Error(err.message ?: "Download failed")) }
      },
    )
  }

  private suspend fun installReleaseApk(
    updater: OtaUpdater,
    release: com.nexwork.edith.data.OtaRelease,
    apkFile: java.io.File,
  ) {
    _launcherState.update { it.copy(otaState = OtaState.Installing(release)) }
    val result = updater.installApk(apkFile)
    result.fold(
      onSuccess = { installedSilently ->
        if (installedSilently) {
          _launcherState.update { it.copy(otaState = OtaState.Installed(release)) }
          delay(2_000L)
          _launcherState.update { state ->
            if (state.otaState is OtaState.Installed) state.copy(otaState = OtaState.Idle) else state
          }
        } else {
          _launcherState.update { it.copy(otaState = OtaState.ReadyToInstall(release, apkFile)) }
          delay(2_000L)
          _launcherState.update { state ->
            if (state.otaState is OtaState.ReadyToInstall) state.copy(otaState = OtaState.Idle) else state
          }
        }
      },
      onFailure = { err ->
        _launcherState.update { it.copy(otaState = OtaState.Error(err.message ?: "Install failed")) }
      },
    )
  }

  fun setRefreshRatePreset(hz: Int?, closeConsole: Boolean = false) {
    val manager = shizukuControlManager
    controlJob?.cancel()
    controlJob = viewModelScope.launch {
      if (manager != null && manager.isShizukuAuthorized()) {
        val res = manager.setRefreshRate(hz ?: 120)
        _launcherState.update { it.copy(shizukuControlAuthorized = true) }
        _feedbackEvents.tryEmit(HudFeedbackEvent(res.formatStatusMessage()))
        if (res.ok) {
          delay(200L)
          val refreshed = launcherRepository.readTelemetry()
          _launcherState.update { it.copy(telemetry = refreshed) }
        } else {
          launcherRepository.openDisplaySettings()
        }
      } else {
        launcherRepository.openDisplaySettings()
      }
      if (closeConsole) {
        closeSpotlightSearch()
      }
    }
  }

  fun toggleTimeFormat() {
    setUse24Hour(!_launcherState.value.launcherSettings.use24Hour)
  }

  fun setUse24Hour(enabled: Boolean) {
    updateLauncherSettings { it.copy(use24Hour = enabled) }
  }

  /** Applies a launcher-only accent preset without altering OS-wide Shizuku Monet. */
  fun updateLauncherAccent(preset: LauncherAccentPreset) {
    updateLauncherSettings { it.copy(accentPreset = preset) }
  }

  fun updateHudIntensity(preset: HudIntensityPreset) {
    updateLauncherSettings { it.copy(hudIntensity = preset) }
  }

  fun updateLauncherFont(style: LauncherFontStyle) {
    updateLauncherSettings { it.copy(fontStyle = style) }
  }

  fun updateLauncherFontScope(scope: com.nexwork.edith.model.HudFontScope) {
    updateLauncherSettings { it.copy(fontScope = scope) }
  }

  fun updateLauncherFontScale(scale: Float) {
    val clamped = scale.coerceIn(0.85f, 1.25f)
    updateLauncherSettings { it.copy(fontScale = clamped) }
  }

  private fun updateLauncherSettings(transform: (LauncherSettingsConfig) -> LauncherSettingsConfig) {
    var updated = LauncherSettingsConfig()
    _launcherState.update { state ->
      updated = transform(state.launcherSettings)
      state.copy(
        launcherSettings = updated,
        use24Hour = updated.use24Hour,
      )
    }
    launcherRepository.saveLauncherSettings(updated)
  }

  fun openSettingsRoot() {
    _launcherState.update { state ->
      state.copy(
        settingsStage = SettingsStage.ROOT_BRANCHES,
        selectedSettingsSection = null,
        isSpotlightSearchOpen = false,
        isAppBrowseOpen = false,
        browseGroup = null,
        searchQuery = "",
        commandHint = null,
        isAmbientCentered = false,
      )
    }
  }

  fun selectSettingsBranch(stage: SettingsStage) {
    _launcherState.update { state ->
      if (state.settingsStage == SettingsStage.CLOSED) state
      else state.copy(
        settingsStage = stage,
        selectedSettingsSection = null,
        isAmbientCentered = false,
      )
    }
  }

  fun selectLauncherSetting(section: LauncherSettingsSection?) {
    _launcherState.update { state ->
      if (state.settingsStage != SettingsStage.LAUNCHER_TREE) state
      else state.copy(selectedSettingsSection = section)
    }
  }

  fun dismissSettings() {
    _launcherState.update { state ->
      state.copy(
        settingsStage = SettingsStage.CLOSED,
        selectedSettingsSection = null,
      )
    }
  }

  fun cycleWeatherCondition() {
    _launcherState.update { state ->
      val entries = WeatherCondition.entries
      val nextIdx = (entries.indexOf(state.weather) + 1) % entries.size
      state.copy(weather = entries[nextIdx])
    }
  }

  fun launchApp(app: AppEntry) {
    launcherRepository.launchApp(app)
  }

  suspend fun footprintOf(app: AppEntry): AppFootprint? =
    withContext(Dispatchers.IO) { launcherRepository.footprint(app.packageName) }

  fun togglePinApp(app: AppEntry) {
    launcherRepository.togglePinPackage(app.packageName)
    refreshLauncherData()
  }

  fun openAppInfo(app: AppEntry) {
    launcherRepository.openAppInfo(app)
  }

  fun requestUninstall(app: AppEntry) {
    launcherRepository.requestUninstall(app)
  }

  fun openHomeSettings() {
    launcherRepository.openHomeSettings()
  }

  fun openUsageAccessSettings() {
    launcherRepository.openUsageAccessSettings()
  }

  fun openNotificationAccessSettings() {
    launcherRepository.openNotificationListenerSettings()
  }

  fun openClockOrAlarms() {
    launcherRepository.openSystemClockOrAlarms()
  }

  fun expandNotifications() {
    launcherRepository.expandNotificationShade()
  }

  fun openSpotlightSearch() {
    _launcherState.update { state ->
      state.copy(
        isSpotlightSearchOpen = true,
        isAppBrowseOpen = false,
        browseGroup = null,
        searchQuery = "",
        commandHint = null,
        isAmbientCentered = false,
        settingsStage = SettingsStage.CLOSED,
        selectedSettingsSection = null,
      )
    }
  }

  fun closeSpotlightSearch() {
    _launcherState.update { state ->
      state.copy(
        isSpotlightSearchOpen = false,
        browseGroup = null,
        searchQuery = "",
        commandHint = null,
        isAmbientCentered = false,
      )
    }
  }

  fun openAppBrowse() {
    _launcherState.update { state ->
      state.copy(
        isAppBrowseOpen = true,
        isSpotlightSearchOpen = false,
        browseGroup = null,
        searchQuery = "",
        commandHint = null,
        isAmbientCentered = false,
        settingsStage = SettingsStage.CLOSED,
        selectedSettingsSection = null,
      )
    }
  }

  /** Drills the browse deck into one category. Typing still searches the flat index. */
  fun openAppGroup(name: String) {
    _launcherState.update { state ->
      if (!state.isAppBrowseOpen) state
      else state.copy(browseGroup = name, searchQuery = "", commandHint = null)
    }
  }

  fun closeAppGroup() {
    _launcherState.update { state ->
      state.copy(browseGroup = null, searchQuery = "", commandHint = null)
    }
  }

  fun closeAppBrowse() {
    _launcherState.update { state ->
      state.copy(
        isAppBrowseOpen = false,
        browseGroup = null,
        searchQuery = "",
        commandHint = null,
        isAmbientCentered = false,
      )
    }
  }

  /** Parks the globe at center screen when no screen activity occurs. */
  fun enterAmbientCenter() {
    _launcherState.update { state ->
      if (state.isSpotlightSearchOpen ||
        state.isAppBrowseOpen ||
        state.settingsStage != SettingsStage.CLOSED ||
        state.isAmbientCentered
      ) {
        state
      } else {
        state.copy(isAmbientCentered = true)
      }
    }
  }

  /** Restores the active horizon globe layout as soon as screen activity resumes. */
  fun wakeFromAmbient() {
    if (!_launcherState.value.isAmbientCentered) return
    _launcherState.update { state ->
      if (!state.isAmbientCentered) state else state.copy(isAmbientCentered = false)
    }
  }

  /** Deactivates an idle search/browse console and glides the globe directly to screen center. */
  fun autoDeactivateConsole() {
    _launcherState.update { state ->
      state.copy(
        isSpotlightSearchOpen = false,
        isAppBrowseOpen = false,
        browseGroup = null,
        searchQuery = "",
        commandHint = null,
        isAmbientCentered = true,
      )
    }
  }

  fun handleBackPress(): Boolean {
    val current = _launcherState.value
    if (current.isSpotlightSearchOpen) {
      closeSpotlightSearch()
      return true
    }
    if (current.isAppBrowseOpen) {
      if (current.browseGroup != null) closeAppGroup() else closeAppBrowse()
      return true
    }
    when (current.settingsStage) {
      SettingsStage.CLOSED -> return false
      SettingsStage.LAUNCHER_TREE -> {
        if (current.selectedSettingsSection != null) {
          selectLauncherSetting(null)
        } else {
          selectSettingsBranch(SettingsStage.ROOT_BRANCHES)
        }
        return true
      }
      SettingsStage.AGENT_PLACEHOLDER -> {
        selectSettingsBranch(SettingsStage.ROOT_BRANCHES)
        return true
      }
      SettingsStage.SHIZUKU_PLACEHOLDER -> {
        selectSettingsBranch(SettingsStage.ROOT_BRANCHES)
        return true
      }
      SettingsStage.ROOT_BRANCHES -> {
        dismissSettings()
        return true
      }
    }
    return false
  }
}

sealed interface MainScreenUiState {
  object Loading : MainScreenUiState

  data class Error(val throwable: Throwable) : MainScreenUiState

  data class Success(val data: List<String>) : MainScreenUiState
}
