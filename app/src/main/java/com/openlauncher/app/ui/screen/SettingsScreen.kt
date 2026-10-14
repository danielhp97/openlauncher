package com.openlauncher.app.ui.screen

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.R
import com.openlauncher.app.data.AppFont
import com.openlauncher.app.data.AppLanguage
import com.openlauncher.app.data.AppSettings
import com.openlauncher.app.data.DASHBOARD_THEMES
import com.openlauncher.app.data.DayNightMode
import com.openlauncher.app.data.SidebarPosition
import com.openlauncher.app.data.ShortcutConfig
import com.openlauncher.app.data.GradientDirection
import com.openlauncher.app.data.UnitSystem
import com.openlauncher.app.data.LocationRefreshInterval
import com.openlauncher.app.ui.theme.LocalDayMode
import com.openlauncher.app.ui.theme.onAccentColor
import com.openlauncher.app.util.SunriseSunset
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.openlauncher.app.ui.components.ColorPickerDialog
import com.openlauncher.app.ui.components.ConfirmDialog

// Resolved at call site via LocalDayMode — see SettingsDivider / SettingsSection

@Composable
fun SettingsScreen(
    settings: AppSettings,
    accent: Color,
    onUpdate: (AppSettings.() -> AppSettings) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showResetDialog       by remember { mutableStateOf(false) }
    var showAccentPicker      by remember { mutableStateOf(false) }
    var showBgPicker          by remember { mutableStateOf(false) }
    var showGradientEndPicker by remember { mutableStateOf(false) }
    var showFontColorPicker   by remember { mutableStateOf(false) }

    // OpenDocument (not GetContent): only SAF document URIs carry a persistable
    // grant, so this is what actually keeps the wallpaper readable after reboot
    val wallpaperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            onUpdate { copy(wallpaperUri = it.toString()) }
        }
    }

    val isDayMode = LocalDayMode.current
    val screenBg  = MaterialTheme.colorScheme.background

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(screenBg)
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // ── Title ────────────────────────────────────────────────────────────
        Text(
            text          = stringResource(R.string.settings_title),
            style         = MaterialTheme.typography.titleLarge,
            color         = if (isDayMode) Color(0xFF111111) else accent,
            letterSpacing = 3.sp,
            fontSize      = 14.sp
        )

        Spacer(Modifier.height(4.dp))

        // ── Permissions ──────────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.settings_section_permissions)) {
            val isMediaConnected by com.openlauncher.app.service.MediaListenerService.isConnected.collectAsState()

            // Bumped on ON_RESUME so statuses refresh when the user returns from
            // system settings (recomposition alone doesn't re-run these checks)
            var permissionRefresh by remember { mutableIntStateOf(0) }
            val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permissionRefresh++
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            // canDrawOverlays requires API 23 — on Android 5.x the permission
            // model doesn't exist, so treat it as granted
            val canDrawOverlays = remember(permissionRefresh) {
                android.os.Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)
            }
            val hasLocation = remember(permissionRefresh) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            val isDefaultLauncher = remember(permissionRefresh) {
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                context.packageManager.resolveActivity(
                    home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
                )?.activityInfo?.packageName == context.packageName
            }

            val homeRoleLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { permissionRefresh++ }

            val locationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { permissionRefresh++ }

            SettingsButton(
                label    = stringResource(R.string.perm_default_launcher),
                sublabel = if (isDefaultLauncher) stringResource(R.string.perm_default_launcher_active)
                           else stringResource(R.string.perm_default_launcher_sub),
                icon     = Icons.Default.Home,
                accent   = if (isDefaultLauncher) accent else Color(0xFF993333),
                onClick  = {
                    // Preferred: the system home-role dialog (API 29+). Vendor ROMs
                    // sometimes ship without it, so fall through to the home-settings
                    // screen, then the default-apps screen.
                    var launched = false
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val rm = context.getSystemService(android.app.role.RoleManager::class.java)
                        if (rm != null && rm.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) &&
                            !rm.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
                        ) {
                            launched = runCatching {
                                homeRoleLauncher.launch(rm.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME))
                            }.isSuccess
                        }
                    }
                    if (!launched) {
                        launched = runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.isSuccess
                    }
                    if (!launched) {
                        runCatching {
                            context.startActivity(
                                Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS")
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            )
            SettingsDivider()
            SettingsButton(
                label    = stringResource(R.string.perm_notification_access),
                sublabel = if (isMediaConnected) stringResource(R.string.perm_notification_granted) else stringResource(R.string.perm_notification_sub),
                icon     = if (isMediaConnected) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                accent   = if (isMediaConnected) accent else Color(0xFF993333),
                onClick  = {
                    context.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            )
            SettingsDivider()
            SettingsButton(
                label    = stringResource(R.string.perm_draw_overlays),
                sublabel = if (canDrawOverlays) stringResource(R.string.perm_draw_overlays_granted) else stringResource(R.string.perm_draw_overlays_sub),
                icon     = if (canDrawOverlays) Icons.Default.Layers else Icons.Default.LayersClear,
                accent   = if (canDrawOverlays) accent else Color(0xFF993333),
                onClick  = {
                    if (android.os.Build.VERSION.SDK_INT >= 23) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            )
            SettingsDivider()
            SettingsButton(
                label    = stringResource(R.string.perm_location_access),
                sublabel = if (hasLocation) stringResource(R.string.perm_location_granted) else stringResource(R.string.perm_location_sub),
                icon     = if (hasLocation) Icons.Default.LocationOn else Icons.Default.LocationOff,
                accent   = if (hasLocation) accent else Color(0xFF993333),
                onClick  = {
                    if (!hasLocation) {
                        // Ask in-app first — previously the only grant path was the
                        // onboarding flow; skipping it left GPS features dead forever
                        locationPermissionLauncher.launch(arrayOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        ))
                    } else {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            )
        }

        // ── Vehicle Name ─────────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.settings_section_vehicle)) {
            var nameInput by remember(settings.vehicleName) { mutableStateOf(settings.vehicleName) }
            SettingsRow(
                label    = stringResource(R.string.vehicle_name),
                sublabel = "",
                icon     = Icons.Default.DirectionsCar
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value         = nameInput,
                        onValueChange = { nameInput = it },
                        placeholder   = { Text("MY CAR", color = if (isDayMode) Color(0xFF999999) else Color(0xFF444444), fontSize = 12.sp) },
                        singleLine    = true,
                        textStyle     = LocalTextStyle.current.copy(fontSize = 12.sp, color = if (isDayMode) Color(0xFF111111) else Color.White),
                        colors        = outlinedFieldColors(accent),
                        modifier      = Modifier.width(140.dp)
                    )
                    if (nameInput != settings.vehicleName) {
                        IconButton(onClick = { onUpdate { copy(vehicleName = nameInput) } }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Check, stringResource(R.string.save), tint = accent, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            SettingsDivider()

            SettingsRow(
                label    = stringResource(R.string.sidebar_position),
                sublabel = when (settings.sidebarPosition) {
                    SidebarPosition.LEFT   -> stringResource(R.string.sidebar_left)
                    SidebarPosition.RIGHT  -> stringResource(R.string.sidebar_right)
                    SidebarPosition.BOTTOM -> stringResource(R.string.sidebar_bottom)
                },
                icon     = Icons.Default.SwapHoriz
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SidebarPosition.entries.forEach { pos ->
                        FilterChip(
                            selected = settings.sidebarPosition == pos,
                            onClick  = { onUpdate { copy(sidebarPosition = pos) } },
                            label    = {
                                Text(
                                    when (pos) {
                                        SidebarPosition.LEFT   -> stringResource(R.string.pos_left)
                                        SidebarPosition.RIGHT  -> stringResource(R.string.pos_right)
                                        SidebarPosition.BOTTOM -> stringResource(R.string.pos_bottom)
                                    },
                                    fontSize = 9.sp,
                                    letterSpacing = 0.5.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent,
                                selectedLabelColor     = Color.Black
                            )
                        )
                    }
                }
            }

            if (settings.sidebarPosition == SidebarPosition.BOTTOM) {
                SettingsDivider()
                SettingsRow(
                    label    = stringResource(R.string.shortcuts_side),
                    sublabel = if (settings.bottomBarShortcutsRight) stringResource(R.string.shortcuts_right_sub) else stringResource(R.string.shortcuts_left_sub),
                    icon     = Icons.Default.FormatAlignRight
                ) {
                    Switch(
                        checked         = settings.bottomBarShortcutsRight,
                        onCheckedChange = { onUpdate { copy(bottomBarShortcutsRight = it) } },
                        colors          = switchColors(accent)
                    )
                }
            }

            SettingsDivider()

            SettingsRow(
                label    = stringResource(R.string.unit_system),
                sublabel = if (settings.unitSystem == UnitSystem.METRIC) stringResource(R.string.unit_metric_sub) else stringResource(R.string.unit_imperial_sub),
                icon     = Icons.Default.Straighten
            ) {
                Row {
                    FilterChip(
                        selected = settings.unitSystem == UnitSystem.METRIC,
                        onClick  = { onUpdate { copy(unitSystem = UnitSystem.METRIC) } },
                        label    = { Text(stringResource(R.string.unit_metric), fontSize = 11.sp) },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent,
                            selectedLabelColor     = Color.Black
                        )
                    )
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        selected = settings.unitSystem == UnitSystem.IMPERIAL,
                        onClick  = { onUpdate { copy(unitSystem = UnitSystem.IMPERIAL) } },
                        label    = { Text(stringResource(R.string.unit_imperial), fontSize = 11.sp) },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent,
                            selectedLabelColor     = Color.Black
                        )
                    )
                }
            }

            SettingsDivider()

            SettingsRow(
                label    = "Location Refresh",
                sublabel = "How often the live neighborhood name updates while driving (Weather + Location)",
                icon     = Icons.Default.GpsFixed
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LocationRefreshInterval.entries.forEach { interval ->
                        FilterChip(
                            selected = settings.locationRefreshInterval == interval,
                            onClick  = { onUpdate { copy(locationRefreshInterval = interval) } },
                            label    = {
                                Text(
                                    when (interval) {
                                        LocationRefreshInterval.SEC_30 -> "30s"
                                        LocationRefreshInterval.MIN_1  -> "1min"
                                        LocationRefreshInterval.MIN_2  -> "2min"
                                        LocationRefreshInterval.MIN_5  -> "5min"
                                    },
                                    fontSize = 9.sp,
                                    letterSpacing = 0.5.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent,
                                selectedLabelColor     = onAccentColor(accent)
                            )
                        )
                    }
                }
            }
        }

        // ── Sidebar Shortcuts ─────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.settings_section_sidebar)) {
            settings.shortcuts.forEachIndexed { index, shortcut ->
                if (index > 0) SettingsDivider()
                SettingsRow(
                    label    = stringResource(R.string.sidebar_slot, index + 1),
                    sublabel = when {
                        shortcut.label.isNotEmpty()       -> shortcut.label
                        shortcut.packageName.isNotEmpty() -> shortcut.packageName
                        else                              -> stringResource(R.string.sidebar_empty)
                    },
                    icon     = Icons.Default.Apps
                ) {
                    if (settings.shortcuts.size > 1) {
                        IconButton(
                            onClick  = {
                                onUpdate {
                                    copy(shortcuts = shortcuts.toMutableList().also { it.removeAt(index) })
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Close, null, tint = Color(0xFF993333), modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            SettingsDivider()

            SettingsButton(
                label    = stringResource(R.string.sidebar_add_slot),
                sublabel = stringResource(R.string.sidebar_add_slot_sub),
                icon     = Icons.Default.Add,
                accent   = accent,
                onClick  = { onUpdate { copy(shortcuts = shortcuts + ShortcutConfig()) } }
            )
        }

        // ── Appearance ───────────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.settings_section_appearance)) {
            // Dashboard Theme — a preset owns both accent and surface tint together;
            // Accent Color / Background below only apply once this is set to Custom.
            DashboardThemeRow(settings = settings, onUpdate = onUpdate)

            SettingsDivider()

            // Display Mode
            SettingsRow(
                label    = stringResource(R.string.display_mode),
                sublabel = when (settings.dayNightMode) {
                    DayNightMode.DARK   -> stringResource(R.string.display_mode_dark)
                    DayNightMode.LIGHT  -> stringResource(R.string.display_mode_light)
                    DayNightMode.AUTO   -> stringResource(R.string.display_mode_auto)
                    DayNightMode.SYSTEM -> stringResource(R.string.display_mode_system)
                },
                icon = when (settings.dayNightMode) {
                    DayNightMode.DARK   -> Icons.Default.NightlightRound
                    DayNightMode.LIGHT  -> Icons.Default.LightMode
                    DayNightMode.AUTO   -> Icons.Default.Brightness4
                    DayNightMode.SYSTEM -> Icons.Default.PhoneAndroid
                }
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    DayNightMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.dayNightMode == mode,
                            onClick  = { onUpdate { copy(dayNightMode = mode) } },
                            label    = {
                                Text(
                                    text      = when (mode) {
                                        DayNightMode.DARK   -> stringResource(R.string.mode_dark)
                                        DayNightMode.LIGHT  -> stringResource(R.string.mode_light)
                                        DayNightMode.AUTO   -> stringResource(R.string.mode_sunset)
                                        DayNightMode.SYSTEM -> stringResource(R.string.mode_system)
                                    },
                                    fontSize  = 9.sp,
                                    letterSpacing = 0.5.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent,
                                selectedLabelColor     = Color.Black
                            )
                        )
                    }
                }
            }

            SettingsDivider()

            // Accent color
            SettingsRow(
                label    = stringResource(R.string.accent_color),
                sublabel = stringResource(R.string.accent_color_sub),
                icon     = Icons.Default.Palette
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF999999), RoundedCornerShape(4.dp))
                        .background(Color(settings.accentColor))
                        .clickable { showAccentPicker = true }
                )
            }

            SettingsDivider()

            // Background color + gradient
            SettingsRow(
                label    = stringResource(R.string.bg_color),
                sublabel = if (settings.useGradient) stringResource(R.string.bg_gradient) else stringResource(R.string.bg_solid),
                icon     = Icons.Default.FormatColorFill
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    // Start color swatch
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, Color(0xFF999999), RoundedCornerShape(4.dp))
                            .background(Color(settings.backgroundColor))
                            .clickable { showBgPicker = true }
                    )
                    if (settings.useGradient) {
                        androidx.compose.material3.Icon(
                            Icons.Default.ArrowForward, null,
                            tint = if (isDayMode) Color(0xFF999999) else Color(0xFF555555), modifier = Modifier.size(14.dp)
                        )
                        // End color swatch
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .border(1.dp, Color(0xFF999999), RoundedCornerShape(4.dp))
                                .background(Color(settings.gradientEndColor))
                                .clickable { showGradientEndPicker = true }
                        )
                    }
                    if (settings.useCustomBackgroundColor) {
                        TextButton(
                            onClick = {
                                onUpdate {
                                    copy(
                                        useCustomBackgroundColor = false,
                                        backgroundColor = Color.Black.toArgb(),
                                        useGradient = false
                                    )
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(stringResource(R.string.bg_default), color = accent, fontSize = 9.sp, letterSpacing = 1.sp)
                        }
                    }
                }
            }

            SettingsDivider()

            // Font Color row
            SettingsRow(
                label    = stringResource(R.string.font_color),
                sublabel = stringResource(R.string.font_color_sub),
                icon     = Icons.Default.FormatSize
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF999999), RoundedCornerShape(4.dp))
                        .background(Color(settings.fontColor))
                        .clickable { showFontColorPicker = true }
                )
            }

            SettingsDivider()

            SettingsRow(label = stringResource(R.string.use_gradient), sublabel = stringResource(R.string.use_gradient_sub), icon = Icons.Default.Gradient) {
                Switch(checked = settings.useGradient,
                    onCheckedChange = { onUpdate { copy(useGradient = it) } },
                    colors = switchColors(accent))
            }

            if (settings.useGradient) {
                SettingsDivider()
                SettingsRow(
                    label    = stringResource(R.string.gradient_direction),
                    sublabel = when (settings.gradientDirection) {
                        GradientDirection.TOP_TO_BOTTOM -> stringResource(R.string.grad_top_bottom)
                        GradientDirection.LEFT_TO_RIGHT -> stringResource(R.string.grad_left_right)
                        GradientDirection.DIAGONAL      -> stringResource(R.string.grad_diagonal)
                        GradientDirection.RADIAL        -> stringResource(R.string.grad_radial)
                    },
                    icon     = Icons.Default.TrendingFlat
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        GradientDirection.entries.forEach { dir ->
                            FilterChip(
                                selected = settings.gradientDirection == dir,
                                onClick  = { onUpdate { copy(gradientDirection = dir) } },
                                label    = {
                                    Text(
                                        text      = when (dir) {
                                            GradientDirection.TOP_TO_BOTTOM -> stringResource(R.string.dir_vertical)
                                            GradientDirection.LEFT_TO_RIGHT -> stringResource(R.string.dir_horizontal)
                                            GradientDirection.DIAGONAL      -> stringResource(R.string.dir_diagonal)
                                            GradientDirection.RADIAL        -> stringResource(R.string.dir_radial)
                                        },
                                        fontSize  = 9.sp,
                                        letterSpacing = 0.5.sp
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = accent,
                                    selectedLabelColor     = Color.Black
                                )
                            )
                        }
                    }
                }
            }

            SettingsDivider()

            // Wallpaper
            SettingsButton(
                label    = stringResource(R.string.set_wallpaper),
                sublabel = if (settings.wallpaperUri.isNotEmpty()) stringResource(R.string.wallpaper_active) else stringResource(R.string.wallpaper_choose),
                icon     = Icons.Default.Wallpaper,
                accent   = accent,
                onClick  = { wallpaperPicker.launch(arrayOf("image/*")) }
            )
            if (settings.wallpaperUri.isNotEmpty()) {
                Column {
                    SettingsRow(
                        label    = stringResource(R.string.wallpaper_dim),
                        sublabel = "${"%.0f".format(settings.wallpaperDim * 100)}%",
                        icon     = Icons.Default.BrightnessLow
                    ) {}
                    Slider(
                        value         = settings.wallpaperDim,
                        onValueChange = { onUpdate { copy(wallpaperDim = it) } },
                        valueRange    = 0f..0.95f,
                        steps         = 18,
                        colors        = sliderColors(accent),
                        modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick  = { onUpdate { copy(wallpaperUri = "") } },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(stringResource(R.string.remove_wallpaper), color = Color(0xFF993333), fontSize = 9.sp, letterSpacing = 1.sp)
                    }
                }
            }
        }

        // ── Language ─────────────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.settings_section_language)) {
            SettingsRow(
                label    = stringResource(R.string.language_label),
                sublabel = when (settings.appLanguage) {
                    AppLanguage.SYSTEM  -> stringResource(R.string.lang_system)
                    AppLanguage.ENGLISH -> "English"
                    AppLanguage.POLISH  -> "Polski"
                    AppLanguage.GERMAN  -> "Deutsch"
                    AppLanguage.FRENCH  -> "Française"
                    AppLanguage.SPANISH -> "Español"
                    AppLanguage.RUSSIAN -> "русский"
                },
                icon     = Icons.Default.Language
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AppLanguage.entries.forEach { lang ->
                        FilterChip(
                            selected = settings.appLanguage == lang,
                            onClick  = { onUpdate { copy(appLanguage = lang) } },
                            label    = {
                                Text(
                                    text = when (lang) {
                                        AppLanguage.SYSTEM  -> stringResource(R.string.lang_system)
                                        AppLanguage.ENGLISH -> "English"
                                        AppLanguage.POLISH  -> "Polski"
                                        AppLanguage.GERMAN  -> "Deutsch"
                                        AppLanguage.FRENCH  -> "Française"
                                        AppLanguage.SPANISH -> "Español"
                                        AppLanguage.RUSSIAN -> "русский"
                                    },
                                    fontSize = 9.sp,
                                    letterSpacing = 0.5.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent,
                                selectedLabelColor     = Color.Black
                            )
                        )
                    }
                }
            }
        }

        // ── Typography ───────────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.settings_section_typography)) {
            SettingsRow(label = stringResource(R.string.font_label), sublabel = fontDisplayName(settings.appFont), icon = Icons.Default.FontDownload) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    com.openlauncher.app.data.AppFont.entries.forEach { font ->
                        FilterChip(
                            selected = settings.appFont == font,
                            onClick  = { onUpdate { copy(appFont = font) } },
                            label    = { Text(fontDisplayName(font), fontSize = 9.sp, letterSpacing = 0.5.sp) },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = accent,
                                selectedLabelColor     = Color.Black
                            )
                        )
                    }
                }
            }

            SettingsDivider()

            SettingsRow(label = stringResource(R.string.bold_font), sublabel = stringResource(R.string.bold_font_sub), icon = Icons.Default.FormatBold) {
                Switch(
                    checked         = settings.fontBold,
                    onCheckedChange = { onUpdate { copy(fontBold = it) } },
                    colors          = switchColors(accent)
                )
            }

            SettingsDivider()

            Column {
                SettingsRow(
                    label    = stringResource(R.string.text_scale),
                    sublabel = "${"%.0f".format(settings.textScale * 100)}%",
                    icon     = Icons.Default.TextFields
                ) {}
                Slider(
                    value         = settings.textScale,
                    onValueChange = { onUpdate { copy(textScale = it) } },
                    valueRange    = 0.8f..1.4f,
                    steps         = 5,
                    colors        = sliderColors(accent),
                    modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }

            SettingsDivider()

            Column {
                SettingsRow(
                    label    = stringResource(R.string.ui_scale),
                    sublabel = "${"%.0f".format(settings.uiScale * 100)}%  — ${stringResource(R.string.ui_scale_sub)}",
                    icon     = Icons.Default.ZoomIn
                ) {}
                Slider(
                    value         = settings.uiScale,
                    onValueChange = { onUpdate { copy(uiScale = it) } },
                    valueRange    = 0.7f..1.5f,
                    steps         = 7,
                    colors        = sliderColors(accent),
                    modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }
        }

        // ── GPS & Calibration ───────────────────────────────────────────────
        SettingsSection(stringResource(R.string.settings_section_gps)) {
            var calibrationStatus by remember { mutableStateOf<String?>(null) }
            val coroutineScope = rememberCoroutineScope()
            var isCalibratingCompass by remember { mutableStateOf(false) }
            var compassCountdown by remember { mutableIntStateOf(0) }

            val agpsClearingMsg = stringResource(R.string.agps_clearing)
            val agpsSuccessMsg = stringResource(R.string.agps_success)
            val agpsFailedMsg = stringResource(R.string.agps_failed)
            val magCompleteMsg = stringResource(R.string.magnetometer_complete)

            // 1. Reset A-GPS Button
            SettingsButton(
                label    = stringResource(R.string.agps_reset),
                sublabel = calibrationStatus ?: stringResource(R.string.agps_reset_sub),
                icon     = Icons.Default.MyLocation,
                accent   = accent,
                onClick  = {
                    calibrationStatus = agpsClearingMsg
                    val lm = context.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
                    var success = false
                    try {
                        // "delete_aiding_data" is the command AOSP's GPS provider
                        // actually recognizes (requires ACCESS_LOCATION_EXTRA_COMMANDS)
                        success = lm.sendExtraCommand(android.location.LocationManager.GPS_PROVIDER, "delete_aiding_data", android.os.Bundle())
                        lm.sendExtraCommand(android.location.LocationManager.GPS_PROVIDER, "force_xtra_injection", null)
                        lm.sendExtraCommand(android.location.LocationManager.GPS_PROVIDER, "force_time_injection", null)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    calibrationStatus = if (success) agpsSuccessMsg else agpsFailedMsg
                }
            )
            
            SettingsDivider()
            
            // 2. Drive-in-circles magnetometer sweep. Android's sensor stack
            // self-calibrates the magnetometer continuously — the circles feed it
            // diverse readings. The timer guides the sweep; it does not (and
            // cannot) apply offsets itself, so the message must not claim it did.
            SettingsButton(
                label    = stringResource(R.string.magnetometer_sweep),
                sublabel = if (isCalibratingCompass) {
                    stringResource(R.string.magnetometer_active, compassCountdown)
                } else {
                    stringResource(R.string.magnetometer_sub)
                },
                icon     = Icons.Default.Navigation,
                accent   = if (isCalibratingCompass) Color.Green else accent,
                onClick  = {
                    if (!isCalibratingCompass) {
                        isCalibratingCompass = true
                        compassCountdown = 30
                        coroutineScope.launch {
                            while (compassCountdown > 0) {
                                delay(1000)
                                compassCountdown--
                            }
                            isCalibratingCompass = false
                            calibrationStatus = magCompleteMsg
                        }
                    }
                }
            )

            SettingsDivider()

            // 4. Manual Compass Heading Offset Slider
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                SettingsRow(
                    label    = stringResource(R.string.compass_offset_label),
                    sublabel = stringResource(R.string.compass_offset_sub, if (settings.compassOffset >= 0) "+" else "", settings.compassOffset.toInt()),
                    icon     = Icons.Default.Explore
                ) {}
                Slider(
                    value         = settings.compassOffset,
                    onValueChange = { onUpdate { copy(compassOffset = it) } },
                    valueRange    = -180f..180f,
                    steps         = 71, // 5 degree steps: 360 / 5 - 1 = 71 steps
                    colors        = sliderColors(accent),
                    modifier      = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                )
            }
        }

        // ── Updates ──────────────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.settings_section_updates)) {
            SettingsButton(
                label    = stringResource(R.string.check_updates),
                sublabel = stringResource(R.string.check_updates_sub),
                icon     = Icons.Default.SystemUpdate,
                accent   = accent,
                onClick  = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dw2lam/openlauncher/releases"))
                    context.startActivity(intent)
                }
            )
        }

        // ── Maintenance ──────────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.settings_section_maintenance)) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick  = { showResetDialog = true },
                shape    = RoundedCornerShape(4.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A0000)),
                modifier = Modifier.fillMaxWidth().height(44.dp)
            ) {
                Icon(Icons.Default.RestartAlt, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.reset_defaults), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, letterSpacing = 1.sp)
            }
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text          = "v0.0.5  ·  Made by David Lam  ·  2026",
            color         = if (isDayMode) Color(0xFFAAAAAA) else Color(0xFF2A2A2A),
            fontSize      = 10.sp,
            letterSpacing = 1.sp,
            modifier      = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 16.dp)
        )
    }
    } // end Box

    // ── Dialogs ──────────────────────────────────────────────────────────────
    if (showResetDialog) {
        ConfirmDialog(
            title        = stringResource(R.string.reset_dialog_title),
            message      = stringResource(R.string.reset_dialog_msg),
            confirmLabel = stringResource(R.string.reset_confirm),
            onConfirm    = { onReset(); showResetDialog = false },
            onDismiss    = { showResetDialog = false }
        )
    }

    if (showAccentPicker) {
        ColorPickerDialog(
            title           = stringResource(R.string.accent_color),
            initialColor    = Color(settings.accentColor),
            onColorSelected = { c -> onUpdate { copy(accentColor = c.toArgb()) } },
            onDismiss       = { showAccentPicker = false }
        )
    }

    if (showBgPicker) {
        ColorPickerDialog(
            title           = stringResource(R.string.background_color),
            initialColor    = Color(settings.backgroundColor),
            onColorSelected = { c -> 
                onUpdate { 
                    copy(
                        backgroundColor = c.toArgb(),
                        useCustomBackgroundColor = true
                    ) 
                } 
            },
            onDismiss       = { showBgPicker = false }
        )
    }

    if (showGradientEndPicker) {
        ColorPickerDialog(
            title           = stringResource(R.string.gradient_end_color),
            initialColor    = Color(settings.gradientEndColor),
            onColorSelected = { c -> 
                onUpdate { 
                    copy(
                        gradientEndColor = c.toArgb(),
                        useCustomBackgroundColor = true
                    ) 
                } 
            },
            onDismiss       = { showGradientEndPicker = false }
        )
    }

    if (showFontColorPicker) {
        ColorPickerDialog(
            title           = stringResource(R.string.font_color),
            initialColor    = Color(settings.fontColor),
            onColorSelected = { c -> onUpdate { copy(fontColor = c.toArgb()) } },
            onDismiss       = { showFontColorPicker = false }
        )
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────────

@Composable
private fun DashboardThemeRow(
    settings: AppSettings,
    onUpdate: (AppSettings.() -> AppSettings) -> Unit
) {
    val isDayMode  = LocalDayMode.current
    val labelColor = if (isDayMode) Color(0xFF111111) else Color(0xFFDDDDDD)
    val subColor   = if (isDayMode) Color(0xFF888888) else Color(0xFF444444)
    val iconTint   = if (isDayMode) Color(0xFF777777) else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    val isCustom   = DASHBOARD_THEMES.none { it.id == settings.themeId }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Palette, null, tint = iconTint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text("Dashboard Theme", style = MaterialTheme.typography.bodyMedium, color = labelColor, fontSize = 13.sp)
                Text(
                    text     = DASHBOARD_THEMES.find { it.id == settings.themeId }?.label ?: "Custom",
                    style    = MaterialTheme.typography.labelSmall,
                    color    = subColor,
                    fontSize = 11.sp
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DASHBOARD_THEMES.forEach { theme ->
                ThemeSwatchButton(
                    label         = theme.label,
                    gradientStart = Color(theme.darkAccent),
                    gradientEnd   = Color(theme.darkBg),
                    selected      = settings.themeId == theme.id,
                    onClick       = { onUpdate { copy(themeId = theme.id) } }
                )
            }
            ThemeSwatchButton(
                label         = "Custom",
                gradientStart = Color(settings.accentColor),
                gradientEnd   = Color(settings.backgroundColor),
                selected      = isCustom,
                onClick       = { onUpdate { copy(themeId = "custom") } }
            )
        }
    }
}

@Composable
private fun ThemeSwatchButton(
    label: String,
    gradientStart: Color,
    gradientEnd: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    val isDayMode  = LocalDayMode.current
    val labelColor = if (selected) gradientStart else if (isDayMode) Color(0xFF888888) else Color(0xFF666666)
    Column(
        horizontalAlignment  = Alignment.CenterHorizontally,
        verticalArrangement  = Arrangement.spacedBy(4.dp),
        modifier             = Modifier.width(54.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(gradientStart, gradientEnd)))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) gradientStart else Color(0x33808080),
                    shape = CircleShape
                )
                .clickable(onClick = onClick)
        )
        Text(
            text          = label,
            color         = labelColor,
            fontSize      = 7.sp,
            letterSpacing = 0.3.sp,
            textAlign     = TextAlign.Center,
            maxLines      = 2,
            lineHeight    = 8.sp
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDayMode     = LocalDayMode.current
    val sectionColor  = if (isDayMode) Color(0xFF888888) else Color(0xFF3A3A3A)
    val dividerColor  = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF1E1E1E)
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(
            text          = title.uppercase(),
            style         = MaterialTheme.typography.labelSmall,
            color         = sectionColor,
            letterSpacing = 2.sp,
            modifier      = Modifier.padding(top = 16.dp, bottom = 6.dp)
        )
        HorizontalDivider(color = dividerColor)
        Column(modifier = Modifier.fillMaxWidth(), content = content)
        HorizontalDivider(color = dividerColor)
    }
}

@Composable
private fun SettingsRow(
    label: String,
    sublabel: String = "",
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable RowScope.() -> Unit
) {
    val isDayMode   = LocalDayMode.current
    val labelColor  = if (isDayMode) Color(0xFF111111) else Color(0xFFDDDDDD)
    val subColor    = if (isDayMode) Color(0xFF888888) else Color(0xFF444444)
    val iconTint    = if (isDayMode) Color(0xFF777777) else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 0.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = labelColor, fontSize = 13.sp)
            if (sublabel.isNotEmpty())
                Text(sublabel, style = MaterialTheme.typography.labelSmall, color = subColor, fontSize = 11.sp)
        }
        content()
    }
}

@Composable
private fun ColumnScope.SettingsButton(
    label: String,
    sublabel: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    onClick: () -> Unit
) {
    val isDayMode  = LocalDayMode.current
    val labelColor = if (isDayMode) Color(0xFF111111) else Color(0xFFDDDDDD)
    val subColor   = if (isDayMode) Color(0xFF888888) else Color(0xFF444444)
    val chevronC   = if (isDayMode) Color(0xFFBBBBBB) else Color(0xFF2A2A2A)
    val iconTint   = if (isDayMode) Color(0xFF777777) else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 0.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = labelColor, fontSize = 13.sp)
            if (sublabel.isNotEmpty())
                Text(sublabel, style = MaterialTheme.typography.labelSmall, color = subColor, fontSize = 11.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = chevronC, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ColumnScope.SettingsDivider() {
    val isDayMode = LocalDayMode.current
    HorizontalDivider(color = if (isDayMode) Color(0xFFDDDDDD) else Color(0xFF141414))
}

@Composable
private fun outlinedFieldColors(accent: Color): androidx.compose.material3.TextFieldColors {
    val isDayMode = LocalDayMode.current
    val textColor = if (isDayMode) Color(0xFF111111) else Color.White
    val borderU   = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF2A2A2A)
    return OutlinedTextFieldDefaults.colors(
        focusedBorderColor   = accent,
        unfocusedBorderColor = borderU,
        focusedTextColor     = textColor,
        unfocusedTextColor   = textColor,
        cursorColor          = accent,
        focusedLabelColor    = accent,
        unfocusedLabelColor  = if (isDayMode) Color(0xFF888888) else Color(0xFF666666)
    )
}

@Composable
private fun switchColors(accent: Color): androidx.compose.material3.SwitchColors {
    val isDayMode = LocalDayMode.current
    return SwitchDefaults.colors(
        checkedThumbColor    = if (isDayMode) Color.White else Color.Black,
        checkedTrackColor    = accent,
        uncheckedThumbColor  = if (isDayMode) Color(0xFFBBBBBB) else Color(0xFF888888),
        uncheckedTrackColor  = if (isDayMode) Color(0xFFDDDDDD) else Color(0xFF1E1E1E),
        uncheckedBorderColor = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF3A3A3A)
    )
}

@Composable
private fun sliderColors(accent: Color): androidx.compose.material3.SliderColors {
    val isDayMode = LocalDayMode.current
    return SliderDefaults.colors(
        thumbColor         = accent,
        activeTrackColor   = accent,
        inactiveTrackColor = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF2A2A2A)
    )
}


private fun fontDisplayName(font: AppFont): String = when (font) {
    AppFont.SYSTEM          -> "System"
    AppFont.JETBRAINS_MONO  -> "JetBrains Mono"
    AppFont.SOURCE_CODE_PRO -> "Source Code Pro"
}
