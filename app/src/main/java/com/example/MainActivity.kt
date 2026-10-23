package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.RunCoachViewModel
import com.example.ui.screens.GearAndShoesScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.NewRunAndRecommendationScreen
import com.example.ui.screens.SeasonSummaryScreen
import com.example.ui.theme.MyApplicationTheme

enum class AppTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    SORTIE("Sortie", Icons.Filled.DirectionsRun, Icons.Outlined.DirectionsRun, "nav_tab_sortie"),
    MATOS("Mon matos", Icons.Filled.Checkroom, Icons.Outlined.Checkroom, "nav_tab_matos"),
    HISTORIQUE("Historique", Icons.Filled.History, Icons.Outlined.History, "nav_tab_historique"),
    BILAN("Bilan", Icons.Filled.Assessment, Icons.Outlined.Assessment, "nav_tab_bilan")
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                RunCoachApp()
            }
        }
    }
}

@Composable
fun RunCoachApp(
    viewModel: RunCoachViewModel = viewModel()
) {
    var currentTab by rememberSaveable { mutableStateOf(AppTab.SORTIE) }

    val formState by viewModel.formState.collectAsStateWithLifecycle()
    val recommendation by viewModel.recommendation.collectAsStateWithLifecycle()
    val shoes by viewModel.shoes.collectAsStateWithLifecycle()
    val gearItems by viewModel.gearItems.collectAsStateWithLifecycle()
    val beforeChecklist by viewModel.beforeStartChecklist.collectAsStateWithLifecycle()
    val runHistory by viewModel.runHistory.collectAsStateWithLifecycle()
    val nutritionSettings by viewModel.nutritionSettings.collectAsStateWithLifecycle()
    val retroConfig by viewModel.retroConfig.collectAsStateWithLifecycle()
    val pastRunDraft by viewModel.pastRunDraft.collectAsStateWithLifecycle()

    if (currentTab != AppTab.SORTIE) {
        BackHandler {
            currentTab = AppTab.SORTIE
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (!isWideScreen) {
                    NavigationBar {
                        AppTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWideScreen) {
                    NavigationRail {
                        AppTab.entries.forEach { tab ->
                            val selected = currentTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                        contentDescription = tab.label
                                    )
                                },
                                label = { Text(tab.label) },
                                modifier = Modifier.testTag(tab.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    when (currentTab) {
                        AppTab.SORTIE -> {
                            NewRunAndRecommendationScreen(
                                formState = formState,
                                recommendation = recommendation,
                                shoes = shoes,
                                beforeStartChecklist = beforeChecklist,
                                retroConfig = retroConfig,
                                asphaltThresholdPercent = nutritionSettings.asphaltThresholdPercent,
                                onTerrainChange = viewModel::updateTerrain,
                                onPlannedDistanceChange = viewModel::updatePlannedDistance,
                                onElevationGainChange = viewModel::updateElevationGainDPlus,
                                onPaceChange = viewModel::updatePaceSecPerKm,
                                onImportGpxUri = viewModel::importGpxFromUri,
                                onRetryOverpassAnalysis = viewModel::retryGpxSurfaceAnalysis,
                                onApplyCustomSurfaceBreakdown = viewModel::updateCustomSurfaceBreakdown,
                                onClearGpx = viewModel::clearImportedGpx,
                                onDurationChange = viewModel::updateDurationMinutes,
                                onSessionTypeChange = viewModel::updateSessionType,
                                onStartTimeChange = { day, hour, minute -> viewModel.updateStartTime(day, hour, minute) },
                                onSearchQueryChange = viewModel::updateCitySearchQuery,
                                onSelectLocation = viewModel::selectGeoLocation,
                                onDetectGps = viewModel::detectGpsLocation,
                                onRefreshWeather = viewModel::refreshWeatherFromOpenMeteo,
                                onStartNewRunReset = viewModel::startNewRunPreparation,
                                onClearBanner = viewModel::clearBannerMessage,
                                onSelectManualShoe = viewModel::selectManualShoe,
                                onToggleChecklist = viewModel::toggleChecklistItem,
                                onAddChecklist = viewModel::addChecklistItem,
                                onDeleteChecklist = viewModel::deleteChecklistItem,
                                onResetBeforeChecklist = viewModel::resetBeforeStartChecklist,
                                onUpdateRetroConfig = viewModel::updateRetroplanningConfig,
                                onThermalProfileChange = viewModel::updateThermalProfile,
                                onWaterRefillCountChange = viewModel::updateWaterRefillCount,
                                onApplyBestWeatherSlot = viewModel::applyBestWeatherSlot,
                                onSubmitPostRun = viewModel::submitPostRunFeedback
                            )
                        }

                        AppTab.MATOS -> {
                            GearAndShoesScreen(
                                shoes = shoes,
                                gearItems = gearItems,
                                nutritionSettings = nutritionSettings,
                                onAddOrUpdateShoe = viewModel::addOrUpdateShoe,
                                onDeleteShoe = viewModel::deleteShoe,
                                onToggleGearOwned = viewModel::toggleGearOwned,
                                onAddCustomGear = viewModel::addCustomGearItem,
                                onUpdateNutritionSettings = { med, long, gelG, asphaltPct ->
                                    viewModel.updateNutritionRatesManually(med, long, gelG, asphaltPct)
                                },
                                onExportBackup = viewModel::exportBackup,
                                onImportBackup = viewModel::importBackup
                            )
                        }

                        AppTab.HISTORIQUE -> {
                            HistoryScreen(
                                history = runHistory,
                                shoes = shoes,
                                pastRunDraft = pastRunDraft,
                                onInitPastRunDialog = viewModel::initPastRunDialog,
                                onUpdatePastRunParams = { dateMs, hour, dur, terrain, session ->
                                    viewModel.updatePastRunParams(dateMs, hour, dur, terrain, session)
                                },
                                onUpdatePastRunCitySearch = viewModel::updatePastRunCitySearch,
                                onSelectPastRunLocation = viewModel::selectPastRunGeoLocation,
                                onRefreshPastRunWeather = viewModel::refreshPastRunHistoricalWeather,
                                onSavePastRun = viewModel::savePastRunEntry,
                                onDeleteEntry = viewModel::deleteHistoryEntry
                            )
                        }

                        AppTab.BILAN -> {
                            SeasonSummaryScreen(
                                history = runHistory
                            )
                        }
                    }
                }
            }
        }
    }
}
