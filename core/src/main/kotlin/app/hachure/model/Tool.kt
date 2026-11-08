package app.hachure.model

/** Drawing tools. Stored by [id] in files, so never rename an id. */
enum class Tool(val id: String) {
    PEN("pen"),
    PENCIL("pencil"),
    FOUNTAIN("fountain"),
    LINE("line"),
    ;

    companion object {
        fun fromId(id: String): Tool =
            entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("unknown tool '$id'")
    }
}
