package com.nexwork.edith.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexwork.edith.data.DataRepository
import com.nexwork.edith.data.DefaultDataRepository
import com.nexwork.edith.data.LauncherRepository
import com.nexwork.edith.data.OtaState
import com.nexwork.edith.data.OtaUpdater
import com.nexwork.edith.model.AppEntry
import com.nexwork.edith.model.AppFootprint
import com.nexwork.edith.model.DeviceTelemetry
import com.nexwork.edith.model.WeatherCondition
import com.nexwork.edith.notifications.EdithNotificationListener
import com.nexwork.edith.ui.hud.warmAppIcons
import com.nexwork.edith.ui.main.MainScreenUiState.Success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
  val telemetry: DeviceTelemetry = DeviceTelemetry(),
  val notificationCounts: Map<String, Int> = emptyMap(),
  val hasNotificationAccess: Boolean = false,
  val otaState: OtaState = OtaState.Idle,
)

class MainScreenViewModel(
  dataRepository: DataRepository = DefaultDataRepository(),
  private val launcherRepository: LauncherRepository = LauncherRepository(null),
) : ViewModel() {

  val uiState: StateFlow<MainScreenUiState> =
    dataRepository.data
      .map<List<String>, MainScreenUiState>(::Success)
      .catch { emit(MainScreenUiState.Error(it)) }
      .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MainScreenUiState.Loading)

  private val _launcherState = MutableStateFlow(LauncherHomeState())
  val launcherState: StateFlow<LauncherHomeState> = _launcherState.asStateFlow()

  private var telemetryObservationJob: Job? = null
  private var otaUpdater: OtaUpdater? = null
  private var otaJob: Job? = null

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
    launcherRepository.setContext(context)
    if (otaUpdater == null) {
      otaUpdater = OtaUpdater(context.applicationContext).also {
        it.requestShizukuPermissionUpfront()
      }
    }
    refreshLauncherData()
    startTelemetryObservation()
    checkForUpdates(force = false)
  }

  private fun startTelemetryObservation() {
    telemetryObservationJob?.cancel()
    telemetryObservationJob = viewModelScope.launch {
      launcherRepository.observeTelemetry().collect { telemetry ->
        _launcherState.update { it.copy(telemetry = telemetry) }
      }
    }
  }

  fun refreshLauncherData() {
    viewModelScope.launch {
      val apps = launcherRepository.loadInstalledApps()
      val telemetry = launcherRepository.readTelemetry()
      val isDefault = launcherRepository.isDefaultLauncher()
      val notifAccess = launcherRepository.hasNotificationListenerAccess()
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
        )
      }
      withContext(Dispatchers.Default) { warmAppIcons(apps) }
    }
  }

  fun onSearchQueryChange(query: String) {
    val trimmed = query.trim()
    if (trimmed.equals("!ota", ignoreCase = true)) {
      closeSpotlightSearch()
      checkForUpdates(force = true)
      return
    }
    if (trimmed.startsWith("!ota https://", ignoreCase = true)) {
      val customUrl = trimmed.substringAfter(" ").trim()
      otaUpdater?.setFeedUrl(customUrl)
      closeSpotlightSearch()
      checkForUpdates(force = true)
      return
    }
    _launcherState.update { state ->
      state.copy(searchQuery = query)
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
    if (_launcherState.value.otaState is OtaState.Downloading) {
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

  fun toggleTimeFormat() {
    _launcherState.update { state ->
      state.copy(use24Hour = !state.use24Hour)
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
        isAmbientCentered = false,
      )
    }
  }

  fun closeSpotlightSearch() {
    _launcherState.update { state ->
      state.copy(
        isSpotlightSearchOpen = false,
        browseGroup = null,
        searchQuery = "",
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
        isAmbientCentered = false,
      )
    }
  }

  /** Drills the browse deck into one category. Typing still searches the flat index. */
  fun openAppGroup(name: String) {
    _launcherState.update { state ->
      if (!state.isAppBrowseOpen) state
      else state.copy(browseGroup = name, searchQuery = "")
    }
  }

  fun closeAppGroup() {
    _launcherState.update { state -> state.copy(browseGroup = null, searchQuery = "") }
  }

  fun closeAppBrowse() {
    _launcherState.update { state ->
      state.copy(
        isAppBrowseOpen = false,
        browseGroup = null,
        searchQuery = "",
        isAmbientCentered = false,
      )
    }
  }

  /** Parks the globe at center screen when no screen activity occurs. */
  fun enterAmbientCenter() {
    _launcherState.update { state ->
      if (state.isSpotlightSearchOpen || state.isAppBrowseOpen || state.isAmbientCentered) state
      else state.copy(isAmbientCentered = true)
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
    return false
  }
}

sealed interface MainScreenUiState {
  object Loading : MainScreenUiState

  data class Error(val throwable: Throwable) : MainScreenUiState

  data class Success(val data: List<String>) : MainScreenUiState
}
