package com.pawneyes.app

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
)

private val Context.dataStore by preferencesDataStore(name = "ajustes")

/** Se guardan con DataStore y se mantienen entre sesiones y actualizaciones. */
class SettingsStore(private val context: Context) {
    private object Keys {
        val THEME = stringPreferencesKey("tema")
        val PIECES = stringPreferencesKey("piezas")
        val COORDINATES = booleanPreferencesKey("coordenadas")
        val LETTERS = stringPreferencesKey("notacion")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val new = transform(prefs.toSettings())
            prefs[Keys.THEME] = new.style.theme.name
            prefs[Keys.PIECES] = new.style.pieces.name
            prefs[Keys.COORDINATES] = new.style.coordinates
            prefs[Keys.LETTERS] = new.letters.name
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
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
