package dev.skyipelago.item;

import java.util.Optional;

import dev.skyipelago.SkyipelagoMod;
import dev.skyipelago.ap.ApChat;
import dev.skyipelago.persist.SkyipelagoSavedData;
import dev.skyipelago.quest.ChapterUnlock;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class ReceivedItems {
    private ReceivedItems() {
    }

    public static void applyIndex(MinecraftServer server, long index, long itemId, String fallbackName) {
        SkyipelagoSavedData data = SkyipelagoSavedData.get(server);
        if (index <= data.receivedItemIndex()) {
            return;
        }
        apply(server, itemId, fallbackName);
        data.setReceivedItemIndex(index);
    }

    public static void apply(MinecraftServer server, long itemId, String fallbackName) {
        ItemEffects.Effect effect = ItemEffects.lookup(itemId);
        if (effect == null) {
            SkyipelagoMod.LOGGER.info("Received unmapped AP item {} ({})", itemId, fallbackName);
            return;
        }
        SkyipelagoSavedData data = SkyipelagoSavedData.get(server);
        if (effect.depositsItem()) {
            deposit(server, data, effect);
        }
        if (effect.unlocksChapter()) {
            unlock(server, data, effect);
        }
    }

    public static void onPlayerLogin(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        SkyipelagoSavedData data = SkyipelagoSavedData.get(server);
        ChapterUnlock.applyAll(player, data.unlockedGates(), false);
        int waiting = data.mailboxCount();
        if (waiting > 0) {
            ApChat.tell(player, waiting + " item(s) in the mailbox. /ap mailbox to collect.");
        }
    }

    public static String collectMailbox(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return "Server is not ready.";
        }
        SkyipelagoSavedData data = SkyipelagoSavedData.get(server);
        int waiting = data.mailboxCount();
        if (waiting <= 0) {
            return "Mailbox is empty.";
        }
        int collected = data.collectMailbox(player);
        int remaining = data.mailboxCount();
        if (remaining == 0) {
            return "Collected " + collected + " item(s) from the mailbox.";
        }
        return "Collected " + collected + " item(s). " + remaining + " still in the mailbox (inventory full).";
    }

    private static void deposit(MinecraftServer server, SkyipelagoSavedData data, ItemEffects.Effect effect) {
        Optional<ItemStack> stack = stackFor(effect.give());
        if (stack.isEmpty()) {
            SkyipelagoMod.LOGGER.warn("Cannot give {} — unknown item id {}", effect.name(), effect.give().item());
            ApChat.broadcast(server, "Could not give " + effect.name() + " (unknown item " + effect.give().item() + ").");
            return;
        }
        data.deposit(stack.get());
        ApChat.broadcast(server, effect.name() + " stored in the mailbox. /ap mailbox to collect.");
    }

    private static void unlock(MinecraftServer server, SkyipelagoSavedData data, ItemEffects.Effect effect) {
        ItemEffects.Unlock unlock = effect.unlock();
        boolean first = !data.unlockedGates().contains(unlock.gateQuestId());
        data.addUnlockedGate(unlock.gateQuestId());
        boolean applied = ChapterUnlock.applyToAllTeams(server, unlock.gateQuestId(), false);
        if (!first) {
            return;
        }
        String title = unlock.title() == null || unlock.title().isBlank() ? unlock.chapter() : unlock.title();
        if (applied) {
            ApChat.broadcast(server, "Unlocked quest chapter: " + title + ".");
        } else {
            SkyipelagoMod.LOGGER.warn("Recorded {} unlock but FTB did not complete gate {}", title, Long.toHexString(unlock.gateQuestId()));
            ApChat.broadcast(server, "Received " + title + " unlock, but the quest book did not update. Rejoin if it stays locked.");
        }
    }

    private static Optional<ItemStack> stackFor(ItemEffects.Give give) {
        ResourceLocation id = ResourceLocation.tryParse(give.item());
        if (id == null) {
            return Optional.empty();
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == Items.AIR) {
            return Optional.empty();
        }
        return Optional.of(new ItemStack(item, give.count()));
    }
}
