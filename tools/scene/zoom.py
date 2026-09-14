#!/usr/bin/env python3
"""Crops part of a scene picture and scales it up, so one subject can be looked at closely.

A scene photographs the whole window, and what is being checked is often a few dozen pixels of it:
the tint on one item, the hat on one villager. Nearest-neighbour scaling keeps a texture's pixels
square instead of smearing them, which is what the checking is about.

    python3 tools/scene/zoom.py build/scene/26.2-items.png --slice 2/11
    python3 tools/scene/zoom.py build/scene/26.2-items.png --box 350 380 500 520 --out /tmp/one.png

`--slice i/n` cuts the picture into n columns and takes the ith, counting from one, which matches the
row a scene places its subjects in. The result lands beside the source as `<name>-zoom.png` unless
`--out` says otherwise.
"""

import argparse
import pathlib
import sys

from PIL import Image

# Wide enough to read a 16x16 texture's pixels, and small enough to look at in one go.
READABLE_WIDTH = 900


def box(picture: Image.Image, arguments: argparse.Namespace) -> tuple[int, int, int, int]:
    """The rectangle to cut out, from whichever way of naming one was given."""
    if arguments.box:
        return tuple(arguments.box)

    index, count = (int(part) for part in arguments.slice.split("/"))
    if not 1 <= index <= count:
        raise SystemExit(f"--slice {arguments.slice} asks for a column that is not there")

    width = picture.width / count
    return (round((index - 1) * width), 0, round(index * width), picture.height)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("picture", type=pathlib.Path)
    parser.add_argument("--box", nargs=4, type=int, metavar=("LEFT", "TOP", "RIGHT", "BOTTOM"))
    parser.add_argument("--slice", help="one column of many, as i/n counting from one")
    parser.add_argument("--out", type=pathlib.Path, help="where to write it")
    parser.add_argument("--width", type=int, default=READABLE_WIDTH, help="how wide to scale it")
    arguments = parser.parse_args()

    if not arguments.box and not arguments.slice:
        print("give either --box or --slice", file=sys.stderr)
        return 2

    with Image.open(arguments.picture) as picture:
        cut = picture.convert("RGB").crop(box(picture, arguments))

    if cut.width < 1 or cut.height < 1:
        print("that rectangle is empty", file=sys.stderr)
        return 2

    scale = max(1, round(arguments.width / cut.width))
    scaled = cut.resize((cut.width * scale, cut.height * scale), Image.Resampling.NEAREST)

    out = arguments.out or arguments.picture.with_name(arguments.picture.stem + "-zoom.png")
    scaled.save(out)
    print(f"{out} {scaled.width}x{scaled.height}, {scale}x of {cut.width}x{cut.height}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
