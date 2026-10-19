"""The showcase's entity skins and the toadstool's texture, painted onto the UV layouts its geometry names.

Each model uses box UV: a cube of size (w, h, d) at (u, v) takes its top at (u + d, v), its bottom
beside that, and its four sides in the row below, in the order right, front, left, back. `Canvas.box`
paints those six faces from one function per face, given the pixel's place inside the face.
"""

from __future__ import annotations

TRANSPARENT = (0, 0, 0, 0)


def noise(x: int, y: int, salt: int = 0) -> int:
    """A fixed per-pixel value from 0 to 99, so a texture comes out the same on every build."""
    value = (x * 374761393 + y * 668265263 + salt * 2246822519) & 0xFFFFFFFF
    value = ((value ^ (value >> 13)) * 1274126177) & 0xFFFFFFFF
    return (value ^ (value >> 16)) % 100


def shade(colour: tuple[int, int, int], amount: int) -> tuple[int, int, int, int]:
    return tuple(max(0, min(255, channel + amount)) for channel in colour) + (255,)


def grain(colour: tuple[int, int, int], x: int, y: int, spread: int = 6) -> tuple[int, int, int, int]:
    """The colour with a little per-pixel variation, the way Minecraft's surfaces are never flat."""
    return shade(colour, noise(x, y) % (2 * spread + 1) - spread)


class Canvas:
    def __init__(self, width: int, height: int):
        self.width = width
        self.height = height
        self.pixels: dict[tuple[int, int], tuple[int, int, int, int]] = {}

    def box(self, u: int, v: int, size: tuple[int, int, int], faces: dict) -> None:
        """Paints the six faces of one cube; `faces` maps a face name to `paint(x, y, width, height)`."""
        width, height, depth = size
        regions = {
            "top": (u + depth, v, width, depth),
            "bottom": (u + depth + width, v, width, depth),
            "right": (u, v + depth, depth, height),
            "front": (u + depth, v + depth, width, height),
            "left": (u + depth + width, v + depth, depth, height),
            "back": (u + 2 * depth + width, v + depth, width, height),
        }
        for name, (left, top, face_width, face_height) in regions.items():
            paint = faces.get(name) or faces.get("sides") or faces.get("all")
            if paint is None:
                continue
            for y in range(face_height):
                for x in range(face_width):
                    self.pixels[(left + x, top + y)] = paint(x, y, face_width, face_height)

    def paint(self, x: int, y: int) -> tuple[int, int, int, int]:
        return self.pixels.get((x, y), TRANSPARENT)


# Each bedbug variant is a shell colour, its highlight and the colour of the spots along its back.
BEDBUG_VARIANTS = {
    "bedbug_chestnut": ((150, 66, 44), (214, 120, 84), (246, 214, 160)),
    "bedbug_rose": ((196, 82, 112), (240, 150, 170), (255, 236, 214)),
    "bedbug_plum": ((96, 64, 138), (150, 118, 196), (238, 220, 120)),
}

BEDBUG_BELLY = (238, 214, 172)
BEDBUG_LEG = (70, 44, 40)
EYE = (30, 24, 30)


def bedbug_skin(shell: tuple[int, int, int], highlight: tuple[int, int, int], spot: tuple[int, int, int]):
    canvas = Canvas(64, 64)

    def shell_top(x: int, y: int, width: int, height: int):
        middle = width // 2
        if x in (middle - 1, middle):
            return shade(shell, -40)
        if (x, y % 4) in ((2, 1), (width - 3, 1), (3, 3), (width - 4, 3)) and 2 <= y <= height - 3:
            return shade(spot, 0)
        lit = x < middle and y < height // 2
        edge = x in (0, width - 1) or y in (0, height - 1)
        base = highlight if lit and noise(x, y, 1) % 5 == 0 else shell
        return grain(base, x, y) if not edge else shade(shell, -28)

    def shell_side(x: int, y: int, width: int, height: int):
        if y == height - 1:
            return shade(shell, -55)
        if y == 0:
            return shade(highlight, 0)
        return grain(shell, x, y) if y < height - 2 else shade(shell, -30)

    canvas.box(0, 0, (10, 5, 12), {"top": shell_top, "sides": shell_side,
                                    "bottom": lambda x, y, w, h: grain(BEDBUG_BELLY, x, y)})
    canvas.box(0, 17, (8, 1, 10), {"all": lambda x, y, w, h: grain(shade(BEDBUG_BELLY, -20)[:3], x, y)})
    canvas.box(0, 36, (8, 1, 10), {"top": shell_top, "sides": lambda x, y, w, h: shade(highlight, -10)})

    def face(x: int, y: int, width: int, height: int):
        if y == 1 and x in (1, width - 2):
            return (255, 255, 255, 255)
        if y in (1, 2) and x in (1, 2, width - 3, width - 2):
            return EYE + (255,)
        if y == 3 and x in (0, width - 1):
            return shade(highlight, 10)
        if y == 3 and x in (2, 3):
            return shade(shell, -70)
        return grain(shade(shell, -30)[:3], x, y, 4)

    canvas.box(0, 28, (6, 4, 4), {"front": face,
                                   "all": lambda x, y, w, h: grain(shade(shell, -30)[:3], x, y, 4)})
    canvas.box(24, 28, (1, 4, 1), {"all": lambda x, y, w, h: shade(BEDBUG_LEG, 30 if y == 0 else 0)})
    canvas.box(30, 28, (4, 1, 1), {"all": lambda x, y, w, h: shade(BEDBUG_LEG, 12 * (x % 2))})
    return canvas.paint


MOTH_FUR = (244, 236, 212)
MOTH_WING = (250, 232, 214)
MOTH_WING_EDGE = (198, 160, 222)
MOTH_EYE_SPOT = (255, 196, 92)


def moth_skin(x: int, y: int) -> tuple[int, int, int, int]:
    return MOTH_CANVAS.paint(x, y)


def _moth_canvas() -> Canvas:
    canvas = Canvas(64, 64)

    def fur(x: int, y: int, width: int, height: int):
        band = (y // 2) % 2 if height > 4 else 0
        return grain(shade(MOTH_FUR, -26 if band else 0)[:3], x, y, 10)

    canvas.box(0, 0, (4, 4, 9), {"all": fur})
    canvas.box(26, 0, (5, 5, 2), {"all": lambda x, y, w, h: grain((248, 244, 230), x, y, 8)})

    def face(x: int, y: int, width: int, height: int):
        if x in (0, width - 1) and y in (0, 1):
            return (255, 255, 255, 255) if y == 0 else EYE + (255,)
        if y == 2 and x in (1, 2):
            return (236, 170, 170, 255)
        return grain(MOTH_FUR, x, y, 6)

    canvas.box(40, 0, (4, 3, 3), {"front": face, "all": lambda x, y, w, h: grain(MOTH_FUR, x, y, 8)})

    def antenna(x: int, y: int, width: int, height: int):
        return shade((150, 110, 80), 30) if x == width // 2 or (y + x) % 2 == 0 else TRANSPARENT

    canvas.box(40, 6, (3, 0, 4), {"all": antenna})

    def wing(spot_at: tuple[float, float], spot_radius: float):
        def paint(x: int, y: int, face_width: int, face_height: int):
            # The outer corner away from the body is rounded off, so the wing reads as a wing.
            outer = face_width - 1 - x
            if (x - 0) ** 2 / (face_width ** 2) + ((y - face_height / 2) / (face_height / 2 + 0.5)) ** 2 > 1.0:
                return TRANSPARENT
            dx, dy = x - spot_at[0] * face_width, y - spot_at[1] * face_height
            distance = (dx * dx + dy * dy) ** 0.5
            if distance <= spot_radius - 1.2:
                return shade(MOTH_WING_EDGE, -90)
            if distance <= spot_radius:
                return shade(MOTH_EYE_SPOT, 0)
            near_edge = (x / face_width) ** 2 + ((y - face_height / 2) / (face_height / 2)) ** 2 > 0.7
            if near_edge:
                return grain(MOTH_WING_EDGE, x, y, 6)
            vein = outer in (face_width - 2,) or (x + y) % 5 == 0 and noise(x, y, 3) % 3 == 0
            return grain(shade(MOTH_WING, -18 if vein else 0)[:3], x, y, 5)
        return paint

    canvas.box(0, 13, (11, 0, 10), {"all": wing((0.55, 0.45), 2.2)})
    canvas.box(0, 24, (8, 0, 7), {"all": wing((0.5, 0.5), 1.6)})
    return canvas


MOTH_CANVAS = _moth_canvas()

CAP = (206, 52, 52)
CAP_SPOT = (250, 244, 228)
GILLS = (232, 212, 178)
STEM = (238, 230, 210)


def toadstool(x: int, y: int) -> tuple[int, int, int, int]:
    """A 32x32 sheet: the cap's top, its gills, its side band, the stem's side and the stem's ends."""
    if x < 12 and y < 12:
        spots = {(2, 2), (3, 2), (2, 3), (3, 3), (8, 1), (9, 1), (8, 2), (9, 2), (5, 6), (6, 6),
                 (5, 7), (6, 7), (9, 8), (2, 9), (3, 9), (10, 5)}
        if (x, y) in spots:
            return CAP_SPOT + (255,)
        rim = x in (0, 11) or y in (0, 11)
        return shade(CAP, -38) if rim else grain(CAP, x, y, 8)
    if 12 <= x < 24 and y < 12:
        return grain(shade(GILLS, -30 if (x - 12 + y) % 3 == 0 else 0)[:3], x, y, 4)
    if x < 12 and 12 <= y < 17:
        row = y - 12
        if (x, row) in ((2, 1), (3, 1), (8, 2), (9, 2), (5, 0)):
            return CAP_SPOT + (255,)
        return shade(CAP, -45) if row == 4 else grain(CAP, x, y, 8)
    if 24 <= x < 28 and y < 8:
        return grain(shade(STEM, -16 if x in (24, 27) else 0)[:3], x, y, 5)
    if 24 <= x < 28 and 8 <= y < 12:
        return grain(shade(STEM, -24)[:3], x, y, 4)
    return TRANSPARENT


SKINS = tuple((f"textures/entity/{name}.png", 64, bedbug_skin(*colours))
              for name, colours in BEDBUG_VARIANTS.items()) + (
    ("textures/entity/lumen_moth.png", 64, moth_skin),
    ("textures/blocks/toadstool.png", 32, toadstool),
)
