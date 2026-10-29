# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""
Downloads the Tabler icons (https://tabler.io/icons, MIT licence) that Curie uses and converts them
to Android vector drawables: res/drawable/tabler_<name>.xml for an outline icon and
res/drawable/tabler_<name>_filled.xml for a filled one.

Outline icons are 2 px strokes with round caps and joins; filled icons are solid shapes. Every path
is drawn in opaque black so that Icon(tint = ...) colours it. The SVGs may only contain <path>
elements: anything else fails the run instead of silently producing a wrong drawable. Drawables of
icons no longer listed here are deleted, so the lists below are the single source of truth.

Usage:
    uv run tools/icons/generate-tabler-icons.py [--output app/src/main/res/drawable]
"""

import argparse
import logging
import pathlib
import re
import sys
import urllib.request
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass

logger = logging.getLogger(__name__)

TABLER_VERSION = "3.50.0"
ICON_URL = "https://unpkg.com/@tabler/icons@{version}/icons/{style}/{name}.svg"

OUTLINE_ICONS = (
    "adjustments",
    "alarm",
    "alert-triangle",
    "apps",
    "arrow-left",
    "bulb",
    "calendar-event",
    "camera",
    "check",
    "chevron-right",
    "external-link",
    "folder-open",
    "hourglass",
    "lock",
    "map-pin",
    "menu-2",
    "message",
    "microphone",
    "microphone-off",
    "music",
    "note",
    "paperclip",
    "pencil",
    "phone",
    "phone-call",
    "phone-off",
    "photo",
    "player-stop",
    "plus",
    "refresh",
    "search",
    "send",
    "settings",
    "tool",
    "trash",
    "volume",
    "world",
    "x",
)

FILLED_ICONS = (
    "alert-circle",
    "caret-down",
    "caret-left",
    "caret-right",
    "caret-up",
    "circle-check",
    "player-pause",
    "player-play",
    "player-stop",
    "sparkles",
    "user",
)

# Icons that point along the reading direction: mirrored in right-to-left layouts
AUTO_MIRRORED_ICONS = {
    "arrow-left",
    "caret-left",
    "caret-right",
    "chevron-right",
    "send",
}

SVG_NAMESPACE = "{http://www.w3.org/2000/svg}"
BOUNDING_BOX_PATH = "M0 0h24v24H0z"
OPAQUE_BLACK = "#FF000000"
TRANSPARENT = "#00000000"

# Arguments per command; the arc's large-arc and sweep flags (arguments 3 and 4) may be written
# without separators ("a1 1 0 011 1"), so they are scanned as single characters.
COMMAND_ARGUMENT_COUNTS = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}
ARC_FLAG_INDICES = (3, 4)
NUMBER_PATTERN = re.compile(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")
SEPARATOR_PATTERN = re.compile(r"[\s,]*")

PATH_ATTRIBUTES = {"d", "stroke", "fill", "stroke-width", "fill-rule", "clip-rule"}
PAINT_VALUES = {"none", "currentColor"}
FILL_RULES = {"nonzero": "nonZero", "evenodd": "evenOdd"}


class IconError(Exception):
    """An icon that cannot be converted faithfully."""


@dataclass(frozen=True)
class IconStyle:
    """How Tabler draws one family of icons, as declared on the root <svg> element."""

    directory: str
    file_suffix: str
    root_attributes: dict[str, str]


OUTLINE = IconStyle(
    directory="outline",
    file_suffix="",
    root_attributes={
        "fill": "none",
        "stroke": "currentColor",
        "stroke-width": "2",
        "stroke-linecap": "round",
        "stroke-linejoin": "round",
    },
)

FILLED = IconStyle(
    directory="filled",
    file_suffix="_filled",
    root_attributes={"fill": "currentColor"},
)


@dataclass(frozen=True)
class VectorPath:
    path_data: str
    stroke_width: str | None
    filled: bool
    fill_type: str | None


def normalize_path_data(path_data: str) -> str:
    """Re-serializes SVG path data with explicit commands and separators, validating it on the way.

    Android's path parser accepts SVG syntax, but older versions misread compact arc flags and
    implicit command repeats; writing every command and argument out avoids both.
    """
    commands: list[str] = []
    position = 0
    command = None

    def skip_separators() -> None:
        nonlocal position
        position = SEPARATOR_PATTERN.match(path_data, position).end()

    skip_separators()
    while position < len(path_data):
        character = path_data[position]
        if character.upper() in COMMAND_ARGUMENT_COUNTS:
            command = character
            position += 1
        elif command is None:
            raise IconError(f"path data does not start with a command: {path_data!r}")
        elif command.upper() == "Z":
            raise IconError(f"arguments after a close-path command: {path_data!r}")
        elif command.upper() == "M":
            # Extra coordinate pairs after a moveto are implicit linetos
            command = "l" if command == "m" else "L"

        argument_count = COMMAND_ARGUMENT_COUNTS[command.upper()]
        arguments: list[str] = []
        for index in range(argument_count):
            skip_separators()
            if command.upper() == "A" and index in ARC_FLAG_INDICES:
                flag = path_data[position : position + 1]
                if flag not in ("0", "1"):
                    raise IconError(f"bad arc flag at {position} in {path_data!r}")
                arguments.append(flag)
                position += 1
                continue

            number = NUMBER_PATTERN.match(path_data, position)
            if number is None:
                raise IconError(f"expected a number at {position} in {path_data!r}")
            arguments.append(number.group())
            position = number.end()

        commands.append(" ".join([command, *arguments]))
        skip_separators()

    if not commands:
        raise IconError("empty path data")

    return " ".join(commands)


def read_paths(svg_text: str, style: IconStyle, name: str) -> list[VectorPath]:
    root = ElementTree.fromstring(svg_text)
    if root.tag != f"{SVG_NAMESPACE}svg":
        raise IconError(f"{name}: root element is {root.tag}, not <svg>")

    if root.get("viewBox") != "0 0 24 24":
        raise IconError(f"{name}: unexpected viewBox {root.get('viewBox')!r}")

    for attribute, expected in style.root_attributes.items():
        actual = root.get(attribute)
        if actual != expected:
            raise IconError(f"{name}: root {attribute}={actual!r}, expected {expected!r}")

    paths: list[VectorPath] = []
    for element in root:
        if element.tag != f"{SVG_NAMESPACE}path":
            raise IconError(f"{name}: unsupported element {element.tag}; only <path> is converted")

        unknown_attributes = set(element.attrib) - PATH_ATTRIBUTES
        if unknown_attributes:
            raise IconError(f"{name}: unsupported path attributes {sorted(unknown_attributes)}")

        stroke = element.get("stroke", root.get("stroke", "none"))
        fill = element.get("fill", root.get("fill", "none"))
        for paint in (stroke, fill):
            if paint not in PAINT_VALUES:
                raise IconError(f"{name}: unsupported paint {paint!r}")

        path_data = element.get("d")
        if path_data is None:
            raise IconError(f"{name}: <path> without data")

        if stroke == "none" and fill == "none":
            if path_data != BOUNDING_BOX_PATH:
                raise IconError(f"{name}: invisible path that is not the bounding box: {path_data!r}")
            continue

        fill_rule = element.get("fill-rule")
        if fill_rule is not None and fill_rule not in FILL_RULES:
            raise IconError(f"{name}: unsupported fill-rule {fill_rule!r}")

        stroke_width = None
        if stroke == "currentColor":
            stroke_width = element.get("stroke-width", root.get("stroke-width"))
            if stroke_width is None or NUMBER_PATTERN.fullmatch(stroke_width) is None:
                raise IconError(f"{name}: bad stroke width {stroke_width!r}")

        vector_path = VectorPath(
            path_data=normalize_path_data(path_data),
            stroke_width=stroke_width,
            filled=fill == "currentColor",
            fill_type=FILL_RULES.get(fill_rule) if fill_rule else None,
        )
        paths.append(vector_path)

    if not paths:
        raise IconError(f"{name}: no visible paths")

    return paths


def render_vector_drawable(paths: list[VectorPath], style: IconStyle, name: str) -> str:
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        f"<!-- Tabler icon {style.directory}/{name} (@tabler/icons {TABLER_VERSION}, MIT licence).",
        "     Generated by tools/icons/generate-tabler-icons.py: do not edit. -->",
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="24dp"',
        '    android:height="24dp"',
        '    android:viewportWidth="24"',
        '    android:viewportHeight="24"',
    ]
    if name in AUTO_MIRRORED_ICONS:
        lines.append('    android:autoMirrored="true"')
    lines[-1] += ">"

    for path in paths:
        lines.append("    <path")
        lines.append(f'        android:pathData="{path.path_data}"')

        fill_color = OPAQUE_BLACK if path.filled else TRANSPARENT
        lines.append(f'        android:fillColor="{fill_color}"')
        if path.fill_type is not None:
            lines.append(f'        android:fillType="{path.fill_type}"')

        if path.stroke_width is not None:
            lines.append(f'        android:strokeColor="{OPAQUE_BLACK}"')
            lines.append(f'        android:strokeWidth="{path.stroke_width}"')
            lines.append('        android:strokeLineCap="round"')
            lines.append('        android:strokeLineJoin="round"')

        lines[-1] += " />"

    lines.append("</vector>")
    return "\n".join(lines) + "\n"


def drawable_name(name: str, style: IconStyle) -> str:
    return f"tabler_{name.replace('-', '_')}{style.file_suffix}.xml"


def download_svg(name: str, style: IconStyle) -> str:
    url = ICON_URL.format(version=TABLER_VERSION, style=style.directory, name=name)
    with urllib.request.urlopen(url, timeout=30) as response:
        return response.read().decode("utf-8")


def generate(output_directory: pathlib.Path) -> None:
    output_directory.mkdir(parents=True, exist_ok=True)
    icons = [(name, OUTLINE) for name in OUTLINE_ICONS] + [(name, FILLED) for name in FILLED_ICONS]

    written: set[str] = set()
    for name, style in icons:
        svg_text = download_svg(name, style)
        paths = read_paths(svg_text, style, name)
        drawable = render_vector_drawable(paths, style, name)

        file_name = drawable_name(name, style)
        (output_directory / file_name).write_text(drawable, encoding="utf-8")
        written.add(file_name)
        logger.info("wrote %s", file_name)

    for stale in sorted(output_directory.glob("tabler_*.xml")):
        if stale.name not in written:
            stale.unlink()
            logger.info("deleted %s (no longer listed)", stale.name)

    logger.info("%d icons from @tabler/icons %s", len(written), TABLER_VERSION)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--output",
        type=pathlib.Path,
        default=pathlib.Path("app/src/main/res/drawable"),
        help="the drawable resource folder (default: %(default)s)",
    )
    arguments = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(message)s")
    try:
        generate(arguments.output)
    except IconError as error:
        logger.error("error: %s", error)
        sys.exit(1)


if __name__ == "__main__":
    main()
