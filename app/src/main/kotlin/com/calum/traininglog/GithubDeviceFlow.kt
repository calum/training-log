package com.calum.traininglog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException

// Pure logic for GitHub's "device flow" with a GitHub App (user-to-server tokens). No Android, no secrets compiled in:
// the client id is public and entered by the user; no client secret is ever used.

private val flowJson = Json { ignoreUnknownKeys = true }

object GithubOAuthUrls {
    const val DEVICE_CODE = "https://github.com/login/device/code"
    const val ACCESS_TOKEN = "https://github.com/login/oauth/access_token"
    const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
    const val VERIFY_FALLBACK = "https://github.com/login/device"

    /** Only ever open a github.com https page in the browser. */
    fun safeVerificationUri(uri: String?): String =
        if (uri != null && uri.startsWith("https://github.com/")) uri else VERIFY_FALLBACK
}

/** Form POST layer for the OAuth endpoints; faked in tests. Throws [IOException] when offline. */
fun interface FormHttp {
    fun post(url: String, form: Map<String, String>): HttpResp
}

data class DeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSec: Int,
    val intervalSec: Int,
) {
    // The device code is a secret-ish value: keep it out of logs.
    override fun toString() = "DeviceCode(user=$userCode, expiresIn=$expiresInSec, interval=$intervalSec)"
}

/** The tokens, with absolute expiry times in epoch millis (null = does not expire, e.g. a pasted personal access token). */
data class TokenSet(
    val access: String,
    val refresh: String? = null,
    val accessExpiresAt: Long? = null,
    val refreshExpiresAt: Long? = null,
) {
    override fun toString() = "TokenSet(redacted)"

    val canRefresh get() = refresh != null

    fun accessExpired(now: Long) = accessExpiresAt != null && now >= accessExpiresAt

    /** True when the access token is expired or about to be (within [skewMs]) and a refresh token exists. */
    fun shouldRefresh(now: Long, skewMs: Long = REFRESH_SKEW_MS): Boolean =
        refresh != null && accessExpiresAt != null && now >= accessExpiresAt - skewMs

    fun refreshUsable(now: Long) = refresh != null && (refreshExpiresAt == null || now < refreshExpiresAt)

    /** What gets encrypted into the [TokenVault]. A plain (pasted) token is stored as-is, for backward compatibility. */
    fun encode(): String =
        if (refresh == null && accessExpiresAt == null) access
        else buildJsonObject {
            put("v", 1)
            put("access", access)
            refresh?.let { put("refresh", it) }
            accessExpiresAt?.let { put("accessExpiresAt", it) }
            refreshExpiresAt?.let { put("refreshExpiresAt", it) }
        }.toString()

    companion object {
        const val REFRESH_SKEW_MS = 5 * 60 * 1000L

        fun decode(stored: String): TokenSet {
            if (!stored.startsWith("{")) return TokenSet(stored)
            val o = runCatching { flowJson.parseToJsonElement(stored).jsonObject }.getOrNull() ?: return TokenSet(stored)
            val access = o["access"]?.jsonPrimitive?.contentOrNull ?: return TokenSet(stored)
            return TokenSet(
                access,
                o["refresh"]?.jsonPrimitive?.contentOrNull,
                o["accessExpiresAt"]?.jsonPrimitive?.longOrNull,
                o["refreshExpiresAt"]?.jsonPrimitive?.longOrNull,
            )
        }
    }
}

/** One reply from the access_token endpoint. GitHub answers HTTP 200 even for errors, with an `error` field. */
sealed interface TokenReply {
    data class Granted(val tokens: TokenSet) : TokenReply
    data class Error(val code: String, val description: String? = null, val interval: Int? = null) : TokenReply
    data class Unexpected(val httpCode: Int) : TokenReply
}

object DeviceFlowParse {
    private fun obj(body: String): JsonObject? = runCatching { flowJson.parseToJsonElement(body).jsonObject }.getOrNull()
    private fun JsonObject.str(k: String) = get(k)?.jsonPrimitive?.contentOrNull
    private fun JsonObject.lng(k: String) = get(k)?.jsonPrimitive?.longOrNull

    fun deviceCode(r: HttpResp): Result<DeviceCode> {
        val o = obj(r.body) ?: return Result.failure(IllegalStateException("GitHub sent an unreadable reply (HTTP ${r.code})."))
        o.str("error")?.let { return Result.failure(IllegalStateException(deviceCodeError(it))) }
        val device = o.str("device_code"); val user = o.str("user_code")
        if (r.code != 200 || device == null || user == null) {
            return Result.failure(IllegalStateException("GitHub replied HTTP ${r.code} to the sign-in request."))
        }
        return Result.success(
            DeviceCode(
                device, user, GithubOAuthUrls.safeVerificationUri(o.str("verification_uri")),
                (o.lng("expires_in") ?: 900).toInt(), (o.lng("interval") ?: 5).toInt().coerceAtLeast(1),
            ),
        )
    }

    private fun deviceCodeError(code: String) = when (code) {
        "device_flow_disabled" -> "Device flow is off for this GitHub App. Tick \"Enable Device Flow\" in the app's settings."
        "incorrect_client_credentials", "Not Found" -> "GitHub doesn't know that Client ID. Check it was copied correctly."
        else -> "GitHub refused the sign-in request ($code)."
    }

    fun tokenReply(r: HttpResp, now: Long): TokenReply {
        val o = obj(r.body) ?: return TokenReply.Unexpected(r.code)
        o.str("error")?.let { return TokenReply.Error(it, o.str("error_description"), o.lng("interval")?.toInt()) }
        val access = o.str("access_token") ?: return TokenReply.Unexpected(r.code)
        return TokenReply.Granted(
            TokenSet(
                access,
                o.str("refresh_token"),
                o.lng("expires_in")?.let { now + it * 1000 },
                o.lng("refresh_token_expires_in")?.let { now + it * 1000 },
            ),
        )
    }
}

sealed interface PollDecision {
    /** Ask again after [intervalSec]. */
    data class Wait(val intervalSec: Int) : PollDecision
    data class Done(val tokens: TokenSet) : PollDecision
    data object Expired : PollDecision
    data object Denied : PollDecision
    data class Fail(val message: String) : PollDecision
}

/** The poll state machine: owns the current interval and the deadline. */
class DeviceFlowPoller(intervalSec: Int, private val expiresAt: Long) {
    var intervalSec: Int = intervalSec.coerceAtLeast(1)
        private set

    fun onReply(reply: TokenReply, now: Long): PollDecision {
        if (reply is TokenReply.Granted) return PollDecision.Done(reply.tokens)
        if (now >= expiresAt) return PollDecision.Expired
        return when (reply) {
            is TokenReply.Granted -> PollDecision.Done(reply.tokens)
            is TokenReply.Unexpected -> if (reply.httpCode >= 500 || reply.httpCode == 429) PollDecision.Wait(intervalSec)
            else PollDecision.Fail("GitHub replied HTTP ${reply.httpCode} while waiting for sign-in.")
            is TokenReply.Error -> when (reply.code) {
                "authorization_pending" -> PollDecision.Wait(intervalSec)
                "slow_down" -> {
                    // GitHub adds 5 seconds; honour the interval it returns when it sends one.
                    intervalSec = maxOf(reply.interval ?: 0, intervalSec + 5)
                    PollDecision.Wait(intervalSec)
                }
                "expired_token" -> PollDecision.Expired
                "access_denied" -> PollDecision.Denied
                "device_flow_disabled" -> PollDecision.Fail("Device flow is off for this GitHub App. Tick \"Enable Device Flow\" in its settings.")
                "incorrect_client_credentials" -> PollDecision.Fail("GitHub doesn't know that Client ID.")
                else -> PollDecision.Fail("GitHub refused the sign-in (${reply.code}).")
            }
        }
    }
}

sealed interface RefreshResult {
    /** [tokens] are current. [changed] is true when they were just refreshed and must be saved. */
    data class Fresh(val tokens: TokenSet, val changed: Boolean) : RefreshResult
    /** No way to get a working token without the user. */
    data object NeedsSignIn : RefreshResult
    /** Offline or GitHub trouble: keep what we have and try again later. */
    data class Retry(val message: String) : RefreshResult
}

class DeviceFlowClient(private val http: FormHttp, private val clock: () -> Long = System::currentTimeMillis) {
    fun requestCode(clientId: String): Result<DeviceCode> = try {
        DeviceFlowParse.deviceCode(http.post(GithubOAuthUrls.DEVICE_CODE, mapOf("client_id" to clientId)))
    } catch (_: IOException) {
        Result.failure(IllegalStateException("Couldn't reach GitHub (offline?)."))
    }

    fun pollOnce(clientId: String, deviceCode: String): TokenReply = try {
        val r = http.post(
            GithubOAuthUrls.ACCESS_TOKEN,
            mapOf("client_id" to clientId, "device_code" to deviceCode, "grant_type" to GithubOAuthUrls.DEVICE_GRANT),
        )
        DeviceFlowParse.tokenReply(r, clock())
    } catch (_: IOException) {
        TokenReply.Unexpected(503) // transient: poller keeps waiting
    }

    /**
     * Runs the whole wait. [sleep] and [cancelled] are injected so tests need no real time.
     * Returns the tokens, or the reason it stopped.
     */
    fun awaitTokens(
        clientId: String,
        code: DeviceCode,
        sleep: (Long) -> Unit,
        cancelled: () -> Boolean = { false },
        onSlowDown: (Int) -> Unit = {},
    ): PollDecision {
        val poller = DeviceFlowPoller(code.intervalSec, clock() + code.expiresInSec * 1000L)
        var wait = poller.intervalSec
        while (true) {
            sleep(wait * 1000L)
            if (cancelled()) return PollDecision.Fail("Sign-in cancelled.")
            val decision = poller.onReply(pollOnce(clientId, code.deviceCode), clock())
            if (decision is PollDecision.Wait) {
                if (decision.intervalSec != wait) onSlowDown(decision.intervalSec)
                wait = decision.intervalSec
                continue
            }
            return decision
        }
    }

    /** Refreshes when [force]d or when the access token is (nearly) expired. A pasted token is returned unchanged. */
    fun ensureFresh(clientId: String?, tokens: TokenSet, force: Boolean = false): RefreshResult {
        val now = clock()
        if (!force && !tokens.shouldRefresh(now)) return RefreshResult.Fresh(tokens, false)
        val refresh = tokens.refresh
        if (refresh == null || clientId.isNullOrBlank() || !tokens.refreshUsable(now)) return RefreshResult.NeedsSignIn
        val reply = try {
            DeviceFlowParse.tokenReply(
                http.post(
                    GithubOAuthUrls.ACCESS_TOKEN,
                    mapOf("client_id" to clientId, "grant_type" to "refresh_token", "refresh_token" to refresh),
                ),
                now,
            )
        } catch (_: IOException) {
            return RefreshResult.Retry("Couldn't reach GitHub to renew the sign-in (offline?).")
        }
        return when (reply) {
            // The refresh token rotates: the reply carries the new one, which must replace the old.
            is TokenReply.Granted -> RefreshResult.Fresh(reply.tokens.copy(refresh = reply.tokens.refresh ?: refresh), true)
            is TokenReply.Error -> RefreshResult.NeedsSignIn
            is TokenReply.Unexpected ->
                if (reply.httpCode >= 500 || reply.httpCode == 429) RefreshResult.Retry("GitHub had a problem renewing the sign-in.")
                else RefreshResult.NeedsSignIn
        }
    }
}
