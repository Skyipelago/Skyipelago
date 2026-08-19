package dev.skyipelago.item;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import dev.skyipelago.SkyipelagoMod;

public final class ItemEffects {
    private static final String RESOURCE = "/data/skyipelago/item_effects.json";

    public record Give(String item, int count) {
    }

    public record Unlock(String chapter, String title, long chapterId, long gateQuestId) {
    }

    public record Effect(long id, String slug, String name, Give give, Unlock unlock) {
        public boolean depositsItem() {
            return give != null;
        }

        public boolean unlocksChapter() {
            return unlock != null;
        }
    }

    private static Map<Long, Effect> byId = Map.of();
    private static Set<Long> gateQuestIds = Set.of();

    private ItemEffects() {
    }

    public static void load() {
        try (InputStream in = ItemEffects.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                SkyipelagoMod.LOGGER.error("Missing item effects resource {}", RESOURCE);
                byId = Map.of();
                gateQuestIds = Set.of();
                return;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray items = root.getAsJsonArray("items");
            Map<Long, Effect> map = new HashMap<>();
            Set<Long> gates = new HashSet<>();
            if (items != null) {
                for (JsonElement element : items) {
                    JsonObject obj = element.getAsJsonObject();
                    long id = obj.get("id").getAsLong();
                    String slug = obj.get("slug").getAsString();
                    String name = obj.get("name").getAsString();
                    Give give = parseGive(obj);
                    Unlock unlock = parseUnlock(obj);
                    map.put(id, new Effect(id, slug, name, give, unlock));
                    if (unlock != null) {
                        gates.add(unlock.gateQuestId());
                    }
                }
            }
            byId = Collections.unmodifiableMap(map);
            gateQuestIds = Collections.unmodifiableSet(gates);
            SkyipelagoMod.LOGGER.info("Loaded {} AP item effects ({} chapter gates)", map.size(), gates.size());
        } catch (Exception e) {
            SkyipelagoMod.LOGGER.error("Failed to load item effects", e);
            byId = Map.of();
            gateQuestIds = Set.of();
        }
    }

    public static Effect lookup(long itemId) {
        return byId.get(itemId);
    }

    public static boolean isGateQuest(String questCode) {
        if (questCode == null || questCode.isBlank()) {
            return false;
        }
        return gateQuestIds.contains(QuestObjectBase.parseCodeString(questCode));
    }

    public static int size() {
        return byId.size();
    }

    private static Give parseGive(JsonObject obj) {
        if (!obj.has("give") || !obj.get("give").isJsonObject()) {
            return null;
        }
        JsonObject give = obj.getAsJsonObject("give");
        if (!give.has("item")) {
            return null;
        }
        int count = give.has("count") ? Math.max(1, give.get("count").getAsInt()) : 1;
        return new Give(give.get("item").getAsString(), count);
    }

    private static Unlock parseUnlock(JsonObject obj) {
        if (!obj.has("unlock") || !obj.get("unlock").isJsonObject()) {
            return null;
        }
        JsonObject unlock = obj.getAsJsonObject("unlock");
        if (!unlock.has("gate_quest")) {
            return null;
        }
        String chapter = unlock.has("chapter") ? unlock.get("chapter").getAsString() : "";
        String title = unlock.has("title") ? unlock.get("title").getAsString() : chapter;
        long chapterId = unlock.has("chapter_id")
                ? QuestObjectBase.parseCodeString(unlock.get("chapter_id").getAsString())
                : 0L;
        long gateQuestId = QuestObjectBase.parseCodeString(unlock.get("gate_quest").getAsString());
        return new Unlock(chapter, title, chapterId, gateQuestId);
    }
}
