package com.calum.traininglog

import android.content.Context
import com.calum.traininglog.core.PublicExport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

// ---------------------------------------------------------------------------------------------
// Pure logic (no Android): unit-tested on the JVM
// ---------------------------------------------------------------------------------------------

/** Where to push. [token] is secret: [toString] never shows it. */
class GithubConfig(val owner: String, val repo: String, val branch: String = "main", val token: String) {
    override fun toString() = "GithubConfig($owner/$repo@$branch, token=redacted)"
}

sealed interface SyncState {
    data object NotConfigured : SyncState
    data class Idle(val lastSyncAt: Long?) : SyncState
    data object Syncing : SyncState
    data class Failed(val message: String) : SyncState
    data object NeedsSignIn : SyncState
}

sealed interface SyncOutcome {
    data class Success(val pushed: Int, val skipped: Int) : SyncOutcome
    /** Token revoked, expired or lacking permission. Data stays queued. */
    data object NeedsSignIn : SyncOutcome
    /** Repo or branch missing. Needs the user to fix settings; no automatic retry. */
    data class NotFound(val message: String) : SyncOutcome
    /** Offline, rate limited or server trouble: try again later. */
    data class Retry(val message: String) : SyncOutcome
    /** Anything else; not retried automatically. */
    data class Failed(val message: String) : SyncOutcome
}

data class ConnResult(val ok: Boolean, val message: String, val needsSignIn: Boolean = false)

/** How an outcome moves the visible state. */
fun SyncState.after(outcome: SyncOutcome, now: Long): SyncState = when (outcome) {
    is SyncOutcome.Success -> SyncState.Idle(now)
    SyncOutcome.NeedsSignIn -> SyncState.NeedsSignIn
    is SyncOutcome.NotFound -> SyncState.Failed(outcome.message)
    is SyncOutcome.Retry -> SyncState.Failed(outcome.message + " Will retry automatically.")
    is SyncOutcome.Failed -> SyncState.Failed(outcome.message)
}

object Redact {
    private val patterns = listOf(
        Regex("github_pat_[A-Za-z0-9_]+"),
        Regex("gh[pousr]_[A-Za-z0-9]{20,}"),
        Regex("(?i)bearer\\s+\\S+"),
    )

    /** Removes the exact [secret] and anything that looks like a GitHub token from [text]. */
    fun scrub(text: String, secret: String? = null): String {
        var out = text
        if (!secret.isNullOrEmpty()) out = out.replace(secret, "••••")
        patterns.forEach { out = it.replace(out, "••••") }
        return out
    }
}

object GithubPaths {
    private fun seg(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Percent-encodes each path segment but keeps the slashes. */
    fun encodePath(path: String): String = path.split('/').joinToString("/") { seg(it) }

    fun contents(owner: String, repo: String, path: String, branch: String? = null): String {
        val base = "https://api.github.com/repos/${seg(owner)}/${seg(repo)}/contents/${encodePath(path)}"
        return if (branch != null) base + "?ref=" + URLEncoder.encode(branch, "UTF-8") else base
    }

    fun repo(owner: String, repo: String) = "https://api.github.com/repos/${seg(owner)}/${seg(repo)}"
    fun branch(owner: String, repo: String, branch: String) = repo(owner, repo) + "/branches/" + encodePath(branch)

    fun base64(content: String): String = Base64.getEncoder().encodeToString(content.toByteArray(Charsets.UTF_8))

    /** The git blob id GitHub reports as `sha` for a file with this content. */
    fun blobSha(content: String): String {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
        md.update(bytes)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun commitMessage(sessions: Int) = "Update training data ($sessions session${if (sessions == 1) "" else "s"})"

    fun validName(s: String) = s.isNotBlank() && s.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    fun validBranch(s: String) = s.isNotBlank() && !s.contains(' ') && !s.contains("..")
}

class HttpReq(val method: String, val url: String, val token: String, val body: String? = null) {
    override fun toString() = "$method $url"
}

class HttpResp(val code: Int, val body: String, headers: Map<String, String> = emptyMap()) {
    val headers: Map<String, String> = headers.mapKeys { it.key.lowercase() }
}

/** The HTTP layer; faked in tests. Throws [IOException] when offline or timing out. */
fun interface GithubHttp {
    fun execute(req: HttpReq): HttpResp
}

/** Last-pushed git blob sha per path, so unchanged files cost no network at all. */
interface HashStore {
    fun get(path: String): String?
    fun put(path: String, sha: String)
}

private val json = Json { ignoreUnknownKeys = true }

class SyncEngine(private val http: GithubHttp, private val cfg: GithubConfig, private val hashes: HashStore) {
    private class Abort(val outcome: SyncOutcome) : Exception()

    private fun send(method: String, url: String, body: String? = null): HttpResp = try {
        http.execute(HttpReq(method, url, cfg.token, body))
    } catch (_: IOException) {
        throw Abort(SyncOutcome.Retry("Couldn't reach GitHub (offline?)."))
    }

    private fun rateLimited(r: HttpResp) =
        r.code == 429 || r.headers["x-ratelimit-remaining"] == "0" || r.headers.containsKey("retry-after") ||
            r.body.contains("rate limit", ignoreCase = true)

    /** Maps the common failure codes by throwing; returns normally for codes the caller handles itself. */
    private fun common(r: HttpResp) {
        when {
            r.code == 401 -> throw Abort(SyncOutcome.NeedsSignIn)
            r.code == 403 || r.code == 429 ->
                throw Abort(if (rateLimited(r)) SyncOutcome.Retry("GitHub rate limit reached.") else SyncOutcome.NeedsSignIn)
            r.code >= 500 -> throw Abort(SyncOutcome.Retry("GitHub had a problem (HTTP ${r.code})."))
        }
    }

    private fun notFound(): Nothing {
        val o = cfg.owner; val r = cfg.repo
        val repo = send("GET", GithubPaths.repo(o, r))
        common(repo)
        if (repo.code == 404) throw Abort(SyncOutcome.NotFound("Repo $o/$r not found, or the token can't see it. Check the names and the token's repository access."))
        val br = send("GET", GithubPaths.branch(o, r, cfg.branch))
        common(br)
        if (br.code == 404) throw Abort(SyncOutcome.NotFound("Branch '${cfg.branch}' not found in $o/$r. Create it (an initial commit) or change the branch."))
        throw Abort(SyncOutcome.Failed("GitHub returned an unexpected 404."))
    }

    /** Remote blob sha of [path], or null when the file doesn't exist yet. */
    private fun remoteSha(path: String): String? {
        val r = send("GET", GithubPaths.contents(cfg.owner, cfg.repo, path, cfg.branch))
        common(r)
        return when (r.code) {
            200 -> (runCatching { json.parseToJsonElement(r.body) }.getOrNull() as? JsonObject)
                ?.get("sha")?.jsonPrimitive?.contentOrNull
                ?: throw Abort(SyncOutcome.Failed("Unexpected reply from GitHub for $path."))
            404 -> {
                // A missing file is normal; a missing repo or branch is not.
                val br = send("GET", GithubPaths.branch(cfg.owner, cfg.repo, cfg.branch))
                common(br)
                if (br.code == 404) notFound()
                null
            }
            else -> throw Abort(SyncOutcome.Failed("GitHub replied HTTP ${r.code} for $path."))
        }
    }

    private fun putFile(path: String, content: String, sha: String?, message: String): HttpResp {
        val body = buildJsonObject {
            put("message", message)
            put("content", GithubPaths.base64(content))
            put("branch", cfg.branch)
            if (sha != null) put("sha", sha)
        }.toString()
        return send("PUT", GithubPaths.contents(cfg.owner, cfg.repo, path), body)
    }

    fun sync(files: Map<String, String>, sessions: Int): SyncOutcome = try {
        val message = GithubPaths.commitMessage(sessions)
        var pushed = 0
        var skipped = 0
        for ((path, content) in files.toSortedMap()) {
            val blob = GithubPaths.blobSha(content)
            if (hashes.get(path) == blob) { skipped++; continue }
            var remote = remoteSha(path)
            if (remote == blob) { hashes.put(path, blob); skipped++; continue }
            var r = putFile(path, content, remote, message)
            if (r.code == 409 || r.code == 422) { // stale sha: re-fetch once and retry
                remote = remoteSha(path)
                if (remote == blob) { hashes.put(path, blob); skipped++; continue }
                r = putFile(path, content, remote, message)
            }
            common(r)
            when (r.code) {
                200, 201 -> { hashes.put(path, blob); pushed++ }
                404 -> notFound()
                409, 422 -> throw Abort(SyncOutcome.Retry("GitHub reported a conflict for $path."))
                else -> throw Abort(SyncOutcome.Failed("GitHub replied HTTP ${r.code} while saving $path."))
            }
        }
        SyncOutcome.Success(pushed, skipped)
    } catch (a: Abort) {
        a.outcome
    }

    /** Checks the token can read the repo and push to it, and that the branch exists. */
    fun testConnection(): ConnResult = try {
        val r = send("GET", GithubPaths.repo(cfg.owner, cfg.repo))
        common(r)
        when {
            r.code == 404 -> ConnResult(false, "Repo ${cfg.owner}/${cfg.repo} not found, or the token can't see it.")
            r.code != 200 -> ConnResult(false, "GitHub replied HTTP ${r.code}.")
            else -> {
                val obj = runCatching { json.parseToJsonElement(r.body).jsonObject }.getOrNull()
                val push = runCatching { obj?.get("permissions")?.jsonObject?.get("push")?.jsonPrimitive?.boolean }.getOrNull()
                val br = send("GET", GithubPaths.branch(cfg.owner, cfg.repo, cfg.branch))
                common(br)
                when {
                    push != true -> ConnResult(false, "The token can read the repo but can't push. Give it Contents: Read and write.")
                    br.code == 404 -> ConnResult(false, "Branch '${cfg.branch}' doesn't exist in the repo.")
                    else -> ConnResult(true, "Connected. The token can push to ${cfg.owner}/${cfg.repo}.")
                }
            }
        }
    } catch (a: Abort) {
        when (val o = a.outcome) {
            SyncOutcome.NeedsSignIn -> ConnResult(false, "GitHub rejected the token. It may be wrong, expired or revoked.", needsSignIn = true)
            is SyncOutcome.Retry -> ConnResult(false, o.message)
            is SyncOutcome.NotFound -> ConnResult(false, o.message)
            is SyncOutcome.Failed -> ConnResult(false, o.message)
            is SyncOutcome.Success -> ConnResult(true, "Connected.")
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Android glue
// ---------------------------------------------------------------------------------------------

private class UrlConnectionHttp : GithubHttp {
    override fun execute(req: HttpReq): HttpResp {
        val conn = URL(req.url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.requestMethod = req.method
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("Authorization", "Bearer ${req.token}")
            conn.setRequestProperty("User-Agent", "training-log-app")
            if (req.body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(req.body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val headers = listOf("x-ratelimit-remaining", "retry-after")
                .mapNotNull { h -> conn.getHeaderField(h)?.let { h to it } }.toMap()
            return HttpResp(code, text, headers)
        } finally {
            conn.disconnect()
        }
    }
}

private class PrefsHashStore(private val ctx: Context) : HashStore {
    private val p get() = ctx.getSharedPreferences("github_sync", Context.MODE_PRIVATE)
    override fun get(path: String) = p.getString("h:$path", null)
    override fun put(path: String, sha: String) { p.edit().putString("h:$path", sha).apply() }
    fun clear() {
        val e = p.edit()
        p.all.keys.filter { it.startsWith("h:") }.forEach { e.remove(it) }
        e.apply()
    }
}

/** Public GitHub data-repo sync: config in private prefs, token in [TokenVault], status in [state]. */
object GithubSync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val http: GithubHttp = UrlConnectionHttp()

    private val _state = MutableStateFlow<SyncState>(SyncState.NotConfigured)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    sealed interface TestState {
        data object Idle : TestState
        data object Testing : TestState
        data class Done(val result: ConnResult) : TestState
    }
    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test: StateFlow<TestState> = _test.asStateFlow()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("github_sync", Context.MODE_PRIVATE)

    class Settings(val owner: String, val repo: String, val branch: String)

    fun settings(ctx: Context): Settings? {
        val p = prefs(ctx)
        val o = p.getString("owner", null) ?: return null
        val r = p.getString("repo", null) ?: return null
        return Settings(o, r, p.getString("branch", "main") ?: "main")
    }

    fun hasToken(ctx: Context) = TokenVault.has(ctx)
    fun lastSyncAt(ctx: Context): Long? = prefs(ctx).getLong("lastSyncAt", 0L).takeIf { it > 0 }
    fun isDirty(ctx: Context) = prefs(ctx).getBoolean("dirty", false)
    fun markDirty(ctx: Context) { prefs(ctx).edit().putBoolean("dirty", true).apply() }
    fun isConfigured(ctx: Context) = settings(ctx) != null && hasToken(ctx)
    private fun needsSignIn(ctx: Context) = prefs(ctx).getBoolean("needsSignIn", false)

    /** Recomputes the state from storage. Safe to call often. */
    fun refresh(ctx: Context) {
        if (_state.value is SyncState.Syncing) return
        _state.value = when {
            !isConfigured(ctx) -> SyncState.NotConfigured
            needsSignIn(ctx) -> SyncState.NeedsSignIn
            else -> (_state.value as? SyncState.Failed) ?: SyncState.Idle(lastSyncAt(ctx))
        }
    }

    /** Saves owner/repo/branch and, when non-null, a new token. Returns an error message or null. */
    fun save(ctx: Context, owner: String, repo: String, branch: String, newToken: String?): String? {
        val app = ctx.applicationContext
        val o = owner.trim(); val r = repo.trim().removeSuffix(".git"); val b = branch.trim().ifEmpty { "main" }
        if (!GithubPaths.validName(o)) return "Owner looks wrong. Use just your GitHub username."
        if (!GithubPaths.validName(r)) return "Repo name looks wrong."
        if (!GithubPaths.validBranch(b)) return "Branch name looks wrong."
        val tok = newToken?.trim()?.takeIf { it.isNotEmpty() }
        if (tok == null && !TokenVault.has(app)) return "Paste your token first."
        if (tok != null && !TokenVault.save(app, tok)) return "Couldn't store the token securely on this device."
        val p = prefs(app)
        val changedTarget = p.getString("owner", null) != o || p.getString("repo", null) != r || p.getString("branch", "main") != b
        if (changedTarget) PrefsHashStore(app).clear()
        val e = p.edit().putString("owner", o).putString("repo", r).putString("branch", b)
        if (tok != null) e.putBoolean("needsSignIn", false)
        e.apply()
        _state.value = SyncState.Idle(lastSyncAt(app))
        _test.value = TestState.Idle
        SyncManager.request(app) // pushes the full history on first connect, and anything queued
        return null
    }

    /** Forgets the token and settings. Data already on GitHub stays there. */
    fun disconnect(ctx: Context) {
        val app = ctx.applicationContext
        TokenVault.clear(app)
        PrefsHashStore(app).clear()
        prefs(app).edit().clear().apply()
        SyncManager.cancel(app)
        _state.value = SyncState.NotConfigured
        _test.value = TestState.Idle
    }

    private fun config(ctx: Context): GithubConfig? {
        val s = settings(ctx) ?: return null
        val t = TokenVault.load(ctx) ?: return null
        return GithubConfig(s.owner, s.repo, s.branch, t)
    }

    fun testConnection(ctx: Context) {
        val app = ctx.applicationContext
        val cfg = config(app)
        if (cfg == null) {
            _test.value = TestState.Done(ConnResult(false, "Save the repo and token first."))
            return
        }
        _test.value = TestState.Testing
        scope.launch {
            val res = runCatching { SyncEngine(http, cfg, PrefsHashStore(app)).testConnection() }
                .getOrElse { ConnResult(false, "Test failed (${it.javaClass.simpleName}).") }
            val safe = res.copy(message = Redact.scrub(res.message, cfg.token))
            if (safe.needsSignIn) {
                prefs(app).edit().putBoolean("needsSignIn", true).apply()
                _state.value = SyncState.NeedsSignIn
            } else if (safe.ok && needsSignIn(app)) {
                prefs(app).edit().putBoolean("needsSignIn", false).apply()
                _state.value = SyncState.Idle(lastSyncAt(app))
            }
            _test.value = TestState.Done(safe)
        }
    }

    /** Runs one sync with the latest saved data. Used by [SyncWorker]; never throws. */
    suspend fun runSync(ctx: Context): SyncOutcome = mutex.withLock {
        val app = ctx.applicationContext
        val cfg = config(app)
        if (cfg == null) {
            if (hasToken(app) || settings(app) != null) {
                // Token can no longer be decrypted: ask for it again.
                if (settings(app) != null) prefs(app).edit().putBoolean("needsSignIn", true).apply()
            }
            refresh(app)
            return@withLock if (settings(app) != null) SyncOutcome.NeedsSignIn else SyncOutcome.Failed("GitHub sync isn't set up.")
        }
        if (needsSignIn(app)) {
            _state.value = SyncState.NeedsSignIn
            return@withLock SyncOutcome.NeedsSignIn
        }
        _state.value = SyncState.Syncing
        val raw = try {
            val data = Store(app).load()
            val files = PublicExport.files(data.records)
            SyncEngine(http, cfg, PrefsHashStore(app)).sync(files, data.records.size)
        } catch (e: Exception) {
            SyncOutcome.Retry("Sync hit a snag (${e.javaClass.simpleName}).")
        }
        val outcome = when (raw) {
            is SyncOutcome.Retry -> raw.copy(message = Redact.scrub(raw.message, cfg.token))
            is SyncOutcome.Failed -> raw.copy(message = Redact.scrub(raw.message, cfg.token))
            is SyncOutcome.NotFound -> raw.copy(message = Redact.scrub(raw.message, cfg.token))
            else -> raw
        }
        val now = System.currentTimeMillis()
        val e = prefs(app).edit()
        when (outcome) {
            is SyncOutcome.Success -> e.putLong("lastSyncAt", now).putBoolean("dirty", false).putBoolean("needsSignIn", false)
            SyncOutcome.NeedsSignIn -> e.putBoolean("needsSignIn", true).putBoolean("dirty", true)
            else -> e.putBoolean("dirty", true)
        }
        e.apply()
        _state.value = _state.value.after(outcome, now)
        outcome
    }

    /** "Sync now" button. */
    fun syncNow(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { runSync(app) }
    }
}
