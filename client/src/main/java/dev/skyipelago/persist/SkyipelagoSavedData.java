package dev.skyipelago.persist;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;

public final class SkyipelagoSavedData extends SavedData {
    public static final String ID = "skyipelago";

    private final Set<Long> checkedLocationIds = new HashSet<>();
    private final Set<Long> pendingLocationIds = new HashSet<>();
    private final List<ItemStack> mailbox = new ArrayList<>();
    private final Set<Long> unlockedGates = new HashSet<>();
    private long receivedItemIndex = -1;
    private String lastHost = "";
    private String lastSlot = "";

    public static SkyipelagoSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(factory(), ID);
    }

    public static Factory<SkyipelagoSavedData> factory() {
        return new Factory<>(SkyipelagoSavedData::new, SkyipelagoSavedData::load, null);
    }

    public static SkyipelagoSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        SkyipelagoSavedData data = new SkyipelagoSavedData();
        readLongSet(tag.getList("Checked", Tag.TAG_LONG), data.checkedLocationIds);
        readLongSet(tag.getList("Pending", Tag.TAG_LONG), data.pendingLocationIds);
        readLongSet(tag.getList("UnlockedGates", Tag.TAG_LONG), data.unlockedGates);
        data.receivedItemIndex = tag.contains("ReceivedItemIndex") ? tag.getLong("ReceivedItemIndex") : -1L;
        data.lastHost = tag.getString("LastHost");
        data.lastSlot = tag.getString("LastSlot");
        ListTag mailboxTag = tag.getList("Mailbox", Tag.TAG_COMPOUND);
        for (int i = 0; i < mailboxTag.size(); i++) {
            ItemStack stack = ItemStack.parseOptional(registries, mailboxTag.getCompound(i));
            if (!stack.isEmpty()) {
                data.mailbox.add(stack);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("Checked", writeLongSet(checkedLocationIds));
        tag.put("Pending", writeLongSet(pendingLocationIds));
        tag.put("UnlockedGates", writeLongSet(unlockedGates));
        tag.putLong("ReceivedItemIndex", receivedItemIndex);
        tag.putString("LastHost", lastHost);
        tag.putString("LastSlot", lastSlot);
        ListTag mailboxTag = new ListTag();
        for (ItemStack stack : mailbox) {
            if (stack.isEmpty()) {
                continue;
            }
            Tag saved = stack.save(registries);
            if (saved instanceof CompoundTag compound) {
                mailboxTag.add(compound);
            }
        }
        tag.put("Mailbox", mailboxTag);
        return tag;
    }

    public boolean markChecked(long locationId) {
        boolean added = checkedLocationIds.add(locationId);
        pendingLocationIds.remove(locationId);
        if (added) {
            setDirty();
        }
        return added;
    }

    public boolean queuePending(long locationId) {
        if (checkedLocationIds.contains(locationId)) {
            return false;
        }
        boolean added = pendingLocationIds.add(locationId);
        if (added) {
            setDirty();
        }
        return added;
    }

    public Set<Long> drainPending() {
        Set<Long> drained = new HashSet<>(pendingLocationIds);
        if (!drained.isEmpty()) {
            pendingLocationIds.clear();
            checkedLocationIds.addAll(drained);
            setDirty();
        }
        return drained;
    }

    public Set<Long> checkedAndPending() {
        Set<Long> all = new HashSet<>(checkedLocationIds);
        all.addAll(pendingLocationIds);
        return all;
    }

    public boolean isChecked(long locationId) {
        return checkedLocationIds.contains(locationId);
    }

    public int checkedCount() {
        return checkedLocationIds.size();
    }

    public int pendingCount() {
        return pendingLocationIds.size();
    }

    public long receivedItemIndex() {
        return receivedItemIndex;
    }

    public void setReceivedItemIndex(long index) {
        if (index > receivedItemIndex) {
            receivedItemIndex = index;
            setDirty();
        }
    }

    public void rememberConnect(String host, String slot) {
        this.lastHost = host == null ? "" : host;
        this.lastSlot = slot == null ? "" : slot;
        setDirty();
    }

    public String lastHost() {
        return lastHost;
    }

    public String lastSlot() {
        return lastSlot;
    }

    public void deposit(ItemStack incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return;
        }
        ItemStack stack = incoming.copy();
        for (ItemStack existing : mailbox) {
            if (!ItemStack.isSameItemSameComponents(existing, stack)) {
                continue;
            }
            int space = Math.min(existing.getMaxStackSize() - existing.getCount(), stack.getCount());
            if (space > 0) {
                existing.grow(space);
                stack.shrink(space);
                setDirty();
                if (stack.isEmpty()) {
                    return;
                }
            }
        }
        mailbox.add(stack);
        setDirty();
    }

    public int mailboxCount() {
        int total = 0;
        for (ItemStack stack : mailbox) {
            total += stack.getCount();
        }
        return total;
    }

    public int collectMailbox(ServerPlayer player) {
        int collected = 0;
        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack stack : mailbox) {
            ItemStack moving = stack.copy();
            int before = moving.getCount();
            if (player.getInventory().add(moving)) {
                collected += before;
            } else {
                collected += before - moving.getCount();
                if (!moving.isEmpty()) {
                    remaining.add(moving);
                }
            }
        }
        mailbox.clear();
        mailbox.addAll(remaining);
        setDirty();
        return collected;
    }

    public void addUnlockedGate(long gateQuestId) {
        if (unlockedGates.add(gateQuestId)) {
            setDirty();
        }
    }

    public Set<Long> unlockedGates() {
        return Collections.unmodifiableSet(unlockedGates);
    }

    private static void readLongSet(ListTag list, Set<Long> target) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) instanceof LongTag longTag) {
                target.add(longTag.getAsLong());
            }
        }
    }

    private static ListTag writeLongSet(Set<Long> source) {
        ListTag list = new ListTag();
        for (long id : source) {
            list.add(LongTag.valueOf(id));
        }
        return list;
    }
}
