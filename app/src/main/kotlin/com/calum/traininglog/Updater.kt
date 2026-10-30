package com.calum.traininglog

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val version: String, val apkUrl: String, val sizeBytes: Long, val whatsNew: String = "") : UpdateState
    data class Downloading(val version: String, val percent: Int) : UpdateState
    data class NeedsPermission(val version: String) : UpdateState
    data class Installing(val version: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

@Serializable
internal data class GhAsset(val name: String, val size: Long = 0, @SerialName("browser_download_url") val url: String)

@Serializable
internal data class GhRelease(@SerialName("tag_name") val tag: String, val body: String? = null, val assets: List<GhAsset> = emptyList())

/**
 * In-app updater for the sideloaded app: reads the newest GitHub release of the public repo,
 * downloads its signed APK and passes it to Android's installer. Android itself refuses any APK
 * not signed with the same key as the installed app, and always asks the user to confirm.
 */
object UpdateManager {
    private const val LATEST = "https://api.github.com/repos/calum/training-log/releases/latest"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun currentVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /** [quiet] hides "up to date" and errors, for the automatic check at launch. */
    fun check(ctx: Context, quiet: Boolean = true) {
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading || s is UpdateState.Installing) return
        val app = ctx.applicationContext
        _state.value = UpdateState.Checking
        scope.launch {
            _state.value = runCatching {
                val conn = URL(LATEST).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000; conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                val release = conn.inputStream.bufferedReader().use { json.decodeFromString<GhRelease>(it.readText()) }
                val apk = release.assets.firstOrNull { it.name.endsWith(".apk") }
                if (apk != null && isNewer(release.tag, currentVersion(app))) {
                    UpdateState.Available(release.tag.removePrefix("v"), apk.url, apk.size, release.body.orEmpty())
                } else UpdateState.UpToDate
            }.getOrElse { if (quiet) UpdateState.Idle else UpdateState.Failed("Couldn't check for updates. Are you online?") }
            if (quiet && _state.value == UpdateState.UpToDate) _state.value = UpdateState.Idle
        }
    }

    internal fun parseRelease(text: String): GhRelease = json.decodeFromString(text)

    /** Bullet lines of a release body ("- x" / "* x"), markdown stripped, for the "What's new" list. */
    internal fun whatsNewBullets(body: String): List<String> =
        body.lines().map { it.trim() }
            .filter { it.startsWith("- ") || it.startsWith("* ") }
            .map { it.drop(2).replace("**", "").replace("`", "").trim() }
            .filter { it.isNotEmpty() }

    /** For tests: show a given state without a network call. */
    internal fun setStateForTest(s: UpdateState) { _state.value = s }

    fun dismiss() { _state.value = UpdateState.Idle }

    fun install(ctx: Context) {
        val avail = _state.value as? UpdateState.Available ?: return
        val app = ctx.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(avail.version)
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        _state.value = UpdateState.Downloading(avail.version, 0)
        scope.launch {
            runCatching {
                val file = download(app, avail)
                _state.value = UpdateState.Installing(avail.version)
                commit(app, file)
            }.onFailure { _state.value = UpdateState.Failed("Update failed: ${it.message}") }
        }
    }

    private fun download(app: Context, a: UpdateState.Available): File {
        val dir = File(app.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "update.apk")
        val conn = URL(a.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000; conn.readTimeout = 30_000
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: a.sizeBytes
        conn.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (pct != last) { last = pct; _state.value = UpdateState.Downloading(a.version, pct) }
                }
            }
        }
        return out
    }

    private fun commit(app: Context, apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("update.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val pi = PendingIntent.getBroadcast(
                app, id, Intent(app, InstallReceiver::class.java).setPackage(app.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pi.intentSender)
        }
    }

    internal fun fail(msg: String) { _state.value = UpdateState.Failed(msg) }

    /** "v0.2.0" newer than "0.1.1"? Compares dotted numbers. */
    internal fun isNewer(tag: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val a = parts(tag); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

/** Receives the installer's progress; launches Android's confirm screen when it needs the user's tap. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateManager.dismiss()
            else -> UpdateManager.fail(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install didn't complete.")
        }
    }
}
