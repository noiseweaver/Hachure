package app.hachure.ui

import android.content.Context
import app.hachure.ink.SnapPreview
import app.hachure.model.Tool
import app.hachure.model.WidthPreset

/** Small UI settings kept across launches. Local only; nothing leaves the device. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("ui", Context.MODE_PRIVATE)

    var showLabels: Boolean
        get() = sp.getBoolean(K_LABELS, false)
        set(v) = sp.edit().putBoolean(K_LABELS, v).apply()

    var dotGrid: Boolean
        get() = sp.getBoolean(K_GRID, true)
        set(v) = sp.edit().putBoolean(K_GRID, v).apply()

    var eraserTrail: Boolean
        get() = sp.getBoolean(K_TRAIL, true)
        set(v) = sp.edit().putBoolean(K_TRAIL, v).apply()

    /** Strokes that start near a ruler edge are straightened onto it. */
    var snap: Boolean
        get() = sp.getBoolean(K_SNAP, true)
        set(v) = sp.edit().putBoolean(K_SNAP, v).apply()

    /** Milestone 6 spike: how a snapping stroke is shown while the pen is down. */
    var snapPreview: SnapPreview
        get() = runCatching { SnapPreview.valueOf(sp.getString(K_SNAP_PREVIEW, null)!!) }.getOrDefault(SnapPreview.AT_PEN_UP)
        set(v) = sp.edit().putString(K_SNAP_PREVIEW, v.name).apply()

    var lockAll: Boolean
        get() = sp.getBoolean(K_LOCK_ALL, false)
        set(v) = sp.edit().putBoolean(K_LOCK_ALL, v).apply()

    /** Ruler rotation snaps to 15 degree steps. */
    var angleSnap: Boolean
        get() = sp.getBoolean(K_ANGLE_SNAP, false)
        set(v) = sp.edit().putBoolean(K_ANGLE_SNAP, v).apply()

    var dock: ToolDock.Dock
        get() = runCatching { ToolDock.Dock.valueOf(sp.getString(K_DOCK, null)!!) }.getOrDefault(ToolDock.Dock.LEFT)
        set(v) = sp.edit().putString(K_DOCK, v.name).apply()

    /** Puck centre as a fraction of the screen, so it survives a size change. */
    var puckFx: Float
        get() = sp.getFloat(K_PX, 0.5f)
        set(v) = sp.edit().putFloat(K_PX, v).apply()

    var puckFy: Float
        get() = sp.getFloat(K_PY, 0.85f)
        set(v) = sp.edit().putFloat(K_PY, v).apply()

    var tool: Tool
        get() = runCatching { Tool.fromId(sp.getString(K_TOOL, null)!!) }.getOrDefault(Tool.PENCIL)
        set(v) = sp.edit().putString(K_TOOL, v.id).apply()

    /** The brush last picked from the style menu that is not pencil or fountain. */
    var extraTool: Tool
        get() = runCatching { Tool.fromId(sp.getString(K_EXTRA, null)!!) }.getOrDefault(Tool.CHARCOAL)
        set(v) = sp.edit().putString(K_EXTRA, v.id).apply()

    var width: WidthPreset
        get() = runCatching { WidthPreset.valueOf(sp.getString(K_WIDTH, null)!!) }.getOrDefault(WidthPreset.MEDIUM)
        set(v) = sp.edit().putString(K_WIDTH, v.name).apply()

    private companion object {
        const val K_LABELS = "show_labels"
        const val K_GRID = "dot_grid"
        const val K_TRAIL = "eraser_trail"
        const val K_SNAP = "snap"
        const val K_SNAP_PREVIEW = "snap_preview"
        const val K_LOCK_ALL = "lock_all"
        const val K_ANGLE_SNAP = "angle_snap"
        const val K_DOCK = "dock"
        const val K_PX = "puck_fx"
        const val K_PY = "puck_fy"
        const val K_TOOL = "tool"
        const val K_EXTRA = "extra_tool"
        const val K_WIDTH = "width"
    }
}
