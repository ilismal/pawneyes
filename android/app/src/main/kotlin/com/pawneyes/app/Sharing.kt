package com.pawneyes.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import com.pawneyes.board.BoardImage
import com.pawneyes.chess.Move
import com.pawneyes.chess.Pgn
import com.pawneyes.chess.Position
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Compartir la posición con otras apps: PGN como texto o el tablero como imagen. */
object Sharing {
    fun sharePgn(context: Context, start: Position, moves: List<Move> = emptyList(), comment: String? = null) {
        val date = SimpleDateFormat("yyyy.MM.dd", Locale.ROOT).format(Date())
        val pgn = Pgn.of(start, moves, comment, date)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_pgn_subject))
            putExtra(Intent.EXTRA_TEXT, pgn)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_pgn)))
    }

    /** El ZIP de escaneos guardados para entrenar (TrainingStore.exportZip). */
    fun shareZip(context: Context, zip: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, zip.name)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.training_export_title)))
    }

    fun shareImage(
        context: Context,
        position: Position,
        flipped: Boolean,
        arrows: List<Pair<Int, Int>>,
        highlighted: Set<Int> = emptySet(),
    ) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "tablero.png")
        val bitmap = BoardImage.render(context, position, flipped, arrows, highlighted)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, position.toFen())
            clipData = ClipData.newRawUri(null, uri)  // miniatura en el selector y permiso de lectura
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_image)))
    }
}
