package dev.skyipelago.ap;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import dev.skyipelago.SkyipelagoMod;
import dev.skyipelago.item.ReceivedItems;
import dev.skyipelago.persist.SkyipelagoSavedData;
import dev.skyipelago.quest.QuestLocationMap;
import io.github.archipelagomw.APResult;
import io.github.archipelagomw.events.ConnectionResultEvent;
import io.github.archipelagomw.events.PrintJSONEvent;
import io.github.archipelagomw.events.ReceiveItemEvent;
import io.github.archipelagomw.network.ConnectionResult;
import io.github.archipelagomw.parts.NetworkItem;
import net.minecraft.server.MinecraftServer;

public final class ApSession {
    private static volatile ApSession instance;

    private final MinecraftServer server;
    private final ApClient client = new ApClient(this);
    private volatile boolean connecting;
    private volatile boolean handshakeDone;
    private String lastError = "";

    private ApSession(MinecraftServer server) {
        this.server = server;
    }

    public static void attach(MinecraftServer server) {
        instance = new ApSession(server);
    }

    public static void detach(MinecraftServer server) {
        ApSession session = instance;
        if (session != null && session.server == server) {
            session.disconnect();
            instance = null;
        }
    }

    @Nullable
    public static ApSession get() {
        return instance;
    }

    public MinecraftServer server() {
        return server;
    }

    public boolean isConnected() {
        return client.isConnected();
    }

    public boolean isConnecting() {
        return connecting && !client.isConnected();
    }

    public String lastError() {
        return lastError;
    }

    public String connectedAddress() {
        return client.getConnectedAddress();
    }

    public String slotName() {
        return client.getMyName();
    }

    public synchronized String connect(String host, String slot, @Nullable String password) {
        if (client.isConnected()) {
            client.disconnect();
        }
        connecting = true;
        handshakeDone = false;
        lastError = "";
        client.setName(slot);
        client.setPassword(password == null ? "" : password);
        SkyipelagoSavedData.get(server).rememberConnect(host, slot);
        try {
            // connect(URI) skips Java-Client's Apache URIBuilder path.
            client.connect(toConnectUri(host));
            return "Connecting to " + host + " as " + slot + "...";
        } catch (URISyntaxException e) {
            connecting = false;
            lastError = "Invalid address: " + e.getMessage();
            return lastError;
        } catch (Throwable t) {
            connecting = false;
            lastError = "Connect failed: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            SkyipelagoMod.LOGGER.error("Archipelago connect failed", t);
            return lastError;
        }
    }

    public synchronized String disconnect() {
        connecting = false;
        handshakeDone = false;
        if (!client.isConnected()) {
            client.close();
            return "Not connected.";
        }
        client.disconnect();
        return "Disconnected.";
    }

    public void tick() {
        if (!client.isReady()) {
            return;
        }
        if (!handshakeDone) {
            finishHandshake();
        }
        syncReceivedItems();
    }

    public void sendCheck(long locationId) {
        if (!client.isConnected()) {
            return;
        }
        Set<Long> ids = new HashSet<>();
        ids.add(locationId);
        flushChecks(ids);
    }

    public void sendChecks(Collection<Long> locationIds) {
        flushChecks(locationIds);
    }

    private void flushChecks(Collection<Long> locationIds) {
        if (!client.isConnected() || locationIds.isEmpty()) {
            return;
        }
        coerceToLongs(client.getLocationManager().getMissingLocations());
        coerceToLongs(client.getLocationManager().getCheckedLocations());
        Set<Long> missing = client.getLocationManager().getMissingLocations();
        Set<Long> toSend = new HashSet<>();
        for (Long id : locationIds) {
            if (id == null) {
                continue;
            }
            if (!missing.contains(id)) {
                SkyipelagoMod.LOGGER.warn(
                        "Location {} was not in the AP missing set (missing={}, checked={}); sending anyway",
                        id,
                        missing.size(),
                        client.getLocationManager().getCheckedLocations().size()
                );
                missing.add(id);
            }
            toSend.add(id);
        }
        if (toSend.isEmpty()) {
            return;
        }
        APResult<Void> result = client.checkLocations(toSend);
        if (result.getCode() != APResult.ResultCode.SUCCESS) {
            SkyipelagoMod.LOGGER.warn("Failed to send {} location(s): {}", toSend.size(), result.getCode());
        } else {
            SkyipelagoMod.LOGGER.info("Sent {} location check(s): {}", toSend.size(), toSend);
        }
    }

    public String statusLine() {
        SkyipelagoSavedData data = SkyipelagoSavedData.get(server);
        if (client.isConnected()) {
            coerceToLongs(client.getLocationManager().getMissingLocations());
            coerceToLongs(client.getLocationManager().getCheckedLocations());
            Set<Long> missing = client.getLocationManager().getMissingLocations();
            Set<Long> checked = client.getLocationManager().getCheckedLocations();
            int roomLocations = missing.size() + checked.size();
            return "Connected to " + client.getConnectedAddress()
                    + " as " + client.getMyName()
                    + " — checked " + checked.size()
                    + ", remaining " + missing.size()
                    + ", room " + roomLocations + "/" + QuestLocationMap.size()
                    + ", pending flush " + data.pendingCount()
                    + ", mailbox " + data.mailboxCount()
                    + ", chapters " + data.unlockedGates().size();
        }
        if (connecting) {
            return "Connecting...";
        }
        String remembered = data.lastHost().isEmpty()
                ? "never connected"
                : "last " + data.lastSlot() + " @ " + data.lastHost();
        return "Disconnected (" + remembered + "). Local checked " + data.checkedCount()
                + ", pending " + data.pendingCount()
                + ", mailbox " + data.mailboxCount()
                + (lastError.isEmpty() ? "" : ". Last error: " + lastError);
    }

    void handleConnectionResult(ConnectionResultEvent event) {
        SkyipelagoMod.executeOnServer(server, () -> {
            connecting = false;
            if (event.getResult() != ConnectionResult.Success) {
                lastError = event.getResult().name();
                handshakeDone = false;
                ApChat.broadcast(server, "Connection failed: " + prettyResult(event.getResult()));
                return;
            }
            finishHandshake();
        });
    }

    void handlePrintJson(PrintJSONEvent event) {
        if (event == null || event.apPrint == null) {
            return;
        }
        String text = event.apPrint.getPlainText();
        if (text == null || text.isBlank()) {
            return;
        }
        SkyipelagoMod.executeOnServer(server, () -> ApChat.broadcast(server, text));
    }

    void handleReceiveItem(ReceiveItemEvent event) {
        SkyipelagoMod.executeOnServer(server, () -> {
            Long itemId = event.getItemID();
            if (itemId == null) {
                return;
            }
            String itemName = event.getItemName() == null ? ("#" + itemId) : event.getItemName();
            String location = event.getLocationName() == null ? "unknown location" : event.getLocationName();
            String player = event.getPlayerName() == null ? "someone" : event.getPlayerName();
            SkyipelagoSavedData data = SkyipelagoSavedData.get(server);
            if (event.getIndex() <= data.receivedItemIndex()) {
                return;
            }
            ApChat.broadcast(server, "Received " + itemName + " from " + player + " (" + location + ").");
            ReceivedItems.applyIndex(server, event.getIndex(), itemId, itemName);
        });
    }

    void handleError(Exception ex) {
        connecting = false;
        lastError = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        SkyipelagoMod.LOGGER.error("Archipelago socket error", ex);
        SkyipelagoMod.executeOnServer(server, () -> ApChat.broadcast(server, "Error: " + lastError));
    }

    void handleClose(String reason, int attemptingReconnect) {
        connecting = attemptingReconnect > 0;
        handshakeDone = false;
        String message = attemptingReconnect > 0
                ? "Disconnected (" + reason + "), retrying..."
                : "Disconnected (" + reason + ").";
        SkyipelagoMod.LOGGER.info("Archipelago closed: {} (retry={})", reason, attemptingReconnect);
        SkyipelagoMod.executeOnServer(server, () -> ApChat.broadcast(server, message));
    }

    private void finishHandshake() {
        if (handshakeDone || !client.isReady()) {
            return;
        }
        handshakeDone = true;
        connecting = false;
        lastError = "";
        coerceToLongs(client.getLocationManager().getMissingLocations());
        coerceToLongs(client.getLocationManager().getCheckedLocations());
        SkyipelagoSavedData data = SkyipelagoSavedData.get(server);
        Set<Long> toFlush = data.checkedAndPending();
        data.drainPending();
        flushChecks(toFlush);
        syncReceivedItems();
        Set<Long> missing = client.getLocationManager().getMissingLocations();
        Set<Long> checked = client.getLocationManager().getCheckedLocations();
        int roomLocations = missing.size() + checked.size();
        ApChat.broadcast(server, "Connected as " + client.getMyName()
                + " — " + checked.size() + " checked, " + missing.size() + " remaining"
                + " (room " + roomLocations + "/" + QuestLocationMap.size() + ").");
        if (roomLocations > 0 && roomLocations < QuestLocationMap.size()) {
            ApChat.broadcast(server, "This room was generated with fewer locations than the current pack. Generate a new seed with the current Skyipelago apworld.");
        }
    }

    private void syncReceivedItems() {
        List<NetworkItem> items = client.getItemManager().getReceivedItems();
        if (items.isEmpty()) {
            return;
        }
        SkyipelagoSavedData data = SkyipelagoSavedData.get(server);
        for (int i = 0; i < items.size(); i++) {
            long index = i + 1L;
            if (index <= data.receivedItemIndex()) {
                continue;
            }
            NetworkItem item = items.get(i);
            String itemName = item.itemName == null || item.itemName.isBlank()
                    ? "#" + item.itemID
                    : item.itemName;
            String location = item.locationName == null || item.locationName.isBlank()
                    ? "unknown location"
                    : item.locationName;
            String player = item.playerName == null || item.playerName.isBlank()
                    ? "someone"
                    : item.playerName;
            ApChat.broadcast(server, "Received " + itemName + " from " + player + " (" + location + ").");
            ReceivedItems.applyIndex(server, index, item.itemID, itemName);
        }
    }

    /**
     * Gson may box location ids as Integer/Double. LocationManager then fails
     * {@code Set<Long>.contains} and silently drops every check.
     */
    private static void coerceToLongs(Set<Long> set) {
        boolean dirty = false;
        List<Long> longs = new ArrayList<>(set.size());
        for (Object value : new ArrayList<>(set)) {
            if (value instanceof Long boxed) {
                longs.add(boxed);
            } else if (value instanceof Number number) {
                longs.add(number.longValue());
                dirty = true;
            } else {
                dirty = true;
            }
        }
        if (!dirty) {
            return;
        }
        set.clear();
        set.addAll(longs);
    }

    private static URI toConnectUri(String host) throws URISyntaxException {
        String trimmed = host.trim();
        if (!trimmed.contains("://")) {
            trimmed = "ws://" + trimmed;
        }
        URI uri = new URI(trimmed);
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new URISyntaxException(host, "missing host");
        }
        if (uri.getPort() == -1) {
            return new URI(
                    uri.getScheme(),
                    uri.getUserInfo(),
                    uri.getHost(),
                    38281,
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment()
            );
        }
        return uri;
    }

    private static String prettyResult(ConnectionResult result) {
        return switch (result) {
            case InvalidSlot -> "unknown slot name";
            case SlotAlreadyTaken -> "slot already taken";
            case IncompatibleVersion -> "incompatible protocol version";
            case InvalidPassword -> "invalid password";
            case InvalidGame -> "this room has no Skyipelago slot";
            case Success -> "success";
        };
    }
}
