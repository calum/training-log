package com.calum.traininglog

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface DriveState {
    data object NotSetUp : DriveState
    data object NeedsSignIn : DriveState
    data class Idle(val lastBackupAt: String?) : DriveState
    data object Backing : DriveState
    data class Failed(val message: String) : DriveState
}

/** Outcome of asking Google for a Drive app-data access token. */
sealed interface DriveAuth {
    data class Token(val accessToken: String) : DriveAuth
    /** Consent screen needed: launch this from an Activity result launcher. */
    data class Resolution(val pendingIntent: PendingIntent) : DriveAuth
    data object NotConfigured : DriveAuth
    data class Error(val message: String, val retryable: Boolean) : DriveAuth
}

private class DriveHttpException(val code: Int, message: String) : IOException("Drive HTTP $code: $message")

/**
 * Private backup of the full data to the user's own Google Drive hidden app-data folder
 * (scope drive.appdata: only this app can see it). Talks to Drive v3 REST with HttpURLConnection.
 * No credential is ever stored or written to the backup; access tokens are requested on demand.
 */
object DriveBackup {
    const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
    const val SETUP_MESSAGE = "Drive isn't set up yet. See docs/google-drive-setup.md"

    private const val API = "https://www.googleapis.com"
    private const val PREFS = "drive_backup"
    private const val DEBOUNCE_MS = 3_000L
    private const val MAX_ATTEMPTS = 8

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var pending: AppData? = null
    private var job: Job? = null
    private var initialised = false

    private val _state = MutableStateFlow<DriveState>(DriveState.NotSetUp)
    val state: StateFlow<DriveState> = _state.asStateFlow()

    /** A backup found on Drive (set by [checkForBackup]). */
    private val _found = MutableStateFlow<Backup?>(null)
    val found: StateFlow<Backup?> = _found.asStateFlow()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun connected(ctx: Context) = prefs(ctx).getBoolean("connected", false)

    /** Loads the persisted "connected" flag into [state]. Safe to call repeatedly. */
    fun init(ctx: Context) {
        synchronized(lock) {
            if (initialised) return
            initialised = true
            val p = prefs(ctx)
            _state.value = if (p.getBoolean("connected", false)) DriveState.Idle(p.getString("lastBackupAt", null))
            else DriveState.NotSetUp
        }
    }

    /** Called after each saved session. Cheap, and does nothing until the user has connected Drive. */
    fun requestBackup(ctx: Context, data: AppData) {
        init(ctx)
        if (!connected(ctx)) return
        enqueue(ctx.applicationContext, data, immediate = false)
    }

    /** "Back up now" button: also works before the first consent (it will ask for sign-in). */
    fun backupNow(ctx: Context, data: AppData) {
        init(ctx)
        enqueue(ctx.applicationContext, data, immediate = true)
    }

    private fun enqueue(app: Context, data: AppData, immediate: Boolean) {
        synchronized(lock) {
            pending = data // coalesce: only the latest data is ever uploaded
            if (job?.isActive == true) return
            job = scope.launch { runLoop(app, immediate) }
        }
    }

    private suspend fun runLoop(app: Context, immediate: Boolean) {
        if (!immediate) delay(DEBOUNCE_MS)
        var attempt = 0
        while (true) {
            val data = synchronized(lock) { pending.also { pending = null } } ?: return
            _state.value = DriveState.Backing
            try {
                val token = when (val a = authorize(app)) {
                    is DriveAuth.Token -> a.accessToken
                    is DriveAuth.Resolution -> { markNeedsSignIn(app); return }
                    DriveAuth.NotConfigured -> { _state.value = DriveState.Failed(SETUP_MESSAGE); return }
                    is DriveAuth.Error -> {
                        if (a.retryable) throw IOException(a.message)
                        _state.value = DriveState.Failed(a.message); return
                    }
                }
                upload(token, data)
                val now = Instant.now().toString()
                prefs(app).edit().putBoolean("connected", true).putString("lastBackupAt", now).apply()
                _state.value = DriveState.Idle(now)
                attempt = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: DriveHttpException) {
                when {
                    e.code == 401 -> { markNeedsSignIn(app); return }
                    e.code < 500 && e.code != 429 && e.code != 403 -> {
                        _state.value = DriveState.Failed("Backup failed (${e.code})."); return
                    }
                    else -> if (!retryLater(data, ++attempt)) return
                }
            } catch (e: IOException) {
                if (!retryLater(data, ++attempt)) return
            } catch (e: Exception) {
                _state.value = DriveState.Failed("Backup failed: ${e.message ?: e.javaClass.simpleName}")
                return
            }
        }
    }

    /** Quiet exponential backoff (offline is normal). Returns false after too many attempts. */
    private suspend fun retryLater(data: AppData, attempt: Int): Boolean {
        synchronized(lock) { if (pending == null) pending = data }
        if (attempt >= MAX_ATTEMPTS) {
            _state.value = DriveState.Failed("Couldn't reach Google Drive. Will try again after the next session.")
            synchronized(lock) { pending = null }
            return false
        }
        delay(minOf(30_000L shl (attempt - 1), 10 * 60_000L))
        return true
    }

    private fun markNeedsSignIn(app: Context) {
        prefs(app).edit().putBoolean("connected", false).apply()
        synchronized(lock) { pending = null }
        _state.value = DriveState.NeedsSignIn
    }

    // ---- Authorization (Google Identity Services) ------------------------------------------

    /** Silent when consent was already granted; otherwise returns [DriveAuth.Resolution] to launch. */
    suspend fun authorize(ctx: Context): DriveAuth {
        val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_APPDATA))).build()
        return try {
            val r = Identity.getAuthorizationClient(ctx.applicationContext).authorize(request).await()
            val token = r.accessToken
            val pi = r.pendingIntent
            when {
                r.hasResolution() && pi != null -> DriveAuth.Resolution(pi)
                token != null -> DriveAuth.Token(token)
                else -> DriveAuth.Error("Google didn't return access.", retryable = false)
            }
        } catch (e: ApiException) {
            when (e.statusCode) {
                10 -> DriveAuth.NotConfigured // DEVELOPER_ERROR: no OAuth client for this package + SHA-1
                7, 15, 8 -> DriveAuth.Error("Couldn't reach Google. Are you online?", retryable = true)
                else -> DriveAuth.Error("Google sign-in failed (${e.statusCode}).", retryable = false)
            }
        }
    }

    /** Called with the activity-result data from the consent screen. */
    fun onConsentResult(ctx: Context, data: Intent?, latest: AppData) {
        init(ctx)
        val token = runCatching { Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(data).accessToken }.getOrNull()
        if (token == null) {
            _state.value = DriveState.NeedsSignIn
            return
        }
        prefs(ctx).edit().putBoolean("connected", true).apply()
        _state.value = DriveState.Idle(prefs(ctx).getString("lastBackupAt", null))
        enqueue(ctx.applicationContext, latest, immediate = true)
        checkForBackup(ctx)
    }

    fun reportNotConfigured() { _state.value = DriveState.Failed(SETUP_MESSAGE) }
    fun reportError(msg: String) { _state.value = DriveState.Failed(msg) }

    // ---- Restore ---------------------------------------------------------------------------

    /** Looks for a backup (quietly) and publishes it in [found]. Used by the card on first open. */
    fun checkForBackup(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { _found.value = runCatching { restore(app) }.getOrNull() }
    }

    /** Downloads the newest readable backup (latest file, else a recent monthly snapshot). Null if none. */
    suspend fun restore(ctx: Context): Backup? = withContext(Dispatchers.IO) {
        val token = (authorize(ctx.applicationContext) as? DriveAuth.Token)?.accessToken
            ?: throw IOException("Connect Google Drive first.")
        val order = DriveFormat.restoreOrder(LocalDate.now())
        val files = listFiles(token, order)
        for (name in order) {
            val id = files[name] ?: continue
            val parsed = runCatching { DriveFormat.decode(get(token, "$API/drive/v3/files/$id?alt=media")) }.getOrNull()
            if (parsed != null) return@withContext parsed
        }
        null
    }

    // ---- Drive REST ------------------------------------------------------------------------

    private fun upload(token: String, data: AppData) {
        val body = DriveFormat.encode(data, Instant.now().toString())
        val names = listOf(DriveFormat.LATEST_NAME, DriveFormat.snapshotName(LocalDate.now()))
        val existing = listFiles(token, names)
        for (name in names) put(token, name, existing[name], body)
    }

    private fun listFiles(token: String, names: List<String>): Map<String, String> {
        val q = URLEncoder.encode(DriveFormat.searchQuery(names), "UTF-8")
        val url = "$API/drive/v3/files?spaces=appDataFolder&q=$q&fields=files(id,name)&pageSize=20"
        val files = json.parseToJsonElement(get(token, url)).jsonObject["files"]?.jsonArray ?: return emptyMap()
        return files.associate { it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["id"]!!.jsonPrimitive.content }
    }

    private fun get(token: String, url: String): String = read(open(token, url, "GET"))

    /** Create (multipart, in appDataFolder) or update-by-id (media) a file. */
    private fun put(token: String, name: String, id: String?, content: String) {
        if (id != null) {
            // HttpURLConnection has no PATCH; Google honours the override header on POST.
            val c = open(token, "$API/upload/drive/v3/files/$id?uploadType=media", "POST")
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.doOutput = true
            c.outputStream.use { it.write(content.toByteArray()) }
            read(c)
        } else {
            val boundary = "tl" + System.nanoTime()
            val meta = JsonObject(
                mapOf(
                    "name" to JsonPrimitive(name),
                    "mimeType" to JsonPrimitive("application/json"),
                    "parents" to JsonArray(listOf(JsonPrimitive("appDataFolder"))),
                ),
            )
            val payload = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" +
                json.encodeToString(JsonObject.serializer(), meta) +
                "\r\n--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$content\r\n--$boundary--"
            val c = open(token, "$API/upload/drive/v3/files?uploadType=multipart", "POST")
            c.setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            c.doOutput = true
            c.outputStream.use { it.write(payload.toByteArray()) }
            read(c)
        }
    }

    private fun open(token: String, url: String, method: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun read(c: HttpURLConnection): String {
        try {
            val code = c.responseCode
            if (code !in 200..299) {
                val err = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty().take(200)
                throw DriveHttpException(code, err)
            }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
