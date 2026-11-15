"""Builders shared by the tests. All resume data is synthetic."""
from __future__ import annotations

from datetime import date

from app.models.matching import MatchFact, MatchRequest, MatchRequirement

TODAY = date(2026, 11, 8)
TOKEN = "test-only-internal-token"


def requirement(ident: int, kind: str, description: str, *, mandatory: bool = True, knockout: bool = False,
                years: int | None = None, alternatives: list[str] | None = None,
                proficiency: str | None = None) -> MatchRequirement:
    return MatchRequirement(id=ident, type=kind, description=description, mandatory=mandatory, knockout=knockout,
                            minimumYears=years, alternatives=alternatives or [], proficiency=proficiency)


def fact(ident: str, kind: str, value: str, *, evidence: str | None = None, tier: str = "RESUME_STATED",
         section: str = "skills", attributes: dict | None = None) -> MatchFact:
    return MatchFact(id=ident, type=kind, value=value, evidence=evidence if evidence is not None else value,
                     sourceSection=section, tier=tier, attributes=attributes)


def request(requirements: list[MatchRequirement], facts: list[MatchFact], timeline=None) -> MatchRequest:
    return MatchRequest(requirements=requirements, facts=facts, timeline=timeline)


def make_doc(lines: list[str]) -> bytes:
    """A minimal Word 97 binary document: an OLE2 compound file with a FIB and a one-piece CLX."""
    import struct

    text = "\r".join(lines).encode("cp1252")
    text_offset = 0x800
    word = bytearray(max(4096, text_offset + len(text) + 1))
    struct.pack_into("<HH", word, 0, 0xA5EC, 0x00C1)
    struct.pack_into("<H", word, 0x0A, 0x0200)  # fWhichTblStm: the table stream is "1Table"
    clx = bytes([0x02]) + struct.pack("<I", 16) + struct.pack("<II", 0, len(text)) + struct.pack(
        "<HIH", 0, (text_offset * 2) | 0x40000000, 0)
    struct.pack_into("<II", word, 0x01A2, 0, len(clx))
    word[text_offset:text_offset + len(text)] = text
    table = clx.ljust(4096, b"\x00")
    return _compound_file([("WordDocument", bytes(word)), ("1Table", table)])


def _compound_file(streams: list[tuple[str, bytes]]) -> bytes:
    import struct

    sector = 512
    free, end_of_chain, fat_sector, no_stream = 0xFFFFFFFF, 0xFFFFFFFE, 0xFFFFFFFD, 0xFFFFFFFF
    data, fat, starts = bytearray(), [], []
    for _, payload in streams:
        payload = payload.ljust(-(-len(payload) // sector) * sector, b"\x00")
        first = len(fat)
        count = len(payload) // sector
        fat.extend(list(range(first + 1, first + count)) + [end_of_chain])
        starts.append(first)
        data.extend(payload)
    directory_start = len(fat)
    fat.append(end_of_chain)
    fat_index = len(fat)
    fat.append(fat_sector)
    fat.extend([free] * (128 - len(fat)))

    def entry(name: str, kind: int, left: int, right: int, child: int, start: int, size: int) -> bytes:
        encoded = (name + "\x00").encode("utf-16-le") if name else b""
        return (encoded.ljust(64, b"\x00") + struct.pack("<HBB", len(encoded), kind, 1) +
                struct.pack("<III", left, right, child) + b"\x00" * 16 + b"\x00" * 4 + b"\x00" * 16 +
                struct.pack("<IQ", start, size))

    (first_name, first_data), (second_name, second_data) = streams
    directory = (entry("Root Entry", 5, no_stream, no_stream, 1, end_of_chain, 0) +
                 entry(first_name, 2, 2, no_stream, no_stream, starts[0], len(first_data)) +
                 entry(second_name, 2, no_stream, no_stream, no_stream, starts[1], len(second_data)) +
                 entry("", 0, no_stream, no_stream, no_stream, 0, 0))
    header = (bytes.fromhex("D0CF11E0A1B11AE1") + b"\x00" * 16 + struct.pack("<HHHHH", 0x3E, 3, 0xFFFE, 9, 6) +
              b"\x00" * 6 + struct.pack("<IIIIIIIII", 0, 1, directory_start, 0, 0x1000, end_of_chain, 0, end_of_chain, 0) +
              struct.pack("<I", fat_index) + struct.pack("<I", free) * 108)
    return header + bytes(data) + directory + struct.pack(f"<{len(fat)}I", *fat)
