"""Writes a Bedrock `.mcstructure`: little endian NBT, uncompressed, x major then y then z.

The test packs ship structures written here rather than exported from the game, so no file in this
repository comes from anybody's pack.
"""

from __future__ import annotations

import struct
from typing import Callable

# The game version Bedrock stamps every palette entry with. Nothing reads it; a real file has one.
BLOCK_VERSION = 18168865

# The tag types this writes, by the number NBT gives each of them.
INT, STRING, LIST, COMPOUND = 3, 8, 9, 10


def named(tag_type: int, name: str, payload: bytes) -> bytes:
    """A tag inside a compound, which carries its type and its name before its payload."""
    encoded = name.encode("utf-8")
    return bytes([tag_type]) + struct.pack("<H", len(encoded)) + encoded + payload


def text(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return struct.pack("<H", len(encoded)) + encoded


def compound(body: bytes) -> bytes:
    return body + b"\x00"


def listed(element_type: int, payloads: list[bytes]) -> bytes:
    """A list payload. Its elements carry neither a type nor a name, only the values."""
    return struct.pack("<bi", element_type, len(payloads)) + b"".join(payloads)


def ints(values: list[int]) -> bytes:
    return listed(INT, [struct.pack("<i", value) for value in values])


def states(declared: dict[str, str]) -> bytes:
    return compound(b"".join(named(STRING, key, text(value)) for key, value in declared.items()))


def structure(size: tuple[int, int, int], palette: list[tuple[str, dict[str, str]]],
              block: Callable[[int, int, int], int]) -> bytes:
    """One structure of that size, whose cell at x, y, z holds the palette entry `block` names.

    An index of -1 leaves whatever the world holds there, which is what Bedrock writes for a cell
    a structure does not cover. The second layer, the liquid sharing a cell, is left empty.
    """
    width, height, depth = size
    cells = [block(x, y, z) for x in range(width) for y in range(height) for z in range(depth)]
    untouched = [-1] * len(cells)

    entries = [compound(named(STRING, "name", text(name))
                        + named(COMPOUND, "states", states(declared))
                        + named(INT, "version", struct.pack("<i", BLOCK_VERSION)))
               for name, declared in palette]

    default = compound(named(LIST, "block_palette", listed(COMPOUND, entries))
                       + named(COMPOUND, "block_position_data", compound(b"")))
    body = compound(named(LIST, "block_indices", listed(LIST, [ints(cells), ints(untouched)]))
                    + named(LIST, "entities", listed(COMPOUND, []))
                    + named(COMPOUND, "palette", compound(named(COMPOUND, "default", default))))

    return named(COMPOUND, "", compound(
        named(INT, "format_version", struct.pack("<i", 1))
        + named(LIST, "size", ints(list(size)))
        + named(COMPOUND, "structure", body)
        + named(LIST, "structure_world_origin", ints([0, 0, 0]))))
