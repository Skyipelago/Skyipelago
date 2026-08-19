package dev.skyipelago.quest;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.skyipelago.SkyipelagoMod;

public final class QuestLocationMap {
    private static final String RESOURCE = "/data/skyipelago/quest_to_location.json";

    public record Location(long id, String name) {
    }

    private static Map<String, Location> byQuestId = Map.of();

    private QuestLocationMap() {
    }

    public static void load() {
        try (InputStream in = QuestLocationMap.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                SkyipelagoMod.LOGGER.error("Missing quest map resource {}", RESOURCE);
                byQuestId = Map.of();
                return;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray locations = root.getAsJsonArray("locations");
            Map<String, Location> map = new HashMap<>();
            if (locations != null) {
                for (JsonElement element : locations) {
                    JsonObject obj = element.getAsJsonObject();
                    String quest = normalize(obj.get("quest").getAsString());
                    long id = obj.get("id").getAsLong();
                    String name = obj.has("name") ? obj.get("name").getAsString() : quest;
                    Location location = new Location(id, name);
                    map.put(quest, location);
                    map.put(unpadded(quest), location);
                    map.put(padded(quest), location);
                }
            }
            byQuestId = Collections.unmodifiableMap(map);
            SkyipelagoMod.LOGGER.info("Loaded {} FTB quest → AP location mappings", locations == null ? 0 : locations.size());
        } catch (Exception e) {
            SkyipelagoMod.LOGGER.error("Failed to load quest map", e);
            byQuestId = Map.of();
        }
    }

    public static OptionalLong locationIdForQuest(String questCode) {
        Location location = lookup(questCode);
        return location == null ? OptionalLong.empty() : OptionalLong.of(location.id());
    }

    public static Location lookup(String questCode) {
        if (questCode == null || questCode.isBlank()) {
            return null;
        }
        String normalized = normalize(questCode);
        Location location = byQuestId.get(normalized);
        if (location != null) {
            return location;
        }
        location = byQuestId.get(unpadded(normalized));
        if (location != null) {
            return location;
        }
        return byQuestId.get(padded(normalized));
    }

    public static int size() {
        return (int) byQuestId.values().stream().distinct().count();
    }

    static String normalize(String raw) {
        String hex = raw.trim().toLowerCase(Locale.ROOT);
        if (hex.startsWith("0x")) {
            hex = hex.substring(2);
        }
        return hex;
    }

    private static String unpadded(String hex) {
        int i = 0;
        while (i < hex.length() - 1 && hex.charAt(i) == '0') {
            i++;
        }
        return hex.substring(i);
    }

    private static String padded(String hex) {
        if (hex.length() >= 16) {
            return hex;
        }
        return "0".repeat(16 - hex.length()) + hex;
    }
}
