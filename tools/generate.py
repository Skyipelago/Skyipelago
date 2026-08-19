#!/usr/bin/env python3
"""Compile data/*.yaml into the APWorld tables, client maps, and FTB quests."""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data"
CLIENT_DIR = ROOT / "client" / "src" / "main" / "resources" / "data" / "skyipelago"
CLIENT_QUEST_JSON = CLIENT_DIR / "quest_to_location.json"
CLIENT_ITEM_JSON = CLIENT_DIR / "item_effects.json"
APWORLD_GEN = ROOT / "apworld" / "worlds" / "skyipelago" / "generated_data.py"
QUEST_DIR = ROOT / "pack-dev" / "config" / "ftbquests" / "quests"
PACK_QUEST_CHAPTERS = ROOT.parent / "modpack" / "config" / "ftbquests" / "quests" / "chapters"


def load_yaml(path: Path) -> dict:
    """Parse the tiny YAML subset used by data/*.yaml (no PyYAML required)."""
    root: dict = {}
    stack: list[tuple[int, dict | list]] = [(-1, root)]
    for raw in path.read_text(encoding="utf-8").splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        line = raw.strip()
        while stack and indent <= stack[-1][0]:
            stack.pop()
        parent = stack[-1][1]
        if line.endswith(":") and ":" not in line[:-1]:
            key = line[:-1]
            child: dict = {}
            if isinstance(parent, dict):
                parent[key] = child
            else:
                raise SystemExit(f"Unexpected mapping under list in {path}")
            stack.append((indent, child))
            continue
        key, value = line.split(":", 1)
        key = key.strip()
        parsed = parse_scalar(value.strip())
        if not isinstance(parent, dict):
            raise SystemExit(f"Unexpected scalar under list in {path}")
        parent[key] = parsed
    return root


def parse_scalar(value: str):
    if value == "" or value is None:
        return {}
    if value.lower() in {"true", "yes"}:
        return True
    if value.lower() in {"false", "no"}:
        return False
    if value.startswith("[") and value.endswith("]"):
        inner = value[1:-1].strip()
        if not inner:
            return []
        return [parse_scalar(part.strip()) for part in split_list(inner)]
    if value.startswith('"') and value.endswith('"'):
        return value[1:-1]
    if value.startswith("'") and value.endswith("'"):
        return value[1:-1]
    if value.lower().startswith("0x"):
        return int(value, 16)
    try:
        return int(value)
    except ValueError:
        return value


def split_list(inner: str) -> list[str]:
    parts: list[str] = []
    buf: list[str] = []
    quote: str | None = None
    for char in inner:
        if quote:
            buf.append(char)
            if char == quote:
                quote = None
            continue
        if char in {'"', "'"}:
            quote = char
            buf.append(char)
            continue
        if char == ",":
            parts.append("".join(buf).strip())
            buf = []
            continue
        buf.append(char)
    if buf:
        parts.append("".join(buf).strip())
    return parts


def parse_id(raw) -> int | None:
    if raw is None or raw == "" or raw == {}:
        return None
    if isinstance(raw, int):
        return raw
    text = str(raw).strip()
    return int(text, 16)


def assign_ids(table: dict, key: str) -> list[dict]:
    base = int(table["id_base"])
    rows = []
    for offset, (slug, entry) in enumerate(table[key].items(), start=1):
        row = dict(entry)
        row["slug"] = slug
        if row.get("event"):
            row["id"] = None
        else:
            row["id"] = base + offset
        rows.append(row)
    return rows


def assign_regions(table: dict, items: list[dict]) -> tuple[list[dict], int]:
    base = int(table["id_base"])
    unlock_by_chapter: dict[str, str] = {}
    for item in items:
        unlock = item.get("unlock") or {}
        chapter = unlock.get("chapter") if isinstance(unlock, dict) else None
        if chapter:
            if chapter in unlock_by_chapter:
                raise SystemExit(f"Chapter {chapter!r} is unlocked by more than one item")
            unlock_by_chapter[chapter] = item["name"]

    rows = []
    for offset, (slug, entry) in enumerate(table["regions"].items(), start=1):
        row = dict(entry)
        row["slug"] = slug
        row["title"] = row.get("title") or slug.replace("_", " ").title()
        row["filename"] = row.get("filename") if isinstance(row.get("filename"), str) else slug
        row["icon"] = row.get("icon") or "minecraft:book"
        row["start_unlocked"] = bool(row.get("start_unlocked"))
        row["via"] = row.get("via") or ("menu" if slug == "spawn" else "spawn")
        authored_chapter = parse_id(row.get("chapter_id"))
        row["chapter_id"] = authored_chapter if authored_chapter is not None else base + offset
        if row["start_unlocked"]:
            row["gate_quest_id"] = None
            row["gate_task_id"] = None
            row["unlock_item"] = None
            if slug in unlock_by_chapter:
                raise SystemExit(f"{slug} is start_unlocked but {unlock_by_chapter[slug]} tries to unlock it")
        else:
            authored_gate = parse_id(row.get("gate_quest_id"))
            authored_task = parse_id(row.get("gate_task_id"))
            row["gate_quest_id"] = authored_gate if authored_gate is not None else base + 0x100 + offset
            row["gate_task_id"] = authored_task if authored_task is not None else base + 0x200 + offset
            row["unlock_item"] = unlock_by_chapter.get(slug)
            if not row["unlock_item"]:
                raise SystemExit(f"Locked region {slug!r} has no item with unlock.chapter: {slug}")
        rows.append(row)

    missing = set(unlock_by_chapter) - {row["slug"] for row in rows}
    if missing:
        raise SystemExit(f"unlock.chapter references unknown region(s): {sorted(missing)}")

    gates_chapter_id = base + 0xFF
    return rows, gates_chapter_id


def validate_quest_ids(locations: list[dict], regions: list[dict], gates_chapter_id: int) -> None:
    # FTB Quests hard-codes the quest file object as id 1. readID() also
    # rejects 0. Those ids get remapped to a random long on load, so the
    # client map never matches.
    seen: set[int] = set()

    def check(raw, label: str) -> None:
        if raw is None:
            return
        quest_id = raw if isinstance(raw, int) else int(str(raw), 16)
        if quest_id in (0, 1):
            raise SystemExit(f"FTB reserves quest ids 0 and 1. {label} uses {raw}")
        if quest_id in seen:
            raise SystemExit(f"Duplicate FTB id {raw} on {label}")
        seen.add(quest_id)

    for loc in locations:
        check(loc.get("quest"), loc["slug"])
    for region in regions:
        check(region["chapter_id"], f"chapter {region['slug']}")
        check(region.get("gate_quest_id"), f"gate {region['slug']}")
        check(region.get("gate_task_id"), f"gate-task {region['slug']}")
    check(gates_chapter_id, "gates chapter")


def validate_pool(locations: list[dict], items: list[dict]) -> None:
    locked = {loc.get("locked_item") for loc in locations if loc.get("locked_item")}
    fillable = [loc for loc in locations if loc.get("id") is not None and not loc.get("locked_item")]
    pool = [
        item
        for item in items
        if item.get("id") is not None and not item.get("event") and item["name"] not in locked
    ]
    if len(pool) != len(fillable):
        raise SystemExit(
            f"Item pool ({len(pool)}) must match fillable locations ({len(fillable)}). "
            "Locked-item locations are placed, not filled."
        )


def hex_id(value: int | str) -> str:
    if isinstance(value, int):
        return f"{value:016x}"
    return str(value).lower()


def write_client_maps(locations: list[dict], items: list[dict], regions: list[dict]) -> None:
    CLIENT_DIR.mkdir(parents=True, exist_ok=True)
    by_slug = {row["slug"]: row for row in regions}
    quest_payload = {
        "locations": [
            {"quest": hex_id(loc["quest"]), "id": loc["id"], "name": loc["name"]}
            for loc in locations
            if loc.get("id") is not None
        ]
    }
    CLIENT_QUEST_JSON.write_text(json.dumps(quest_payload, indent=2) + "\n", encoding="utf-8")

    item_rows = []
    for item in items:
        if item.get("id") is None:
            continue
        row: dict = {"id": item["id"], "slug": item["slug"], "name": item["name"]}
        give = item.get("give") or {}
        if isinstance(give, dict) and give.get("item"):
            row["give"] = {"item": give["item"], "count": int(give.get("count") or 1)}
        unlock = item.get("unlock") or {}
        chapter = unlock.get("chapter") if isinstance(unlock, dict) else None
        if chapter:
            region = by_slug[chapter]
            row["unlock"] = {
                "chapter": chapter,
                "title": region["title"],
                "chapter_id": hex_id(region["chapter_id"]),
                "gate_quest": hex_id(region["gate_quest_id"]),
            }
        item_rows.append(row)
    CLIENT_ITEM_JSON.write_text(json.dumps({"items": item_rows}, indent=2) + "\n", encoding="utf-8")


def write_apworld(locations: list[dict], items: list[dict], regions: list[dict]) -> None:
    lines = [
        "# generated by tools/generate.py — do not edit",
        "REGIONS = [",
    ]
    for region in regions:
        lines.append(
            "    {"
            f"\"slug\": {region['slug']!r}, \"title\": {region['title']!r}, "
            f"\"start_unlocked\": {bool(region['start_unlocked'])!r}, "
            f"\"via\": {region.get('via')!r}, "
            f"\"unlock_item\": {region.get('unlock_item')!r}"
            "},"
        )
    lines.append("]")
    lines.append("LOCATIONS = [")
    for loc in locations:
        lines.append(
            "    {"
            f"\"slug\": {loc['slug']!r}, \"name\": {loc['name']!r}, "
            f"\"id\": {loc['id']!r}, \"quest\": {hex_id(loc['quest'])!r}, "
            f"\"region\": {loc.get('region', 'spawn')!r}, "
            f"\"locked_item\": {loc.get('locked_item')!r}"
            "},"
        )
    lines.append("]")
    lines.append("ITEMS = [")
    for item in items:
        lines.append(
            "    {"
            f"\"slug\": {item['slug']!r}, \"name\": {item['name']!r}, "
            f"\"id\": {item['id']!r}, \"classification\": {item['classification']!r}, "
            f"\"event\": {bool(item.get('event'))!r}"
            "},"
        )
    lines.append("]")
    lines.append("")
    APWORLD_GEN.parent.mkdir(parents=True, exist_ok=True)
    APWORLD_GEN.write_text("\n".join(lines), encoding="utf-8")


def write_quests(locations: list[dict], regions: list[dict], gates_chapter_id: int) -> None:
    # The playable book is modpack/config/ftbquests. Only emit the hidden
    # Gates chapter used to unlock locked regions; do not write stub books.
    QUEST_DIR.mkdir(parents=True, exist_ok=True)
    chapters = QUEST_DIR / "chapters"
    chapters.mkdir(exist_ok=True)
    for stale in chapters.glob("*.snbt"):
        stale.unlink()

    by_region: dict[str, list[dict]] = {region["slug"]: [] for region in regions}
    for loc in locations:
        region = loc.get("region") or "spawn"
        if region not in by_region:
            raise SystemExit(f"Location {loc['slug']} is in unknown region {region!r}")
        by_region[region].append(loc)

    write_gates_chapter(chapters, regions, gates_chapter_id)
    if PACK_QUEST_CHAPTERS.is_dir():
        write_gates_chapter(PACK_QUEST_CHAPTERS, regions, gates_chapter_id, disk_name="gates")


def write_gates_chapter(
    chapters: Path,
    regions: list[dict],
    gates_chapter_id: int,
    disk_name: str | None = None,
) -> None:
    gated = [region for region in regions if region.get("gate_quest_id")]
    if not gated:
        return
    gate_blocks = []
    for index, region in enumerate(gated):
        task = (
            "\t\t\ttasks: [{\n"
            f"\t\t\t\tid: \"{hex_id(region['gate_task_id'])}\"\n"
            "\t\t\t\ttype: \"checkmark\"\n"
            "\t\t\t}]"
        )
        gate_blocks.append(
            quest_block(
                hex_id(region["gate_quest_id"]),
                region["unlock_item"],
                (index % 4) * 2.0,
                (index // 4) * 2.0,
                task,
                [],
            )
        )
    write_chapter(
        chapters,
        gates_chapter_id,
        "gates",
        "Gates",
        "minecraft:iron_bars",
        "\talways_invisible: true\n",
        gate_blocks,
        disk_name=disk_name,
    )


def write_chapter(
    chapters: Path,
    chapter_id: int,
    filename: str,
    title: str,
    icon: str,
    extra: str,
    quest_blocks: list[str],
    disk_name: str | None = None,
) -> None:
    hexed = hex_id(chapter_id)
    body = (
        "{\n"
        f"\tid: \"{hexed}\"\n"
        f"\tfilename: \"{filename}\"\n"
        f"\ttitle: \"{title}\"\n"
        f"\ticon: \"{icon}\"\n"
        f"{extra}"
        "\tquests: [\n"
        + ",\n".join(quest_blocks)
        + "\n\t]\n}\n"
    )
    name = disk_name or hexed.upper()
    (chapters / f"{name}.snbt").write_text(body, encoding="utf-8")


def quest_block(quest_id: int | str, title: str, x: float, y: float, task: str, deps: list[str]) -> str:
    dep_line = ""
    if deps:
        quoted = ", ".join(f"\"{hex_id(dep)}\"" for dep in deps)
        dep_line = f"\n\t\t\tdependencies: [{quoted}]"
    return (
        "\t\t{\n"
        f"\t\t\tx: {x}d\n"
        f"\t\t\ty: {y}d\n"
        f"\t\t\tid: \"{hex_id(quest_id)}\"\n"
        f"\t\t\ttitle: \"{title}\"\n"
        f"{task}"
        f"{dep_line}\n"
        "\t\t}"
    )


def task_for(loc: dict, task_id: str) -> str:
    slug = loc["slug"]
    if slug == "punch_the_dirt":
        item = "minecraft:dirt"
    elif slug == "craft_a_plank":
        item = "minecraft:oak_planks"
    elif slug == "eat_something":
        item = "minecraft:apple"
    else:
        return (
            "\t\t\ttasks: [{\n"
            f"\t\t\t\tid: \"{task_id}\"\n"
            "\t\t\t\ttype: \"checkmark\"\n"
            "\t\t\t}]"
        )
    return (
        "\t\t\ttasks: [{\n"
        f"\t\t\t\tid: \"{task_id}\"\n"
        "\t\t\t\ttype: \"item\"\n"
        f"\t\t\t\titem: {{count: 1, id: \"{item}\"}}\n"
        "\t\t\t}]"
    )


def main() -> int:
    locations = assign_ids(load_yaml(DATA / "locations.yaml"), "locations")
    items = assign_ids(load_yaml(DATA / "items.yaml"), "items")
    regions, gates_chapter_id = assign_regions(load_yaml(DATA / "regions.yaml"), items)
    validate_quest_ids(locations, regions, gates_chapter_id)
    validate_pool(locations, items)
    location_names = [row["name"] for row in locations]
    item_names = [row["name"] for row in items]
    if len(location_names) != len(set(location_names)):
        raise SystemExit("Duplicate location names")
    if len(item_names) != len(set(item_names)):
        raise SystemExit("Duplicate item names")
    region_slugs = {row["slug"] for row in regions}
    for loc in locations:
        region = loc.get("region") or "spawn"
        if region not in region_slugs:
            raise SystemExit(f"Location {loc['slug']} is in unknown region {region!r}")
    write_client_maps(locations, items, regions)
    write_apworld(locations, items, regions)
    write_quests(locations, regions, gates_chapter_id)
    gated = sum(1 for row in regions if row.get("gate_quest_id"))
    print(
        f"Wrote {len(locations)} locations, {len(items)} items, "
        f"{len(regions)} chapters ({gated} gated)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
