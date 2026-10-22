package com.pawneyes.app

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource

/** «Compartir», con un menú para elegir PGN o imagen del tablero. */
@Composable
fun ShareButton(onPgn: () -> Unit, onImage: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(stringResource(R.string.share)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.share_pgn)) }, onClick = { open = false; onPgn() })
            DropdownMenuItem(text = { Text(stringResource(R.string.share_image)) }, onClick = { open = false; onImage() })
        }
    }
}
