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
