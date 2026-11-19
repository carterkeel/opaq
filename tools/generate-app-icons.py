"""Generates Opaq's app icon resources from the brand pack's SVG geometry (OPAQ-brand-assets, Nov 2026).

The A is a lock that is also a map pin: an arch and crossbar over an Indigo lock body, the whole
bottom clipped to a softened V point, with a soft shadow under the point. Oversized A keeps the
straight-legged A that runs off the bottom of the icon.

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

def rect(x, y, w, h): return f"M{x} {y} H{x + w} V{y + h} H{x} Z"
def circle(cx, cy, r): return f"M{cx - r} {cy} A{r} {r} 0 1 0 {cx + r} {cy} A{r} {r} 0 1 0 {cx - r} {cy} Z"
def ellipse(cx, cy, rx, ry): return f"M{cx - rx} {cy} A{rx} {ry} 0 1 0 {cx + rx} {cy} A{rx} {ry} 0 1 0 {cx - rx} {cy} Z"

# The lock-pin A, in the brand files' glyph coordinates.
PIN_CLIP = "M-10 0 H66 V61.74 L30.82 80.68 Q28 82.2 25.18 80.68 L-10 61.74 Z"
PIN_ARCH = "M2 85 V38 C2 21 14 13 28 13 C42 13 54 21 54 38 V85 H44 V38 C44 23.5 38 15 28 15 C18 15 12 23.5 12 38 V85 Z"
PIN_BODY = rect(9, 40, 38, 45)
PIN_BAR = rect(9, 38.5, 38, 3.5)
KEYHOLE = circle(28, 50, 3.4) + " M26.5 52.5 L25 60 H31 L29.5 52.5 Z"
SHADOW = ellipse(28, 84.6, 8, 1.6)
# The small-size (favicon) lock-pin: bolder, no keyhole. For 32 px and below.
SMALL_ARCH = "M2 85 V38 C2 20 14 12 28 12 C42 12 54 20 54 38 V85 H42 V38 C42 25 36 19 28 19 C20 19 14 25 14 38 V85 Z"
SMALL_BODY = rect(11, 42, 34, 43)
# Oversized A (unchanged in the pack): straight legs running off the bottom.
OVER_A = ("M6 90 L6 38 C6 21 16 13 28 13 C40 13 50 21 50 38 L50 90 L40 90 L40 38 "
          "C40 23.5 35.8 15 28 15 C20.2 15 16 23.5 16 38 L16 90 Z")

def place(x, y, w, h, vb):
    """An inner <svg x y width height viewBox> (preserveAspectRatio meet): scale and translation."""
    vx, vy, vw, vh = vb
    s = min(w / vw, h / vh)
    return s, x + (w - vw * s) / 2 - vx * s, y + (h - vh * s) / 2 - vy * s

def pin(fg, shadow):
    return [("path", SHADOW, shadow, 0.22),
            ("clip", PIN_CLIP, [("path", PIN_BODY, INDIGO, 1), ("path", PIN_ARCH, fg, 1), ("path", PIN_BAR, fg, 1)]),
            ("path", KEYHOLE, INK if fg == INK else fg, 1)]

# id, label, background, placement, layers. Layers: ("path", d, colour, alpha) or ("clip", d, [layers]).
VARIANTS = [
    ("white", "Dark on white", WHITE, place(18, 12, 64, 75, (-6, 7, 68, 80)), pin(INK, INK)),
    ("twotone", "Two-tone", INK, place(18, 12, 64, 75, (-6, 7, 68, 80)), pin(WHITE, WHITE)),
    # The body runs off the bottom with the legs (the brand file stops it out of view, at 42 tall).
    ("oversized", "Oversized A", INDIGO, place(4, 14, 92, 92, (-6, 7.5, 68, 68)),
     [("path", rect(13, 40, 30, 50), INK, 1), ("path", OVER_A, WHITE, 1), ("path", rect(13, 38.5, 30, 3.5), WHITE, 1),
      ("path", KEYHOLE, WHITE, 1)]),
]
SMALL = [("clip", PIN_CLIP, [("path", SMALL_BODY, "#000000", 1), ("path", SMALL_ARCH, "#000000", 1)])]
SMALL_PLACE = place(14, 14, 72, 72, (-11, 6, 78, 78))  # within the favicon's 100-unit square

def f(v): return f"{v:.4f}".rstrip("0").rstrip(".")
def write(path, text):
    full = os.path.join(repo, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w") as fh: fh.write(text)

HEADER = "<!-- Generated from the OPAQ brand pack by tools/generate-app-icons.py; change the script, not this file. -->\n"
NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'

def xml_layers(layers, indent, colour=None):
    out = []
    for layer in layers:
        if layer[0] == "clip":
            out.append(f"{indent}<group>\n{indent}    <clip-path android:pathData=\"{layer[1]}\" />\n")
            out += xml_layers(layer[2], indent + "    ", colour)
            out.append(f"{indent}</group>\n")
        else:
            _, d, c, a = layer
            alpha = f' android:fillAlpha="{a}"' if a != 1 else ""
            out.append(f'{indent}<path android:fillColor="{colour or c}"{alpha} android:pathData="{d}" />\n')
    return out

def vector(width, viewport, outer, glyph, layers, colour=None):
    """outer: (translate, scale) for the 100-unit square; glyph: (scale, tx, ty) within it."""
    s, tx, ty = glyph
    out = [f'<?xml version="1.0" encoding="utf-8"?>\n{HEADER}<vector {NS}\n    android:width="{width}dp" android:height="{width}dp"\n'
           f'    android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">\n']
    ind = "    "
    if outer:
        out.append(f'{ind}<group android:translateX="{f(outer[0])}" android:translateY="{f(outer[0])}" android:scaleX="{f(outer[1])}" android:scaleY="{f(outer[1])}">\n')
        ind += "    "
    out.append(f'{ind}<group android:translateX="{f(tx)}" android:translateY="{f(ty)}" android:scaleX="{f(s)}" android:scaleY="{f(s)}">\n')
    out += xml_layers(layers, ind + "    ", colour)
    out.append(f"{ind}</group>\n")
    if outer: out.append("    </group>\n")
    out.append("</vector>\n")
    return "".join(out)

# Adaptive icons: 108 dp canvas, of which the middle 72 dp shows. The 100-unit square masters
# map onto those 72 dp, so the art sits where the brand files put it.
for vid, label, bg, glyph, layers in VARIANTS:
    write(f"androidApp/src/main/res/drawable/ic_launcher_fg_{vid}.xml", vector(108, 108, (18, 0.72), glyph, layers))
    write(f"androidApp/src/main/res/mipmap-anydpi-v26/ic_launcher_{vid}.xml",
          f'<?xml version="1.0" encoding="utf-8"?>\n{HEADER}<adaptive-icon {NS}>\n'
          f'    <background android:drawable="@color/ic_launcher_bg_{vid}" />\n'
          f'    <foreground android:drawable="@drawable/ic_launcher_fg_{vid}" />\n'
          f'    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />\n</adaptive-icon>\n')

write("androidApp/src/main/res/values/ic_launcher_colors.xml",
      f'<?xml version="1.0" encoding="utf-8"?>\n{HEADER}<resources>\n' +
      "".join(f'    <color name="ic_launcher_bg_{v[0]}">{v[2]}</color>\n' for v in VARIANTS) + "</resources>\n")

# Themed (monochrome) icon: the small lock-pin, one colour, the size of the main icon's A.
write("androidApp/src/main/res/drawable/ic_launcher_monochrome.xml",
      vector(108, 108, (18, 0.72), place(18, 12, 64, 75, (-6, 7, 68, 80)), SMALL, "#FF000000"))
# Notification (24 dp): the small lock-pin's box (x 2..54, y 12..~82) fitted to 20 dp and centred.
ns = 20 / 70
write("androidApp/src/main/res/drawable/ic_notification.xml",
      vector(24, 24, None, (ns, 12 - 28 * ns, 2 - 12 * ns), SMALL, "#FFFFFFFF"))

# Compose: the same icons, for the picker in Settings.
def kcolor(c): return f"Color(0xFF{c[1:]})"
def kname(vid): return "TwoTone" if vid == "twotone" else vid.capitalize()
def k_layers(layers, indent):
    out = []
    for layer in layers:
        if layer[0] == "clip":
            out.append(f'{indent}addGroup(clipPathData = addPathNodes("{layer[1]}"))\n')
            out += k_layers(layer[2], indent)
            out.append(f"{indent}clearGroup()\n")
        else:
            _, d, c, a = layer
            alpha = f", fillAlpha = {a}f" if a != 1 else ""
            out.append(f'{indent}addPath(addPathNodes("{d}"), fill = SolidColor({kcolor(c)}){alpha})\n')
    return out

k = ['''package app.opaq.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Generated from the OPAQ brand pack by tools/generate-app-icons.py, like the Android icon resources.

/** The app icons someone can pick in Settings → Appearance. [id] is what the platform stores. */
enum class AppIconChoice(val id: String, val label: String) {
''']
k += [f'    {kname(v[0])}("{v[0]}", "{v[1]}"),\n' for v in VARIANTS]
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
k += [f"    AppIconChoice.{kname(v[0])} -> {v[0]}Art\n" for v in VARIANTS]
k.append("}\n")
for vid, label, bg, (s, tx, ty), layers in VARIANTS:
    k.append(f'''
private val {vid}Art: ImageVector by lazy {{
    ImageVector.Builder("{label}", 48.dp, 48.dp, 100f, 100f).apply {{
        addPath(addPathNodes("{rect(0, 0, 100, 100)}"), fill = SolidColor({kcolor(bg)}))
        addGroup(scaleX = {f(s)}f, scaleY = {f(s)}f, translationX = {f(tx)}f, translationY = {f(ty)}f)
''')
    k += k_layers(layers, "        ")
    k.append("        clearGroup()\n    }.build()\n}\n")
write("ui/src/commonMain/kotlin/app/opaq/ui/AppIconArt.kt", "".join(k))
print("ok")
