package app.hachure.store

/** Cover hatch patterns for the shelf. Stored by [id], so never rename an id. */
enum class CoverPattern(val id: String) {
    DIAGONAL("diagonal"),
    DIAGONAL_BACK("diagonal_back"),
    CROSS("cross"),
    HORIZONTAL("horizontal"),
    DOTS("dots"),
    ;

    companion object {
        fun fromId(id: String?): CoverPattern = entries.firstOrNull { it.id == id } ?: DIAGONAL
    }
}

/** Notebook metadata. Pages live in separate files next to it. */
data class Notebook(
    val id: String,
    val title: String,
    val cover: CoverPattern,
    val createdMs: Long,
    val modifiedMs: Long,
    val archived: Boolean,
    val pageCount: Int,
)

enum class ShelfFilter { RECENT, A_TO_Z, ARCHIVED }

/** What the shelf shows for a filter, in display order. */
fun List<Notebook>.forShelf(filter: ShelfFilter): List<Notebook> = when (filter) {
    ShelfFilter.RECENT -> filter { !it.archived }.sortedByDescending { it.modifiedMs }
    ShelfFilter.A_TO_Z -> filter { !it.archived }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    ShelfFilter.ARCHIVED -> filter { it.archived }.sortedByDescending { it.modifiedMs }
}

/**
 * Short age for a cover caption: "today", "yesterday", "3 days", "2 weeks", "5 months".
 * Day boundaries use [dayOf], which maps epoch ms to a local day number (injected for tests).
 */
fun relativeAge(thenMs: Long, nowMs: Long, dayOf: (Long) -> Long): String {
    val days = (dayOf(nowMs) - dayOf(thenMs)).coerceAtLeast(0)
    return when {
        days == 0L -> "today"
        days == 1L -> "yesterday"
        days < 7 -> "$days days"
        days < 14 -> "1 week"
        days < 60 -> "${days / 7} weeks"
        days < 365 -> "${days / 30} months"
        days < 730 -> "1 year"
        else -> "${days / 365} years"
    }
}
