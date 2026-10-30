package com.calum.traininglog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme as M3
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calum.traininglog.core.Effort
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.MissReason
import com.calum.traininglog.core.Role
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.Stats
import com.calum.traininglog.core.SessionOption
import kotlinx.coroutines.delay
import java.time.LocalDate

internal val Mint = Color(0xFF7FD1AE)
private val colors = darkColorScheme(
    primary = Mint, onPrimary = Color(0xFF00382A), background = Color(0xFF101214), onBackground = Color(0xFFE6E8EA),
    surface = Color(0xFF181B1E), onSurface = Color(0xFFE6E8EA), surfaceVariant = Color(0xFF23282C), onSurfaceVariant = Color(0xFFB8BEC4),
    secondaryContainer = Color(0xFF2B4A3F), onSecondaryContainer = Color(0xFFD5F2E6),
)

@Composable
fun AppRoot(vm: AppViewModel, inPip: Boolean) {
    M3(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = M3.colorScheme.background) {
            val screen by vm.screen.collectAsState()
            if (inPip) {
                PlayerContent(vm, compact = true)
                return@Surface
            }
            Box(Modifier.statusBarsPadding()) {
                when (val s = screen) {
                    Screen.Home -> HomeScreen(vm)
                    Screen.Start -> StartScreen(vm)
                    Screen.Gym -> GymScreen(vm)
                    Screen.TreadmillSetup -> TreadmillSetupScreen(vm)
                    Screen.TreadmillPlayer -> PlayerContent(vm, compact = false)
                    Screen.TreadmillDone -> TreadmillDoneScreen(vm)
                    is Screen.Light -> LightScreen(vm, s.type)
                    Screen.Manual -> ManualLogScreen(vm)
                    Screen.Plan -> PlanScreen(vm)
                    Screen.Trophies -> TrophyScreen(vm)
                    Screen.Export -> ExportScreen(vm)
                }
            }
        }
    }
}

