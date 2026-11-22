package dev.ring.companion

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.ring.companion.service.RingService
import dev.ring.companion.ui.Palette
import dev.ring.companion.ui.PrimaryButton
import dev.ring.companion.ui.RingIcons
import dev.ring.companion.ui.RingTheme
import dev.ring.companion.ui.screens.ConnectScreen
import dev.ring.companion.ui.screens.HistoryScreen
import dev.ring.companion.ui.screens.MeasureScreen
import dev.ring.companion.ui.screens.RingScreen
import dev.ring.companion.ui.screens.SleepScreen
import dev.ring.companion.ui.screens.RawScreen
import dev.ring.companion.ui.screens.SummaryScreen
import dev.ring.companion.ui.screens.TodayScreen
import dev.ring.companion.ui.screens.WorkoutScreen
import dev.ring.companion.workout.WorkoutKind

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RingTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Palette.Ground)
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                ) { AppRoot() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as RingApp).resumeRing()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) (application as RingApp).onUiHidden()
    }
}

private fun requiredPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as RingApp
    var granted by remember { mutableStateOf(hasBluetoothPermission(context)) }
    var btOn by remember { mutableStateOf(isBluetoothOn(context)) }

    // Re-check when the user comes back from settings.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                granted = hasBluetoothPermission(context)
                btOn = isBluetoothOn(context)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasBluetoothPermission(context)
        if (granted) app.resumeRing()
    }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        btOn = isBluetoothOn(context)
        if (btOn) app.resumeRing()
    }

    when {
        !granted -> Gate(
            title = "Allow Bluetooth",
            body = "The app talks to your ring over Bluetooth. Android asks for permission to find and connect to nearby devices.",
            button = "Allow",
        ) { permLauncher.launch(requiredPermissions()) }

        !btOn -> Gate(
            title = "Turn on Bluetooth",
            body = "Bluetooth is off, so the app can't reach your ring.",
            button = "Turn on Bluetooth",
        ) { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }

        else -> Navigation()
    }
}

private fun isBluetoothOn(context: android.content.Context): Boolean =
    context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

@Composable
private fun Gate(title: String, body: String, button: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 64.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(RingIcons.Ring, null, tint = Palette.Accent, modifier = Modifier.size(48.dp))
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        Text(body, fontSize = 15.sp, color = Palette.Muted, lineHeight = 22.sp)
        Spacer(Modifier.weight(1f))
        PrimaryButton(button, onClick, Modifier.fillMaxWidth())
    }
}

private object Routes {
    const val CONNECT = "connect"
    const val TODAY = "today"
    const val HISTORY = "history"
    const val SLEEP = "sleep"
    const val RAW = "raw"
    const val RING = "ring"
    const val WORKOUT = "workout"
    const val MEASURE = "measure/{kind}"
    const val SUMMARY = "summary/{id}?new={new}"
}

@Composable
private fun Navigation() {
    val context = LocalContext.current
    val g = context.graph
    val nav = rememberNavController()
    // Today is always the bottom of the stack once a ring is paired, so every
    // screen can get back to it. An active workout opens on top of it.
    val start = remember { if (g.prefs.ringAddress.value != null) Routes.TODAY else Routes.CONNECT }
    LaunchedEffect(Unit) {
        if (g.workout.isActive && start == Routes.TODAY) nav.navigate(Routes.WORKOUT)
    }

    var pendingKind by remember { mutableStateOf<WorkoutKind?>(null) }
    val startWorkout: (WorkoutKind, Boolean) -> Unit = { kind, gps ->
        (context.applicationContext as RingApp).resumeConnection()
        g.workout.start(kind, gps)
        RingService.start(context)
        nav.navigate(Routes.WORKOUT)
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingKind?.let { startWorkout(it, hasLocationPermission(context)) }
        pendingKind = null
    }

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showTabs = route in setOf(Routes.TODAY, Routes.SLEEP, Routes.HISTORY, Routes.RAW, Routes.RING)

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            NavHost(nav, startDestination = start) {
                composable(Routes.CONNECT) {
                    ConnectScreen(onConnected = {
                        nav.navigate(Routes.TODAY) { popUpTo(Routes.CONNECT) { inclusive = true } }
                    })
                }
                composable(Routes.TODAY) {
                    TodayScreen(
                        onStartWorkout = { kind ->
                            if (kind.usesGps && !hasLocationPermission(context)) {
                                pendingKind = kind
                                locationLauncher.launch(
                                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                                )
                            } else {
                                startWorkout(kind, kind.usesGps)
                            }
                        },
                        onMeasure = { nav.navigate("measure/$it") },
                        onOpenRing = { nav.switchTab(Routes.RING) },
                        onOpenSleep = { nav.switchTab(Routes.SLEEP) },
                        onResumeWorkout = { nav.navigate(Routes.WORKOUT) },
                    )
                }
                composable(Routes.SLEEP) { SleepScreen() }
                composable(Routes.RAW) { RawScreen() }
                composable(Routes.HISTORY) {
                    HistoryScreen(onOpenWorkout = { nav.navigate("summary/$it?new=false") })
                }
                composable(Routes.RING) {
                    RingScreen(onForgotten = {
                        nav.navigate(Routes.CONNECT) { popUpTo(0) { inclusive = true } }
                    })
                }
                composable(Routes.MEASURE, listOf(navArgument("kind") { type = NavType.StringType })) { entry ->
                    MeasureScreen(entry.arguments?.getString("kind") ?: "hr", onClose = { nav.popBackStack() })
                }
                composable(Routes.WORKOUT) {
                    WorkoutScreen(onFinished = { id ->
                        if (id != null) {
                            nav.navigate("summary/$id?new=true") { popUpTo(Routes.TODAY) }
                        } else {
                            nav.navigate(Routes.TODAY) { popUpTo(Routes.TODAY) { inclusive = true } }
                        }
                    })
                }
                composable(
                    Routes.SUMMARY,
                    listOf(
                        navArgument("id") { type = NavType.LongType },
                        navArgument("new") { type = NavType.BoolType; defaultValue = false },
                    ),
                ) { entry ->
                    val isNew = entry.arguments?.getBoolean("new") ?: false
                    SummaryScreen(
                        id = entry.arguments?.getLong("id") ?: 0L,
                        justFinished = isNew,
                        onDone = {
                            if (isNew) nav.navigate(Routes.HISTORY) { popUpTo(Routes.TODAY) }
                            else nav.popBackStack()
                        },
                    )
                }
            }
        }
        if (showTabs) TabBar(route, onSelect = { nav.switchTab(it) })
    }
}

/**
 * Tabs sit on top of Today. Going to Today just pops back to it; restoring saved
 * state for Today would bring back the screens that were above it, so the tap
 * would look like it did nothing.
 */
private fun NavHostController.switchTab(route: String) {
    val hasToday = runCatching { getBackStackEntry(Routes.TODAY) }.isSuccess
    if (route == Routes.TODAY) {
        if (hasToday) popBackStack(Routes.TODAY, inclusive = false)
        else navigate(Routes.TODAY) { popUpTo(0) { inclusive = true } }
        return
    }
    navigate(route) {
        if (hasToday) popUpTo(Routes.TODAY) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun TabBar(route: String?, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.NavBar)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Tab(RingIcons.Calendar, "Today", route == Routes.TODAY) { onSelect(Routes.TODAY) }
        Tab(RingIcons.Moon, "Sleep", route == Routes.SLEEP) { onSelect(Routes.SLEEP) }
        Tab(RingIcons.Clock, "History", route == Routes.HISTORY) { onSelect(Routes.HISTORY) }
        Tab(RingIcons.Pulse, "Raw", route == Routes.RAW) { onSelect(Routes.RAW) }
        Tab(RingIcons.Ring, "Ring", route == Routes.RING) { onSelect(Routes.RING) }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Tab(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .weight(1f)
            .heightIn(min = 52.dp)
            .clickable(role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        val c = if (selected) Palette.Accent else Palette.Muted
        Icon(icon, null, tint = c, modifier = Modifier.size(22.dp))
        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = c)
    }
}
