import com.mundymorning.touchreader.core.art.NineSlice
import com.mundymorning.touchreader.core.art.PxRect
import org.json.JSONObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NineSliceTest {
    @Test fun nineSeparatePiecesThatTileTheTargetExactly() {
        val p = NineSlice.pieces(192, 192, 60, 400, 300, 60f)
        assertEquals(9, p.size)
        assertEquals(400 * 300, p.sumOf { it.dst.w * it.dst.h })
        assertEquals(192 * 192, p.sumOf { it.src.w * it.src.h })
        // corners keep their size, the middle takes up the rest
        assertEquals(PxRect(0, 0, 60, 60), p[0].dst)
        assertEquals(PxRect(340, 240, 60, 60), p[8].dst)
        assertEquals(PxRect(60, 60, 280, 180), p[4].dst)
    }

    @Test fun cornersAreScaledForOtherScreens() {
        val p = NineSlice.pieces(192, 192, 60, 400, 300, 40f)
        assertEquals(PxRect(0, 0, 40, 40), p[0].dst)
        assertEquals(PxRect(0, 0, 60, 60), p[0].src, "the picture is always cut at the same place")
    }

    @Test fun aSmallTargetShrinksTheCornersInsteadOfOverlapping() {
        val p = NineSlice.pieces(192, 192, 60, 50, 30, 60f)
        assertTrue(p.isNotEmpty())
        assertTrue(p.all { it.dst.w > 0 && it.dst.h > 0 && it.dst.x >= 0 && it.dst.y >= 0 && it.dst.x + it.dst.w <= 50 && it.dst.y + it.dst.h <= 30 })
        assertEquals(50 * 30, p.sumOf { it.dst.w * it.dst.h })
    }

    @Test fun anOversizedSliceIsLimitedToHalfThePicture() {
        val p = NineSlice.pieces(100, 60, 80, 300, 200, 30f)
        assertTrue(p.all { it.src.w > 0 && it.src.h > 0 })
        assertEquals(100 * 60, p.sumOf { it.src.w * it.src.h })
    }

    @Test fun nothingToDrawGivesNothing() {
        assertTrue(NineSlice.pieces(0, 10, 4, 10, 10, 4f).isEmpty())
        assertTrue(NineSlice.pieces(10, 10, 4, 0, 10, 4f).isEmpty())
    }
}

class ArtSpecTest {
    private val slots = JSONObject(File("../../art/slots.json").readText()).getJSONArray("slots")
    private val doc = File("../../docs/ASSETS.md").readText()

    @Test fun everySlotIsWellFormed() {
        val ids = HashSet<String>()
        for (i in 0 until slots.length()) {
            val s = slots.getJSONObject(i)
            val id = s.getString("id")
            assertTrue(Regex("[a-z][a-z0-9_]*").matches(id), "bad id $id (Android resource names are lowercase letters, digits and underscores)")
            assertTrue(ids.add(id), "duplicate id $id")
            assertTrue(Regex("[a-z]+").matches(s.getString("page")), "bad page for $id")
            assertTrue(s.getInt("width") > 0 && s.getInt("height") > 0, id)
            if (s.getString("kind") == "nine_slice") {
                val slice = s.getInt("slice")
                assertTrue(slice > 0 && slice * 2 < minOf(s.getInt("width"), s.getInt("height")), "slice of $id must leave a middle")
            }
            assertTrue(s.getString("what").length > 10, "describe $id")
        }
        assertTrue(ids.size >= 30)
    }

    @Test fun theFrameTableInTheAppMatchesTheSlotList() {
        val fromJson = HashMap<String, com.mundymorning.touchreader.core.art.ArtFrames.Spec>()
        for (i in 0 until slots.length()) {
            val s = slots.getJSONObject(i)
            if (s.getString("kind") == "nine_slice") fromJson[s.getString("id")] = com.mundymorning.touchreader.core.art.ArtFrames.Spec(s.getInt("width"), s.getInt("height"), s.getInt("slice"))
        }
        assertEquals(fromJson, com.mundymorning.touchreader.core.art.ArtFrames.all)
    }

    @Test fun theSpecSheetListsEverySlotAndTheFolderItGoesIn() {
        for (i in 0 until slots.length()) {
            val s = slots.getJSONObject(i)
            assertTrue(doc.contains("`${s.getString("id")}`"), "docs/ASSETS.md does not mention ${s.getString("id")}")
            assertTrue(doc.contains("art/${s.getString("page")}/"), "docs/ASSETS.md does not mention the folder art/${s.getString("page")}/")
        }
    }
}
