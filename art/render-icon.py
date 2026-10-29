# /// script
# requires-python = ">=3.11"
# dependencies = [
#     "pillow>=11",
# ]
# ///
"""
Renders Curie's launcher icon and splash screen from the app's resources to PNG.

The adaptive icon (app/src/main/res/mipmap-anydpi/ic_launcher.xml) and the splash screen theme
(Theme.Curie.Starting) are read from the resources and converted to SVG, then rasterized with
rsvg-convert (librsvg: `brew install librsvg` or `apt install librsvg2-bin`):

    docs/icon-512.png    the Google Play store icon: the 72dp visible area as a full square
    --preview-dir DIR    also writes previews: the icon under circle, squircle and rounded square
                         masks, the themed (monochrome) icon in light and dark mode, the splash screen

Usage:
    uv run art/render-icon.py [--preview-dir build/icon-previews]
"""

import argparse
import math
import pathlib
import subprocess
import tempfile
import xml.etree.ElementTree as ElementTree

from PIL import Image

REPO_DIR = pathlib.Path(__file__).resolve().parent.parent
RES_DIR = REPO_DIR / "app/src/main/res"
ADAPTIVE_ICON = RES_DIR / "mipmap-anydpi/ic_launcher.xml"
SPLASH_THEME = "Theme.Curie.Starting"
STORE_ICON = REPO_DIR / "docs/icon-512.png"

ANDROID = "{http://schemas.android.com/apk/res/android}"
AAPT = "{http://schemas.android.com/aapt}"

# Adaptive icon layers are 108dp squares; launchers mask the 72dp in the middle
CANVAS_DP = 108
VISIBLE_DP = 72
VISIBLE_INSET_DP = (CANVAS_DP - VISIBLE_DP) / 2

# The splash screen draws its icon at 288dp, masked to a circle 2 / 3 as wide, like a launcher
SPLASH_ICON_DP = 288
SPLASH_WINDOW_DP = 360

STORE_ICON_PX = 512
PREVIEW_PX = 432

# Tonal colors like the ones Android 13+ derives from the wallpaper for themed icons
THEMED_COLORS = {
    "light": ("#D3E3FD", "#041E49"),
    "dark": ("#004A77", "#C2E7FF"),
}


def android_attribute(element: ElementTree.Element, name: str, default: str | None = None) -> str | None:
    return element.get(ANDROID + name, default)


def svg_color(android_color: str) -> tuple[str, float]:
    """#RGB, #ARGB, #RRGGBB or #AARRGGBB -> (#RRGGBB, opacity)."""
    digits = android_color.removeprefix("#")
    if len(digits) in (3, 4):
        digits = "".join(digit * 2 for digit in digits)
    if len(digits) == 6:
        digits = "FF" + digits
    if len(digits) != 8:
        raise ValueError(f"not a color: {android_color}")

    alpha = int(digits[:2], 16) / 255
    return f"#{digits[2:]}", alpha


def values_elements(tag: str) -> list[ElementTree.Element]:
    """Every <tag> element of res/values/*.xml."""
    elements = []
    for values_file in sorted((RES_DIR / "values").glob("*.xml")):
        root = ElementTree.parse(values_file).getroot()
        elements.extend(root.iter(tag))
    return elements


def color_value(reference: str) -> str:
    """@color/name, looked up in res/values, or a literal color."""
    if not reference.startswith("@color/"):
        return reference

    name = reference.removeprefix("@color/")
    for color in values_elements("color"):
        if color.get("name") == name:
            return color.text.strip()
    raise ValueError(f"{reference} is not defined in res/values")


def theme_item(style_name: str, item_name: str) -> str:
    for style in values_elements("style"):
        if style.get("name") != style_name:
            continue
        for item in style.iter("item"):
            if item.get("name") == item_name:
                return item.text.strip()
    raise ValueError(f"{style_name} does not set {item_name}")


def drawable_file(reference: str) -> pathlib.Path:
    """@drawable/name -> res/drawable/name.xml"""
    name = reference.removeprefix("@drawable/")
    return RES_DIR / "drawable" / f"{name}.xml"


def gradient_svg(gradient: ElementTree.Element, gradient_id: str) -> str:
    """An inline <gradient> fill (in viewport coordinates) as an SVG gradient."""
    gradient_type = android_attribute(gradient, "type", "linear")
    if gradient_type != "radial":
        raise NotImplementedError(f"{gradient_type} gradients are not supported yet")

    stops = []
    for item in gradient.iter("item"):
        color, opacity = svg_color(android_attribute(item, "color"))
        offset = android_attribute(item, "offset")
        stops.append(f'<stop offset="{offset}" stop-color="{color}" stop-opacity="{opacity:.4f}"/>')
    if len(stops) < 2:
        raise NotImplementedError("gradients need at least two <item> stops")

    centre_x = android_attribute(gradient, "centerX")
    centre_y = android_attribute(gradient, "centerY")
    radius = android_attribute(gradient, "gradientRadius")
    attributes = f'id="{gradient_id}" gradientUnits="userSpaceOnUse" cx="{centre_x}" cy="{centre_y}" r="{radius}"'
    return f"<radialGradient {attributes}>{''.join(stops)}</radialGradient>"


def path_svg(path: ElementTree.Element, gradient_id: str) -> tuple[str, str]:
    """A <path> as (SVG definitions, SVG element)."""
    if android_attribute(path, "strokeColor") is not None:
        raise NotImplementedError("stroked paths are not supported yet")

    definitions = ""
    fill_attribute = path.find(f"{AAPT}attr[@name='android:fillColor']")
    if fill_attribute is not None:
        definitions = gradient_svg(fill_attribute.find("gradient"), gradient_id)
        fill = f"url(#{gradient_id})"
        opacity = 1.0
    else:
        fill, opacity = svg_color(android_attribute(path, "fillColor", "#00000000"))

    fill_alpha = float(android_attribute(path, "fillAlpha", "1"))
    fill_rule = "evenodd" if android_attribute(path, "fillType") == "evenOdd" else "nonzero"
    data = android_attribute(path, "pathData")
    element = f'<path d="{data}" fill="{fill}" fill-opacity="{opacity * fill_alpha:.4f}" fill-rule="{fill_rule}"/>'
    return definitions, element


def vector_svg(drawable: pathlib.Path) -> tuple[str, str]:
    """A vector drawable as (SVG definitions, SVG group) on the 108dp canvas."""
    vector = ElementTree.parse(drawable).getroot()
    if vector.tag != "vector":
        raise NotImplementedError(f"{drawable.name}: only <vector> drawables are supported")

    unsupported = [child.tag for child in vector if child.tag != "path"]
    if unsupported:
        raise NotImplementedError(f"{drawable.name}: unsupported elements {unsupported}")

    scale_x = CANVAS_DP / float(android_attribute(vector, "viewportWidth"))
    scale_y = CANVAS_DP / float(android_attribute(vector, "viewportHeight"))
    definitions = []
    elements = []
    for index, path in enumerate(vector.iter("path")):
        path_definitions, element = path_svg(path, f"{drawable.stem}-{index}")
        definitions.append(path_definitions)
        elements.append(element)

    group = f'<g transform="scale({scale_x} {scale_y})">{"".join(elements)}</g>'
    return "".join(definitions), group


def layer_svg(reference: str) -> tuple[str, str]:
    """An adaptive icon layer (a color or a vector drawable) as (SVG definitions, SVG element)."""
    if reference.startswith("@drawable/"):
        return vector_svg(drawable_file(reference))

    color, opacity = svg_color(color_value(reference))
    rectangle = f'<rect width="{CANVAS_DP}" height="{CANVAS_DP}" fill="{color}" fill-opacity="{opacity:.4f}"/>'
    return "", rectangle


def adaptive_icon_layers() -> dict[str, tuple[str, str]]:
    """background, foreground and monochrome of the launcher icon as (SVG definitions, SVG element)."""
    icon = ElementTree.parse(ADAPTIVE_ICON).getroot()
    layers = {}
    for layer in icon:
        reference = android_attribute(layer, "drawable")
        layers[layer.tag] = layer_svg(reference)
    return layers


def squircle_points(radius: float, exponent: float = 4.0, steps: int = 360) -> str:
    """A superellipse |x|^n + |y|^n = r^n around the canvas centre, as SVG polygon points."""
    centre = CANVAS_DP / 2
    points = []
    for step in range(steps):
        angle = 2 * math.pi * step / steps
        cosine = math.cos(angle)
        sine = math.sin(angle)
        x = centre + radius * math.copysign(abs(cosine) ** (2 / exponent), cosine)
        y = centre + radius * math.copysign(abs(sine) ** (2 / exponent), sine)
        points.append(f"{x:.3f},{y:.3f}")
    return " ".join(points)


def launcher_masks() -> dict[str, str]:
    """Common launcher mask shapes over the 72dp visible area, as SVG clip path contents."""
    centre = CANVAS_DP / 2
    radius = VISIBLE_DP / 2
    corner = VISIBLE_DP * 0.16
    rounded_square = (
        f'<rect x="{VISIBLE_INSET_DP}" y="{VISIBLE_INSET_DP}" '
        f'width="{VISIBLE_DP}" height="{VISIBLE_DP}" rx="{corner}"/>'
    )
    return {
        "circle": f'<circle cx="{centre}" cy="{centre}" r="{radius}"/>',
        "squircle": f'<polygon points="{squircle_points(radius)}"/>',
        "rounded-square": rounded_square,
    }


def svg_document(definitions: str, body: str, view_box: str) -> str:
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{view_box}"><defs>{definitions}</defs>{body}</svg>'


def rasterize(svg: str, size: int, output: pathlib.Path) -> None:
    """Renders svg to a size x size PNG with an alpha channel, the 32-bit PNG Google Play asks for."""
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as work_dir:
        svg_path = pathlib.Path(work_dir) / "image.svg"
        png_path = pathlib.Path(work_dir) / "image.png"
        svg_path.write_text(svg)
        command = [
            "rsvg-convert",
            "--width", str(size),
            "--height", str(size),
            "--output", str(png_path),
            str(svg_path),
        ]
        subprocess.run(command, check=True)

        # rsvg-convert leaves the alpha channel out of opaque images
        with Image.open(png_path) as image:
            rgba_image = image.convert("RGBA")
        rgba_image.save(output)
    print(f"Wrote {output}")


def render_store_icon(layers: dict[str, tuple[str, str]], output: pathlib.Path) -> None:
    background_definitions, background = layers["background"]
    foreground_definitions, foreground = layers["foreground"]
    view_box = f"{VISIBLE_INSET_DP} {VISIBLE_INSET_DP} {VISIBLE_DP} {VISIBLE_DP}"

    svg = svg_document(background_definitions + foreground_definitions, background + foreground, view_box)
    rasterize(svg, STORE_ICON_PX, output)


def render_masked_icons(layers: dict[str, tuple[str, str]], preview_dir: pathlib.Path) -> None:
    background_definitions, background = layers["background"]
    foreground_definitions, foreground = layers["foreground"]
    canvas = f"0 0 {CANVAS_DP} {CANVAS_DP}"

    for mask_name, mask_shape in launcher_masks().items():
        definitions = f'{background_definitions}{foreground_definitions}<clipPath id="mask">{mask_shape}</clipPath>'
        body = f'<g clip-path="url(#mask)">{background}{foreground}</g>'

        svg = svg_document(definitions, body, canvas)
        rasterize(svg, PREVIEW_PX, preview_dir / f"launcher-{mask_name}.png")


def render_themed_icons(layers: dict[str, tuple[str, str]], preview_dir: pathlib.Path) -> None:
    """Launchers tint the monochrome layer (SRC_IN) and draw it over a tonal background."""
    monochrome_definitions, monochrome = layers["monochrome"]
    circle = launcher_masks()["circle"]
    canvas = f"0 0 {CANVAS_DP} {CANVAS_DP}"

    for mode, (background_color, tint) in THEMED_COLORS.items():
        tint_filter = (
            f'<filter id="tint" filterUnits="userSpaceOnUse" x="0" y="0" width="{CANVAS_DP}" height="{CANVAS_DP}">'
            f'<feFlood flood-color="{tint}"/><feComposite in2="SourceGraphic" operator="in"/></filter>'
        )
        definitions = f'{monochrome_definitions}{tint_filter}<clipPath id="mask">{circle}</clipPath>'
        themed_background = f'<rect width="{CANVAS_DP}" height="{CANVAS_DP}" fill="{background_color}"/>'
        body = f'<g clip-path="url(#mask)">{themed_background}<g filter="url(#tint)">{monochrome}</g></g>'

        svg = svg_document(definitions, body, canvas)
        rasterize(svg, PREVIEW_PX, preview_dir / f"themed-{mode}.png")


def render_splash_screen(preview_dir: pathlib.Path) -> None:
    """The splash icon of Theme.Curie.Starting on its background, masked as Android 12+ does."""
    background_reference = theme_item(SPLASH_THEME, "windowSplashScreenBackground")
    background_color, background_opacity = svg_color(color_value(background_reference))
    icon_reference = theme_item(SPLASH_THEME, "windowSplashScreenAnimatedIcon")
    icon_definitions, icon = vector_svg(drawable_file(icon_reference))

    centre = SPLASH_WINDOW_DP / 2
    offset = (SPLASH_WINDOW_DP - SPLASH_ICON_DP) / 2
    scale = SPLASH_ICON_DP / CANVAS_DP
    mask = f'<circle cx="{centre}" cy="{centre}" r="{SPLASH_ICON_DP / 3}"/>'
    definitions = f'{icon_definitions}<clipPath id="mask">{mask}</clipPath>'
    window = (
        f'<rect width="{SPLASH_WINDOW_DP}" height="{SPLASH_WINDOW_DP}" fill="{background_color}" '
        f'fill-opacity="{background_opacity:.4f}"/>'
    )
    placed_icon = f'<g clip-path="url(#mask)"><g transform="translate({offset} {offset}) scale({scale})">{icon}</g></g>'

    svg = svg_document(definitions, window + placed_icon, f"0 0 {SPLASH_WINDOW_DP} {SPLASH_WINDOW_DP}")
    rasterize(svg, PREVIEW_PX, preview_dir / "splash.png")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--output", type=pathlib.Path, default=STORE_ICON, help="Where the store icon is written")
    parser.add_argument("--preview-dir", type=pathlib.Path, help="Also write previews here")
    args = parser.parse_args()

    layers = adaptive_icon_layers()
    render_store_icon(layers, args.output.resolve())

    if args.preview_dir is not None:
        preview_dir = args.preview_dir.resolve()
        render_masked_icons(layers, preview_dir)
        render_themed_icons(layers, preview_dir)
        render_splash_screen(preview_dir)


if __name__ == "__main__":
    main()
