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
    override fun paths(): Set<String> = m.keys.toSet()
    override fun remove(path: String) { m.remove(path) }
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
    val deletes = mutableListOf<String>()
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
                val dir = files.keys.filter { it.startsWith("$path/") }
                if (files[path] == null && dir.isNotEmpty()) {
                    val items = dir.sorted().joinToString(",") { """{"type":"file","path":"$it","name":"${it.substringAfterLast('/')}"}""" }
                    return HttpResp(200, "[$items]")
                }
                files[path]?.let {
                    HttpResp(200, """{"sha":"${GithubPaths.blobSha(it)}","content":"${java.util.Base64.getEncoder().encodeToString(it.toByteArray())}"}""")
                } ?: HttpResp(404, "{}")
            }
            url.startsWith("$prefix/contents/") && req.method == "DELETE" -> {
                val path = url.removePrefix("$prefix/contents/")
                val sha = Regex("\"sha\":\"([^\"]+)\"").find(req.body!!)?.groupValues?.get(1)
                val have = files[path]
                if (have == null) return HttpResp(404, "{}")
                if (sha != GithubPaths.blobSha(have)) return HttpResp(409, "{}")
                files.remove(path)
                deletes += path
                HttpResp(200, "{}")
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

    @Test fun deletedMonthIsRemovedFromGithubButOnlyCsvInTheThreeFolders() {
        val gh = FakeGithub(files = mutableMapOf("README.md" to "keep", "data/summary.json" to "{}", "data/other.txt" to "x"))
        val hashes = MemHashes()
        val files = mapOf(
            "data/gym/2026-09.csv" to "a\n1\n", "data/gym/2026-10.csv" to "a\n2\n",
            "data/treadmill/2026-10.csv" to "t\n", "data/other/2026-10.csv" to "o\n", "data/summary.json" to "{}",
        )
        SyncEngine(gh, cfg, hashes).sync(files, 3)
        // Something non-csv and something outside the folders also look "known" but must never be deleted.
        hashes.put("data/summary.json.old", "x"); gh.files["data/summary.json.old"] = "x"
        hashes.put("README.md", "x"); hashes.put("data/gym/nested/x.csv", "x"); gh.files["data/gym/nested/x.csv"] = "x"

        val out = SyncEngine(gh, cfg, hashes).sync(files - "data/gym/2026-09.csv" - "data/treadmill/2026-10.csv", 1)
        assertEquals(SyncOutcome.Success(0, 3, 2), out)
        assertEquals(listOf("data/gym/2026-09.csv", "data/treadmill/2026-10.csv"), gh.deletes)
        assertTrue("README.md" in gh.files && "data/summary.json" in gh.files && "data/other.txt" in gh.files)
        assertTrue("data/summary.json.old" in gh.files && "data/gym/nested/x.csv" in gh.files)
        assertFalse("data/gym/2026-09.csv" in hashes.m)

        // Next sync: nothing left to delete, no DELETE requests.
        gh.log.clear()
        SyncEngine(gh, cfg, hashes).sync(files - "data/gym/2026-09.csv" - "data/treadmill/2026-10.csv", 1)
        assertTrue(gh.log.none { it.startsWith("DELETE") })
    }

    @Test fun pruneNeverTouchesFilesThisInstallDidNotPush() {
        // Fresh install with only new data must not wipe history that lives on GitHub.
        val gh = FakeGithub(files = mutableMapOf("data/gym/2020-01.csv" to "old"))
        SyncEngine(gh, cfg, MemHashes()).sync(mapOf("data/gym/2026-10.csv" to "new"), 1)
        assertTrue(gh.deletes.isEmpty())
        assertTrue("data/gym/2020-01.csv" in gh.files)
    }

    @Test fun alreadyGoneRemoteFileJustForgetsTheHash() {
        val gh = FakeGithub()
        val hashes = MemHashes().also { it.put("data/gym/2026-01.csv", "x") }
        assertEquals(SyncOutcome.Success(0, 0, 0), SyncEngine(gh, cfg, hashes).sync(emptyMap(), 0))
        assertTrue(hashes.m.isEmpty())
    }

    @Test fun prunablePathRules() {
        assertTrue(GithubPaths.isPrunable("data/gym/2026-10.csv"))
        assertTrue(GithubPaths.isPrunable("data/treadmill/2026-10.csv"))
        assertTrue(GithubPaths.isPrunable("data/other/2026-10.csv"))
        assertFalse(GithubPaths.isPrunable("data/gym/2026-10.txt"))
        assertFalse(GithubPaths.isPrunable("data/summary.json"))
        assertFalse(GithubPaths.isPrunable("data/gym/../x.csv"))
        assertFalse(GithubPaths.isPrunable("src/data/gym/x.csv"))
        assertFalse(GithubPaths.isPrunable("data/gym/a/b.csv"))
        assertFalse(GithubPaths.isPrunable(".github/workflows/x.csv"))
    }

    @Test fun fetchReadsTheThreeFoldersAndRebuildsRecords() {
        val records = listOf(
            com.calum.traininglog.core.SessionRecord(
                "a", java.time.LocalDate.parse("2026-10-05"), com.calum.traininglog.core.SessionType.GYM, 60, 50,
                com.calum.traininglog.core.Feeling.OKAY,
                exercises = listOf(com.calum.traininglog.core.ExerciseLog("squat", listOf(com.calum.traininglog.core.LoggedSet(80.0, 5, 5, 2)))),
            ),
        )
        val pub = com.calum.traininglog.core.PublicExport.files(records)
        val gh = FakeGithub(files = (pub + ("README.md" to "x")).toMutableMap())
        val res = SyncEngine(gh, cfg, MemHashes()).fetchPublicFiles()
        val got = (res as FetchResult.Files).files
        assertEquals(pub.filterKeys { it.endsWith(".csv") }, got)
        val back = com.calum.traininglog.core.PublicImport.records(got)
        assertEquals(1, back.size)
        assertEquals(80.0, back[0].exercises[0].sets[0].weightKg)
        // Empty repo: no folders is fine.
        assertEquals(FetchResult.Files(emptyMap()), SyncEngine(FakeGithub(), cfg, MemHashes()).fetchPublicFiles())
        assertEquals(FetchResult.Problem(SyncOutcome.NeedsSignIn), SyncEngine(FakeGithub(failAll = 401), cfg, MemHashes()).fetchPublicFiles())
        assertIs<FetchResult.Problem>(SyncEngine(FakeGithub(branchExists = false), cfg, MemHashes()).fetchPublicFiles())
    }
}
