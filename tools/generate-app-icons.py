"""Generates Opaq's app icon resources from the brand pack's SVG geometry (OPAQ-brand-assets v1.0).

Run from the repo root: python3 tools/generate-app-icons.py .
Writes:
  androidApp/src/main/res/drawable/ic_launcher_fg_<id>.xml   adaptive-icon foregrounds
  androidApp/src/main/res/drawable/ic_launcher_monochrome.xml themed-icon layer (small-size A)
  androidApp/src/main/res/drawable/ic_notification.xml       status bar icon (small-size A)
  androidApp/src/main/res/mipmap-anydpi-v26/ic_launcher_<id>.xml
  androidApp/src/main/res/values/ic_launcher_colors.xml
  ui/src/commonMain/kotlin/app/opaq/ui/AppIconArt.kt          the same art for in-app previews
"""
import os, sys

repo = sys.argv[1]
INK, INDIGO, WHITE = "#0F172A", "#6366F1", "#FFFFFF"

# Glyph geometry, in the brand files' inner viewBox (-6 7.5 68 68).
A_SERIF = ("M1 70 L1 68.7 Q5.3 68.5 6 65.8 L6 38 C6 21 16 13 28 13 C40 13 50 21 50 38 L50 65.8 "
           "Q50.7 68.5 55 68.7 L55 70 L35 70 L35 68.7 Q39.3 68.5 40 65.8 L40 38 C40 23.5 35.8 15 28 15 "
           "C20.2 15 16 23.5 16 38 L16 65.8 Q16.7 68.5 21 68.7 L21 70 Z")
A_OVERSIZED = ("M6 90 L6 38 C6 21 16 13 28 13 C40 13 50 21 50 38 L50 90 L40 90 L40 38 "
               "C40 23.5 35.8 15 28 15 C20.2 15 16 23.5 16 38 L16 90 Z")
def rect(x, y, w, h): return f"M{x} {y} H{x + w} V{y + h} H{x} Z"
def circle(cx, cy, r): return f"M{cx - r} {cy} A{r} {r} 0 1 0 {cx + r} {cy} A{r} {r} 0 1 0 {cx - r} {cy} Z"
CROSSBAR = rect(13, 38.5, 30, 3.5)
KEYHOLE = circle(28, 50, 3.4) + " M26.5 52.5 L25 60 H31 L29.5 52.5 Z"
# The favicon's simplified A (no serifs or keyhole), for sizes of 32 px and below.
SMALL_A = "M2 70 V38 C2 20 14 12 28 12 C42 12 54 20 54 38 V70 H42 V38 C42 25 36 19 28 19 C20 19 14 25 14 38 V70 Z"
SMALL_BODY = rect(11, 42, 34, 28)

def placement(x, y, size):
    """Inner viewBox (-6 7.5 68 68) placed at x, y, size in the 100-unit icon: scale and translation."""
    s = size / 68
    return s, x + 6 * s, y - 7.5 * s

# id, label, background, rings, placement, layers [(path, colour)]
VARIANTS = [
    ("white", "Dark on white", WHITE, False, placement(18, 16, 64),
     [(rect(13, 40, 30, 30), INDIGO), (A_SERIF, INK), (CROSSBAR, INK), (KEYHOLE, INK)]),
    ("twotone", "Two-tone", INK, False, placement(18, 16, 64),
     [(rect(13, 40, 30, 30), INDIGO), (A_SERIF, WHITE), (CROSSBAR, WHITE), (KEYHOLE, WHITE)]),
    # The body runs off the bottom with the legs (the brand file stops it out of view, at 42 tall).
    ("oversized", "Oversized A", INDIGO, False, placement(4, 14, 92),
     [(rect(13, 40, 30, 50), INK), (A_OVERSIZED, WHITE), (CROSSBAR, WHITE), (KEYHOLE, WHITE)]),
    ("rings", "Signal rings", INK, True, placement(18, 16, 64),
     [(rect(13, 40, 30, 30), INDIGO), (A_SERIF, WHITE), (CROSSBAR, WHITE), (KEYHOLE, WHITE)]),
]
RINGS = [(circle(50, 56, 46), 0.18), (circle(50, 56, 34), 0.32)]

def f(v): return f"{v:.4f}".rstrip("0").rstrip(".")
def write(path, text):
    full = os.path.join(repo, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w") as fh: fh.write(text)

HEADER = "<!-- Generated from the OPAQ brand pack (v1.0) by tools/generate-app-icons.py; change the script, not this file. -->\n"
NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'

# Adaptive icons: 108 dp canvas, of which the middle 72 dp shows. The 100-unit square masters
# map onto those 72 dp, so the art sits where the brand files put it.
for vid, label, bg, rings, (s, tx, ty), layers in VARIANTS:
    out = [f'<?xml version="1.0" encoding="utf-8"?>\n{HEADER}<vector {NS}\n    android:width="108dp" android:height="108dp"\n'
           f'    android:viewportWidth="108" android:viewportHeight="108">\n'
           f'    <group android:translateX="18" android:translateY="18" android:scaleX="0.72" android:scaleY="0.72">\n']
    if rings:
        for d, a in RINGS:
            out.append(f'        <path android:pathData="{d}" android:strokeColor="{INDIGO}" android:strokeAlpha="{a}" android:strokeWidth="3" />\n')
    out.append(f'        <group android:translateX="{f(tx)}" android:translateY="{f(ty)}" android:scaleX="{f(s)}" android:scaleY="{f(s)}">\n')
    for d, c in layers:
        out.append(f'            <path android:fillColor="{c}" android:pathData="{d}" />\n')
    out.append("        </group>\n    </group>\n</vector>\n")
    write(f"androidApp/src/main/res/drawable/ic_launcher_fg_{vid}.xml", "".join(out))
    write(f"androidApp/src/main/res/mipmap-anydpi-v26/ic_launcher_{vid}.xml",
          f'<?xml version="1.0" encoding="utf-8"?>\n{HEADER}<adaptive-icon {NS}>\n'
          f'    <background android:drawable="@color/ic_launcher_bg_{vid}" />\n'
          f'    <foreground android:drawable="@drawable/ic_launcher_fg_{vid}" />\n'
          f'    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n</adaptive-icon>\n')

write("androidApp/src/main/res/values/ic_launcher_colors.xml",
      f'<?xml version="1.0" encoding="utf-8"?>\n{HEADER}<resources>\n' +
      "".join(f'    <color name="ic_launcher_bg_{v[0]}">{v[2]}</color>\n' for v in VARIANTS) + "</resources>\n")

# Themed (monochrome) icon and notification icon: the small-size A, one colour.
s, tx, ty = placement(18, 16, 64)
write("androidApp/src/main/res/drawable/ic_launcher_monochrome.xml",
      f'<?xml version="1.0" encoding="utf-8"?>\n{HEADER}<vector {NS}\n    android:width="108dp" android:height="108dp"\n'
      f'    android:viewportWidth="108" android:viewportHeight="108">\n'
      f'    <group android:translateX="18" android:translateY="18" android:scaleX="0.72" android:scaleY="0.72">\n'
      f'        <group android:translateX="{f(tx)}" android:translateY="{f(ty)}" android:scaleX="{f(s)}" android:scaleY="{f(s)}">\n'
      f'            <path android:fillColor="#FF000000" android:pathData="{SMALL_A} {SMALL_BODY}" />\n'
      f'        </group>\n    </group>\n</vector>\n')
# 24 dp: the A's box (x 2..54, y 12..70) scaled to 20 dp tall and centred.
ns = 20 / 58
write("androidApp/src/main/res/drawable/ic_notification.xml",
      f'<?xml version="1.0" encoding="utf-8"?>\n{HEADER}<vector {NS}\n    android:width="24dp" android:height="24dp"\n'
      f'    android:viewportWidth="24" android:viewportHeight="24">\n'
      f'    <group android:translateX="{f(12 - 28 * ns)}" android:translateY="{f(2 - 12 * ns)}" android:scaleX="{f(ns)}" android:scaleY="{f(ns)}">\n'
      f'        <path android:fillColor="#FFFFFFFF" android:pathData="{SMALL_A} {SMALL_BODY}" />\n'
      f'    </group>\n</vector>\n')

# Compose: the same four icons, for the picker in Settings.
def kcolor(c): return f"Color(0xFF{c[1:]})"
k = ['''package app.opaq.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Generated from the OPAQ brand pack (v1.0) by tools/generate-app-icons.py, like the Android icon resources.

/** The app icons someone can pick in Settings → Appearance. [id] is what the platform stores. */
enum class AppIconChoice(val id: String, val label: String) {
''']
for vid, label, *_ in VARIANTS:
    k.append(f'    {vid.capitalize() if vid != "twotone" else "TwoTone"}("{vid}", "{label}"),\n')
k[-1] = k[-1].rstrip(",\n") + ";\n"
k.append('''
    companion object {
        /** Dark A on white, used until someone picks another. */
        val Default = White
        fun of(id: String?): AppIconChoice = entries.firstOrNull { it.id == id } ?: Default
    }
}

/** Draws [choice] as its square master (100 × 100); clip it to the shape the preview needs. */
fun appIconArt(choice: AppIconChoice): ImageVector = when (choice) {
''')
for vid, *_ in VARIANTS:
    name = "TwoTone" if vid == "twotone" else vid.capitalize()
    k.append(f"    AppIconChoice.{name} -> {vid}Art\n")
k.append("}\n")
for vid, label, bg, rings, (s, tx, ty), layers in VARIANTS:
    k.append(f'''
private val {vid}Art: ImageVector by lazy {{
    ImageVector.Builder("{label}", 48.dp, 48.dp, 100f, 100f).apply {{
        addPath(addPathNodes("{rect(0, 0, 100, 100)}"), fill = SolidColor({kcolor(bg)}))
''')
    if rings:
        for d, a in RINGS:
            k.append(f'        addPath(addPathNodes("{d}"), stroke = SolidColor({kcolor(INDIGO)}), strokeAlpha = {a}f, strokeLineWidth = 3f)\n')
    k.append(f'        addGroup(scaleX = {f(s)}f, scaleY = {f(s)}f, translationX = {f(tx)}f, translationY = {f(ty)}f)\n')
    for d, c in layers:
        k.append(f'        addPath(addPathNodes("{d}"), fill = SolidColor({kcolor(c)}))\n')
    k.append("        clearGroup()\n    }.build()\n}\n")
write("ui/src/commonMain/kotlin/app/opaq/ui/AppIconArt.kt", "".join(k))
print("ok")
