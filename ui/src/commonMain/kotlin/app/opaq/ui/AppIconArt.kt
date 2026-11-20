package app.opaq.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Generated from the OPAQ brand pack by tools/generate-app-icons.py, like the Android icon resources.

/** The app icons someone can pick in Settings → Appearance. [id] is what the platform stores. */
enum class AppIconChoice(val id: String, val label: String) {
    White("white", "Dark on white"),
    TwoTone("twotone", "Two-tone"),
    Oversized("oversized", "Oversized A");

    companion object {
        /** Dark A on white, used until someone picks another. */
        val Default = White
        fun of(id: String?): AppIconChoice = entries.firstOrNull { it.id == id } ?: Default
    }
}

/** Draws [choice] as its square master (100 × 100); clip it to the shape the preview needs. */
fun appIconArt(choice: AppIconChoice): ImageVector = when (choice) {
    AppIconChoice.White -> whiteArt
    AppIconChoice.TwoTone -> twotoneArt
    AppIconChoice.Oversized -> oversizedArt
}

private val whiteArt: ImageVector by lazy {
    ImageVector.Builder("Dark on white", 48.dp, 48.dp, 100f, 100f).apply {
        addPath(addPathNodes("M0 0 H100 V100 H0 Z"), fill = SolidColor(Color(0xFFFFFFFF)))
        addGroup(scaleX = 0.9375f, scaleY = 0.9375f, translationX = 23.75f, translationY = 5.4375f)
        addPath(addPathNodes("M20 84.6 A8 1.6 0 1 0 36 84.6 A8 1.6 0 1 0 20 84.6 Z"), fill = SolidColor(Color(0xFF0F172A)), fillAlpha = 0.22f)
        addGroup(clipPathData = addPathNodes("M-10 0 H66 V61.74 L30.82 80.68 Q28 82.2 25.18 80.68 L-10 61.74 Z"))
        addPath(addPathNodes("M9 40 H47 V85 H9 Z"), fill = SolidColor(Color(0xFF6366F1)))
        addPath(addPathNodes("M2 85 V38 C2 21 14 13 28 13 C42 13 54 21 54 38 V85 H44 V38 C44 23.5 38 15 28 15 C18 15 12 23.5 12 38 V85 Z"), fill = SolidColor(Color(0xFF0F172A)))
        addPath(addPathNodes("M9 38.5 H47 V42.0 H9 Z"), fill = SolidColor(Color(0xFF0F172A)))
        clearGroup()
        addPath(addPathNodes("M24.6 50 A3.4 3.4 0 1 0 31.4 50 A3.4 3.4 0 1 0 24.6 50 Z M26.5 52.5 L25 60 H31 L29.5 52.5 Z"), fill = SolidColor(Color(0xFF0F172A)))
        clearGroup()
    }.build()
}

private val twotoneArt: ImageVector by lazy {
    ImageVector.Builder("Two-tone", 48.dp, 48.dp, 100f, 100f).apply {
        addPath(addPathNodes("M0 0 H100 V100 H0 Z"), fill = SolidColor(Color(0xFF0F172A)))
        addGroup(scaleX = 0.9375f, scaleY = 0.9375f, translationX = 23.75f, translationY = 5.4375f)
        addPath(addPathNodes("M20 84.6 A8 1.6 0 1 0 36 84.6 A8 1.6 0 1 0 20 84.6 Z"), fill = SolidColor(Color(0xFFFFFFFF)), fillAlpha = 0.22f)
        addGroup(clipPathData = addPathNodes("M-10 0 H66 V61.74 L30.82 80.68 Q28 82.2 25.18 80.68 L-10 61.74 Z"))
        addPath(addPathNodes("M9 40 H47 V85 H9 Z"), fill = SolidColor(Color(0xFF6366F1)))
        addPath(addPathNodes("M2 85 V38 C2 21 14 13 28 13 C42 13 54 21 54 38 V85 H44 V38 C44 23.5 38 15 28 15 C18 15 12 23.5 12 38 V85 Z"), fill = SolidColor(Color(0xFFFFFFFF)))
        addPath(addPathNodes("M9 38.5 H47 V42.0 H9 Z"), fill = SolidColor(Color(0xFFFFFFFF)))
        clearGroup()
        addPath(addPathNodes("M24.6 50 A3.4 3.4 0 1 0 31.4 50 A3.4 3.4 0 1 0 24.6 50 Z M26.5 52.5 L25 60 H31 L29.5 52.5 Z"), fill = SolidColor(Color(0xFFFFFFFF)))
        clearGroup()
    }.build()
}

private val oversizedArt: ImageVector by lazy {
    ImageVector.Builder("Oversized A", 48.dp, 48.dp, 100f, 100f).apply {
        addPath(addPathNodes("M0 0 H100 V100 H0 Z"), fill = SolidColor(Color(0xFF6366F1)))
        addGroup(scaleX = 1.3529f, scaleY = 1.3529f, translationX = 12.1176f, translationY = 3.8529f)
        addPath(addPathNodes("M13 40 H43 V90 H13 Z"), fill = SolidColor(Color(0xFF0F172A)))
        addPath(addPathNodes("M6 90 L6 38 C6 21 16 13 28 13 C40 13 50 21 50 38 L50 90 L40 90 L40 38 C40 23.5 35.8 15 28 15 C20.2 15 16 23.5 16 38 L16 90 Z"), fill = SolidColor(Color(0xFFFFFFFF)))
        addPath(addPathNodes("M13 38.5 H43 V42.0 H13 Z"), fill = SolidColor(Color(0xFFFFFFFF)))
        addPath(addPathNodes("M24.6 50 A3.4 3.4 0 1 0 31.4 50 A3.4 3.4 0 1 0 24.6 50 Z M26.5 52.5 L25 60 H31 L29.5 52.5 Z"), fill = SolidColor(Color(0xFFFFFFFF)))
        clearGroup()
    }.build()
}
