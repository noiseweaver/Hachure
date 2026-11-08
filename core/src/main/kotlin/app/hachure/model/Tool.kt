package app.hachure.model

/**
 * Brushes. Each matches one Onyx A2 live stroke style so the committed render can match what the
 * pen drew live. Stored by [id] in files, so never rename an id.
 *
 * [widthsMm] are the fine, medium and bold presets.
 */
enum class Tool(val id: String, val widthsMm: FloatArray) {
    PENCIL("pencil", floatArrayOf(0.25f, 0.5f, 1.0f)),
    FOUNTAIN("fountain", floatArrayOf(0.4f, 0.8f, 1.6f)),
    MARKER("marker", floatArrayOf(2f, 4f, 8f)),
    BRUSH("brush", floatArrayOf(1f, 2f, 4f)),
    CHARCOAL("charcoal", floatArrayOf(0.8f, 1.5f, 3f)),
    DASH("dash", floatArrayOf(0.25f, 0.5f, 1.0f)),
    CHARCOAL2("charcoal2", floatArrayOf(0.8f, 1.5f, 3f)),
    ;

    fun widthMm(preset: WidthPreset): Float = widthsMm[preset.ordinal]

    companion object {
        fun fromId(id: String): Tool =
            entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("unknown tool '$id'")
    }
}

enum class WidthPreset { FINE, MEDIUM, BOLD }
