package com.zenith.player

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import androidx.room.Room
import com.google.common.util.concurrent.ListenableFuture
import com.zenith.player.cloud.SupabaseAccountPanel
import com.zenith.player.data.TrackEntity
import com.zenith.player.data.ZenithDatabase
import com.zenith.player.playback.ZenithPlaybackService
import com.zenith.player.playback.ZenithAudioEffects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import coil.compose.AsyncImage
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private val Ink = Color(0xFF000000)
private val Panel = Color(0xFF121212)
private val Card = Color(0xFF1B1B1D)
private val Muted = Color(0xFFA1A1A6)
private val Silver = Color(0xFFE5E5E7)

class MainActivity : ComponentActivity() {
    private val db by lazy { ZenithDatabase.get(this) }
    private var controller: MediaController? = null
    private val controllerReady = androidx.compose.runtime.mutableStateOf(false)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val pickAudio = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) importTracks(uris)
    }
    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attachVideoCover(uri)
    }
    private var videoTargetUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectPlayer()
        setContent {
            var tracks by remember { mutableStateOf(emptyList<TrackEntity>()) }
            var selected by remember { mutableStateOf("Главная") }
            var query by remember { mutableStateOf("") }
            var nowTitle by remember { mutableStateOf("Выберите, что послушать") }
            var nowArtist by remember { mutableStateOf("ZENITH") }
            var currentTrack by remember { mutableStateOf<TrackEntity?>(null) }
            var isPlaying by remember { mutableStateOf(false) }
            var showFullPlayer by remember { mutableStateOf(false) }
            var shuffle by remember { mutableStateOf(false) }
            var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
            var position by remember { mutableLongStateOf(0L) }
            var duration by remember { mutableLongStateOf(0L) }
            var speed by remember { mutableFloatStateOf(1f) }
            var sleepSeconds by remember { mutableIntStateOf(0) }
            var sleepDeadlineMs by remember { mutableLongStateOf(0L) }
            var lyrics by remember { mutableStateOf<String?>(null) }
            var lyricsLoading by remember { mutableStateOf(false) }
            var lyricsError by remember { mutableStateOf<String?>(null) }
            var artworkLoading by remember { mutableStateOf(false) }
            val playerReady by controllerReady

            LaunchedEffect(Unit) { db.dao().observeTracks().collectLatest { tracks = it } }
            LaunchedEffect(sleepDeadlineMs) {
                if (sleepDeadlineMs > 0L) {
                    while (true) {
                        val remaining = ((sleepDeadlineMs - System.currentTimeMillis() + 999L) / 1000L).toInt()
                        if (remaining <= 0) break
                        sleepSeconds = remaining
                        delay(1000)
                    }
                    sleepSeconds = 0
                    sleepDeadlineMs = 0L
                    controller?.pause()
                } else sleepSeconds = 0
            }
            LaunchedEffect(currentTrack?.uri) {
                lyrics = null
                lyricsError = null
            }
            LaunchedEffect(tracks, currentTrack?.uri) {
                val activeUri = currentTrack?.uri
                if (activeUri != null) currentTrack = tracks.firstOrNull { it.uri == activeUri } ?: currentTrack
            }
            LaunchedEffect(playerReady, tracks) {
                if (playerReady) {
                    val c = controller ?: return@LaunchedEffect
                    val listener = object : Player.Listener {
                        override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            val uri = mediaItem?.localConfiguration?.uri?.toString()
                            currentTrack = tracks.firstOrNull { it.uri == uri }
                            nowTitle = mediaItem?.mediaMetadata?.title?.toString() ?: "ZENITH"
                            nowArtist = mediaItem?.mediaMetadata?.artist?.toString() ?: "Локальная библиотека"
                        }
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            duration = c.duration.takeIf { it > 0 } ?: (currentTrack?.durationMs ?: 0L)
                        }
                    }
                    c.addListener(listener)
                    if (c.currentMediaItem != null) {
                        val uri = c.currentMediaItem?.localConfiguration?.uri?.toString()
                        currentTrack = tracks.firstOrNull { it.uri == uri }
                    }
                    try {
                        while (true) {
                            position = c.currentPosition.coerceAtLeast(0L)
                            duration = c.duration.takeIf { it > 0 } ?: (currentTrack?.durationMs ?: 0L)
                            delay(350)
                        }
                    } finally { c.removeListener(listener) }
                }
            }
            val shown = if (selected == "Поиск" && query.isNotBlank()) tracks.filter {
                it.title.contains(query, true) || it.artist.contains(query, true)
            } else if (selected == "Медиатека") tracks.filter { it.isLiked } else tracks

            MaterialTheme(colorScheme = darkColorScheme(
                background = Ink, surface = Panel, surfaceVariant = Card,
                primary = Silver, onPrimary = Ink, onBackground = Color.White,
                onSurface = Color.White, secondary = Muted
            )) {
                BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
                    val wide = maxWidth > 700.dp
                    if (wide) {
                        Row(Modifier.fillMaxSize().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NavigationPanel(selected, { selected = it }, Modifier.width(230.dp).fillMaxHeight())
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                MainContent(selected, tracks, shown, query, { query = it }, { pickAudio.launch(arrayOf("audio/*")) }, { playTrack(it, tracks, shuffle) }, { toggleLike(it) }, Modifier.weight(1f))
                                MiniPlayer(nowTitle, nowArtist, isPlaying, currentTrack?.artworkUrl, { togglePlayback() }, { playNext() }, { showFullPlayer = true })
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxSize()) {
                            MainContent(selected, tracks, shown, query, { query = it }, { pickAudio.launch(arrayOf("audio/*")) }, { playTrack(it, tracks, shuffle) }, { toggleLike(it) }, Modifier.weight(1f))
                            MiniPlayer(nowTitle, nowArtist, isPlaying, currentTrack?.artworkUrl, { togglePlayback() }, { playNext() }, { showFullPlayer = true })
                            NavigationBar(containerColor = Panel, contentColor = Silver) {
                                listOf("Главная" to Icons.Default.Home, "Поиск" to Icons.Default.Search, "Медиатека" to Icons.Default.LibraryMusic, "Настройки" to Icons.Default.Settings).forEach { (label, icon) ->
                                    NavigationBarItem(selected == label, onClick = { selected = label }, icon = { Icon(icon, null) }, label = { Text(label) })
                                }
                            }
                        }
                    }
                    if (showFullPlayer) {
                        FullPlayerDialog(
                            track = currentTrack, title = nowTitle, artist = nowArtist, playing = isPlaying,
                            position = position, duration = duration, shuffle = shuffle, repeatMode = repeatMode,
                            speed = speed, sleepSeconds = sleepSeconds, lyrics = lyrics, artworkLoading = artworkLoading,
                            lyricsLoading = lyricsLoading, lyricsError = lyricsError,
                            queue = controller?.let { c -> (0 until c.mediaItemCount).mapNotNull { i ->
                                val uri = c.getMediaItemAt(i).localConfiguration?.uri?.toString()
                                tracks.firstOrNull { it.uri == uri }
                            } } ?: emptyList(),
                            onDismiss = { showFullPlayer = false }, onToggle = { togglePlayback() },
                            onPrevious = { controller?.seekToPreviousMediaItem() }, onNext = { playNext() },
                            onSeek = { controller?.seekTo(it) }, onShuffle = { shuffle = !shuffle; controller?.shuffleModeEnabled = shuffle },
                            onRepeat = { repeatMode = when (repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }; controller?.repeatMode = repeatMode },
                            onSpeed = { speed = it; controller?.playbackParameters = PlaybackParameters(it) },
                            onAddVideo = { currentTrack?.let { videoTargetUri = it.uri; pickVideo.launch(arrayOf("video/*")) } },
                            onPlayQueueItem = { playTrack(it, tracks, shuffle) },
                            onSleepTimer = { seconds -> if (seconds <= 0) { sleepDeadlineMs = 0L; sleepSeconds = 0 } else { sleepDeadlineMs = System.currentTimeMillis() + seconds * 1000L; sleepSeconds = seconds } },
                            onFindArtwork = { currentTrack?.let { selectedTrack ->
                                artworkLoading = true
                                lifecycleScope.launch {
                                    val result = lookupArtwork(selectedTrack)
                                    if (result != null) db.dao().setArtwork(selectedTrack.uri, result)
                                    artworkLoading = false
                                    if (result == null) withContext(Dispatchers.Main) { android.widget.Toast.makeText(this@MainActivity, "Обложка не найдена", android.widget.Toast.LENGTH_SHORT).show() }
                                }
                            } },
                            onLoadLyrics = { currentTrack?.let { selectedTrack ->
                                lyricsLoading = true; lyricsError = null
                                lifecycleScope.launch {
                                    val result = loadLrclibLyrics(selectedTrack)
                                    lyrics = result.first; lyricsError = result.second; lyricsLoading = false
                                }
                            } }
                        )
                    }
                }
            }
        }
    }

    private suspend fun loadLrclibLyrics(track: TrackEntity): Pair<String?, String?> = withContext(Dispatchers.IO) {
        try {
            fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
            val durationSeconds = (track.durationMs / 1000).coerceAtLeast(1)
            val url = "https://lrclib.net/api/get?track_name=${enc(track.title)}&artist_name=${enc(track.artist)}&duration=$durationSeconds"
            val request = Request.Builder().url(url).header("User-Agent", "ZENITH-Android/1.1").build()
            val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Pair(null, "Текст для этой песни не найден")
                val json = JSONObject(response.body?.string().orEmpty())
                val text = json.optString("syncedLyrics").takeIf { it.isNotBlank() }
                    ?: json.optString("plainLyrics").takeIf { it.isNotBlank() }
                Pair(text, if (text == null) "Текст для этой песни не найден" else null)
            }
        } catch (_: Exception) { Pair(null, "Не удалось загрузить текст. Проверь подключение к интернету") }
    }

    private suspend fun lookupArtwork(track: TrackEntity): String? = withContext(Dispatchers.IO) {
        try {
            fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
            val term = listOf(track.artist, track.album.ifBlank { track.title }).joinToString(" ")
            val url = "https://itunes.apple.com/search?term=${enc(term)}&entity=song&limit=8"
            val request = Request.Builder().url(url).header("User-Agent", "ZENITH-Android/1.2").build()
            val client = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val results = JSONObject(response.body?.string().orEmpty()).optJSONArray("results") ?: return@withContext null
                var best: String? = null
                var bestScore = -1
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val itemArtist = item.optString("artistName")
                    val itemTrack = item.optString("trackName")
                    val itemAlbum = item.optString("collectionName")
                    var score = 0
                    if (itemArtist.equals(track.artist, true)) score += 4
                    if (itemTrack.equals(track.title, true)) score += 3
                    if (track.album.isNotBlank() && itemAlbum.equals(track.album, true)) score += 2
                    val image = item.optString("artworkUrl100").takeIf { it.startsWith("https://") }
                    if (image != null && score > bestScore) { best = image.replace("100x100bb", "600x600bb"); bestScore = score }
                }
                best
            }
        } catch (_: Exception) { null }
    }

    private fun connectPlayer() {
        val token = SessionToken(this, ComponentName(this, ZenithPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            try { controller = controllerFuture?.get(); controllerReady.value = controller != null } catch (_: Exception) { }
        }, Executor { it.run() })
    }

    private fun importTracks(uris: List<Uri>) {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                    val cursor = contentResolver.query(uri, null, null, null, null) ?: return@mapNotNull null
                    val displayName = cursor.use { c ->
                        val nameIndex = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (c.moveToFirst() && nameIndex >= 0) c.getString(nameIndex) else "Без названия"
                    }
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(this@MainActivity, uri)
                        val title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: displayName.substringBeforeLast('.', displayName)
                        val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() } ?: "Локальный файл"
                        val album = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
                        val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        TrackEntity(uri = uri.toString(), title = title, artist = artist, album = album, durationMs = duration)
                    } catch (_: Exception) {
                        TrackEntity(uri = uri.toString(), title = displayName.substringBeforeLast('.', displayName), artist = "Локальный файл")
                    } finally { try { retriever.release() } catch (_: Exception) { } }
                }
            }
            db.dao().upsertTracks(rows)
        }
    }

    private fun attachVideoCover(uri: Uri) {
        val target = videoTargetUri ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val retriever = android.media.MediaMetadataRetriever()
                val duration = try { retriever.setDataSource(this@MainActivity, uri); retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } finally { retriever.release() }
                if (duration in 1..7000) db.dao().setVideoCover(target, uri.toString())
                else withContext(Dispatchers.Main) { android.widget.Toast.makeText(this@MainActivity, "Видеообложка должна длиться не более 7 секунд", android.widget.Toast.LENGTH_LONG).show() }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { android.widget.Toast.makeText(this@MainActivity, "Не удалось открыть видеофайл", android.widget.Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun playTrack(track: TrackEntity, library: List<TrackEntity>, shuffle: Boolean) {
        val c = controller ?: return
        val index = library.indexOfFirst { it.uri == track.uri }.coerceAtLeast(0)
        val items = library.map { row -> MediaItem.Builder().setUri(row.uri)
            .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(row.title).setArtist(row.artist).build()).build() }
        c.setMediaItems(items, index, 0L); c.shuffleModeEnabled = shuffle; c.prepare(); c.play()
    }
    private fun togglePlayback() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    private fun playNext() { controller?.seekToNextMediaItem() }
    private fun toggleLike(track: TrackEntity) { lifecycleScope.launch { db.dao().setLiked(track.uri, !track.isLiked) } }
    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }
}

@Composable
private fun NavigationPanel(selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.background(Panel, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text("◉  Z E N I T H", color = Silver, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(12.dp))
        listOf("Главная" to Icons.Default.Home, "Поиск" to Icons.Default.Search, "Медиатека" to Icons.Default.LibraryMusic, "Настройки" to Icons.Default.Settings).forEach { (label, icon) ->
            NavigationDrawerItem(label = { Text(label) }, selected = selected == label, onClick = { onSelect(label) }, icon = { Icon(icon, null) }, modifier = Modifier.padding(vertical = 3.dp))
        }
        Spacer(Modifier.weight(1f))
        Text("OFFLINE-FIRST", color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun MainContent(
    selected: String, allTracks: List<TrackEntity>, tracks: List<TrackEntity>, query: String,
    onQuery: (String) -> Unit, onImport: () -> Unit, onPlay: (TrackEntity) -> Unit,
    onLike: (TrackEntity) -> Unit, modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize().background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 22.dp, vertical = 20.dp)) {
        when (selected) {
            "Поиск" -> { Text("Поиск", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold); OutlinedTextField(query, onQuery, modifier = Modifier.fillMaxWidth().padding(top = 14.dp), placeholder = { Text("Треки и исполнители") }, singleLine = true) }
            "Медиатека" -> Text("Избранное", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            "Настройки" -> { Text("Настройки", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold); Text("Аккаунт и облачная синхронизация", color = Muted, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp)); SupabaseAccountPanel() }
            else -> { Text("Добро пожаловать в", color = Muted); Text("ZENITH", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black, color = Silver); Text("Твоя музыка. Твоя библиотека.", color = Muted) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("Треки", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text("${tracks.size} в списке · ${allTracks.size} всего", color = Muted, style = MaterialTheme.typography.bodySmall) }
            FilledTonalButton(onClick = onImport) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Добавить музыку") }
        }
        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.LibraryMusic, null, tint = Muted, modifier = Modifier.size(54.dp)); Text("Здесь пока тихо", color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp)); Text("Добавь аудиофайлы с устройства", color = Muted) }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(tracks, key = { _, track -> track.uri }) { _, track ->
                    Row(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(10.dp)).clickable { onPlay(track) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).background(Color(0xFF303034), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = Silver) }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(track.artist, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                        IconButton(onClick = { onLike(track) }) { Icon(if (track.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, tint = if (track.isLiked) Silver else Muted) }
                        IconButton(onClick = { onPlay(track) }) { Icon(Icons.Default.PlayArrow, null, tint = Silver) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(title: String, artist: String, playing: Boolean, artworkUrl: String?, onToggle: () -> Unit, onNext: () -> Unit, onExpand: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp).background(Card, RoundedCornerShape(12.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).clickable(onClick = onExpand), verticalAlignment = Alignment.CenterVertically) {
            if (artworkUrl != null) AsyncImage(model = artworkUrl, contentDescription = "Обложка альбома", modifier = Modifier.size(44.dp), contentScale = ContentScale.Crop) else Box(Modifier.size(44.dp).background(Color(0xFF303034), RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Default.MusicNote, null, tint = Silver) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(artist, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
        }
        IconButton(onClick = onToggle) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Silver) }
        IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, null, tint = Silver) }
        IconButton(onClick = onExpand) { Icon(Icons.Default.OpenInFull, null, tint = Muted) }
    }
}

@Composable
private fun FullPlayerDialog(
    track: TrackEntity?, title: String, artist: String, playing: Boolean, position: Long, duration: Long,
    shuffle: Boolean, repeatMode: Int, speed: Float, sleepSeconds: Int, lyrics: String?, artworkLoading: Boolean,
    lyricsLoading: Boolean, lyricsError: String?, queue: List<TrackEntity>,
    onDismiss: () -> Unit, onToggle: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onSeek: (Long) -> Unit, onShuffle: () -> Unit, onRepeat: () -> Unit, onSpeed: (Float) -> Unit,
    onAddVideo: () -> Unit, onPlayQueueItem: (TrackEntity) -> Unit,
    onSleepTimer: (Int) -> Unit, onLoadLyrics: () -> Unit, onFindArtwork: () -> Unit
) {
    var showQueue by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var showEqualizer by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF09090A)).padding(horizontal = 22.dp, vertical = 18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Default.KeyboardArrowDown, "Свернуть", tint = Silver) }
                Spacer(Modifier.weight(1f)); Text("СЕЙЧАС ИГРАЕТ", color = Muted, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f))
                IconButton(onClick = { showQueue = !showQueue }) { Icon(Icons.Default.QueueMusic, "Очередь", tint = Silver) }
            }
            if (track?.videoCoverUri != null) VideoCover(track.videoCoverUri!!, Modifier.fillMaxWidth().weight(1f).padding(top = 18.dp))
            else if (track?.artworkUrl != null) AsyncImage(model = track.artworkUrl, contentDescription = "Обложка альбома", modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 18.dp), contentScale = ContentScale.Fit)
            else Box(Modifier.fillMaxWidth().weight(1f).padding(top = 18.dp).background(Color(0xFF1B1B1E), RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AudioVisualizer(active = playing, modifier = Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 24.dp))
                    Text("ZENITH", color = Color(0xFF77777E), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Black, modifier = Modifier.padding(top = 18.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(title, color = Silver, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(artist, color = Muted, style = MaterialTheme.typography.titleMedium, maxLines = 1) }
                IconButton(onClick = onAddVideo) { Icon(Icons.Default.VideoLibrary, "Добавить видеообложку", tint = Muted) }
            }
            Slider(value = if (duration > 0) position.coerceIn(0, duration).toFloat() else 0f, onValueChange = { onSeek(it.toLong()) }, valueRange = 0f..(duration.coerceAtLeast(1L).toFloat()), modifier = Modifier.padding(top = 10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(formatTime(position), color = Muted, style = MaterialTheme.typography.labelSmall); Text(formatTime(duration), color = Muted, style = MaterialTheme.typography.labelSmall) }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onShuffle) { Icon(Icons.Default.Shuffle, "Перемешать", tint = if (shuffle) Silver else Muted) }
                IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Предыдущий", tint = Silver, modifier = Modifier.size(34.dp)) }
                FilledIconButton(onClick = onToggle, modifier = Modifier.size(68.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Silver, contentColor = Ink)) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, modifier = Modifier.size(36.dp)) }
                IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Следующий", tint = Silver, modifier = Modifier.size(34.dp)) }
                IconButton(onClick = onRepeat) { Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Повтор", tint = if (repeatMode == Player.REPEAT_MODE_OFF) Muted else Silver) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(when (repeatMode) { Player.REPEAT_MODE_ALL -> "Повтор очереди"; Player.REPEAT_MODE_ONE -> "Повтор трека"; else -> "Повтор выключен" }, color = Muted, style = MaterialTheme.typography.labelSmall)
                Box {
                    TextButton(onClick = { showSpeed = true }) { Text("${speed}×", color = Silver) }
                    DropdownMenu(expanded = showSpeed, onDismissRequest = { showSpeed = false }) {
                        listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { value -> DropdownMenuItem(text = { Text("${value}×") }, onClick = { onSpeed(value); showSpeed = false }) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onFindArtwork, enabled = !artworkLoading) { if (artworkLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Album, null); Spacer(Modifier.width(5.dp)); Text("Обложка") }
                TextButton(onClick = { showEqualizer = true }) { Icon(Icons.Default.Equalizer, null); Spacer(Modifier.width(5.dp)); Text("Эквалайзер") }
                TextButton(onClick = { showSleep = true }) { Icon(Icons.Default.Bedtime, null); Spacer(Modifier.width(5.dp)); Text(if (sleepSeconds > 0) "Сон ${formatTime(sleepSeconds * 1000L)}" else "Таймер сна") }
                TextButton(onClick = { showLyrics = true; if (lyrics == null && !lyricsLoading) onLoadLyrics() }) { Icon(Icons.Default.Subtitles, null); Spacer(Modifier.width(5.dp)); Text("Текст") }
            }
            if (showQueue) {
                Text("Очередь воспроизведения", color = Silver, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp))
                LazyColumn(Modifier.fillMaxWidth().weight(0.7f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    itemsIndexed(queue, key = { _, item -> item.uri }) { _, item ->
                        Row(Modifier.fillMaxWidth().clickable { onPlayQueueItem(item) }.padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MusicNote, null, tint = Muted); Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(item.title, color = Silver, maxLines = 1); Text(item.artist, color = Muted, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            if (showEqualizer) EqualizerDialog(onDismiss = { showEqualizer = false })
            if (showSleep) AlertDialog(
                onDismissRequest = { showSleep = false },
                title = { Text("Таймер сна") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (sleepSeconds > 0) "Осталось: ${formatTime(sleepSeconds * 1000L)}" else "Остановить воспроизведение через:")
                    listOf(5, 10, 15, 30, 45, 60).forEach { minutes -> TextButton(onClick = { onSleepTimer(minutes * 60); showSleep = false }) { Text("$minutes минут") } }
                    TextButton(onClick = { onSleepTimer(0); showSleep = false }) { Text("Отключить таймер") }
                } }, confirmButton = { TextButton(onClick = { showSleep = false }) { Text("Закрыть") } }
            )
            if (showLyrics) AlertDialog(
                onDismissRequest = { showLyrics = false },
                title = { Text("Текст песни") },
                text = { Column(Modifier.heightIn(max = 420.dp)) {
                    when { lyricsLoading -> CircularProgressIndicator(); lyrics != null -> SyncedLyrics(lyrics = lyrics!!, positionMs = position); else -> Text(lyricsError ?: "Нажми обновить, чтобы найти текст", color = Muted) }
                } },
                confirmButton = { TextButton(onClick = { onLoadLyrics() }) { Text("Обновить") } },
                dismissButton = { TextButton(onClick = { showLyrics = false }) { Text("Закрыть") } }
            )
        }
    }
}

private data class LyricLine(val timeMs: Long, val text: String)

private fun parseTimedLyrics(raw: String): List<LyricLine> {
    val regex = Regex("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?]")
    return raw.lines().flatMap { line ->
        val matches = regex.findAll(line).toList()
        if (matches.isEmpty()) emptyList() else {
            val text = line.substringAfterLast("]").trim()
            matches.map { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                LyricLine((minutes * 60 + seconds) * 1000 + fraction, text)
            }
        }
    }.sortedBy { it.timeMs }
}

@Composable
private fun SyncedLyrics(lyrics: String, positionMs: Long) {
    val lines = remember(lyrics) { parseTimedLyrics(lyrics) }
    if (lines.isEmpty()) {
        Text(lyrics, color = Silver, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp))
    } else {
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val active = lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)
        LaunchedEffect(active) { listState.animateScrollToItem((active - 2).coerceAtLeast(0)) }
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(lines) { index, line ->
                Text(line.text.ifBlank { "♪" }, color = if (index == active) Silver else Muted.copy(alpha = 0.62f), fontWeight = if (index == active) FontWeight.Bold else FontWeight.Normal, style = if (index == active) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AudioVisualizer(active: Boolean, modifier: Modifier = Modifier) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "zenith-visualizer")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = (2f * Math.PI).toFloat(),
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(1100, easing = androidx.compose.animation.core.LinearEasing)
        ), label = "wave"
    )
    Canvas(modifier) {
        val count =  thirtyBars
        val gap = size.width * 0.012f
        val barWidth = (size.width - gap * (count - 1)) / count
        for (i in 0 until count) {
            val wave = kotlin.math.abs(kotlin.math.sin(phase + i * 0.57f)).toFloat()
            val envelope = (0.18f + 0.82f * wave) * if (active) 1f else 0.16f
            val h = size.height * envelope
            drawRoundRect(
                color = Color(0xFFBFC0C7).copy(alpha = if (active) 0.9f else 0.3f),
                topLeft = androidx.compose.ui.geometry.Offset(i * (barWidth + gap), (size.height - h) / 2f),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f)
            )
        }
    }
}

private const val thirtyBars = 30

@Composable
private fun EqualizerDialog(onDismiss: () -> Unit) {
    val bands = remember { ZenithAudioEffects.bandCount() }
    val range = remember { ZenithAudioEffects.bandRange() }
    var enabled by remember { mutableStateOf(ZenithAudioEffects.isEnabled()) }
    var levels by remember { mutableStateOf((0 until bands).map { ZenithAudioEffects.bandLevel(it) }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Эквалайзер") },
        text = {
            if (bands == 0) Text("Эквалайзер недоступен для текущего аудиоустройства", color = Muted)
            else Column(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Включён", modifier = Modifier.weight(1f)); Switch(checked = enabled, onCheckedChange = { enabled = it; ZenithAudioEffects.setEnabled(it) }) }
                levels.forEachIndexed { index, level ->
                    Text("${ZenithAudioEffects.bandFrequency(index)} Гц", color = Muted, style = MaterialTheme.typography.labelSmall)
                    Slider(value = level.toFloat(), onValueChange = { value ->
                        val updated = levels.toMutableList().also { it[index] = value.toInt() }
                        levels = updated; ZenithAudioEffects.setBand(index, value.toInt())
                    }, valueRange = range[0].toFloat()..range[1].toFloat())
                }
                Text("Настройки зависят от аудиоустройства", color = Muted, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        dismissButton = { TextButton(onClick = { levels = List(bands) { 0 }; levels.indices.forEach { ZenithAudioEffects.setBand(it, 0) }; enabled = true; ZenithAudioEffects.setEnabled(true) }) { Text("Сбросить") } }
    )
}

@Composable
private fun VideoCover(uri: String, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(uri) { ExoPlayer.Builder(context).build().apply {
        volume = 0f
        repeatMode = Player.REPEAT_MODE_ONE
        val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(7000L).build()).build()
        setMediaItem(item); prepare(); playWhenReady = true
    } }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(modifier = modifier, factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER) } }, update = { it.player = player })
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
