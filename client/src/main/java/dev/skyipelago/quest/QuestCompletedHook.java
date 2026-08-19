package dev.skyipelago.quest;

import dev.architectury.event.EventResult;
import dev.ftb.mods.ftbquests.events.ObjectCompletedEvent;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.skyipelago.SkyipelagoMod;
import dev.skyipelago.ap.ApChat;
import dev.skyipelago.ap.ApSession;
import dev.skyipelago.item.ItemEffects;
import dev.skyipelago.persist.SkyipelagoSavedData;
import net.minecraft.server.MinecraftServer;

public final class QuestCompletedHook {
    private QuestCompletedHook() {
    }

    public static void register() {
        ObjectCompletedEvent.QUEST.register(QuestCompletedHook::onQuestCompleted);
        SkyipelagoMod.LOGGER.info("Registered FTB Quests completion hook");
    }

    private static EventResult onQuestCompleted(ObjectCompletedEvent.QuestEvent event) {
        Quest quest = event.getQuest();
        if (quest == null || !quest.getQuestFile().isServerSide()) {
            return EventResult.pass();
        }
        String questId = quest.getCodeString();
        if (ItemEffects.isGateQuest(questId)) {
            return EventResult.pass();
        }
        QuestLocationMap.Location location = QuestLocationMap.lookup(questId);
        if (location == null) {
            SkyipelagoMod.LOGGER.info("Unmapped FTB quest completed: {}", questId);
            return EventResult.pass();
        }

        MinecraftServer server = null;
        if (!event.getOnlineMembers().isEmpty()) {
            server = event.getOnlineMembers().getFirst().getServer();
        }
        ApSession session = ApSession.get();
        if (server == null && session != null) {
            server = session.server();
        }
        if (server == null) {
            SkyipelagoMod.LOGGER.warn("Completed mapped quest {} but no server is available", questId);
            return EventResult.pass();
        }

        SkyipelagoSavedData data = SkyipelagoSavedData.get(server);
        if (data.isChecked(location.id())) {
            return EventResult.pass();
        }

        boolean connected = session != null && session.isConnected();
        if (connected) {
            data.markChecked(location.id());
            session.sendCheck(location.id());
        } else {
            data.queuePending(location.id());
        }

        String message = connected
                ? "Checked " + location.name() + "."
                : "Queued " + location.name() + " until you connect.";
        SkyipelagoMod.LOGGER.info("FTB quest {} → location {} ({})", questId, location.id(), message);
        ApChat.broadcast(server, message);
        return EventResult.pass();
    }
}
