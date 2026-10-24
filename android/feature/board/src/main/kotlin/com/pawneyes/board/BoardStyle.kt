package com.pawneyes.board

import androidx.annotation.DrawableRes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.pawneyes.chess.Piece
import com.pawneyes.chess.PieceLetters
import com.pawneyes.chess.PieceType
import com.pawneyes.chess.Side

/** Colores del tablero (#11). */
enum class BoardTheme(val light: Color, val dark: Color) {
    WOOD(Color(0xFFF0D9B5), Color(0xFFB58863)),
    GREEN(Color(0xFFEEEED2), Color(0xFF769656)),
    BLUE(Color(0xFFDEE3E6), Color(0xFF8CA2AD)),
    DARK(Color(0xFF8E9199), Color(0xFF4F535C)),
}

/**
 * Juegos de piezas incluidos: solo con licencias que permiten redistribuirlos con
 * una app GPLv3 (ver android/LICENSES.md). Los PNG salen de ml/export/app_pieces.py.
 */
enum class PieceSet(val credit: String) {
    CHESSNUT("Alexis Luengas · Apache 2.0"),
    CBURNETT("Colin M.L. Burnett · GPLv2+"),
    MERIDA("Armando Hernandez Marroquin · GPLv2+"),
    FANTASY("Maurizio Monge · MIT");

    @DrawableRes
    fun drawable(piece: Piece): Int = when (this) {
        CHESSNUT -> when (piece.side) {
            Side.WHITE -> when (piece.type) {
                PieceType.PAWN -> R.drawable.piece_wp
                PieceType.KNIGHT -> R.drawable.piece_wn
                PieceType.BISHOP -> R.drawable.piece_wb
                PieceType.ROOK -> R.drawable.piece_wr
                PieceType.QUEEN -> R.drawable.piece_wq
                PieceType.KING -> R.drawable.piece_wk
            }
            Side.BLACK -> when (piece.type) {
                PieceType.PAWN -> R.drawable.piece_bp
                PieceType.KNIGHT -> R.drawable.piece_bn
                PieceType.BISHOP -> R.drawable.piece_bb
                PieceType.ROOK -> R.drawable.piece_br
                PieceType.QUEEN -> R.drawable.piece_bq
                PieceType.KING -> R.drawable.piece_bk
            }
        }
        CBURNETT -> when (piece.side) {
            Side.WHITE -> when (piece.type) {
                PieceType.PAWN -> R.drawable.piece_cburnett_wp
                PieceType.KNIGHT -> R.drawable.piece_cburnett_wn
                PieceType.BISHOP -> R.drawable.piece_cburnett_wb
                PieceType.ROOK -> R.drawable.piece_cburnett_wr
                PieceType.QUEEN -> R.drawable.piece_cburnett_wq
                PieceType.KING -> R.drawable.piece_cburnett_wk
            }
            Side.BLACK -> when (piece.type) {
                PieceType.PAWN -> R.drawable.piece_cburnett_bp
                PieceType.KNIGHT -> R.drawable.piece_cburnett_bn
                PieceType.BISHOP -> R.drawable.piece_cburnett_bb
                PieceType.ROOK -> R.drawable.piece_cburnett_br
                PieceType.QUEEN -> R.drawable.piece_cburnett_bq
                PieceType.KING -> R.drawable.piece_cburnett_bk
            }
        }
        MERIDA -> when (piece.side) {
            Side.WHITE -> when (piece.type) {
                PieceType.PAWN -> R.drawable.piece_merida_wp
                PieceType.KNIGHT -> R.drawable.piece_merida_wn
                PieceType.BISHOP -> R.drawable.piece_merida_wb
                PieceType.ROOK -> R.drawable.piece_merida_wr
                PieceType.QUEEN -> R.drawable.piece_merida_wq
                PieceType.KING -> R.drawable.piece_merida_wk
            }
            Side.BLACK -> when (piece.type) {
                PieceType.PAWN -> R.drawable.piece_merida_bp
                PieceType.KNIGHT -> R.drawable.piece_merida_bn
                PieceType.BISHOP -> R.drawable.piece_merida_bb
                PieceType.ROOK -> R.drawable.piece_merida_br
                PieceType.QUEEN -> R.drawable.piece_merida_bq
                PieceType.KING -> R.drawable.piece_merida_bk
            }
        }
        FANTASY -> when (piece.side) {
            Side.WHITE -> when (piece.type) {
                PieceType.PAWN -> R.drawable.piece_fantasy_wp
                PieceType.KNIGHT -> R.drawable.piece_fantasy_wn
                PieceType.BISHOP -> R.drawable.piece_fantasy_wb
                PieceType.ROOK -> R.drawable.piece_fantasy_wr
                PieceType.QUEEN -> R.drawable.piece_fantasy_wq
                PieceType.KING -> R.drawable.piece_fantasy_wk
            }
            Side.BLACK -> when (piece.type) {
                PieceType.PAWN -> R.drawable.piece_fantasy_bp
                PieceType.KNIGHT -> R.drawable.piece_fantasy_bn
                PieceType.BISHOP -> R.drawable.piece_fantasy_bb
                PieceType.ROOK -> R.drawable.piece_fantasy_br
                PieceType.QUEEN -> R.drawable.piece_fantasy_bq
                PieceType.KING -> R.drawable.piece_fantasy_bk
            }
        }
    }
}

/** Cómo se dibujan los tableros: colores, piezas y coordenadas. */
data class BoardStyle(
    val theme: BoardTheme = BoardTheme.WOOD,
    val pieces: PieceSet = PieceSet.CHESSNUT,
    val coordinates: Boolean = true,
)

/** Estilo de los tableros de la app (lo fija la pantalla principal según los ajustes). */
val LocalBoardStyle = staticCompositionLocalOf { BoardStyle() }

/** Letras de las piezas en la notación que se muestra (el PGN siempre va en inglés). */
val LocalPieceLetters = staticCompositionLocalOf { PieceLetters.SPANISH }
