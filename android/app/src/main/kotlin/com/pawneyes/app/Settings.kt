package com.pawneyes.app

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pawneyes.board.BoardStyle
import com.pawneyes.board.BoardTheme
import com.pawneyes.board.PieceSet
import com.pawneyes.chess.PieceLetters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Preferencias del usuario (#11): aspecto de los tableros y letras de la notación. */
data class AppSettings(
    val style: BoardStyle = BoardStyle(),
    val letters: PieceLetters = PieceLetters.SPANISH,
    /** Análisis (#24): líneas que se muestran y profundidad máxima de la búsqueda. */
    val analysisLines: Int = 3,
    val analysisDepth: Int = 40,
) {
    companion object {
        val LINES = 1..5
        val DEPTHS = listOf(20, 30, 40)
    }
}

private val Context.dataStore by preferencesDataStore(name = "ajustes")

/** Se guardan con DataStore y se mantienen entre sesiones y actualizaciones. */
class SettingsStore(private val context: Context) {
    private object Keys {
        val THEME = stringPreferencesKey("tema")
        val PIECES = stringPreferencesKey("piezas")
        val COORDINATES = booleanPreferencesKey("coordenadas")
        val LETTERS = stringPreferencesKey("notacion")
        val LINES = intPreferencesKey("lineas")
        val DEPTH = intPreferencesKey("profundidad")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val new = transform(prefs.toSettings())
            prefs[Keys.THEME] = new.style.theme.name
            prefs[Keys.PIECES] = new.style.pieces.name
            prefs[Keys.COORDINATES] = new.style.coordinates
            prefs[Keys.LETTERS] = new.letters.name
            prefs[Keys.LINES] = new.analysisLines
            prefs[Keys.DEPTH] = new.analysisDepth
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val default = AppSettings()
        // Un valor que ya no existe (un juego de piezas retirado, p. ej.) vuelve al de por defecto.
        return AppSettings(
            style = BoardStyle(
                theme = enumOr(this[Keys.THEME], default.style.theme),
                pieces = enumOr(this[Keys.PIECES], default.style.pieces),
                coordinates = this[Keys.COORDINATES] ?: default.style.coordinates,
            ),
            letters = enumOr(this[Keys.LETTERS], default.letters),
            analysisLines = this[Keys.LINES]?.takeIf { it in AppSettings.LINES } ?: default.analysisLines,
            analysisDepth = this[Keys.DEPTH]?.takeIf { it in AppSettings.DEPTHS } ?: default.analysisDepth,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
