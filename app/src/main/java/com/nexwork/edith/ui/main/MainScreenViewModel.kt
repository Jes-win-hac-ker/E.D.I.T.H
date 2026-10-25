package com.nexwork.edith.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexwork.edith.data.DataRepository
import com.nexwork.edith.data.DefaultDataRepository
import com.nexwork.edith.data.LauncherRepository
import com.nexwork.edith.model.AppEntry
import com.nexwork.edith.model.AppFootprint
import com.nexwork.edith.model.DeviceTelemetry
import com.nexwork.edith.model.WeatherCondition
import com.nexwork.edith.notifications.EdithNotificationListener
import com.nexwork.edith.ui.main.MainScreenUiState.Success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
  val railPackages: List<String> = LauncherRepository.DEFAULT_RAIL_PACKAGES,
  val isFullOverlayOpen: Boolean = false,
  val isSpotlightSearchOpen: Boolean = false,
  val cornerSwipeProgress: Float = 0f,
  val use24Hour: Boolean = false,
  val isDefaultLauncher: Boolean = true,
  val weather: WeatherCondition = WeatherCondition.PARTLY_CLOUDY,
  val telemetry: DeviceTelemetry = DeviceTelemetry(),
  val notificationCounts: Map<String, Int> = emptyMap(),
  val hasNotificationAccess: Boolean = false,
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
    refreshLauncherData()
    startTelemetryObservation()
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
          railPackages = launcherRepository.getRailPackages(),
          telemetry = telemetry,
          isDefaultLauncher = isDefault,
          hasNotificationAccess = notifAccess,
        )
      }
    }
  }

  fun onSearchQueryChange(query: String) {
    _launcherState.update { state ->
      state.copy(searchQuery = query)
    }
  }

  fun onCornerSwipeProgress(progress: Float) {
    _launcherState.update { state ->
      state.copy(cornerSwipeProgress = progress.coerceIn(0f, 1f))
    }
  }

  fun onCornerSwipeFinish(shouldUnlock: Boolean) {
    _launcherState.update { state ->
      state.copy(
        isFullOverlayOpen = shouldUnlock,
        cornerSwipeProgress = if (shouldUnlock) 1f else 0f,
      )
    }
  }

  fun openFullAppListOverlay() {
    _launcherState.update { state ->
      state.copy(
        isFullOverlayOpen = true,
        cornerSwipeProgress = 1f,
      )
    }
  }

  fun closeFullAppListOverlay() {
    _launcherState.update { state ->
      state.copy(
        isFullOverlayOpen = false,
        cornerSwipeProgress = 0f,
        searchQuery = "",
      )
    }
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

  fun toggleRailPackage(packageName: String) {
    val updated = launcherRepository.toggleRailPackage(packageName)
    _launcherState.update { it.copy(railPackages = updated) }
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
        searchQuery = "",
      )
    }
  }

  fun closeSpotlightSearch() {
    _launcherState.update { state ->
      state.copy(
        isSpotlightSearchOpen = false,
        searchQuery = "",
      )
    }
  }

  fun handleBackPress(): Boolean {
    val current = _launcherState.value
    if (current.isSpotlightSearchOpen) {
      closeSpotlightSearch()
      return true
    }
    if (current.isFullOverlayOpen || current.searchQuery.isNotEmpty() || current.cornerSwipeProgress > 0f) {
      closeFullAppListOverlay()
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
