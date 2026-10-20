package com.maiconerick.xscreentime.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class AppInfo(
    val pkg: String,
    val label: String,
    val icon: ImageBitmap?,
    /** Average vivid color of the icon, used for timeline bars and charts (ARGB). */
    val color: Int,
)

/**
 * Labels + icons, decoded once at a fixed small size and kept for the process lifetime.
 * Always call [load] off the main thread; the UI only ever reads already-resolved [AppInfo]s.
 */
object AppInfoCache {
    private const val ICON_PX = 96
    private val cache = ConcurrentHashMap<String, AppInfo>()
    @Volatile private var ignored: Set<String>? = null

    fun load(ctx: Context, pkg: String): AppInfo = cache.getOrPut(pkg) {
        if (pkg.startsWith(Importer.PREFIX)) {
            // Imported app that is no longer installed: keep its name, give it a stable neon-ish color.
            val hsv = floatArrayOf((pkg.hashCode().toUInt() % 360u).toFloat(), 0.6f, 0.95f)
            return@getOrPut AppInfo(pkg, pkg.removePrefix(Importer.PREFIX), null, Color.HSVToColor(hsv))
        }
        val pm = ctx.packageManager
        runCatching {
            val ai = pm.getApplicationInfo(pkg, 0)
            val bmp = pm.getApplicationIcon(ai).toBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
            AppInfo(pkg, pm.getApplicationLabel(ai).toString(), bmp.asImageBitmap(), vividColor(bmp))
        }.getOrElse {
            // Uninstalled since it was used.
            AppInfo(pkg, pkg.substringAfterLast('.'), null, 0xFF6B6B7B.toInt())
        }
    }

    fun loadAll(ctx: Context, pkgs: Collection<String>): Map<String, AppInfo> = pkgs.associateWith { load(ctx, it) }

    /** Home screens and System UI are "the phone", not apps you spent time in. */
    fun ignoredPackages(ctx: Context): Set<String> = ignored ?: run {
        val home = ctx.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
        (home + listOf("com.android.systemui", "android")).toSet().also { ignored = it }
    }

    private fun vividColor(bmp: Bitmap): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        val hsv = FloatArray(3)
        for (y in 0 until bmp.height step 4) for (x in 0 until bmp.width step 4) {
            val c = bmp.getPixel(x, y)
            if (Color.alpha(c) < 200) continue
            Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.35f || hsv[2] < 0.35f) continue
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        if (n == 0L) return 0xFF9AA0B4.toInt()
        Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
        // Push toward neon so it reads well on pure black.
        hsv[1] = (hsv[1] * 1.2f).coerceAtMost(1f)
        hsv[2] = hsv[2].coerceAtLeast(0.85f)
        return Color.HSVToColor(hsv)
    }
}
