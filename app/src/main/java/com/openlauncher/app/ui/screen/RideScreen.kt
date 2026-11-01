package com.openlauncher.app.ui.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.openlauncher.app.R
import com.openlauncher.app.data.AppSettings
import com.openlauncher.app.data.OdometerState
import com.openlauncher.app.data.UnitSystem
import com.openlauncher.app.model.AppInfo
import com.openlauncher.app.service.PhoneGpsReceiver
import com.openlauncher.app.ui.components.ConfirmDialog
import com.openlauncher.app.ui.widget.AddFuelEntryDialog
import com.openlauncher.app.ui.widget.KM_PER_MILE
import com.openlauncher.app.util.LocationData
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// Tricolour livery of the 1991 XRV750 (RD04); red is also the theme accent
private val LiveryRed  = Color(0xFFD7261E)
private val LiveryBlue = Color(0xFF2346A0)
// Mechanical counter drums look the same day or night: black drums, white figures
private val DrumBg     = Color(0xFF111214)
private val DrumInk    = Color(0xFFF2F0EA)

// Tile apps with a paid and a free edition: fall back to the one that is installed
private val APP_ALTERNATIVES = mapOf(
    "net.osmand.plus" to "net.osmand",
    "net.osmand" to "net.osmand.plus"
)

private const val PAGE_RIDE  = 0
private const val PAGE_TOOLS = 1

/**
 * Single-screen home for riding, styled after the 1991 Africa Twin's clocks:
 * an analog speedo with a red needle, mechanical drum counters for trip and
 * odometer, and the tricolour tank stripes. Everything is sized for gloves;
 * the full launcher (widgets, sidebar, settings) is one long-press on the clock away.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RideScreen(
    settings: AppSettings,
    apps: List<AppInfo>,
    location: LocationData?,
    placeName: String?,
    odometer: OdometerState,
    accent: Color,
    onLaunchApp: (packageName: String) -> Unit,
    onPickApp: (slot: Int) -> Unit,
    onResetTrip: () -> Unit,
    onAddFuelEntry: (odometerKm: Double, volume: Double, cost: Double) -> Unit,
    onOpenLauncher: () -> Unit,
    onOpenAllApps: () -> Unit,
    isDayMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val text   = MaterialTheme.colorScheme.onBackground
    val dim    = text.copy(alpha = 0.5f)
    val tileBg = text.copy(alpha = if (isDayMode) 0.05f else 0.07f)
    val line   = text.copy(alpha = 0.15f)
    val metric = settings.unitSystem == UnitSystem.METRIC
    val distUnit = if (metric) "km" else "mi"
    fun toDist(km: Double) = if (metric) km else km / KM_PER_MILE

    var showResetTrip by remember { mutableStateOf(false) }
    var showAddFuel   by remember { mutableStateOf(false) }
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()

    val odoKm  = odometer.odometerMeters / 1000.0
    val tripKm = odometer.tripMeters / 1000.0
    val speed  = location?.let { if (metric) it.speedMps * 3.6f else it.speedMps * 2.23694f }

    // Fuel: consumption from the last two fill-ups, distance since the last one
    val fills    = settings.fuelLog.sortedBy { it.timestampMs }
    val lastFill = fills.lastOrNull()
    val prevFill = fills.getOrNull(fills.size - 2)
    val efficiency = if (lastFill != null && prevFill != null) {
        val d = lastFill.odometerKm - prevFill.odometerKm
        if (d > 0 && lastFill.volume > 0) {
            if (metric) lastFill.volume / d * 100.0 else d / KM_PER_MILE / lastFill.volume
        } else null
    } else null
    // The dialog pre-fills a rounded (or hand-edited) odometer, so the last fill-up
    // can sit slightly ahead of the live total — clamp rather than hide it
    val sinceFillKm = lastFill?.let { (odoKm - it.odometerKm).coerceAtLeast(0.0) }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Top bar: time · place · bike name with tank stripes ──────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RideClock(
                use24h = settings.use24HourFormat, color = text,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .combinedClickable(onClick = {}, onLongClick = onOpenLauncher)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            if (settings.phoneGpsEnabled) {
                Spacer(Modifier.width(14.dp))
                PhoneGpsDot(dim)
            }
            Text(
                text = placeName
                    ?: location?.let { "%.4f, %.4f".format(it.latitude, it.longitude) }
                    ?: stringResource(R.string.ride_no_gps),
                color = dim, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
            )
            if (settings.logoUri.isNotEmpty()) {
                coil.compose.AsyncImage(
                    model = android.net.Uri.parse(settings.logoUri),
                    contentDescription = settings.vehicleName,
                    modifier = Modifier.height(30.dp).widthIn(max = 160.dp)
                )
                Spacer(Modifier.width(10.dp))
            }
            // Glove-sized switch between the dashboard and the tools page (swiping works too)
            val onTools = pager.currentPage == PAGE_TOOLS
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (onTools) accent.copy(alpha = 0.15f) else tileBg)
                    .border(1.dp, if (onTools) accent.copy(alpha = 0.6f) else line, RoundedCornerShape(10.dp))
                    .combinedClickable(onClick = {
                        scope.launch { pager.animateScrollToPage(if (onTools) PAGE_RIDE else PAGE_TOOLS) }
                    }),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (onTools) Icons.Default.Speed else Icons.Default.Build,
                    contentDescription = stringResource(if (onTools) R.string.ride_dashboard else R.string.ride_tools),
                    tint = if (onTools) accent else dim, modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            TankStripes(Modifier.size(width = 56.dp, height = 22.dp))
            if (settings.vehicleName.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = settings.vehicleName.uppercase(),
                    color = text, fontSize = 18.sp, fontWeight = FontWeight.Black,
                    fontStyle = FontStyle.Italic, letterSpacing = 1.sp, maxLines = 1
                )
            }
        }

        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxWidth().weight(1f),
            pageSpacing = 16.dp,
            // Keep both pages composed: rebuilding the tools grid mid-swipe stutters on the unit
            beyondViewportPageCount = 1
        ) { page ->
        if (page == PAGE_TOOLS) {
            ToolsPage(
                apps = apps, background = tileBg, border = line, text = text, dim = dim, accent = accent,
                onOpenBikeSettings = onOpenLauncher,
                onOpenAllApps = onOpenAllApps,
                modifier = Modifier.fillMaxSize()
            )
            return@HorizontalPager
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ── Middle: speedo · trip/odo drums · fuel ───────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Speedometer(
                speed = speed,
                maxSpeed = if (metric) 180 else 120,
                unit = if (metric) "km/h" else "mph",
                ink = text, dim = dim, needle = accent,
                modifier = Modifier.weight(1.25f).fillMaxHeight()
            )

            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Trip (hold to reset)
                DataPanel(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    background = tileBg, border = line,
                    onLongClick = { showResetTrip = true }
                ) {
                    PanelLabel(stringResource(R.string.trip), stringResource(R.string.ride_hold_to_reset), dim)
                    Spacer(Modifier.height(6.dp))
                    DrumCounter(toDist(tripKm), intDigits = 4, decimals = 1, unit = distUnit, unitColor = dim)
                }
                // Odometer
                DataPanel(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    background = tileBg, border = line
                ) {
                    PanelLabel(stringResource(R.string.odometer), null, dim)
                    Spacer(Modifier.height(6.dp))
                    DrumCounter(toDist(odoKm), intDigits = 6, decimals = 0, unit = distUnit, unitColor = dim)
                }
            }

            // Fuel
            DataPanel(
                modifier = Modifier.weight(0.85f).fillMaxHeight(),
                background = tileBg, border = line
            ) {
                PanelLabel(stringResource(R.string.ride_fuel), null, dim)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        efficiency?.let { "%.1f".format(it) } ?: "--.-",
                        color = text, fontSize = 40.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                    )
                    Text(
                        if (metric) "L/100" else "MPG",
                        color = dim, fontSize = 15.sp, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
                    )
                }
                Text(
                    text = sinceFillKm?.let { stringResource(R.string.ride_since_fillup, "%.0f %s".format(toDist(it), distUnit)) }
                        ?: stringResource(R.string.ride_no_fillups),
                    color = dim, fontSize = 14.sp, maxLines = 2
                )
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(accent.copy(alpha = 0.15f))
                        .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                        .combinedClickable(onClick = { showAddFuel = true })
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, null, tint = accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.log_fillup), color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ── Bottom: four app tiles ───────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.36f),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            settings.rideApps.forEachIndexed { slot, pkg ->
                val app = apps.firstOrNull { it.packageName == pkg }
                    ?: APP_ALTERNATIVES[pkg]?.let { alt -> apps.firstOrNull { it.packageName == alt } }
                AppTile(
                    app = app,
                    background = tileBg, border = line, text = text, dim = dim,
                    stripe = if (slot % 2 == 0) LiveryRed else LiveryBlue,
                    onClick = { app?.let { onLaunchApp(it.packageName) } },
                    onLongClick = { onPickApp(slot) },
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        }
        }
        }
    }

    if (showResetTrip) {
        ConfirmDialog(
            title = stringResource(R.string.ride_reset_trip_title),
            message = stringResource(R.string.ride_reset_trip_message),
            onConfirm = { onResetTrip(); showResetTrip = false },
            onDismiss = { showResetTrip = false }
        )
    }

    if (showAddFuel) {
        AddFuelEntryDialog(
            initialOdometer = toDist(odoKm),
            isMetric  = metric,
            accent    = accent,
            isDayMode = isDayMode,
            onDismiss = { showAddFuel = false },
            onConfirm = { odo, volume, cost ->
                onAddFuelEntry(if (metric) odo else odo * KM_PER_MILE, volume, cost)
                showAddFuel = false
            }
        )
    }
}

/** Ticks on its own so the rest of the Ride screen doesn't recompose every second. */
@Composable
private fun RideClock(use24h: Boolean, color: Color, modifier: Modifier) {
    var now by remember { mutableStateOf(Calendar.getInstance()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Calendar.getInstance()
            // Wake on the minute boundary instead of polling every second
            delay(60_000L - now.get(Calendar.SECOND) * 1000L - now.get(Calendar.MILLISECOND))
        }
    }
    Text(
        text = formatTime(now, use24h),
        color = color, fontSize = 30.sp, fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace, modifier = modifier
    )
}

/** iPhone GPS link: green = positions arriving, amber = connecting, dim = no phone. */
@Composable
private fun PhoneGpsDot(dim: Color) {
    val status by PhoneGpsReceiver.status.collectAsState()
    val color = when (status.state) {
        PhoneGpsReceiver.State.RECEIVING -> Color(0xFF3DBE5B)
        PhoneGpsReceiver.State.CONNECTING,
        PhoneGpsReceiver.State.WAITING   -> Color(0xFFE0A030)
        else                             -> dim.copy(alpha = 0.35f)
    }
    Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
}

private fun formatTime(cal: Calendar, use24h: Boolean): String {
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    val minute = cal.get(Calendar.MINUTE)
    return if (use24h) "%02d:%02d".format(hour, minute)
    else "%d:%02d %s".format(if (hour % 12 == 0) 12 else hour % 12, minute, if (hour < 12) "AM" else "PM")
}

/** Two slanted bars, red then blue, after the RD04 tank graphics. */
@Composable
private fun TankStripes(modifier: Modifier) {
    Canvas(modifier) {
        val slant = size.height * 0.6f
        val barW = (size.width - slant) / 2f - 2.dp.toPx()
        listOf(LiveryRed, LiveryBlue).forEachIndexed { i, color ->
            val x = i * (barW + 4.dp.toPx())
            drawPath(Path().apply {
                moveTo(x + slant, 0f)
                lineTo(x + slant + barW, 0f)
                lineTo(x + barW, size.height)
                lineTo(x, size.height)
                close()
            }, color)
        }
    }
}

/**
 * 90s analog speedo: 240° dial, major ticks with figures every 20 (km/h) / 10 (mph),
 * a red needle, and the exact reading in digits at the centre.
 */
/** Shared layout of the 240° speedo dial, so the cached dial and the needle line up. */
private class DialGeometry(size: Size, val track: Float) {
    val radius = min(size.width, size.height * 1.12f) / 2f - track
    val center = Offset(size.width / 2f, size.height / 2f + radius * 0.12f)
    val arcSize = Size(radius * 2, radius * 2)
    val arcTopLeft = Offset(center.x - radius, center.y - radius)

    fun angleFor(fraction: Float) = START + SWEEP * fraction

    fun pointAt(deg: Float, r: Float): Offset {
        val rad = Math.toRadians(deg.toDouble())
        return Offset(center.x + r * cos(rad).toFloat(), center.y + r * sin(rad).toFloat())
    }

    companion object {
        const val START = 150f
        const val SWEEP = 240f
    }
}

@Composable
private fun Speedometer(
    speed: Float?,
    maxSpeed: Int,
    unit: String,
    ink: Color,
    dim: Color,
    needle: Color,
    modifier: Modifier
) {
    val measurer = rememberTextMeasurer()
    val major = if (maxSpeed > 150) 20 else 10
    // GPS speed arrives once a second; glide the needle between readings
    val shown = animateFloatAsState(
        targetValue = speed ?: 0f,
        animationSpec = tween(durationMillis = 900, easing = LinearEasing),
        label = "speedo"
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        // Dial: ticks and labels are measured once per size, not on every needle frame
        Spacer(
            Modifier.fillMaxSize().drawWithCache {
                val g = DialGeometry(size, 6.dp.toPx())
                val minor = major / 2
                val ticks = (0..maxSpeed step minor).map { v ->
                    val deg = g.angleFor(v.toFloat() / maxSpeed)
                    val isMajor = v % major == 0
                    val outer = g.radius - g.track / 2
                    val inner = outer - (if (isMajor) 14.dp else 7.dp).toPx()
                    val label = if (isMajor) {
                        val layout = measurer.measure(v.toString(), TextStyle(color = ink, fontSize = 13.sp, fontWeight = FontWeight.Bold))
                        val p = g.pointAt(deg, inner - 14.dp.toPx())
                        layout to Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f)
                    } else null
                    Triple(g.pointAt(deg, inner) to g.pointAt(deg, outer), isMajor, label)
                }
                onDrawBehind {
                    drawArc(dim.copy(alpha = 0.25f), DialGeometry.START, DialGeometry.SWEEP, false,
                        g.arcTopLeft, g.arcSize, style = Stroke(g.track, cap = StrokeCap.Butt))
                    ticks.forEach { (line, isMajor, label) ->
                        drawLine(if (isMajor) ink else dim, line.first, line.second,
                            strokeWidth = (if (isMajor) 3.dp else 1.5.dp).toPx())
                        label?.let { (layout, at) -> drawText(layout, topLeft = at) }
                    }
                }
            }
        )
        // Needle layer: reads the animated value in the draw phase, so animating it
        // only redraws this canvas instead of recomposing the screen
        Canvas(Modifier.fillMaxSize()) {
            val g = DialGeometry(size, 6.dp.toPx())
            val frac = (shown.value / maxSpeed).coerceIn(0f, 1f)
            if (frac > 0f) drawArc(needle.copy(alpha = 0.35f), DialGeometry.START, DialGeometry.SWEEP * frac, false,
                g.arcTopLeft, g.arcSize, style = Stroke(g.track))
            if (speed != null) {
                val deg = g.angleFor(frac)
                drawLine(needle, g.pointAt(deg + 180f, g.radius * 0.12f), g.pointAt(deg, g.radius * 0.86f),
                    strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
            }
            drawCircle(ink, radius = 9.dp.toPx(), center = g.center)
            drawCircle(needle, radius = 4.dp.toPx(), center = g.center)
        }
        // Digital readout sits in the lower half of the dial, under the hub
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
        ) {
            Text(
                speed?.let { "%.0f".format(it) } ?: "--",
                color = ink, fontSize = 44.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, lineHeight = 44.sp
            )
            Text(unit, color = dim, fontSize = 14.sp)
        }
    }
}

/**
 * Mechanical counter: one black drum per digit, the tenths drum in red, leading
 * zeros kept like the real thing.
 */
@Composable
private fun DrumCounter(value: Double, intDigits: Int, decimals: Int, unit: String, unitColor: Color) {
    val scaled = (value * if (decimals > 0) 10.0 else 1.0).toLong().coerceAtLeast(0)
    val digits = scaled.toString().padStart(intDigits + decimals, '0').takeLast(intDigits + decimals)
    Row(verticalAlignment = Alignment.Bottom) {
        Row(
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(DrumBg).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            digits.forEachIndexed { i, c ->
                val isDecimal = i >= intDigits
                Box(
                    modifier = Modifier
                        .size(width = 22.dp, height = 34.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isDecimal) LiveryRed else Color(0xFF1E2024)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(c.toString(), color = DrumInk, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Text(unit, color = unitColor, fontSize = 14.sp, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
    }
}

@Composable
private fun PanelLabel(label: String, hint: String?, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        if (hint != null) {
            Spacer(Modifier.width(8.dp))
            Text(hint, color = color.copy(alpha = color.alpha * 0.8f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private data class Tool(
    val label: String,
    val icon: ImageVector,
    val app: AppInfo? = null,
    val onClick: () -> Unit
)

/**
 * Second page of the Ride screen: the system screens that the stock launcher used
 * to reach (it is disabled), sized for gloves like the app tiles.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolsPage(
    apps: List<AppInfo>,
    background: Color,
    border: Color,
    text: Color,
    dim: Color,
    accent: Color,
    onOpenBikeSettings: () -> Unit,
    onOpenAllApps: () -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    // First intent that resolves wins; Settings screens differ between vendor builds
    fun open(vararg intents: android.content.Intent) {
        for (intent in intents) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }
    fun component(pkg: String, cls: String) = android.content.Intent().setClassName(pkg, cls)
    fun action(action: String) = android.content.Intent(action)
    // Fixed labels: the store's own name varies ("Google Play Store", "license checker")
    fun appTool(pkg: String, label: String, icon: ImageVector): Tool {
        val app = apps.firstOrNull { it.packageName == pkg }
        return Tool(label, icon, app) {
            context.packageManager.getLaunchIntentForPackage(pkg)?.let { open(it) }
        }
    }

    val tools = listOf(
        Tool(stringResource(R.string.tool_wifi), Icons.Default.Wifi) {
            open(action(android.provider.Settings.ACTION_WIFI_SETTINGS))
        },
        Tool(stringResource(R.string.tool_bluetooth), Icons.Default.Bluetooth) {
            open(action(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
        },
        Tool(stringResource(R.string.tool_display), Icons.Default.LightMode) {
            open(action(android.provider.Settings.ACTION_DISPLAY_SETTINGS))
        },
        Tool(stringResource(R.string.tool_android_settings), Icons.Default.Settings) {
            open(action(android.provider.Settings.ACTION_SETTINGS))
        },
        // Hidden in this unit's Settings menu, but the screen itself still opens
        Tool(stringResource(R.string.tool_developer), Icons.Default.Code) {
            open(
                action(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                component("com.android.settings", "com.android.settings.Settings\$DevelopmentSettingsActivity")
            )
        },
        appTool("com.android.vending", "Play Store", Icons.Default.Apps),
        appTool("de.szalkowski.activitylauncher", "Activity Launcher", Icons.Default.Apps),
        Tool(stringResource(R.string.tool_files), Icons.Default.Folder) {
            open(
                component("com.android.documentsui", "com.android.documentsui.files.FilesActivity"),
                action(android.content.Intent.ACTION_VIEW).apply {
                    type = "vnd.android.document/root"
                }
            )
        },
        Tool(stringResource(R.string.tool_all_apps), Icons.Default.GridView, onClick = onOpenAllApps),
        Tool(stringResource(R.string.tool_bike_settings), Icons.Default.TwoWheeler, onClick = onOpenBikeSettings)
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tools.chunked(5).forEachIndexed { row, rowTools ->
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowTools.forEachIndexed { col, tool ->
                    ToolTile(
                        tool = tool, background = background, border = border, text = text, dim = dim,
                        stripe = if ((row + col) % 2 == 0) LiveryRed else LiveryBlue,
                        highlight = if (tool.label == stringResource(R.string.tool_bike_settings)) accent else null,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolTile(
    tool: Tool,
    background: Color,
    border: Color,
    text: Color,
    dim: Color,
    stripe: Color,
    highlight: Color?,
    modifier: Modifier
) {
    val icon = remember(tool.app) { tool.app?.icon?.toBitmap(128, 128)?.asImageBitmap() }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            .combinedClickable(onClick = tool.onClick)
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(stripe).align(Alignment.TopCenter))
        Column(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(48.dp))
            } else {
                Icon(tool.icon, null, tint = highlight ?: text, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                tool.label, color = text, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DataPanel(
    modifier: Modifier,
    background: Color,
    border: Color,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .let { m -> if (onLongClick != null) m.combinedClickable(onClick = {}, onLongClick = onLongClick) else m }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
        content = content
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(
    app: AppInfo?,
    background: Color,
    border: Color,
    text: Color,
    dim: Color,
    stripe: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier
) {
    // Drawable → bitmap once per app, not on every recomposition
    val icon = remember(app) { app?.icon?.toBitmap(160, 160)?.asImageBitmap() }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(14.dp))
            // Not installed: a tap opens the picker too, instead of doing nothing
            .combinedClickable(onClick = if (app != null) onClick else onLongClick, onLongClick = onLongClick)
    ) {
        // Livery accent along the top edge, alternating red / blue across the row
        Box(Modifier.fillMaxWidth().height(4.dp).background(stripe).align(Alignment.TopCenter))
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Image(bitmap = icon, contentDescription = app?.appName, modifier = Modifier.size(64.dp))
            } else {
                Icon(Icons.Default.Apps, null, tint = dim, modifier = Modifier.size(56.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = app?.appName ?: stringResource(R.string.ride_choose_app),
                color = if (app != null) text else dim,
                fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}
