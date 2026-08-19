from dataclasses import dataclass

from Options import DeathLink, PerGameCommonOptions


@dataclass
class SkyipelagoOptions(PerGameCommonOptions):
    death_link: DeathLink
