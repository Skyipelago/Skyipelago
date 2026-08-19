package dev.skyipelago.quest;

import java.util.Collection;
import java.util.UUID;

import dev.ftb.mods.ftbquests.quest.QuestObject;
import dev.ftb.mods.ftbquests.quest.ServerQuestFile;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.util.ProgressChange;
import dev.skyipelago.SkyipelagoMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Reveals a gated FTB chapter by completing its hidden gate quest.
 * Equivalent to {@code /ftbquests change_progress <player> complete <gate>},
 * but applied to the whole team and idempotent.
 */
public final class ChapterUnlock {
    private ChapterUnlock() {
    }

    public static boolean applyToAllTeams(MinecraftServer server, long gateQuestId, boolean notify) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        if (file == null) {
            SkyipelagoMod.LOGGER.warn("No FTB quest file; deferring gate {}", Long.toHexString(gateQuestId));
            return false;
        }
        QuestObject quest = file.get(gateQuestId);
        if (quest == null) {
            SkyipelagoMod.LOGGER.warn("Missing gate quest {}", Long.toHexString(gateQuestId));
            return false;
        }
        var teams = file.getAllTeamData();
        if (teams.isEmpty()) {
            SkyipelagoMod.LOGGER.warn("No FTB team data yet; deferring gate {}", Long.toHexString(gateQuestId));
            return false;
        }
        boolean any = false;
        for (TeamData team : teams) {
            any |= complete(team, quest, actor(team), notify);
        }
        return any;
    }

    public static boolean applyToPlayer(ServerPlayer player, long gateQuestId, boolean notify) {
        ServerQuestFile file = ServerQuestFile.INSTANCE;
        if (file == null) {
            return false;
        }
        QuestObject quest = file.get(gateQuestId);
        if (quest == null) {
            SkyipelagoMod.LOGGER.warn("Missing gate quest {}", Long.toHexString(gateQuestId));
            return false;
        }
        return file.getTeamData(player)
                .map(team -> complete(team, quest, player.getUUID(), notify))
                .orElse(false);
    }

    public static void applyAll(ServerPlayer player, Collection<Long> gateQuestIds, boolean notify) {
        for (long gateQuestId : gateQuestIds) {
            applyToPlayer(player, gateQuestId, notify);
        }
    }

    private static boolean complete(TeamData team, QuestObject quest, UUID actor, boolean notify) {
        if (team.isCompleted(quest)) {
            return false;
        }
        // ProgressChange defaults reset=true. That is the /ftbquests change_progress
        // reset path; complete must flip it or we wipe the gate instead of finishing it.
        ProgressChange change = new ProgressChange(quest, actor).setReset(false);
        if (notify) {
            change.withNotifications();
        }
        quest.forceProgressRaw(team, change);
        if (!team.isCompleted(quest)) {
            SkyipelagoMod.LOGGER.warn(
                    "Gate {} did not complete for team {}",
                    Long.toHexString(quest.id),
                    team.getName()
            );
            return false;
        }
        return true;
    }

    private static UUID actor(TeamData team) {
        if (!team.getOnlineMembers().isEmpty()) {
            return team.getOnlineMembers().iterator().next().getUUID();
        }
        return team.getTeamId();
    }
}
