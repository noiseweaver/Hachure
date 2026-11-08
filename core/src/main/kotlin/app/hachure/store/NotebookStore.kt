package app.hachure.store

import app.hachure.model.Page
import java.io.File
import java.io.IOException
import java.util.Properties
import java.util.UUID

/**
 * Notebooks on local storage, one folder each:
 *
 * ```
 * <root>/<id>/notebook.properties   title, cover, times, archived, page count
 * <root>/<id>/page-1.hach           PageCodec
 * ```
 *
 * Every write goes to a temp file first and is renamed into place, so a crash mid-save leaves the
 * previous version intact. Not thread-safe: the app serialises saves on one thread.
 */
class NotebookStore(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {

    init {
        root.mkdirs()
    }

    fun list(): List<Notebook> =
        root.listFiles()?.filter { it.isDirectory }?.mapNotNull { runCatching { readMeta(it) }.getOrNull() }.orEmpty()

    fun get(id: String): Notebook? = dir(id).takeIf { it.isDirectory }?.let { runCatching { readMeta(it) }.getOrNull() }

    fun create(title: String, cover: CoverPattern): Notebook {
        val now = clock()
        val nb = Notebook(UUID.randomUUID().toString(), title.trim().ifEmpty { "Untitled" }, cover, now, now, false, 1)
        dir(nb.id).mkdirs()
        writeMeta(nb)
        return nb
    }

    fun rename(id: String, title: String): Notebook? = update(id) { it.copy(title = title.trim().ifEmpty { it.title }) }

    fun setCover(id: String, cover: CoverPattern): Notebook? = update(id) { it.copy(cover = cover) }

    fun setArchived(id: String, archived: Boolean): Notebook? = update(id) { it.copy(archived = archived) }

    fun delete(id: String): Boolean = dir(id).let { it.isDirectory && it.deleteRecursively() }

    /** Page [index] (1-based), or null if it was never saved. */
    fun loadPage(id: String, index: Int = 1): Page? {
        val f = pageFile(id, index)
        if (!f.isFile) return null
        return f.inputStream().use { PageCodec.read(it) }
    }

    /** Saves a page and bumps the notebook's modified time. */
    fun savePage(id: String, page: Page, index: Int = 1) {
        val nb = get(id) ?: throw IOException("no notebook $id")
        atomicWrite(pageFile(id, index)) { PageCodec.write(page, it) }
        writeMeta(nb.copy(modifiedMs = clock(), pageCount = maxOf(nb.pageCount, index)))
    }

    private fun update(id: String, change: (Notebook) -> Notebook): Notebook? {
        val nb = get(id) ?: return null
        return change(nb).also { writeMeta(it) }
    }

    private fun dir(id: String): File {
        require(id.isNotEmpty() && id.none { it == '/' || it == '\\' } && id != "." && id != "..") { "bad id" }
        return File(root, id)
    }

    private fun pageFile(id: String, index: Int): File {
        require(index >= 1)
        return File(dir(id), "page-$index.hach")
    }

    private fun readMeta(d: File): Notebook {
        val p = Properties()
        File(d, META).inputStream().use { p.load(it) }
        return Notebook(
            id = d.name,
            title = p.getProperty("title") ?: "Untitled",
            cover = CoverPattern.fromId(p.getProperty("cover")),
            createdMs = p.getProperty("created")?.toLongOrNull() ?: 0L,
            modifiedMs = p.getProperty("modified")?.toLongOrNull() ?: 0L,
            archived = p.getProperty("archived") == "true",
            pageCount = p.getProperty("pages")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
        )
    }

    private fun writeMeta(nb: Notebook) {
        val p = Properties()
        p.setProperty("format", "1")
        p.setProperty("title", nb.title)
        p.setProperty("cover", nb.cover.id)
        p.setProperty("created", nb.createdMs.toString())
        p.setProperty("modified", nb.modifiedMs.toString())
        p.setProperty("archived", nb.archived.toString())
        p.setProperty("pages", nb.pageCount.toString())
        atomicWrite(File(dir(nb.id), META)) { p.store(it, null) }
    }

    private fun atomicWrite(target: File, write: (java.io.OutputStream) -> Unit) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { out ->
            write(out)
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IOException("could not replace $target")
        }
    }

    private companion object {
        const val META = "notebook.properties"
    }
}
