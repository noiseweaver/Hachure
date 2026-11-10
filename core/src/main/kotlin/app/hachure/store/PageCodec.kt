package app.hachure.store

import app.hachure.model.InkPoint
import app.hachure.model.Page
import app.hachure.model.Stroke
import app.hachure.model.Tool
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Binary page format, version 1. Big-endian (java.io.Data*):
 *
 * ```
 * "HACH" magic, int version, float widthMm, float heightMm, int strokeCount,
 * per stroke: long id, UTF tool id, float widthMm, int pointCount,
 *             per point: float x, float y, float pressure, int t (ms)
 * ```
 *
 * Compact (14 bytes a point) and fast to read on the device. Export formats (PNG, SVG) are
 * Milestone 7.
 */
object PageCodec {
    private const val MAGIC = 0x48414348 // "HACH"
    const val VERSION = 1

    fun write(page: Page, out: OutputStream) {
        val d = DataOutputStream(out.buffered())
        d.writeInt(MAGIC)
        d.writeInt(VERSION)
        d.writeFloat(page.widthMm)
        d.writeFloat(page.heightMm)
        val strokes = page.strokes
        d.writeInt(strokes.size)
        for (s in strokes) {
            d.writeLong(s.id)
            d.writeUTF(s.tool.id)
            d.writeFloat(s.widthMm)
            d.writeInt(s.points.size)
            for (p in s.points) {
                d.writeFloat(p.x)
                d.writeFloat(p.y)
                d.writeFloat(p.pressure)
                d.writeInt(p.t.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            }
        }
        d.flush()
    }

    fun read(input: InputStream): Page {
        val d = DataInputStream(input.buffered())
        if (d.readInt() != MAGIC) throw IOException("not a Hachure page")
        val version = d.readInt()
        if (version != VERSION) throw IOException("unsupported page version $version")
        val page = Page(d.readFloat(), d.readFloat())
        val n = d.readInt()
        if (n < 0) throw IOException("bad stroke count $n")
        repeat(n) {
            val id = d.readLong()
            val tool = Tool.fromId(d.readUTF())
            val width = d.readFloat()
            val count = d.readInt()
            if (count <= 0) throw IOException("bad point count $count")
            val pts = ArrayList<InkPoint>(count)
            repeat(count) {
                pts += InkPoint(d.readFloat(), d.readFloat(), d.readFloat().coerceIn(0f, 1f), d.readInt().toLong())
            }
            page.add(Stroke(id, tool, width, pts))
        }
        return page
    }
}
