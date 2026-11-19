package com.calum.traininglog

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.calum.traininglog.core.CalfMode
import com.calum.traininglog.core.CoreKind
import com.calum.traininglog.core.CoreRun
import com.calum.traininglog.core.ExerciseLibrary
import com.calum.traininglog.core.ExerciseLog
import com.calum.traininglog.core.Feeling
import com.calum.traininglog.core.KneeCareRecord
import com.calum.traininglog.core.KneeCareRoutine
import com.calum.traininglog.core.KneeCareSession
import com.calum.traininglog.core.LoggedSet
import com.calum.traininglog.core.Profile
import com.calum.traininglog.core.RacePlanSettings
import com.calum.traininglog.core.SessionRecord
import com.calum.traininglog.core.SessionType
import com.calum.traininglog.core.TreadmillLog
import com.calum.traininglog.core.TreadmillStyle
import com.calum.traininglog.core.UserSettings
import com.calum.traininglog.ui.AppColors
import com.calum.traininglog.ui.TrainingTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

private fun outDir() = File(System.getProperty("roborazzi.output.dir") ?: "../docs/screenshots").also { it.mkdirs() }

/** Own fixtures: a heavy home calf history, a week-1 plan history and the sessions of one routine. */
private object KneeCareFixtures {
    private var n = 0
    private val d = { m: Int, day: Int -> LocalDate.of(if (m < 6) 2027 else 2026, m, day) }

    fun home(date: LocalDate, kg: Double, vararg reps: Int, target: Int = 8) = SessionRecord(
        "kc-${n++}", date, SessionType.CORE, 15, 15, Feeling.GOOD, kneePain = 1, dayLabel = KneeCareRecord.DAY_LABEL,
        exercises = listOf(ExerciseLog(ExerciseLibrary.CALF_HOME_ID, reps.map { LoggedSet(kg, it, target, 2) })),
        note = "Knee care: Hip circles, Clamshell",
    )

    private fun gym(date: LocalDate, calf: Boolean = true) = SessionRecord(
        "kc-${n++}", date, SessionType.GYM, 50, 50, Feeling.GOOD, kneePain = 1,
        exercises = if (calf) listOf(ExerciseLog("calf", listOf(LoggedSet(60.0, 12, 12, 2), LoggedSet(60.0, 12, 12, 2)))) else emptyList(),
    )

    private fun walk(date: LocalDate, min: Int = 40) = SessionRecord(
        "kc-${n++}", date, SessionType.TREADMILL, min, min, Feeling.GOOD, kneePain = 1,
        treadmill = TreadmillLog(TreadmillStyle.STEADY, min * 60, min * 60, distanceM = min * 85),
    )

    /** Calm weeks before the plan. */
    private fun base(): List<SessionRecord> {
        n = 0
        return listOf(gym(d(11, 2)), walk(d(11, 5)), gym(d(11, 9)), walk(d(11, 12)), gym(d(11, 16)), walk(d(11, 18)))
    }

    /** Thursday of week 1: a gym session (with calf) on Monday, no knee care yet. */
    fun week1Nudge(): List<SessionRecord> = base() + gym(d(11, 23))

    /** Friday of week 1: two knee care sessions done. */
    fun week1Done(): List<SessionRecord> = base() + gym(d(11, 23)) + home(d(11, 24), 22.5, 8, 8, 8) + home(d(11, 26), 22.5, 9, 8, 8)

    /** Home with last time's heavy calf log. */
    fun homeHistory(): List<SessionRecord> = base() + gym(d(11, 23)) + home(d(11, 24), 25.0, 8, 8, 7)

    fun session(): KneeCareSession = KneeCareRoutine.build(
        15, last = null, kneePain = 1, calfStartKg = 25.0, seed = 0, calfMode = CalfMode.HEAVY, calfReps = 8, lastTime = "Last time: 25 kg x 8, 8, 7",
    )

    fun ui(session: KneeCareSession, index: Int, elapsedMs: Long = 0, calf: List<CalfEntry> = emptyList(), phase: KneeCarePhase = KneeCarePhase.RUNNING): KneeCareUi {
        val before = session.steps.take(index).sumOf { it.seconds } * 1000L
        return KneeCareUi(
            phase = phase, session = session, knee = 1, mode = CalfMode.HEAVY, calf = calf,
            run = CoreRun(session.steps, index = index, elapsedMs = elapsedMs, totalElapsedMs = before + elapsedMs),
        )
    }
}

/** Renders the knee care screens, Home with the button and the race plan strip to `kneeCare-*.png` in docs/screenshots. */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-xxhdpi", application = Application::class)
class KneeCareScreenshotTest(private val s: Case) {

    class Case(
        val name: String,
        val today: LocalDate = LocalDate.of(2026, 11, 26),
        val tallDp: Int? = null,
        val scrollTo: String? = null,
        val content: @Composable (LocalDate) -> Unit,
    ) {
        override fun toString() = name
    }

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun capture() {
        s.tallDp?.let { RuntimeEnvironment.setQualifiers("w411dp-h${it}dp-xxhdpi") }
        rule.setContent {
            CompositionLocalProvider(LocalToday provides s.today) {
                TrainingTheme { Surface(Modifier.fillMaxSize(), color = AppColors.background) { s.content(s.today) } }
            }
        }
        rule.waitForIdle()
        s.scrollTo?.let { rule.onNodeWithTag(it).performScrollTo(); rule.waitForIdle() }
        rule.onRoot().captureRoboImage(File(outDir(), "${s.name}.png").path)
    }

    companion object {
        private val fx = KneeCareFixtures
        private val mine = RacePlanSettings(sessionsPerWeek = 4, maxWeeklyMinutes = 300)

        private fun setup(knee: Int?) = @Composable { _: LocalDate ->
            val session = KneeCareRoutine.build(15, kneePain = knee, calfStartKg = 25.0, calfReps = 8, lastTime = "Last time: 25 kg x 8, 8, 7")
            KneeCareSetupContent(
                KneeCareUi(knee = knee, session = session), {}, {}, {}, {}, {}, {},
            )
        }

        private fun plan(records: () -> List<SessionRecord>) = @Composable { today: LocalDate ->
            val profile = Profile(settings = UserSettings(racePlan = mine))
            RacePlanContent(records(), profile, today, onSettings = {}, onStart = {}, onBack = {})
        }

        private fun home(records: () -> List<SessionRecord>, today: LocalDate) = @Composable { _: LocalDate ->
            val ctx = ApplicationProvider.getApplicationContext<Context>()
            Fixtures.seed(ctx, records(), Profile(settings = UserSettings(racePlan = mine)))
            val vm = AppViewModel(ctx as Application, clock = { today })
            HomeScreen(vm)
        }

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> = listOf(
            Case("kneeCare-setup", content = setup(1)),
            Case("kneeCare-setup-full", tallDp = 2600, content = setup(1)),
            Case("kneeCare-setup-gated", tallDp = 1500, content = setup(5)),
            Case("kneeCare-hip-step", content = { _ ->
                val sess = fx.session()
                val i = sess.steps.indexOfFirst { it.kind == CoreKind.WORK && !KneeCareRoutine.isRepStep(it) }
                KneeCarePlayerBody(fx.ui(sess, i, 12_000), false, {}, {}, {}, {}, {}, {}, {})
            }),
            Case("kneeCare-calf-step", content = { _ ->
                val sess = fx.session()
                val i = sess.steps.indices.filter { sess.steps[it].kind == CoreKind.WORK && KneeCareRoutine.isRepStep(sess.steps[it]) }[1]
                val calf = listOf(CalfEntry(25.0, 8, 2), CalfEntry(25.0, 8), CalfEntry(25.0, 8))
                KneeCarePlayerBody(fx.ui(sess, i, 20_000, calf), false, {}, {}, {}, {}, {}, {}, {})
            }),
            Case("kneeCare-rest-step", content = { _ ->
                val sess = fx.session()
                val i = sess.steps.indices.first { sess.steps[it].kind == CoreKind.REST && sess.steps[it].exerciseId == ExerciseLibrary.CALF_HOME_ID }
                val calf = listOf(CalfEntry(25.0, 8, 2), CalfEntry(25.0, 8), CalfEntry(25.0, 8))
                KneeCarePlayerBody(fx.ui(sess, i, 27_000, calf), false, {}, {}, {}, {}, {}, {}, {})
            }),
            Case("kneeCare-finish", content = { _ ->
                val sess = fx.session()
                val u = fx.ui(sess, sess.steps.size, 0, listOf(CalfEntry(25.0, 8, 2), CalfEntry(25.0, 8, 1), CalfEntry(25.0, 7, 0)), KneeCarePhase.DONE)
                    .copy(kneeAfter = 1, feeling = Feeling.GOOD)
                KneeCareDoneContent(u, listOf(LoggedSet(25.0, 8, 8, 2), LoggedSet(25.0, 8, 8, 1), LoggedSet(25.0, 7, 8, 0)), {}, {}, {})
            }),
            Case("kneeCare-home", tallDp = 2300, content = home(fx::homeHistory, LocalDate.of(2026, 11, 26))),
            Case("kneeCare-plan-nudge", scrollTo = "kneeCare", tallDp = 1700, content = plan(fx::week1Nudge)),
            Case("kneeCare-plan-done", today = LocalDate.of(2026, 11, 27), scrollTo = "kneeCare", tallDp = 1700, content = plan(fx::week1Done)),
        ).map { arrayOf(it) }
    }
}
