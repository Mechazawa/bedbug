"""Pixel-art textures for the Bedbug showcase add-on, painted from nothing.

Every texture is a hard-pixel RGBA image: no anti-aliasing, light from the top left,
deterministic noise from a hash of the coordinates.
"""
import io
import math
import sys
from functools import cache
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "testpack"))
from build import png  # noqa: E402

Colour = tuple[int, int, int, int]
CLEAR: Colour = (0, 0, 0, 0)

ROSE = (196, 116, 128)
CREAM = (240, 228, 198)
TEAL = (60, 142, 146)
MUSTARD = (216, 166, 50)


def noise(x: int, y: int, salt: int = 0) -> float:
    """Deterministic value in [0, 1] from a coordinate hash."""
    value = (x * 374761393 + y * 668265263 + salt * 2147483647) & 0xFFFFFFFF
    value = ((value ^ (value >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((value ^ (value >> 16)) & 0xFF) / 255.0


def shade(colour: tuple[int, ...], amount: float, alpha: int = 255) -> Colour:
    red, green, blue = (max(0, min(255, round(channel + amount))) for channel in colour[:3])
    return (red, green, blue, alpha)


class Canvas:
    def __init__(self, width: int, height: int):
        self.width = width
        self.height = height
        self.pixels: list[list[Colour]] = [[CLEAR] * width for _ in range(height)]

    def get(self, x: int, y: int) -> Colour:
        if 0 <= x < self.width and 0 <= y < self.height:
            return self.pixels[y][x]
        return CLEAR

    def put(self, x: int, y: int, colour: Colour) -> None:
        if 0 <= x < self.width and 0 <= y < self.height:
            self.pixels[y][x] = colour

    def stamp(self, rows: list[str], palette: dict[str, Colour], left: int = 0, top: int = 0,
              only_over_opaque: bool = False) -> None:
        for dy, row in enumerate(rows):
            for dx, symbol in enumerate(row):
                if symbol in palette and (not only_over_opaque or self.get(left + dx, top + dy)[3]):
                    self.put(left + dx, top + dy, palette[symbol])

    def outline(self, colour: Colour) -> None:
        marked = [(x, y) for y in range(self.height) for x in range(self.width)
                  if not self.pixels[y][x][3] and any(
                      self.get(x + dx, y + dy)[3] for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))]
        for x, y in marked:
            self.put(x, y, colour)

    def painter(self):
        return lambda x, y: self.pixels[y][x]


# ---------------------------------------------------------------- blocks

def patch_colour(index: int) -> tuple[int, int, int]:
    return (ROSE, CREAM, TEAL, MUSTARD)[index]


def quilt_top(x: int, y: int) -> Colour:
    quadrant = (x >= 8) + 2 * (y >= 8)
    base = patch_colour((0, 1, 2, 3)[quadrant])
    px, py = x % 8, y % 8
    puff = 7 - 2.2 * math.hypot(px - 3.5, py - 3.5)
    amount = puff + (noise(x, y) - 0.5) * 9 + (3 if (x + y) % 2 else -3)
    on_seam_x = x in (7, 8)
    on_seam_y = y in (7, 8)
    if on_seam_x or on_seam_y:
        stitch = (x if on_seam_y and not on_seam_x else y) % 2 == 0
        amount = -52 if stitch else -20
    elif base == CREAM and (x * 3 + y * 5) % 7 == 0 and 0 < px < 7 and 0 < py < 7:
        return shade((214, 138, 120), noise(x, y, 3) * 10)
    if x == 0 or y == 0:
        amount += 12
    if x == 15 or y == 15:
        amount -= 14
    return shade(base, amount)


def quilt_side(x: int, y: int) -> Colour:
    if y < 4:
        base = patch_colour(x // 4)
        amount = (10, 4, -2, -26)[y] + (noise(x, y) - 0.5) * 9
        if x % 4 == 0 and y < 3:
            amount -= 10
        return shade(base, amount)
    if y == 15:
        return shade((158, 140, 110), (noise(x, y) - 0.5) * 8)
    puff_top = 4 if y < 10 else 10
    puff_rows = 6
    along = (y - puff_top) / (puff_rows - 1)
    across = abs((x % 8) - 3.5) / 3.5
    amount = 12 - 30 * along - 12 * across ** 2 + (noise(x, y) - 0.5) * 8
    if y in (9, 14) and across < 1.1 and x % 8 in (0, 1, 2, 4, 5, 6):
        return shade((178, 156, 118), (noise(x, y, 5) - 0.5) * 8)
    if y == 5 and x % 4 == 1:
        return shade((200, 180, 144), 0)
    if y == 4:
        amount -= 14 if x % 2 == 0 else 4
    return shade(CREAM, amount - 4)


# ----------------------------------------------------------------- items

OUTLINE = (58, 36, 34, 255)
WOOD = {"L": (184, 130, 76, 255), "M": (144, 94, 54, 255), "D": (100, 64, 38, 255)}


@cache
def bug_net() -> Canvas:
    canvas = Canvas(16, 16)
    centre_x, centre_y = 10.5, 5.5
    for y in range(16):
        for x in range(16):
            distance = math.hypot(x - centre_x, y - centre_y)
            if distance <= 3.5:
                line = x % 2 == 0 or y % 2 == 0
                canvas.put(x, y, (255, 255, 255, 190) if line else (236, 244, 250, 24))
            elif distance <= 4.7:
                lit = (x - centre_x) + (y - centre_y) < 0
                canvas.put(x, y, WOOD["L"] if lit and distance < 4.3 else
                           WOOD["D"] if not lit and distance > 4.1 else WOOD["M"])
    for x in range(1, 8):
        canvas.put(x, 14 - x, WOOD["L"])
        canvas.put(x, 15 - x, WOOD["M"] if x > 3 else WOOD["D"])
    for x in (2, 3):
        canvas.put(x, 14 - x, (222, 92, 92, 255))
        canvas.put(x, 15 - x, (168, 56, 64, 255))
    canvas.put(1, 14, WOOD["D"])
    canvas.outline(OUTLINE)
    return canvas


BUN_SHADES = [(132, 74, 30), (170, 98, 36), (204, 132, 52), (230, 164, 76), (246, 196, 112)]
DRIZZLE_ROWS = [
    "......hhaa......",
    "....aahaaaaa....",
    "...aaaaaddaaa...",
    ".....aaa..aaah..",
    "..........aad...",
    "..........ad....",
]
DRIZZLE = {"a": (226, 142, 24, 255), "h": (255, 218, 112, 255), "d": (176, 98, 16, 255)}


@cache
def honey_bun() -> Canvas:
    canvas = Canvas(16, 16)
    for y in range(16):
        for x in range(16):
            nx, ny = (x - 7.5) / 6.7, (y - 9.0) / 5.3
            if nx * nx + ny * ny > 1:
                continue
            light = 0.55 - (nx + ny) * 0.33 + (noise(x, y) - 0.5) * 0.22
            if ny > 0.55:
                light -= 0.22
            index = max(0, min(4, int(light * 5)))
            canvas.put(x, y, (*BUN_SHADES[index], 255))
    for x, y in ((3, 9), (12, 11), (5, 12), (10, 13)):
        canvas.put(x, y, (*BUN_SHADES[0], 255))
    canvas.stamp(DRIZZLE_ROWS, DRIZZLE, top=5, only_over_opaque=True)
    canvas.outline((86, 44, 20, 255))
    return canvas


@cache
def quilt_patch() -> Canvas:
    canvas = Canvas(16, 16)
    angle = 0.0
    cosine, sine = math.cos(angle), math.sin(angle)
    half = 5.9
    for y in range(16):
        for x in range(16):
            dx, dy = x - 7.5, y - 7.5
            u = dx * cosine + dy * sine
            v = -dx * sine + dy * cosine
            edge = half - max(abs(u), abs(v))
            if edge < 0:
                continue
            base = patch_colour((u >= 0) + 2 * (v >= 0))
            amount = 6 - (u + v) * 1.3 + (noise(x, y) - 0.5) * 10
            if edge < 0.9:
                amount -= 26
            elif edge < 1.9:
                stitched = int(u + v + 20) % 2 == 0
                canvas.put(x, y, (250, 242, 220, 255) if stitched else shade(base, amount - 14))
                continue
            if abs(u) < 0.55 or abs(v) < 0.55:
                amount -= 34
            canvas.put(x, y, shade(base, amount))
    for x, y in ((13, 1), (14, 2), (14, 1)):
        canvas.put(x, y, CLEAR)
    for x, y, amount in ((13, 2, 30), (12, 2, 12), (13, 3, 12), (12, 1, -999)):
        if amount > -999:
            canvas.put(x, y, shade(CREAM, amount - 40))
    canvas.outline((72, 40, 48, 255))
    return canvas


JAR_ROWS = [
    "................",
    "......ccCC......",
    ".....ccCCCd.....",
    ".....cCCCCd.....",
    "....gGGGGGGh....",
    "...gGGGGGGGGh...",
    "...gGGGGGGGGh...",
    "...gGGGGGGGGh...",
    "...gGGGGGGGGh...",
    "...gGGGGGGGGh...",
    "...gGGGGGGGGh...",
    "...gGGGGGGGGh...",
    "...gGGGGGGGGh...",
    "...gGGGGGGGGh...",
    "...hhhhhhhhhh...",
    "................",
]
JAR_PALETTE = {
    "c": (206, 156, 96, 255), "C": (170, 120, 68, 255), "d": (122, 82, 46, 255),
    "g": (236, 250, 255, 235), "G": (150, 190, 206, 120), "h": (96, 134, 156, 235),
}
FIREFLIES = ((6, 7), (9, 9), (5, 11), (8, 12), (10, 6), (7, 10))


@cache
def lumen_jar() -> Canvas:
    canvas = Canvas(16, 16)
    canvas.stamp(JAR_ROWS, JAR_PALETTE)
    for x, y in FIREFLIES:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if canvas.get(x + dx, y + dy)[3] and canvas.get(x + dx, y + dy)[:3] == JAR_PALETTE["G"][:3]:
                canvas.put(x + dx, y + dy, (176, 224, 72, 190))
        canvas.put(x, y, (246, 255, 128, 255))
    for y in range(5, 14):
        canvas.put(4, y, (255, 255, 255, 245) if y in (6, 7, 8) else JAR_PALETTE["g"])
    canvas.outline((46, 56, 66, 255))
    return canvas


# ------------------------------------------------------------- particles

PALE = (255, 255, 255, 255)
GOLD = (255, 232, 150, 255)
GOLD_DIM = (255, 214, 110, 235)


def star_frame(size: int) -> Canvas:
    canvas = Canvas(8, 8)
    centre = 3
    canvas.put(centre, centre, PALE)
    for step in range(1, size + 1):
        tip = step == size and size > 1
        colour = GOLD if tip else PALE
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            canvas.put(centre + dx * step, centre + dy * step, colour if step > 1 else PALE)
    if size == 3:
        for dx, dy in ((1, 1), (1, -1), (-1, 1), (-1, -1)):
            canvas.put(centre + dx, centre + dy, GOLD_DIM)
    return canvas


MOTE_ROWS = [
    ".ooo.",
    "oiwio",
    "owwwo",
    "oiwio",
    ".ooo.",
]
MOTE_PALETTE = {"o": (255, 255, 255, 110), "i": (255, 255, 255, 200), "w": (255, 255, 255, 255)}
HEART_ROWS = [
    ".hp.pp.",
    "hhpppps",
    "hpppppS",
    "ppppppS",
    ".pppps.",
    "..ppS..",
    "...S...",
]
HEART_PALETTE = {"h": (255, 190, 210, 255), "p": (248, 120, 160, 255),
                 "s": (214, 84, 130, 255), "S": (186, 62, 108, 255)}
LEAF_ROWS = [
    "....lll",
    "..lLLlD",
    ".lLLvLD",
    ".lLvLDD",
    "lLvLDD.",
    "lvLDD..",
    "v.D....",
]
LEAF_PALETTE = {"l": (150, 220, 96, 255), "L": (96, 184, 72, 255),
                "D": (58, 140, 60, 255), "v": (200, 244, 150, 255)}


@cache
def particle_sheet() -> Canvas:
    sheet = Canvas(64, 64)
    for frame in range(4):
        star = star_frame(frame)
        for y in range(8):
            for x in range(8):
                sheet.put(frame * 8 + x, y, star.get(x, y))
    sheet.stamp(MOTE_ROWS, MOTE_PALETTE, left=1, top=9)
    sheet.stamp(HEART_ROWS, HEART_PALETTE, left=8, top=8)
    sheet.stamp(LEAF_ROWS, LEAF_PALETTE, left=16, top=8)
    return sheet


ICONS = (
    ("textures/blocks/quilt_top.png", 16, quilt_top),
    ("textures/blocks/quilt_side.png", 16, quilt_side),
    ("textures/items/bug_net.png", 16, lambda x, y: bug_net().get(x, y)),
    ("textures/items/honey_bun.png", 16, lambda x, y: honey_bun().get(x, y)),
    ("textures/items/quilt_patch.png", 16, lambda x, y: quilt_patch().get(x, y)),
    ("textures/items/lumen_jar.png", 16, lambda x, y: lumen_jar().get(x, y)),
    ("textures/particle/showcase_particles.png", 64, lambda x, y: particle_sheet().get(x, y)),
)


def preview(destination: str) -> None:
    from PIL import Image, ImageDraw

    scale, pad, label_height = 16, 16, 14
    images = [(path, Image.open(io.BytesIO(png(size, size, paint))).convert("RGBA"))
              for path, size, paint in ICONS]
    columns = 3
    small = [entry for entry in images if entry[1].width == 16]
    large = [entry for entry in images if entry[1].width != 16]
    cell = 16 * scale + pad
    rows = -(-len(small) // columns)
    width = max(columns * cell + pad, large[0][1].width * scale + 2 * pad)
    height = rows * (cell + label_height) + pad + sum(
        entry[1].height * scale + label_height + pad for entry in large)
    sheet = Image.new("RGBA", (width, height), (128, 128, 128, 255))
    draw = ImageDraw.Draw(sheet)

    def place(path: str, image, left: int, top: int) -> int:
        draw.text((left, top), Path(path).name, fill=(255, 255, 255, 255))
        scaled = image.resize((image.width * scale, image.height * scale), Image.NEAREST)
        sheet.alpha_composite(scaled, (left, top + label_height))
        return scaled.height + label_height + pad

    for index, (path, image) in enumerate(small):
        place(path, image, pad + (index % columns) * cell, pad + (index // columns) * (cell + label_height))
    top = pad + rows * (cell + label_height)
    for path, image in large:
        top += place(path, image, pad, top)
    sheet.convert("RGB").save(destination)


if __name__ == "__main__":
    preview(sys.argv[1] if len(sys.argv) > 1 else "icons-preview.png")
