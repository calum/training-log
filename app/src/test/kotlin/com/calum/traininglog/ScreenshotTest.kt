package com.calum.traininglog

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.TreadmillStyle
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders every screen of the REAL app (real [AppRoot], real [AppViewModel], fixture data on disk) to PNG.
 *
 *   gradle :app:recordRoborazziDebug      ->  PNG files in docs/screenshots
 *
 * This only ever records; nothing is compared, so UI changes never fail a build. To add a screen, append one
 * [Shot] to [shots]: a name (becomes `<name>.png`) and a setup lambda that gets the ViewModel after the
 * fixture data is on disk. Pass `seed = ...` to start from different data, `pip = true` for the 320x180 PiP view.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = android.app.Application::class)
class ScreenshotTest(private val shot: Shot) {

    /** Everything a setup lambda can touch. [clock] is a fake monotonic clock (ms) for [TreadmillEngine]. */
    class Env(val vm: AppViewModel, val ctx: Context) {
        /** Overwrites a private `MutableStateFlow` field of a singleton (GithubSync, DriveBackup, UpdateManager). */
        fun force(owner: Any, field: String, value: Any?) {
            val f = owner.javaClass.getDeclaredField(field).apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            (f.get(owner) as kotlinx.coroutines.flow.MutableStateFlow<Any?>).value = value
        }

        /** Guided core session started and advanced [intoMs] into the first step of [kind] (after [skipSteps] of them). */
        fun corePlayer(kind: com.calum.traininglog.core.CoreKind, intoMs: Long, skipSteps: Int = 0, pausedAway: Boolean = false) {
            vm.begin(SessionType.CORE, 20)
            vm.core.start()
            val steps = vm.core.ui.value.session!!.steps
            val idx = steps.indices.filter { steps[it].kind == kind }[skipSteps]
            val into = if (intoMs < 0) steps[idx].seconds * 1000L + intoMs else intoMs
            var run = com.calum.traininglog.core.CoreRun(steps, index = idx, elapsedMs = into, totalElapsedMs = steps.take(idx).sumOf { it.seconds } * 1000L + into)
            if (pausedAway) run = run.pause(away = true)
            force(vm.core, "_ui", vm.core.ui.value.copy(run = run, changes = 0))
        }

        /** Signed in through the GitHub App (needs the Keystore; falls back to a pasted-token look). */
        fun githubConnected() {
            ctx.getSharedPreferences("github_sync", Context.MODE_PRIVATE).edit()
                .putString("owner", "calum").putString("repo", "training-log").putString("branch", "data")
                .putString("clientId", "Iv23liExampleClientId").putLong("lastSyncAt", System.currentTimeMillis() - 3_600_000).apply()
            TokenVault.save(ctx, TokenSet("ghu_example", "ghr_example", System.currentTimeMillis() + 3_600_000, System.currentTimeMillis() + 86_400_000).encode())
        }

        fun driveConnected(found: Backup? = null) {
            DriveBackup.javaClass.getDeclaredField("initialised").apply { isAccessible = true }.setBoolean(DriveBackup, true)
            force(DriveBackup, "_state", DriveState.Idle(java.time.OffsetDateTime.now().minusHours(5).toString()))
            force(DriveBackup, "_found", found)
        }

        fun treadmillPlayer(style: TreadmillStyle, minutes: Int, tickToMs: List<Long>) {
            vm.setInputs { it.copy(minutes = minutes) }
            vm.begin(SessionType.TREADMILL, minutes, style)
            // The player refreshes itself from the device clock, so drive the engine from that same clock.
            TreadmillEngine.start(vm.pendingTreadmill.value!!, android.os.SystemClock.elapsedRealtime())
            var done = 0L
            tickToMs.forEach { ms ->
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(ms - done))
                done = ms
                TreadmillEngine.tick(android.os.SystemClock.elapsedRealtime())
            }
            vm.go(Screen.TreadmillPlayer)
        }
    }

    class Shot(
        val name: String,
        val pip: Boolean = false,
        val seed: (() -> Pair<List<com.calum.traininglog.core.SessionRecord>, Profile>)? = null,
        /** Taller viewport (dp) to see a whole long screen in one PNG. */
        val tallDp: Int? = null,
        /** Replaces [AppRoot] with custom content (for stateless galleries). */
        val content: (@androidx.compose.runtime.Composable (AppViewModel) -> Unit)? = null,
        /** Runs after the first composition (clicks etc.). */
        val act: (androidx.compose.ui.test.junit4.ComposeContentTestRule.() -> Unit)? = null,
        val setup: Env.() -> Unit = {},
    ) {
        override fun toString() = name
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capture() {
        TreadmillEngine.stop()
        shot.tallDp?.let { org.robolectric.RuntimeEnvironment.setQualifiers("w411dp-h${it}dp-xxhdpi") }
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val (records, profile) = (shot.seed ?: ::busy)()
        Fixtures.seed(ctx, records, profile)
        val vm = AppViewModel(ctx as android.app.Application)
        val env = Env(vm, ctx)
        env.force(UpdateManager, "_state", UpdateState.Idle)
        env.force(GithubSync, "_state", SyncState.NotConfigured)
        env.force(GithubSync, "_device", GithubSync.DeviceState.Idle)
        env.force(GithubSync, "_restore", GithubSync.RestoreState.Idle)
        env.force(GithubSync, "_test", GithubSync.TestState.Idle)
        env.force(DriveBackup, "_state", DriveState.NotSetUp)
        env.force(DriveBackup, "_found", null)
        env.apply(shot.setup)
        rule.setContent {
            if (shot.content != null) shot.content.invoke(vm)
            else if (shot.pip) Box(Modifier.size(320.dp, 180.dp).testTag("shot")) { AppRoot(vm, inPip = true) }
            else AppRoot(vm, inPip = false)
        }
        rule.waitForIdle()
        shot.act?.let { rule.it(); rule.waitForIdle() }
        val file = File(outputDir(), "${shot.name}.png")
        if (shot.pip) rule.onNodeWithTag("shot").captureRoboImage(file.path)
        else rule.onAllNodes(androidx.compose.ui.test.isRoot()).let { it[it.fetchSemanticsNodes().size - 1] }.captureRoboImage(file.path)
        TreadmillEngine.stop()
    }

    companion object {
        private fun outputDir(): File =
            File(System.getProperty("roborazzi.output.dir") ?: "../docs/screenshots").also { it.mkdirs() }

        private fun busy() = Fixtures.busyRecords() to Fixtures.busyProfile()
        private fun empty() = emptyList<com.calum.traininglog.core.SessionRecord>() to Profile()

        /** THE list: add a line here to capture another screen/state. */
        private val shots = listOf(
            Shot("home-empty", seed = ::empty),
            Shot("home-busy"),
            Shot("home-celebration") { vm.saveManual(SessionType.TREADMILL, 0, 45, TreadmillStyle.PYRAMID, "") },
            Shot("start-default") { vm.go(Screen.Start) },
            Shot("start-recommendation") {
                vm.go(Screen.Start)
                vm.setInputs { it.copy(minutes = 60) }
                vm.recommend()
            },
            Shot("gym-mid-session") {
                vm.setInputs { it.copy(minutes = 75) }
                vm.begin(SessionType.GYM, 75)
                vm.gym.value!!.exercises[0].warmups.indices.forEach { vm.toggleWarmup(0, it) }
                vm.doneSet(0, 0); vm.setRir(0, 0, 3)
                vm.doneSet(0, 1); vm.setRir(0, 1, 2)
                vm.doneSet(1, 0); vm.setRir(1, 0, 3)
                vm.doneSet(1, 1) // rest timer starts here
            },
            Shot("treadmill-setup") { vm.setInputs { it.copy(minutes = 45) }; vm.begin(SessionType.TREADMILL, 45, TreadmillStyle.PYRAMID) },
            Shot("treadmill-player") { treadmillPlayer(TreadmillStyle.PYRAMID, 45, listOf(700_000, 701_500)) },
            Shot("treadmill-player-flashing") { treadmillPlayer(TreadmillStyle.PYRAMID, 45, listOf(284_000, 285_000)) },
            Shot("treadmill-player-pip", pip = true) { treadmillPlayer(TreadmillStyle.PYRAMID, 45, listOf(700_000, 701_500)) },
            Shot("treadmill-done") {
                treadmillPlayer(TreadmillStyle.INTERVALS, 40, listOf(2_400_000, 2_401_500))
                vm.endTreadmill()
            },
            Shot("manual-log") { vm.go(Screen.Manual) },
            Shot("light-hike") { vm.begin(SessionType.HIKE, 120) },
            Shot("light-core") { vm.begin(SessionType.CORE, 15) },
            Shot("export") { vm.go(Screen.Export) },
            Shot("plan") { vm.go(Screen.Plan) },
            Shot("plan-empty", seed = ::empty) { vm.go(Screen.Plan) },
            Shot("trophies") { vm.go(Screen.Trophies) },
            Shot("backup-sync") { vm.go(Screen.Backup) },
            Shot("settings") { vm.go(Screen.Settings) },
            Shot("blocks") { vm.go(Screen.Blocks) },
            // ---- Second pass: guided core, blocks, backup, update banner ----
            Shot("core-setup") { vm.begin(SessionType.CORE, 20) },
            Shot("core-setup-full", tallDp = 2300) { vm.begin(SessionType.CORE, 20) },
            Shot("core-player-work") { corePlayer(com.calum.traininglog.core.CoreKind.WORK, 14_000) },
            Shot("core-player-warn") { corePlayer(com.calum.traininglog.core.CoreKind.WORK, -2_000, skipSteps = 1) },
            Shot("core-player-rest") { corePlayer(com.calum.traininglog.core.CoreKind.REST, 6_000) },
            Shot("core-player-warmup") { corePlayer(com.calum.traininglog.core.CoreKind.WARMUP, 20_000) },
            Shot("core-player-away") { corePlayer(com.calum.traininglog.core.CoreKind.WORK, 9_000, pausedAway = true) },
            Shot("core-done") {
                corePlayer(com.calum.traininglog.core.CoreKind.WORK, 0, skipSteps = 3)
                vm.core.end()
            },
            Shot("blocks-active", seed = { Fixtures.busyRecords() to Fixtures.blockProfile() }, tallDp = 2300) { vm.go(Screen.Blocks) },
            Shot("blocks-active-top", seed = { Fixtures.busyRecords() to Fixtures.blockProfile() }) { vm.go(Screen.Blocks) },
            Shot("blocks-only-finished", seed = { Fixtures.busyRecords() to Fixtures.blockProfile(withCurrent = false) }, tallDp = 1700) { vm.go(Screen.Blocks) },
            Shot("blocks-empty-full", tallDp = 2300) { vm.go(Screen.Blocks) },
            Shot("blocks-new-form", seed = { Fixtures.busyRecords() to Fixtures.blockProfile() }, tallDp = 3300,
                act = { onNodeWithText("Start a new block instead").performScrollTo().performClick() }) { vm.go(Screen.Blocks) },
            Shot("blocks-review", seed = { Fixtures.busyRecords() to Fixtures.blockProfile() },
                act = { onNodeWithText("Block review").performScrollTo().performClick() }) { vm.go(Screen.Blocks) },
            Shot("home-block", seed = { Fixtures.busyRecords() to Fixtures.blockProfile(withFinished = false) }, tallDp = 1700),
            Shot("home-block-done", seed = { Fixtures.busyRecords() to Fixtures.blockProfile(withCurrent = false) }, tallDp = 1700),
            Shot("manual-log-full", tallDp = 1700) { vm.go(Screen.Manual) },
            Shot("light-hike-full", tallDp = 1100) { vm.begin(SessionType.HIKE, 120) },
            Shot("export-full", tallDp = 1700) { vm.go(Screen.Export) },
            Shot("backup-sync-full", tallDp = 3000) { vm.go(Screen.Backup) },
            Shot("backup-github", tallDp = 3000) { githubConnected(); driveConnected(); vm.go(Screen.Backup) },
            Shot("backup-device-code", tallDp = 1700) {
                force(GithubSync, "_device", GithubSync.DeviceState.Waiting("WDJB-MJHT", "https://github.com/login/device"))
                vm.go(Screen.Backup)
            },
            Shot("backup-restore-preview", seed = ::empty, tallDp = 3000) {
                githubConnected()
                val recs = Fixtures.busyRecords()
                force(GithubSync, "_restore", GithubSync.RestoreState.Preview(recs, recs.size))
                driveConnected(Backup(1, java.time.OffsetDateTime.now().minusDays(2).toString(), AppData(recs, Profile())))
                vm.go(Screen.Backup)
            },
            Shot("update-banners", content = { _ ->
                com.calum.traininglog.ui.TrainingTheme {
                    androidx.compose.foundation.layout.Column(
                        Modifier.fillMaxSize().background(com.calum.traininglog.ui.AppColors.background).statusBarsPadding().padding(16.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                    ) {
                        listOf(
                            UpdateState.Checking, UpdateState.UpToDate, UpdateState.Available("0.6.0", "u", 9_000_000),
                            UpdateState.Downloading("0.6.0", 42), UpdateState.NeedsPermission("0.6.0"), UpdateState.Installing("0.6.0"),
                            UpdateState.Failed("Couldn't reach GitHub. Check your connection and try again."),
                        ).forEach { UpdateBannerContent(it) }
                    }
                }
            }),
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = shots.map { arrayOf(it) }
    }
}
