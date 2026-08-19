from typing import Any

from BaseClasses import Item, ItemClassification, Location, Region, Tutorial
from worlds.AutoWorld import WebWorld, World

from .generated_data import ITEMS, LOCATIONS, REGIONS
from .Options import SkyipelagoOptions


class SkyipelagoWeb(WebWorld):
    theme = "ocean"
    tutorials = [
        Tutorial(
            "Multiworld Setup Guide",
            "A guide to setting up Skyipelago for Archipelago.",
            "English",
            "setup_en.md",
            "setup/en",
            ["Skyipelago"],
        )
    ]


class SkyipelagoItem(Item):
    game = "Skyipelago"


class SkyipelagoLocation(Location):
    game = "Skyipelago"


class SkyipelagoWorld(World):
    """A 1.21.1 NeoForge skyblock whose FTB Quests send Archipelago checks."""

    game = "Skyipelago"
    web = SkyipelagoWeb()
    options_dataclass = SkyipelagoOptions
    options: SkyipelagoOptions
    topology_present = True

    item_name_to_id = {item["name"]: item["id"] for item in ITEMS if item["id"] is not None}
    location_name_to_id = {loc["name"]: loc["id"] for loc in LOCATIONS if loc["id"] is not None}

    def create_regions(self) -> None:
        menu = Region("Menu", self.player, self.multiworld)
        regions: dict[str, Region] = {"menu": menu}
        for row in REGIONS:
            regions[row["slug"]] = Region(row["title"], self.player, self.multiworld)

        spawn = regions.get("spawn")
        if spawn is None:
            raise Exception("data/regions.yaml must define spawn")
        menu.connect(spawn)

        player = self.player
        for row in REGIONS:
            if row["slug"] == "spawn":
                continue
            parent = regions.get(row.get("via") or "spawn")
            if parent is None:
                raise Exception(f"region {row['slug']} via unknown {row.get('via')!r}")
            child = regions[row["slug"]]
            unlock = row.get("unlock_item")
            if unlock:
                parent.connect(
                    child,
                    rule=lambda state, item=unlock, p=player: state.has(item, p),
                )
            else:
                parent.connect(child)

        for loc in LOCATIONS:
            region = regions.get(loc.get("region") or "spawn", spawn)
            location = SkyipelagoLocation(self.player, loc["name"], loc["id"], region)
            region.locations.append(location)

        ordered = [menu]
        for row in REGIONS:
            region = regions[row["slug"]]
            if region not in ordered:
                ordered.append(region)
        self.multiworld.regions += ordered

    def create_items(self) -> None:
        locked = {loc.get("locked_item") for loc in LOCATIONS if loc.get("locked_item")}
        for item in ITEMS:
            if item.get("event") or item["name"] in locked:
                continue
            self.multiworld.itempool.append(self.create_item(item["name"]))

    def create_item(self, name: str) -> SkyipelagoItem:
        definition = next(item for item in ITEMS if item["name"] == name)
        classification = {
            "progression": ItemClassification.progression,
            "useful": ItemClassification.useful,
            "trap": ItemClassification.trap,
        }.get(definition["classification"], ItemClassification.filler)
        return SkyipelagoItem(name, classification, definition["id"], self.player)

    def set_rules(self) -> None:
        locked_locations = [loc for loc in LOCATIONS if loc.get("locked_item")]
        for loc in locked_locations:
            # Must be a real item id. Event items serialize as None and AP 0.6.7
            # LocationStore refuses to load the seed.
            self.get_location(loc["name"]).place_locked_item(self.create_item(loc["locked_item"]))

        if any(loc.get("locked_item") == "Victory" for loc in locked_locations):
            self.multiworld.completion_condition[self.player] = (
                lambda state, p=self.player: state.has("Victory", p)
            )
            return

        keys = tuple(row["unlock_item"] for row in REGIONS if row.get("unlock_item"))
        if keys:
            self.multiworld.completion_condition[self.player] = (
                lambda state, names=keys, p=self.player: all(state.has(name, p) for name in names)
            )
            return

        self.multiworld.completion_condition[self.player] = lambda state: True

    def create_event(self, name: str) -> SkyipelagoItem:
        return SkyipelagoItem(name, ItemClassification.progression, None, self.player)

    def get_filler_item_name(self) -> str:
        for item in ITEMS:
            if item.get("classification") == "filler" and item.get("id") is not None:
                return item["name"]
        return next(item["name"] for item in ITEMS if item.get("id") is not None)

    def fill_slot_data(self) -> dict[str, Any]:
        return {
            "quest_map": {loc["quest"]: loc["id"] for loc in LOCATIONS if loc["id"] is not None},
        }
