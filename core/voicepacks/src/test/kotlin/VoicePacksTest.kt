import com.mundymorning.touchreader.core.voicepacks.*
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class VoicePacksTest {
    private val tmp = createTempDirectory("vp").toFile()
    private val requests = CopyOnWriteArrayList<String?>() // Range headers seen
    private var server: HttpServer? = null

    @AfterTest fun tearDown() { server?.stop(0); tmp.deleteRecursively() }

    private val rnd = java.util.Random(42)
    /** Incompressible-ish text so archive size is close to content size. */
    private fun noise(n: Int) = String(CharArray(n) { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"[rnd.nextInt(62)] })

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val b = ByteArrayOutputStream()
        ZipOutputStream(b).use { z -> entries.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
        return b.toByteArray()
    }

    private fun sha(bytes: ByteArray) =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Serves [body] at /pack.zip, honouring Range requests. */
    private fun serve(body: ByteArray, honourRange: Boolean = true): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/pack.zip") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            requests += range
            if (honourRange && range != null) {
                val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                val part = body.copyOfRange(from, body.size)
                ex.sendResponseHeaders(206, part.size.toLong()); ex.responseBody.use { it.write(part) }
            } else {
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
        }
        s.start(); server = s
        return "http://127.0.0.1:${s.address.port}/pack.zip"
    }

    private fun pack(url: String, body: ByteArray, size: Long = body.size.toLong(), sha: String = sha(body)) = VoicePack(
        "test-voice", "Test Voice", "", "kokoro", "en", 1, url, size, 1_000_000_000L, sha, "Apache-2.0", null, null,
    )

    private val http = PackDownloader(allowInsecureHttp = true)

    /** Serves several files under /f/<name> with Range support. */
    private fun serveFiles(files: Map<String, ByteArray>): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/f/") { ex ->
            val body = files[ex.requestURI.path.removePrefix("/f/")]
            val range = ex.requestHeaders.getFirst("Range")
            requests += ex.requestURI.path + (range?.let { " $it" } ?: "")
            if (body == null) { ex.sendResponseHeaders(404, -1); ex.close(); return@createContext }
            if (range != null) {
                val part = body.copyOfRange(range.removePrefix("bytes=").removeSuffix("-").toInt(), body.size)
                ex.sendResponseHeaders(206, part.size.toLong()); ex.responseBody.use { it.write(part) }
            } else { ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) } }
        }
        s.start(); server = s
        return "http://127.0.0.1:${s.address.port}/f/"
    }

    private fun filePack(base: String, files: Map<String, ByteArray>) = VoicePack(
        "multi", "Multi", "", "supertonic", "en", 1, "", files.values.sumOf { it.size.toLong() }, files.values.sumOf { it.size.toLong() }, "",
        "OpenRAIL-M", null, null,
        files = files.map { (n, b) -> PackFile("sub/$n", base + n, b.size.toLong(), sha(b)) },
        notice = "Use restrictions apply", requiresAcceptance = true,
    )

    @Test fun fileListPackInstallsAllFilesAtomically() {
        val files = mapOf("a.bin" to noise(3000).toByteArray(), "b.json" to noise(500).toByteArray(), "LICENSE" to noise(200).toByteArray())
        val pack = filePack(serveFiles(files), files)
        val store = PackStore(tmp)
        val progress = mutableListOf<Long>()
        store.installFiles(pack, http) { d, t -> progress += d; assertEquals(pack.sizeBytes, t) }
        assertEquals(pack.sizeBytes, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> b >= a }, "progress went backwards")
        val dir = store.get("multi")!!.dir
        files.forEach { (n, b) -> assertContentEquals(b, File(dir, "sub/$n").readBytes()) }
        assertFalse(File(tmp, "downloads/multi-1.files").exists())
    }

    @Test fun fileListPackResumesAndSkipsFinishedFiles() {
        val files = mapOf("a.bin" to noise(3000).toByteArray(), "b.bin" to noise(4000).toByteArray())
        val pack = filePack(serveFiles(files), files)
        val store = PackStore(tmp)
        val staging = File(tmp, "downloads/multi-1.files/sub").also { it.mkdirs() }
        File(staging, "a.bin").writeBytes(files["a.bin"]!!)                       // already complete
        File(staging, "b.bin.part").writeBytes(files["b.bin"]!!.copyOfRange(0, 1500)) // half done
        store.installFiles(pack, http)
        assertEquals(1, requests.size, "only the unfinished file should be requested: $requests")
        assertTrue(requests.single()!!.endsWith("b.bin bytes=1500-"))
        assertEquals(1, store.installed().size)
    }

    @Test fun oneCorruptFileAbortsWithoutInstalling() {
        val files = mapOf("a.bin" to noise(1000).toByteArray(), "b.bin" to noise(1000).toByteArray())
        val good = filePack(serveFiles(files), files)
        val bad = good.copy(files = good.files.map { if (it.path.endsWith("b.bin")) it.copy(sha256 = "0".repeat(64)) else it })
        val store = PackStore(tmp)
        assertFailsWith<ChecksumMismatchException> { store.installFiles(bad, http) }
        assertTrue(store.installed().isEmpty())
    }

    @Test fun catalogParsesFileListAndSumsSizes() {
        val json = """{"packs":[{"id":"m","name":"M","engine":"supertonic","license":"OpenRAIL-M","requiresAcceptance":true,"notice":"n",
          "files":[{"path":"onnx/a.onnx","url":"https://h.test/a","sizeBytes":100,"sha256":"${"a".repeat(64)}"},
                   {"path":"LICENSE","url":"https://h.test/l","sizeBytes":20,"sha256":"${"b".repeat(64)}"}]}]}"""
        val r = VoicePackCatalog.parse(json)
        assertTrue(r.rejected.isEmpty(), r.rejected.toString())
        assertEquals(120, r.packs.single().sizeBytes)
        assertTrue(r.packs.single().requiresAcceptance)
    }

    @Test fun catalogRejectsUnsafeOrIncompleteFileLists() {
        fun pack(file: String, extra: String = "") = """{"id":"x","name":"X","engine":"e","license":"L"$extra,"files":[$file]}"""
        val ok = """{"path":"a","url":"https://h/a","sizeBytes":1,"sha256":"${"a".repeat(64)}"}"""
        val traversal = ok.replace("\"a\",", "\"../a\",")
        val http = ok.replace("https://", "http://")
        val nohash = """{"path":"a","url":"https://h/a","sizeBytes":1}"""
        val r = VoicePackCatalog.parse("""{"packs":[${pack(ok)},${pack(traversal)},${pack(http)},${pack(nohash)},${pack(ok, ",\"requiresAcceptance\":true")}]}""")
        assertEquals(1, r.packs.size)
        assertEquals(4, r.rejected.size)
    }

    @Test fun catalogAcceptsValidAndRejectsIncomplete() {
        val json = """{"packs":[
          {"id":"good","name":"Good","engine":"kokoro","url":"https://x.test/a.zip","sizeBytes":10,"unpackedBytes":40,"sha256":"${"a".repeat(64)}","license":"Apache-2.0"},
          {"id":"nohash","name":"X","engine":"kokoro","url":"https://x.test/a.zip","sizeBytes":10,"unpackedBytes":40,"license":"MIT"},
          {"id":"insecure","name":"X","engine":"k","url":"http://x.test/a.zip","sizeBytes":10,"unpackedBytes":40,"sha256":"${"b".repeat(64)}","license":"MIT"},
          {"id":"nolicense","name":"X","engine":"k","url":"https://x.test/a.zip","sizeBytes":10,"unpackedBytes":40,"sha256":"${"b".repeat(64)}"}]}"""
        val r = VoicePackCatalog.parse(json)
        assertEquals(listOf("good"), r.packs.map { it.id })
        assertEquals(3, r.rejected.size)
        assertEquals(40, r.packs[0].unpackedBytes)
    }

    @Test fun emptyCatalogIsValid() {
        val r = VoicePackCatalog.parse("""{"packs":[]}""")
        assertTrue(r.packs.isEmpty() && r.rejected.isEmpty())
    }

    @Test fun downloadsAndVerifies() {
        val body = zip("model.onnx" to noise(5000))
        val p = pack(serve(body), body)
        val store = PackStore(tmp)
        val progress = mutableListOf<Long>()
        store.installFromNetwork(p, http) { d, _ -> progress += d }
        assertTrue(progress.last() == body.size.toLong())
        assertEquals(listOf("test-voice"), store.installed().map { it.id })
        assertEquals(5000, File(store.get("test-voice")!!.dir, "model.onnx").readText().length)
        assertFalse(store.archiveFile(p).exists())
    }

    @Test fun corruptDownloadIsRejectedAndCleaned() {
        val body = zip("a" to "hello")
        val p = pack(serve(body), body, sha = "0".repeat(64))
        val store = PackStore(tmp)
        assertFailsWith<ChecksumMismatchException> { store.installFromNetwork(p, http) }
        assertTrue(store.installed().isEmpty())
        assertFalse(store.partFile(p).exists())
    }

    @Test fun resumesFromPartialFile() {
        val body = zip("big.bin" to noise(20000))
        val p = pack(serve(body), body)
        val store = PackStore(tmp)
        store.partFile(p).also { it.parentFile.mkdirs(); it.writeBytes(body.copyOfRange(0, 1000)) }
        store.installFromNetwork(p, http)
        assertEquals("bytes=1000-", requests.single())
        assertEquals(1, store.installed().size)
    }

    @Test fun restartsWhenServerIgnoresRange() {
        val body = zip("f" to noise(3000))
        val p = pack(serve(body, honourRange = false), body)
        val store = PackStore(tmp)
        store.partFile(p).also { it.parentFile.mkdirs(); it.writeBytes(body.copyOfRange(0, 500)) }
        store.installFromNetwork(p, http)
        assertEquals(1, store.installed().size)
    }

    @Test fun cancelKeepsPartialThenResumes() {
        val body = zip("f" to noise(400_000))
        val p = pack(serve(body), body)
        val store = PackStore(tmp)
        var seen = 0L
        assertFailsWith<DownloadCancelledException> {
            store.installFromNetwork(p, http, isCancelled = { seen > 100_000 }) { d, _ -> seen = d }
        }
        val kept = store.partFile(p).length()
        assertTrue(kept in 1 until body.size)
        store.installFromNetwork(p, http)
        assertEquals(1, store.installed().size)
    }

    @Test fun refusesWhenNotEnoughSpace() {
        val body = zip("f" to "x")
        val p = pack("https://example.invalid/p.zip", body, size = Long.MAX_VALUE / 4)
        assertFailsWith<InsufficientSpaceException> { PackStore(tmp).installFromNetwork(p) }
    }

    @Test fun refusesPlainHttpByDefault() {
        val body = zip("f" to "x")
        val p = pack(serve(body), body)
        assertFailsWith<IllegalArgumentException> { PackStore(tmp).installFromNetwork(p, PackDownloader()) }
        assertTrue(requests.isEmpty())
    }

    @Test fun zipSlipIsRejected() {
        val evil = zip("../evil.txt" to "pwn")
        val p = pack("https://x.test/a.zip", evil)
        val archive = File(tmp, "evil.zip").also { it.writeBytes(evil) }
        val store = PackStore(File(tmp, "root"))
        assertFailsWith<java.io.IOException> { store.install(p, archive) }
        assertFalse(File(tmp, "root/evil.txt").exists() || File(tmp, "evil.txt").exists())
        assertTrue(store.installed().isEmpty())
    }

    @Test fun zipBombIsRejected() {
        val bomb = zip("big" to "0".repeat(100_000))
        val p = pack("https://x.test/a.zip", bomb).copy(unpackedBytes = 1000)
        val archive = File(tmp, "b.zip").also { it.writeBytes(bomb) }
        val store = PackStore(File(tmp, "root"))
        assertFailsWith<java.io.IOException> { store.install(p, archive) }
        assertTrue(store.installed().isEmpty())
        assertFalse(File(tmp, "root/installed/.tmp-test-voice").exists())
    }

    @Test fun deleteRemovesPack() {
        val body = zip("a" to "hello")
        val p = pack(serve(body), body)
        val store = PackStore(tmp)
        store.installFromNetwork(p, http)
        assertTrue(store.sizeOnDisk("test-voice") > 0)
        assertTrue(store.delete("test-voice"))
        assertTrue(store.installed().isEmpty())
    }
}

class BundledCatalogTest {
    private val asset = java.io.File("../../app/src/main/assets/voicepacks.json")

    @Test fun shippedCatalogIsValidAndPinned() {
        val r = VoicePackCatalog.parse(asset.readText())
        assertTrue(r.rejected.isEmpty(), "rejected entries: ${r.rejected}")
        val p = r.packs.single()
        assertEquals("supertonic-3", p.id)
        assertEquals(17, p.files.size)
        assertTrue(p.requiresAcceptance && !p.notice.isNullOrBlank())
        assertTrue(p.license.contains("OpenRAIL-M"))
        assertTrue(p.files.any { it.path == "LICENSE" }, "the licence must be installed with the model")
        assertEquals(setOf("duration_predictor", "text_encoder", "vector_estimator", "vocoder"),
            p.files.filter { it.path.endsWith(".onnx") }.map { it.path.substringAfter('/').removeSuffix(".onnx") }.toSet())
        assertTrue(p.files.all { it.url.startsWith("https://huggingface.co/") && it.url.contains("aafc6e32416a594460b32413efc49d7fe4ce6d46") }, "downloads must be pinned to one revision")
        assertTrue(p.sizeBytes in 390_000_000L..410_000_000L, "size ${p.sizeBytes}")
    }

    /** Regression guard: voice-pack downloads fail on a real phone ("missing INTERNET permission?") without it. */
    @Test fun manifestDeclaresInternetPermissionForDownloads() {
        val manifest = java.io.File("../../app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.permission.INTERNET"), "app needs INTERNET to download voice packs")
    }
}
