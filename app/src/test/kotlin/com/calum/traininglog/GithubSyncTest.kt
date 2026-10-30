package com.calum.traininglog

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class MemHashes : HashStore {
    val m = mutableMapOf<String, String>()
    override fun get(path: String) = m[path]
    override fun put(path: String, sha: String) { m[path] = sha }
}

/** Fake GitHub: files by path -> content; records every request. */
private class FakeGithub(
    val files: MutableMap<String, String> = mutableMapOf(),
    var repoExists: Boolean = true,
    var branchExists: Boolean = true,
    var push: Boolean = true,
    var failAll: Int? = null,
    var offline: Boolean = false,
    var conflictsOnPut: Int = 0,
) : GithubHttp {
    val log = mutableListOf<String>()
    val puts = mutableListOf<String>()
    val bodies = mutableListOf<String>()
    private val prefix = "https://api.github.com/repos/me/data"

    override fun execute(req: HttpReq): HttpResp {
        log += "${req.method} ${req.url}"
        if (offline) throw IOException("offline")
        failAll?.let { return HttpResp(it, "{}") }
        val url = req.url
        return when {
            url == prefix -> if (repoExists) HttpResp(200, """{"permissions":{"push":$push}}""") else HttpResp(404, "{}")
            url.startsWith("$prefix/branches/") -> if (repoExists && branchExists) HttpResp(200, "{}") else HttpResp(404, "{}")
            url.startsWith("$prefix/contents/") && req.method == "GET" -> {
                if (!repoExists || !branchExists) return HttpResp(404, "{}")
                val path = url.removePrefix("$prefix/contents/").substringBefore("?")
                files[path]?.let { HttpResp(200, """{"sha":"${GithubPaths.blobSha(it)}"}""") } ?: HttpResp(404, "{}")
            }
            url.startsWith("$prefix/contents/") && req.method == "PUT" -> {
                if (!repoExists || !branchExists) return HttpResp(404, "{}")
                if (conflictsOnPut > 0) { conflictsOnPut--; return HttpResp(409, "{}") }
                val path = url.removePrefix("$prefix/contents/")
                val content = String(java.util.Base64.getDecoder().decode(Regex("\"content\":\"([^\"]+)\"").find(req.body!!)!!.groupValues[1]))
                files[path] = content
                puts += path
                bodies += req.body!!
                HttpResp(200, "{}")
            }
            else -> HttpResp(500, "{}")
        }
    }
}

class GithubSyncTest {
    private val cfg = GithubConfig("me", "data", "main", "ghp_SECRETSECRETSECRETSECRET1234")

    @Test fun pathsAreEncodedAndBase64Works() {
        assertEquals("data/gym/2026-10.csv", GithubPaths.encodePath("data/gym/2026-10.csv"))
        assertEquals("a%20b/c%23d.csv", GithubPaths.encodePath("a b/c#d.csv"))
        assertEquals(
            "https://api.github.com/repos/me/data/contents/data/x.csv?ref=main",
            GithubPaths.contents("me", "data", "data/x.csv", "main"),
        )
        assertEquals("aGk=", GithubPaths.base64("hi"))
        assertEquals("Update training data (1 session)", GithubPaths.commitMessage(1))
        assertEquals("Update training data (12 sessions)", GithubPaths.commitMessage(12))
    }

    @Test fun blobShaMatchesGit() {
        // `printf 'hello\n' | git hash-object --stdin`
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", GithubPaths.blobSha("hello\n"))
    }

    @Test fun validation() {
        assertTrue(GithubPaths.validName("calum-4ster.x_y"))
        assertFalse(GithubPaths.validName("a/b"))
        assertFalse(GithubPaths.validName(" "))
        assertFalse(GithubPaths.validBranch("a b"))
    }

    @Test fun pushesNewFilesThenSkipsUnchangedOnes() {
        val gh = FakeGithub()
        val hashes = MemHashes()
        val files = mapOf("data/gym/2026-09.csv" to "a,b\n1,2\n", "data/gym/2026-10.csv" to "a,b\n3,4\n")
        val first = SyncEngine(gh, cfg, hashes).sync(files, 2)
        assertEquals(SyncOutcome.Success(2, 0), first)
        assertEquals(2, gh.puts.size)
        assertTrue(gh.bodies.all { it.contains("Update training data (2 sessions)") })

        // Same data again: hash store short-circuits, no network at all.
        gh.log.clear()
        assertEquals(SyncOutcome.Success(0, 2), SyncEngine(gh, cfg, hashes).sync(files, 2))
        assertTrue(gh.log.isEmpty())

        // Fresh install (no local hashes): remote already equal, so GET only, no commits.
        gh.puts.clear(); gh.log.clear()
        assertEquals(SyncOutcome.Success(0, 2), SyncEngine(gh, cfg, MemHashes()).sync(files, 2))
        assertTrue(gh.puts.isEmpty())
        assertTrue(gh.log.none { it.startsWith("PUT") })
    }

    @Test fun onlyChangedMonthIsPushed() {
        val gh = FakeGithub()
        val hashes = MemHashes()
        SyncEngine(gh, cfg, hashes).sync(mapOf("m1.csv" to "1", "m2.csv" to "2"), 2)
        gh.puts.clear()
        val out = SyncEngine(gh, cfg, hashes).sync(mapOf("m1.csv" to "1", "m2.csv" to "22"), 3)
        assertEquals(SyncOutcome.Success(1, 1), out)
        assertEquals(listOf("m2.csv"), gh.puts)
    }

    @Test fun staleShaIsRetriedOnce() {
        val gh = FakeGithub(files = mutableMapOf("m.csv" to "old"), conflictsOnPut = 1)
        assertEquals(SyncOutcome.Success(1, 0), SyncEngine(gh, cfg, MemHashes()).sync(mapOf("m.csv" to "new"), 1))
        assertEquals("new", gh.files["m.csv"])
        gh.conflictsOnPut = 5
        assertIs<SyncOutcome.Retry>(SyncEngine(gh, cfg, MemHashes()).sync(mapOf("m.csv" to "newer"), 1))
    }

    @Test fun failuresMapToOutcomes() {
        val f = mapOf("m.csv" to "x")
        assertEquals(SyncOutcome.NeedsSignIn, SyncEngine(FakeGithub(failAll = 401), cfg, MemHashes()).sync(f, 1))
        assertEquals(SyncOutcome.NeedsSignIn, SyncEngine(FakeGithub(failAll = 403), cfg, MemHashes()).sync(f, 1))
        assertIs<SyncOutcome.Retry>(SyncEngine(FakeGithub(failAll = 429), cfg, MemHashes()).sync(f, 1))
        assertIs<SyncOutcome.Retry>(SyncEngine(FakeGithub(failAll = 503), cfg, MemHashes()).sync(f, 1))
        assertIs<SyncOutcome.Retry>(SyncEngine(FakeGithub(offline = true), cfg, MemHashes()).sync(f, 1))
        val noRepo = SyncEngine(FakeGithub(repoExists = false), cfg, MemHashes()).sync(f, 1)
        assertTrue((noRepo as SyncOutcome.NotFound).message.contains("me/data"))
        val noBranch = SyncEngine(FakeGithub(branchExists = false), cfg, MemHashes()).sync(f, 1)
        assertTrue((noBranch as SyncOutcome.NotFound).message.contains("main"))
    }

    @Test fun rateLimited403IsRetryNotSignIn() {
        val http = GithubHttp { HttpResp(403, "{}", mapOf("X-RateLimit-Remaining" to "0")) }
        assertIs<SyncOutcome.Retry>(SyncEngine(http, cfg, MemHashes()).sync(mapOf("m.csv" to "x"), 1))
    }

    @Test fun failedFileIsNotMarkedAsSynced() {
        val hashes = MemHashes()
        SyncEngine(FakeGithub(failAll = 401), cfg, hashes).sync(mapOf("m.csv" to "x"), 1)
        assertTrue(hashes.m.isEmpty())
    }

    @Test fun connectionTest() {
        assertTrue(SyncEngine(FakeGithub(), cfg, MemHashes()).testConnection().ok)
        val ro = SyncEngine(FakeGithub(push = false), cfg, MemHashes()).testConnection()
        assertFalse(ro.ok); assertTrue(ro.message.contains("push"))
        assertTrue(SyncEngine(FakeGithub(failAll = 401), cfg, MemHashes()).testConnection().needsSignIn)
        assertFalse(SyncEngine(FakeGithub(repoExists = false), cfg, MemHashes()).testConnection().ok)
        assertFalse(SyncEngine(FakeGithub(branchExists = false), cfg, MemHashes()).testConnection().ok)
    }

    @Test fun stateTransitions() {
        val s: SyncState = SyncState.Idle(null)
        assertEquals(SyncState.Idle(99), s.after(SyncOutcome.Success(1, 0), 99))
        assertEquals(SyncState.NeedsSignIn, s.after(SyncOutcome.NeedsSignIn, 1))
        assertIs<SyncState.Failed>(s.after(SyncOutcome.Retry("offline"), 1))
        assertTrue((s.after(SyncOutcome.Retry("offline"), 1) as SyncState.Failed).message.contains("retry"))
        assertEquals(SyncState.Failed("gone"), s.after(SyncOutcome.NotFound("gone"), 1))
        assertEquals("Not set up yet.", syncStatusText(SyncState.NotConfigured) { "" })
        assertTrue(syncStatusText(SyncState.Idle(5)) { "T$it" }.contains("T5"))
    }

    @Test fun tokensAreRedacted() {
        val t = "ghp_SECRETSECRETSECRETSECRET1234"
        assertFalse(Redact.scrub("failed with $t here", t).contains("SECRET"))
        assertFalse(Redact.scrub("Authorization: Bearer abc.def", null).contains("abc.def"))
        assertFalse(Redact.scrub("x github_pat_11ABC_def123 y").contains("11ABC"))
        assertFalse(cfg.toString().contains("SECRET"))
        assertFalse(HttpReq("GET", "u", t).toString().contains("SECRET"))
    }
}
