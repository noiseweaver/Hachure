package app.hachure.store

import app.hachure.model.InkPoint
import app.hachure.model.Page
import app.hachure.model.Perspective
import app.hachure.model.VanishingPoint
import app.hachure.model.Ruler
import app.hachure.model.Stroke
import app.hachure.model.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

class StoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000L
    private fun store() = NotebookStore(tmp.root) { now }

    private fun page() = Page(157f, 209f).apply {
        add(Stroke(1, Tool.FOUNTAIN, 0.8f, listOf(InkPoint(1f, 2f, 0.5f, 0), InkPoint(3f, 4f, 1f, 16))))
        add(Stroke(7, Tool.CHARCOAL2, 1.5f, listOf(InkPoint(9f, 9f, 0.2f, 0))))
    }

    @Test fun codecRoundTrip() {
        val out = ByteArrayOutputStream()
        PageCodec.write(page(), out)
        val back = PageCodec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(157f, back.widthMm)
        assertEquals(page().strokes, back.strokes)
        assertEquals(8L, back.newStrokeId())
    }

    @Test fun codecKeepsRulers() {
        val pg = page().apply {
            putRuler(Ruler(3, 40f, 60f, 212.5f, 120f, 14f, true, 1.5f, -1))
            putRuler(Ruler(4, 80f, 90f, 0f))
        }
        val out = ByteArrayOutputStream()
        PageCodec.write(pg, out)
        val back = PageCodec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(pg.rulers, back.rulers)
        assertEquals(pg.strokes, back.strokes)
        assertEquals(5L, back.newGuideId())
    }

    @Test fun codecKeepsPerspectiveAndAim() {
        val pg = page().apply {
            putRuler(Ruler(3, 40f, 60f, 212.5f, aimVp = 2))
            perspective = Perspective(
                2,
                listOf(VanishingPoint(-300f, 80f, true), VanishingPoint(500f, 81f), VanishingPoint(70f, 900f)),
                horizonLocked = true, linesVisible = false, rays = 36,
            )
        }
        val out = ByteArrayOutputStream()
        PageCodec.write(pg, out)
        val back = PageCodec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(pg.perspective, back.perspective)
        assertEquals(pg.rulers, back.rulers)
    }

    @Test fun codecReadsVersion2WithoutPerspective() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(0x48414348); writeInt(2); writeFloat(157f); writeFloat(209f); writeInt(0)
            writeInt(1)
            writeLong(4); writeFloat(10f); writeFloat(20f); writeFloat(30f); writeFloat(130f); writeFloat(14f)
            writeBoolean(true); writeFloat(2.5f); writeByte(-1)
            flush()
        }
        val back = PageCodec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(Ruler(4, 10f, 20f, 30f, 130f, 14f, true, 2.5f, -1, 0), back.rulers.single())
        assertEquals(0, back.perspective.points)
    }

    @Test fun codecReadsVersion1WithoutRulers() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(0x48414348); writeInt(1); writeFloat(157f); writeFloat(209f); writeInt(1)
            writeLong(5); writeUTF(Tool.PENCIL.id); writeFloat(0.3f); writeInt(1)
            writeFloat(1f); writeFloat(2f); writeFloat(0.5f); writeInt(0)
            flush()
        }
        val back = PageCodec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(1, back.strokes.size)
        assertEquals(0, back.rulers.size)
    }

    @Test fun codecRejectsNewerVersion() {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply { writeInt(0x48414348); writeInt(PageCodec.VERSION + 1); writeFloat(1f); writeFloat(1f); writeInt(0); flush() }
        assertThrows(IOException::class.java) { PageCodec.read(ByteArrayInputStream(out.toByteArray())) }
    }

    @Test fun codecRejectsGarbage() {
        assertThrows(IOException::class.java) { PageCodec.read(ByteArrayInputStream(ByteArray(16))) }
    }

    @Test fun createSaveLoad() {
        val s = store()
        val nb = s.create("  Street study ", CoverPattern.CROSS)
        assertEquals("Street study", nb.title)
        assertNull(s.loadPage(nb.id))
        now = 5_000L
        s.savePage(nb.id, page())
        assertEquals(2, s.loadPage(nb.id)!!.strokes.size)
        assertEquals(5_000L, s.get(nb.id)!!.modifiedMs)
        assertEquals(1, s.list().size)
        assertFalse(tmp.root.walk().any { it.name.endsWith(".tmp") })
    }

    @Test fun renameCoverArchiveDelete() {
        val s = store()
        val id = s.create("A", CoverPattern.DIAGONAL).id
        assertEquals("B", s.rename(id, "B")!!.title)
        assertEquals("B", s.rename(id, "   ")!!.title)
        assertEquals(CoverPattern.DOTS, s.setCover(id, CoverPattern.DOTS)!!.cover)
        assertTrue(s.setArchived(id, true)!!.archived)
        assertTrue(s.delete(id))
        assertNull(s.get(id))
    }

    @Test fun rejectsPathIds() {
        assertThrows(IllegalArgumentException::class.java) { store().loadPage("../x") }
    }

    @Test fun shelfFilters() {
        fun nb(t: String, m: Long, a: Boolean = false) = Notebook(t, t, CoverPattern.DIAGONAL, 0, m, a, 1)
        val all = listOf(nb("beta", 3), nb("Alpha", 1), nb("gamma", 2, a = true))
        assertEquals(listOf("beta", "Alpha"), all.forShelf(ShelfFilter.RECENT).map { it.title })
        assertEquals(listOf("Alpha", "beta"), all.forShelf(ShelfFilter.A_TO_Z).map { it.title })
        assertEquals(listOf("gamma"), all.forShelf(ShelfFilter.ARCHIVED).map { it.title })
    }

    @Test fun ages() {
        val day = { ms: Long -> ms / 100 }
        assertEquals("today", relativeAge(1000, 1050, day))
        assertEquals("yesterday", relativeAge(1000, 1100, day))
        assertEquals("3 days", relativeAge(1000, 1300, day))
        assertEquals("2 weeks", relativeAge(0, 1400, day))
        assertEquals("2 months", relativeAge(0, 6000, day))
    }
}
