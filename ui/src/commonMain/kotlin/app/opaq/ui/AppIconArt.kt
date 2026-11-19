package app.opaq.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Generated from the OPAQ brand pack (v1.0) by tools/generate-app-icons.py, like the Android icon resources.

/** The app icons someone can pick in Settings → Appearance. [id] is what the platform stores. */
enum class AppIconChoice(val id: String, val label: String) {
    White("white", "Dark on white"),
    TwoTone("twotone", "Two-tone"),
    Oversized("oversized", "Oversized A"),
    Rings("rings", "Signal rings");

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
    AppIconChoice.Rings -> ringsArt
}

private val whiteArt: ImageVector by lazy {
    ImageVector.Builder("Dark on white", 48.dp, 48.dp, 100f, 100f).apply {
        addPath(addPathNodes("M0 0 H100 V100 H0 Z"), fill = SolidColor(Color(0xFFFFFFFF)))
        addGroup(scaleX = 0.9412f, scaleY = 0.9412f, translationX = 23.6471f, translationY = 8.9412f)
        addPath(addPathNodes("M13 40 H43 V70 H13 Z"), fill = SolidColor(Color(0xFF6366F1)))
        addPath(addPathNodes("M1 70 L1 68.7 Q5.3 68.5 6 65.8 L6 38 C6 21 16 13 28 13 C40 13 50 21 50 38 L50 65.8 Q50.7 68.5 55 68.7 L55 70 L35 70 L35 68.7 Q39.3 68.5 40 65.8 L40 38 C40 23.5 35.8 15 28 15 C20.2 15 16 23.5 16 38 L16 65.8 Q16.7 68.5 21 68.7 L21 70 Z"), fill = SolidColor(Color(0xFF0F172A)))
        addPath(addPathNodes("M13 38.5 H43 V42.0 H13 Z"), fill = SolidColor(Color(0xFF0F172A)))
        addPath(addPathNodes("M24.6 50 A3.4 3.4 0 1 0 31.4 50 A3.4 3.4 0 1 0 24.6 50 Z M26.5 52.5 L25 60 H31 L29.5 52.5 Z"), fill = SolidColor(Color(0xFF0F172A)))
        clearGroup()
    }.build()
}

private val twotoneArt: ImageVector by lazy {
    ImageVector.Builder("Two-tone", 48.dp, 48.dp, 100f, 100f).apply {
        addPath(addPathNodes("M0 0 H100 V100 H0 Z"), fill = SolidColor(Color(0xFF0F172A)))
        addGroup(scaleX = 0.9412f, scaleY = 0.9412f, translationX = 23.6471f, translationY = 8.9412f)
        addPath(addPathNodes("M13 40 H43 V70 H13 Z"), fill = SolidColor(Color(0xFF6366F1)))
        addPath(addPathNodes("M1 70 L1 68.7 Q5.3 68.5 6 65.8 L6 38 C6 21 16 13 28 13 C40 13 50 21 50 38 L50 65.8 Q50.7 68.5 55 68.7 L55 70 L35 70 L35 68.7 Q39.3 68.5 40 65.8 L40 38 C40 23.5 35.8 15 28 15 C20.2 15 16 23.5 16 38 L16 65.8 Q16.7 68.5 21 68.7 L21 70 Z"), fill = SolidColor(Color(0xFFFFFFFF)))
        addPath(addPathNodes("M13 38.5 H43 V42.0 H13 Z"), fill = SolidColor(Color(0xFFFFFFFF)))
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

private val ringsArt: ImageVector by lazy {
    ImageVector.Builder("Signal rings", 48.dp, 48.dp, 100f, 100f).apply {
        addPath(addPathNodes("M0 0 H100 V100 H0 Z"), fill = SolidColor(Color(0xFF0F172A)))
        addPath(addPathNodes("M4 56 A46 46 0 1 0 96 56 A46 46 0 1 0 4 56 Z"), stroke = SolidColor(Color(0xFF6366F1)), strokeAlpha = 0.18f, strokeLineWidth = 3f)
        addPath(addPathNodes("M16 56 A34 34 0 1 0 84 56 A34 34 0 1 0 16 56 Z"), stroke = SolidColor(Color(0xFF6366F1)), strokeAlpha = 0.32f, strokeLineWidth = 3f)
        addGroup(scaleX = 0.9412f, scaleY = 0.9412f, translationX = 23.6471f, translationY = 8.9412f)
        addPath(addPathNodes("M13 40 H43 V70 H13 Z"), fill = SolidColor(Color(0xFF6366F1)))
        addPath(addPathNodes("M1 70 L1 68.7 Q5.3 68.5 6 65.8 L6 38 C6 21 16 13 28 13 C40 13 50 21 50 38 L50 65.8 Q50.7 68.5 55 68.7 L55 70 L35 70 L35 68.7 Q39.3 68.5 40 65.8 L40 38 C40 23.5 35.8 15 28 15 C20.2 15 16 23.5 16 38 L16 65.8 Q16.7 68.5 21 68.7 L21 70 Z"), fill = SolidColor(Color(0xFFFFFFFF)))
        addPath(addPathNodes("M13 38.5 H43 V42.0 H13 Z"), fill = SolidColor(Color(0xFFFFFFFF)))
        addPath(addPathNodes("M24.6 50 A3.4 3.4 0 1 0 31.4 50 A3.4 3.4 0 1 0 24.6 50 Z M26.5 52.5 L25 60 H31 L29.5 52.5 Z"), fill = SolidColor(Color(0xFFFFFFFF)))
        clearGroup()
    }.build()
}
