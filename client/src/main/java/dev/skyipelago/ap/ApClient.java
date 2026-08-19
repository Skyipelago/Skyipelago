package dev.skyipelago.ap;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

import dev.skyipelago.SkyipelagoMod;
import io.github.archipelagomw.APResult;
import io.github.archipelagomw.Client;
import io.github.archipelagomw.EventManager;
import io.github.archipelagomw.events.ArchipelagoEventListener;
import io.github.archipelagomw.events.ConnectionResultEvent;
import io.github.archipelagomw.events.PrintJSONEvent;
import io.github.archipelagomw.events.ReceiveItemEvent;
import io.github.archipelagomw.flags.ItemsHandling;

final class ApClient extends Client {
    private final ApSession session;

    ApClient(ApSession session) {
        this.session = session;
        setGame(SkyipelagoMod.GAME_NAME);
        setItemsHandlingFlags(
                ItemsHandling.SEND_ITEMS | ItemsHandling.SEND_OWN_ITEMS | ItemsHandling.SEND_STARTING_INVENTORY
        );
        addTag("AP");
        bindListeners();
    }

    boolean isReady() {
        return isConnected() && ensureConnectedAndAuth().getCode() == APResult.ResultCode.SUCCESS;
    }

    @ArchipelagoEventListener
    public void onConnectionResult(ConnectionResultEvent event) {
        session.handleConnectionResult(event);
    }

    @ArchipelagoEventListener
    public void onPrintJson(PrintJSONEvent event) {
        session.handlePrintJson(event);
    }

    @ArchipelagoEventListener
    public void onReceiveItem(ReceiveItemEvent event) {
        session.handleReceiveItem(event);
    }

    @Override
    public void onError(Exception ex) {
        session.handleError(ex);
    }

    @Override
    public void onClose(String reason, int attemptingReconnect) {
        session.handleClose(reason, attemptingReconnect);
    }

    /**
     * Java-Client's EventManager invokes listener methods by reflection. NeoForge
     * modules can make that invoke fail silently, so also force-bind with
     * setAccessible and rely on {@link ApSession#tick()} as a fallback.
     */
    private void bindListeners() {
        getEventManager().registerListener(this);
        try {
            Field field = EventManager.class.getDeclaredField("registeredListeners");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<Method, Object> listeners = (Map<Method, Object>) field.get(getEventManager());
            int bound = 0;
            for (Method method : getClass().getMethods()) {
                if (!method.isAnnotationPresent(ArchipelagoEventListener.class)) {
                    continue;
                }
                method.setAccessible(true);
                listeners.put(method, this);
                bound++;
            }
            SkyipelagoMod.LOGGER.info("Bound {} Archipelago event listeners (map size {})", bound, listeners.size());
        } catch (ReflectiveOperationException e) {
            SkyipelagoMod.LOGGER.warn("Could not force-bind Archipelago listeners; tick sync will apply items", e);
        }
    }
}
