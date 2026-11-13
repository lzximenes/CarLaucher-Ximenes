@file:OptIn(ExperimentalFoundationApi::class)

package com.ximenes.carlauncher

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------- Modelos e temas ----------

data class AppItem(val pkg: String, val label: String, val icon: ImageBitmap)

data class LauncherTheme(
    val name: String,
    val bgTop: Color,
    val bgBottom: Color,
    val accent: Color,
    val card: Color,
)

val Themes = listOf(
    LauncherTheme("Azul Noite", Color(0xFF0B1030), Color(0xFF1A3A8F), Color(0xFF2F7BFF), Color(0x33FFFFFF)),
    LauncherTheme("Vermelho Esporte", Color(0xFF1A0508), Color(0xFF7A1020), Color(0xFFFF3B4A), Color(0x33FFFFFF)),
    LauncherTheme("Verde Floresta", Color(0xFF06140E), Color(0xFF0F5A3A), Color(0xFF2EE59D), Color(0x33FFFFFF)),
    LauncherTheme("Âmbar", Color(0xFF140C02), Color(0xFF6B3A06), Color(0xFFFFB020), Color(0x33FFFFFF)),
    LauncherTheme("AMOLED", Color(0xFF000000), Color(0xFF0A0A0A), Color(0xFF666666), Color(0x22FFFFFF)),
)

val DEFAULT_DOCK = listOf(
    "com.spotify.music",
    "com.google.android.apps.youtube.music",
    "com.waze",
    "com.google.android.apps.maps",
    "com.google.android.youtube",
    "com.android.chrome",
    "com.android.settings",
)

// ---------- Preferências ----------

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    var themeIndex: Int
        get() = sp.getInt("theme", 0).coerceIn(0, Themes.lastIndex)
        set(v) {
            sp.edit().putInt("theme", v).apply()
        }

    // null = nunca personalizado (usa o dock padrão)
    var dock: List<String>?
        get() = sp.getString("dock", null)?.split(",")?.filter { it.isNotBlank() }
        set(v) {
            sp.edit().putString("dock", (v ?: emptyList()).joinToString(",")).apply()
        }
}

// ---------- Utilitários ----------

fun toast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

fun loadApps(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(intent, 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        .distinctBy { it.activityInfo.packageName }
        .map { ri ->
            AppItem(
                ri.activityInfo.packageName,
                ri.loadLabel(pm).toString(),
                ri.loadIcon(pm).toBitmap(128, 128).asImageBitmap(),
            )
        }
        .sortedBy { it.label.lowercase() }
}

fun launchApp(ctx: Context, pkg: String) {
    val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(i)
    } catch (e: Exception) {
        toast(ctx, "Não foi possível abrir o app")
    }
}

fun openSettings(ctx: Context, action: String) {
    try {
        ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        toast(ctx, "Esta central não abriu essa tela de ajustes")
    }
}

// ---------- Foto do carro ----------

fun carFile(ctx: Context) = File(ctx.filesDir, "car.png")

fun loadCar(ctx: Context): ImageBitmap? {
    val f = carFile(ctx)
    if (!f.exists()) return null
    return BitmapFactory.decodeFile(f.path)?.asImageBitmap()
}

fun saveCar(ctx: Context, uri: Uri): Boolean {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (bmp == null) {
            false
        } else {
            carFile(ctx).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        }
    } catch (e: Exception) {
        false
    }
}

// ---------- Mídia ----------

class MediaListener : NotificationListenerService()

data class MediaInfo(
    val title: String,
    val artist: String,
    val playing: Boolean,
    val controller: MediaController?,
)

// Retorna null se o acesso a notificações ainda não foi liberado
fun readMedia(ctx: Context): MediaInfo? {
    return try {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val c = msm.getActiveSessions(ComponentName(ctx, MediaListener::class.java)).firstOrNull()
        if (c == null) {
            MediaInfo("Nenhuma música tocando", "", false, null)
        } else {
            val md = c.metadata
            MediaInfo(
                md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: "Reproduzindo",
                md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
                c.playbackState?.state == PlaybackState.STATE_PLAYING,
                c,
            )
        }
    } catch (e: SecurityException) {
        null
    }
}

// ---------- Activity ----------

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(applicationContext)
        setContent { LauncherScreen(prefs) }
    }
}

// ---------- Tela principal ----------

@Composable
fun LauncherScreen(prefs: Prefs) {
    val ctx = LocalContext.current
    var themeIdx by remember { mutableIntStateOf(prefs.themeIndex) }
    val theme = Themes[themeIdx]
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var showApps by remember { mutableStateOf(false) }
    var dock by remember { mutableStateOf(prefs.dock) }
    var car by remember { mutableStateOf(loadCar(ctx)) }
    var media by remember { mutableStateOf<MediaInfo?>(null) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }

    // Recarrega a lista de apps ao abrir/fechar a grade (pega apps recém-instalados)
    LaunchedEffect(showApps) { apps = withContext(Dispatchers.Default) { loadApps(ctx) } }
    LaunchedEffect(Unit) {
        while (true) {
            media = readMedia(ctx)
            delay(1500)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1000)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            if (saveCar(ctx, uri)) car = loadCar(ctx) else toast(ctx, "Não consegui abrir essa imagem")
        }
    }
    val pickCar = {
        try {
            picker.launch("image/*")
        } catch (e: ActivityNotFoundException) {
            toast(ctx, "Esta central não tem app de galeria ou arquivos")
        }
    }

    val dockPkgs: List<String> = dock ?: DEFAULT_DOCK.filter { p -> apps.any { it.pkg == p } }
    val dockApps = dockPkgs.mapNotNull { p -> apps.find { it.pkg == p } }

    fun toggleDock(pkg: String) {
        val updated = if (pkg in dockPkgs) dockPkgs - pkg else (dockPkgs + pkg).takeLast(8)
        dock = updated
        prefs.dock = updated
    }

    val time = now.format(DateTimeFormatter.ofPattern("HH:mm"))
    val date = now.format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", Locale("pt", "BR")))
        .replaceFirstChar { it.uppercase() }

    BackHandler(enabled = showApps) { showApps = false }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(theme.bgTop, theme.bgBottom)))
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                // Coluna esquerda: relógio + carro
                Column(Modifier.weight(1.2f).fillMaxHeight()) {
                    Text(time, color = Color.White, fontSize = 88.sp, fontWeight = FontWeight.Bold)
                    Text(date, color = Color.White.copy(alpha = 0.75f), fontSize = 24.sp)
                    Box(
                        Modifier.weight(1f).fillMaxWidth().clickable { pickCar() },
                        contentAlignment = Alignment.Center,
                    ) {
                        val c = car
                        if (c != null) {
                            Image(
                                bitmap = c,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text(
                                "Toque aqui para escolher a foto do seu carro",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 20.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(24.dp))
                // Coluna direita: ajustes + mídia
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Chip("Tema", theme) {
                            themeIdx = (themeIdx + 1) % Themes.size
                            prefs.themeIndex = themeIdx
                            toast(ctx, Themes[themeIdx].name)
                        }
                        Chip("Foto do carro", theme) { pickCar() }
                        Chip("Ajustes", theme) { openSettings(ctx, Settings.ACTION_SETTINGS) }
                    }
                    MediaCard(media, theme)
                }
            }
            Spacer(Modifier.height(16.dp))
            // Dock
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(theme.card)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                dockApps.forEach { app ->
                    DockIcon(app, onClick = { launchApp(ctx, app.pkg) }, onLongClick = { toggleDock(app.pkg) })
                }
                Spacer(Modifier.weight(1f))
                AppsButton(theme) { showApps = true }
            }
        }
        if (showApps) {
            AppsOverlay(apps, dockPkgs, theme, onToggle = { toggleDock(it) }, onClose = { showApps = false })
        }
    }
}

// ---------- Componentes ----------

@Composable
fun Chip(text: String, theme: LauncherTheme, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(theme.card)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        Text(text, color = Color.White, fontSize = 16.sp)
    }
}

@Composable
fun DockIcon(app: AppItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(bitmap = app.icon, contentDescription = app.label, modifier = Modifier.size(64.dp))
        Text(
            app.label,
            color = Color.White,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(76.dp),
        )
    }
}

@Composable
fun AppsButton(theme: LauncherTheme, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(theme.accent),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(30.dp)) {
                val w = this.size.width
                val s = w * 0.42f
                val corners = listOf(Offset(0f, 0f), Offset(w - s, 0f), Offset(0f, w - s), Offset(w - s, w - s))
                corners.forEach { o -> drawRect(color = Color.White, topLeft = o, size = Size(s, s)) }
            }
        }
        Text("Apps", color = Color.White, fontSize = 13.sp)
    }
}

@Composable
fun MediaButton(kind: String, btn: Dp, bg: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(btn).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(btn * 0.4f)) {
            val w = this.size.width
            val h = this.size.height
            when (kind) {
                "play" -> drawPath(
                    Path().apply {
                        moveTo(w * 0.15f, 0f)
                        lineTo(w, h / 2f)
                        lineTo(w * 0.15f, h)
                        close()
                    },
                    Color.White,
                )
                "pause" -> {
                    drawRect(color = Color.White, topLeft = Offset(w * 0.1f, 0f), size = Size(w * 0.3f, h))
                    drawRect(color = Color.White, topLeft = Offset(w * 0.6f, 0f), size = Size(w * 0.3f, h))
                }
                "next" -> {
                    drawPath(
                        Path().apply {
                            moveTo(0f, 0f)
                            lineTo(w * 0.8f, h / 2f)
                            lineTo(0f, h)
                            close()
                        },
                        Color.White,
                    )
                    drawRect(color = Color.White, topLeft = Offset(w * 0.85f, 0f), size = Size(w * 0.15f, h))
                }
                else -> {
                    drawPath(
                        Path().apply {
                            moveTo(w, 0f)
                            lineTo(w * 0.2f, h / 2f)
                            lineTo(w, h)
                            close()
                        },
                        Color.White,
                    )
                    drawRect(color = Color.White, topLeft = Offset(0f, 0f), size = Size(w * 0.15f, h))
                }
            }
        }
    }
}

@Composable
fun MediaCard(media: MediaInfo?, theme: LauncherTheme) {
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(theme.card)
            .padding(24.dp)
    ) {
        if (media == null) {
            Text(
                "Para mostrar a música que está tocando, libere o acesso às notificações para o Car Launcher.",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 18.sp,
            )
            Spacer(Modifier.height(14.dp))
            Chip("Liberar acesso", theme) {
                openSettings(ctx, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
        } else {
            Text(
                media.title,
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                media.artist.ifBlank { " " },
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MediaButton("prev", 64.dp, theme.card) { media.controller?.transportControls?.skipToPrevious() }
                MediaButton(if (media.playing) "pause" else "play", 80.dp, theme.accent) {
                    val t = media.controller?.transportControls
                    if (media.playing) t?.pause() else t?.play()
                }
                MediaButton("next", 64.dp, theme.card) { media.controller?.transportControls?.skipToNext() }
            }
        }
    }
}

@Composable
fun AppsOverlay(
    apps: List<AppItem>,
    dockPkgs: List<String>,
    theme: LauncherTheme,
    onToggle: (String) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .background(theme.bgTop.copy(alpha = 0.97f))
            .padding(24.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Todos os apps: toque para abrir, segure para fixar ou tirar do dock",
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
            )
            Chip("Fechar", theme, onClose)
        }
        Spacer(Modifier.height(16.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(apps, key = { it.pkg }) { app ->
                val pinned = app.pkg in dockPkgs
                Column(
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .then(if (pinned) Modifier.border(2.dp, theme.accent, RoundedCornerShape(16.dp)) else Modifier)
                        .combinedClickable(
                            onClick = { launchApp(ctx, app.pkg) },
                            onLongClick = { onToggle(app.pkg) },
                        )
                        .padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(bitmap = app.icon, contentDescription = app.label, modifier = Modifier.size(64.dp))
                    Text(
                        app.label,
                        color = Color.White,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
