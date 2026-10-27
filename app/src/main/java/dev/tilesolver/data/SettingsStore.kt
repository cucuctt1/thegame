package dev.tilesolver.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.tilesolver.model.NormalizedDetectionArea
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.solverSettings by preferencesDataStore("solver_settings")

class SettingsStore(private val context: Context) {
    val gamePackage: Flow<String> = context.solverSettings.data.map { it[GAME_PACKAGE].orEmpty() }
    val boardArea: Flow<NormalizedDetectionArea?> = context.solverSettings.data.map { preferences ->
        val area = NormalizedDetectionArea(
            preferences[BOARD_LEFT] ?: return@map null,
            preferences[BOARD_TOP] ?: return@map null,
            preferences[BOARD_RIGHT] ?: return@map null,
            preferences[BOARD_BOTTOM] ?: return@map null,
        )
        area.takeIf { it.isValid() }
    }
    val trayArea: Flow<NormalizedDetectionArea?> = context.solverSettings.data.map { preferences ->
        val area = NormalizedDetectionArea(
            preferences[TRAY_LEFT] ?: return@map null,
            preferences[TRAY_TOP] ?: return@map null,
            preferences[TRAY_RIGHT] ?: return@map null,
            preferences[TRAY_BOTTOM] ?: return@map null,
        )
        area.takeIf { it.isValid() }
    }

    suspend fun setGamePackage(value: String) { context.solverSettings.edit { it[GAME_PACKAGE] = value.trim() } }
    suspend fun setDetectionAreas(board: NormalizedDetectionArea, tray: NormalizedDetectionArea) {
        if (!board.isValid() || !tray.isValid()) return
        context.solverSettings.edit {
            it[BOARD_LEFT] = board.left
            it[BOARD_TOP] = board.top
            it[BOARD_RIGHT] = board.right
            it[BOARD_BOTTOM] = board.bottom
            it[TRAY_LEFT] = tray.left
            it[TRAY_TOP] = tray.top
            it[TRAY_RIGHT] = tray.right
            it[TRAY_BOTTOM] = tray.bottom
        }
    }

    companion object {
        private val GAME_PACKAGE = stringPreferencesKey("game_package")
        private val BOARD_LEFT = floatPreferencesKey("detection_area_left")
        private val BOARD_TOP = floatPreferencesKey("detection_area_top")
        private val BOARD_RIGHT = floatPreferencesKey("detection_area_right")
        private val BOARD_BOTTOM = floatPreferencesKey("detection_area_bottom")
        private val TRAY_LEFT = floatPreferencesKey("tray_area_left")
        private val TRAY_TOP = floatPreferencesKey("tray_area_top")
        private val TRAY_RIGHT = floatPreferencesKey("tray_area_right")
        private val TRAY_BOTTOM = floatPreferencesKey("tray_area_bottom")
    }
}
