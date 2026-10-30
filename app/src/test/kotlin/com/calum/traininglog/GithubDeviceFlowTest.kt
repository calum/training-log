package com.calum.traininglog

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Scripted fake of github.com/login: replies are consumed in order; every request is recorded. */
private class FakeOAuth(vararg replies: Any) : FormHttp {
    private val queue = ArrayDeque(replies.toList())
    val requests = mutableListOf<Pair<String, Map<String, String>>>()
    override fun post(url: String, form: Map<String, String>): HttpResp {
        requests += url to form
        val next = queue.removeFirst()
        if (next is IOException) throw next
        return next as HttpResp
    }
}

private fun ok(json: String) = HttpResp(200, json)

class GithubDeviceFlowTest {
    private val now = 1_000_000L
    private val codeJson = """{"device_code":"DEV123","user_code":"ABCD-1234","verification_uri":"https://github.com/login/device","expires_in":900,"interval":5}"""
    private fun tokenJson(access: String = "ghu_acc", refresh: String = "ghr_ref") =
        """{"access_token":"$access","expires_in":28800,"refresh_token":"$refresh","refresh_token_expires_in":15897600,"token_type":"bearer"}"""
    private fun err(code: String, extra: String = "") = ok("""{"error":"$code"$extra}""")

    @Test fun parsesDeviceCodeAndRejectsNonGithubLinks() {
        val c = DeviceFlowParse.deviceCode(ok(codeJson)).getOrThrow()
        assertEquals("DEV123", c.deviceCode); assertEquals("ABCD-1234", c.userCode)
        assertEquals(5, c.intervalSec); assertEquals(900, c.expiresInSec)
        assertFalse(c.toString().contains("DEV123"))
        val evil = DeviceFlowParse.deviceCode(ok(codeJson.replace("https://github.com/login/device", "https://evil.example/x"))).getOrThrow()
        assertEquals(GithubOAuthUrls.VERIFY_FALLBACK, evil.verificationUri)
        assertTrue(DeviceFlowParse.deviceCode(err("device_flow_disabled")).exceptionOrNull()!!.message!!.contains("Enable Device Flow"))
        assertTrue(DeviceFlowParse.deviceCode(HttpResp(404, "not json")).isFailure)
    }

    @Test fun parsesTokenReplies() {
        val g = DeviceFlowParse.tokenReply(ok(tokenJson()), now) as TokenReply.Granted
        assertEquals("ghu_acc", g.tokens.access); assertEquals("ghr_ref", g.tokens.refresh)
        assertEquals(now + 28_800_000L, g.tokens.accessExpiresAt)
        assertEquals(now + 15_897_600_000L, g.tokens.refreshExpiresAt)
        assertEquals("authorization_pending", (DeviceFlowParse.tokenReply(err("authorization_pending"), now) as TokenReply.Error).code)
        assertEquals(10, (DeviceFlowParse.tokenReply(err("slow_down", ""","interval":10"""), now) as TokenReply.Error).interval)
        assertIs<TokenReply.Unexpected>(DeviceFlowParse.tokenReply(HttpResp(502, "<html>"), now))
    }

    @Test fun pollerHandlesEveryState() {
        val p = DeviceFlowPoller(5, now + 900_000)
        assertEquals(PollDecision.Wait(5), p.onReply(TokenReply.Error("authorization_pending"), now))
        assertEquals(PollDecision.Wait(10), p.onReply(TokenReply.Error("slow_down"), now))
        assertEquals(PollDecision.Wait(15), p.onReply(TokenReply.Error("slow_down"), now))
        assertEquals(PollDecision.Wait(20), p.onReply(TokenReply.Error("slow_down", interval = 20), now))
        assertEquals(PollDecision.Expired, p.onReply(TokenReply.Error("expired_token"), now))
        assertEquals(PollDecision.Denied, p.onReply(TokenReply.Error("access_denied"), now))
        assertIs<PollDecision.Fail>(p.onReply(TokenReply.Error("incorrect_client_credentials"), now))
        assertIs<PollDecision.Wait>(p.onReply(TokenReply.Unexpected(503), now))
        assertIs<PollDecision.Fail>(p.onReply(TokenReply.Unexpected(404), now))
        assertEquals(PollDecision.Expired, p.onReply(TokenReply.Error("authorization_pending"), now + 900_001))
        assertIs<PollDecision.Done>(p.onReply(TokenReply.Granted(TokenSet("a")), now + 999_999))
    }

    @Test fun awaitLoopWaitsThenSucceedsAndSlowsDown() {
        val oauth = FakeOAuth(err("authorization_pending"), err("slow_down"), err("authorization_pending"), ok(tokenJson()))
        val sleeps = mutableListOf<Long>()
        val c = DeviceFlowClient(oauth) { now }
        val code = DeviceFlowParse.deviceCode(ok(codeJson)).getOrThrow()
        val res = c.awaitTokens("Iv1.abc", code, sleep = { sleeps += it })
        assertIs<PollDecision.Done>(res)
        assertEquals(listOf(5000L, 5000L, 10_000L, 10_000L), sleeps)
        val poll = oauth.requests.first()
        assertEquals(GithubOAuthUrls.ACCESS_TOKEN, poll.first)
        assertEquals(GithubOAuthUrls.DEVICE_GRANT, poll.second["grant_type"])
        assertEquals("DEV123", poll.second["device_code"])
        assertEquals("Iv1.abc", poll.second["client_id"])
        assertFalse(oauth.requests.any { it.second.containsKey("client_secret") })
    }

    @Test fun awaitLoopStopsOnDeniedExpiredCancelAndSurvivesOffline() {
        val code = DeviceFlowParse.deviceCode(ok(codeJson)).getOrThrow()
        assertEquals(PollDecision.Denied, DeviceFlowClient(FakeOAuth(err("access_denied"))) { now }.awaitTokens("c", code, {}))
        assertEquals(PollDecision.Expired, DeviceFlowClient(FakeOAuth(err("expired_token"))) { now }.awaitTokens("c", code, {}))
        val flaky = FakeOAuth(IOException("offline"), ok(tokenJson()))
        assertIs<PollDecision.Done>(DeviceFlowClient(flaky) { now }.awaitTokens("c", code, {}))
        var calls = 0
        val cancelled = DeviceFlowClient(FakeOAuth()) { now }.awaitTokens("c", code, {}, cancelled = { calls++ >= 0 })
        assertIs<PollDecision.Fail>(cancelled)
    }

    @Test fun requestCodeSendsOnlyClientId() {
        val oauth = FakeOAuth(ok(codeJson))
        DeviceFlowClient(oauth) { now }.requestCode("Iv1.abc").getOrThrow()
        assertEquals(GithubOAuthUrls.DEVICE_CODE to mapOf("client_id" to "Iv1.abc"), oauth.requests.single())
        assertTrue(DeviceFlowClient(FakeOAuth(IOException("x"))) { now }.requestCode("c").isFailure)
    }

    @Test fun refreshDecision() {
        val t = TokenSet("a", "r", accessExpiresAt = now + 8 * 3_600_000L, refreshExpiresAt = now + 100 * 86_400_000L)
        assertFalse(t.shouldRefresh(now))
        assertFalse(t.shouldRefresh(now + 8 * 3_600_000L - 6 * 60_000L))
        assertTrue(t.shouldRefresh(now + 8 * 3_600_000L - 4 * 60_000L))
        assertTrue(t.shouldRefresh(now + 9 * 3_600_000L))
        assertFalse(TokenSet("pat").shouldRefresh(now + 999_999_999L)) // pasted token: never refreshed
        assertFalse(TokenSet("a", null, now - 1).shouldRefresh(now)) // nothing to refresh with
        assertFalse(t.refreshUsable(now + 101 * 86_400_000L))
    }

    @Test fun freshTokenIsLeftAloneAndPastedTokenNeverTouchesNetwork() {
        val oauth = FakeOAuth()
        val c = DeviceFlowClient(oauth) { now }
        val fresh = TokenSet("a", "r", now + 3_600_000L, now + 86_400_000L)
        assertEquals(RefreshResult.Fresh(fresh, false), c.ensureFresh("cid", fresh))
        assertEquals(RefreshResult.Fresh(TokenSet("pat"), false), c.ensureFresh(null, TokenSet("pat")))
        assertTrue(oauth.requests.isEmpty())
    }

    @Test fun expiredTokenIsRefreshedAndRefreshTokenRotates() {
        val oauth = FakeOAuth(ok(tokenJson("ghu_new", "ghr_new")))
        val old = TokenSet("ghu_old", "ghr_old", now - 1, now + 86_400_000L)
        val r = DeviceFlowClient(oauth) { now }.ensureFresh("cid", old) as RefreshResult.Fresh
        assertTrue(r.changed)
        assertEquals("ghu_new", r.tokens.access); assertEquals("ghr_new", r.tokens.refresh)
        assertEquals(now + 28_800_000L, r.tokens.accessExpiresAt)
        val form = oauth.requests.single().second
        assertEquals("refresh_token", form["grant_type"]); assertEquals("ghr_old", form["refresh_token"]); assertEquals("cid", form["client_id"])
        assertFalse(form.containsKey("client_secret"))
    }

    @Test fun forcedRefreshWorksBeforeExpiry() {
        val oauth = FakeOAuth(ok(tokenJson("n", "m")))
        val t = TokenSet("a", "r", now + 3_600_000L, now + 86_400_000L)
        val r = DeviceFlowClient(oauth) { now }.ensureFresh("cid", t, force = true) as RefreshResult.Fresh
        assertEquals("n", r.tokens.access)
    }

    @Test fun failedRefreshMeansNeedsSignInAndOfflineMeansRetry() {
        val old = TokenSet("a", "r", now - 1, now + 86_400_000L)
        assertEquals(RefreshResult.NeedsSignIn, DeviceFlowClient(FakeOAuth(err("bad_refresh_token"))) { now }.ensureFresh("cid", old))
        assertEquals(RefreshResult.NeedsSignIn, DeviceFlowClient(FakeOAuth()) { now }.ensureFresh(null, old)) // no client id saved
        assertEquals(RefreshResult.NeedsSignIn, DeviceFlowClient(FakeOAuth()) { now }.ensureFresh("cid", old.copy(refreshExpiresAt = now - 1)))
        assertIs<RefreshResult.Retry>(DeviceFlowClient(FakeOAuth(IOException("offline"))) { now }.ensureFresh("cid", old))
        assertIs<RefreshResult.Retry>(DeviceFlowClient(FakeOAuth(HttpResp(503, "x"))) { now }.ensureFresh("cid", old))
    }

    @Test fun tokenSetEncodingRoundTripsAndIsBackwardCompatible() {
        val t = TokenSet("ghu_a", "ghr_b", 123L, 456L)
        assertEquals(t, TokenSet.decode(t.encode()))
        assertEquals("github_pat_xyz", TokenSet("github_pat_xyz").encode()) // pasted tokens stay as they were
        assertEquals(TokenSet("github_pat_xyz"), TokenSet.decode("github_pat_xyz"))
        assertNull(TokenSet.decode("ghp_x").refresh)
        assertFalse(t.toString().contains("ghu_a"))
    }
}
