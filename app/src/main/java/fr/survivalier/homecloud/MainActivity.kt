package fr.survivalier.homecloud

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App(vm) }
    }
}

@Composable
fun App(vm: MainViewModel) {
    val dark = when (vm.themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val c = WindowCompat.getInsetsController(window, view)
            c.isAppearanceLightStatusBars = !dark
            c.isAppearanceLightNavigationBars = !dark
        }
    }

    HomeCloudTheme(dark) {
        val snackbar = remember { SnackbarHostState() }
        val ctx = LocalContext.current

        LaunchedEffect(Unit) {
            for (ev in vm.events) {
                when (ev) {
                    is UiEvent.Toast -> launch { snackbar.showSnackbar(ev.msg) }
                    is UiEvent.Open -> launchExternal(ctx, ev)
                }
            }
        }

        BackHandler(enabled = vm.page == Page.SETTINGS || vm.page == Page.ADMIN) { vm.goMain() }
        BackHandler(enabled = vm.page == Page.SYNC) { vm.openSettings() }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                when {
                    vm.booting -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else -> when (vm.page) {
                        Page.LOGIN -> LoginScreen(vm)
                        Page.MAIN -> MainScreen(vm)
                        Page.SETTINGS -> SettingsScreen(vm)
                        Page.ADMIN -> AdminScreen(vm)
                        Page.EDITOR -> EditorScreen(vm)
                        Page.SYNC -> SyncScreen(vm)
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding())
            }
        }

        vm.lockPrompt?.let { LockPromptDialog(it) }
    }
}

private fun launchExternal(ctx: Context, ev: UiEvent.Open) {
    val intent = if (ev.share) {
        Intent(Intent.ACTION_SEND).apply {
            type = ev.mime
            putExtra(Intent.EXTRA_STREAM, ev.uri)
        }
    } else {
        Intent(Intent.ACTION_VIEW).apply { setDataAndType(ev.uri, ev.mime) }
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newRawUri("", ev.uri)
    try {
        ctx.startActivity(Intent.createChooser(intent, if (ev.share) "Partager avec" else "Ouvrir avec"))
    } catch (e: Exception) {
        Toast.makeText(ctx, "Aucune application disponible pour ce fichier", Toast.LENGTH_LONG).show()
    }
}
