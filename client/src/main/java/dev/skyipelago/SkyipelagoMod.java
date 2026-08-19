package dev.skyipelago;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.skyipelago.ap.ApSession;
import dev.skyipelago.command.ApCommands;
import dev.skyipelago.item.ItemEffects;
import dev.skyipelago.item.ReceivedItems;
import dev.skyipelago.quest.QuestCompletedHook;
import dev.skyipelago.quest.QuestLocationMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(SkyipelagoMod.MOD_ID)
public class SkyipelagoMod {
    public static final String MOD_ID = "skyipelago";
    public static final String GAME_NAME = "Skyipelago";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SkyipelagoMod(IEventBus modEventBus) {
        modEventBus.addListener(this::commonSetup);
        NeoForge.EVENT_BUS.register(ServerEvents.class);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            QuestLocationMap.load();
            ItemEffects.load();
            QuestCompletedHook.register();
        });
    }

    public static final class ServerEvents {
        private ServerEvents() {
        }

        @SubscribeEvent
        public static void onRegisterCommands(RegisterCommandsEvent event) {
            ApCommands.register(event.getDispatcher());
        }

        @SubscribeEvent
        public static void onServerStarted(ServerStartedEvent event) {
            ApSession.attach(event.getServer());
        }

        @SubscribeEvent
        public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                ReceivedItems.onPlayerLogin(player);
            }
        }

        @SubscribeEvent
        public static void onServerTick(ServerTickEvent.Post event) {
            ApSession session = ApSession.get();
            if (session != null && session.server() == event.getServer()) {
                session.tick();
            }
        }

        @SubscribeEvent
        public static void onServerStopping(ServerStoppingEvent event) {
            ApSession.detach(event.getServer());
        }
    }

    public static void executeOnServer(MinecraftServer server, Runnable task) {
        if (server == null) {
            return;
        }
        if (server.isSameThread()) {
            task.run();
        } else {
            server.execute(task);
        }
    }
}
