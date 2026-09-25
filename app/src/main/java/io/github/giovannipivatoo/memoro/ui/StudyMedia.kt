// SPDX-License-Identifier: BSD-2-Clause
package io.github.giovannipivatoo.memoro.ui

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.giovannipivatoo.memoro.data.MemoroRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

private val mediaMarker = Regex("\\[(image|audio):([^]]+)]")

@Composable
internal fun StudyFace(face: String, repo: MemoroRepository, modifier: Modifier = Modifier, textStyle: TextStyle = MaterialTheme.typography.bodyLarge) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        var start = 0
        mediaMarker.findAll(face).forEach { match ->
            val text = face.substring(start, match.range.first).trim()
            if (text.isNotBlank()) Text(text, style = textStyle)
            val path = match.groupValues[2]
            if (path.startsWith("media/") && !path.contains("..")) {
                if (match.groupValues[1] == "image") StudyImage(repo, path)
                else StudyAudio(repo, path)
            }
            start = match.range.last + 1
        }
        val tail = face.substring(start).trim()
        if (tail.isNotBlank()) Text(tail, style = textStyle)
    }
}

@Composable
private fun StudyImage(repo: MemoroRepository, path: String) {
    val image by produceState<android.graphics.Bitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            try {
                repo.openFile(path)?.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > 12 * 1024 * 1024) return@use null
                        output.write(buffer, 0, count)
                    }
                    val bytes = output.toByteArray()
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val sample = generateSequence(1) { it * 2 }.first { bounds.outWidth / it <= 1600 && bounds.outHeight / it <= 1600 }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                }
            } catch (_: IOException) { null } catch (_: IllegalArgumentException) { null }
        }
    }
    if (image != null) Image(image!!.asImageBitmap(), contentDescription = "Immagine della carta", modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit)
    else Text("Immagine non disponibile", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun StudyAudio(repo: MemoroRepository, path: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playing by remember(path) { mutableStateOf(false) }
    var error by remember(path) { mutableStateOf(false) }
    var player by remember(path) { mutableStateOf<MediaPlayer?>(null) }
    var tempFile by remember(path) { mutableStateOf<File?>(null) }
    DisposableEffect(path) { onDispose { player?.release(); tempFile?.delete() } }
    OutlinedButton(enabled = !playing, onClick = { scope.launch {
        try {
            playing = true; error = false
            val file = withContext(Dispatchers.IO) {
                val extension = path.substringAfterLast('.').takeIf { it.matches(Regex("[A-Za-z0-9]{1,8}")) } ?: "audio"
                val temp = File.createTempFile("memoro-audio-", ".$extension", context.cacheDir)
                repo.openFile(path)?.use { input -> temp.outputStream().use { input.copyTo(it) } } ?: error("Audio non disponibile")
                temp
            }
            player?.release()
            tempFile?.delete()
            tempFile = file
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { playing = false; release(); player = null; file.delete(); tempFile = null }
                prepare(); start()
            }
        } catch (_: Exception) { playing = false; error = true }
    } }) { Text(if (playing) "Audio in riproduzione" else "Riproduci audio") }
    if (error) Text("Audio non disponibile", color = MaterialTheme.colorScheme.error)
}
