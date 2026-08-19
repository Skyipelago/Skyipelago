package dev.skyipelago.command;

import java.util.function.Function;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import dev.skyipelago.SkyipelagoMod;
import dev.skyipelago.ap.ApChat;
import dev.skyipelago.ap.ApSession;
import dev.skyipelago.item.ReceivedItems;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

public final class ApCommands {
    private ApCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("ap")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("connect")
                        .then(Commands.argument("host", StringArgumentType.string())
                                .then(Commands.argument("slot", StringArgumentType.string())
                                        .executes(ctx -> connect(ctx, null))
                                        .then(Commands.argument("password", StringArgumentType.greedyString())
                                                .executes(ctx -> connect(ctx, StringArgumentType.getString(ctx, "password")))))))
                .then(Commands.literal("disconnect").executes(ApCommands::disconnect))
                .then(Commands.literal("status").executes(ApCommands::status))
                .then(Commands.literal("mailbox").executes(ApCommands::mailbox));

        dispatcher.register(root);
        dispatcher.register(Commands.literal("archipelago")
                .requires(source -> source.hasPermission(2))
                .redirect(dispatcher.getRoot().getChild("ap")));
        dispatcher.register(Commands.literal("skyipelago")
                .requires(source -> source.hasPermission(2))
                .redirect(dispatcher.getRoot().getChild("ap")));
    }

    private static int connect(CommandContext<CommandSourceStack> ctx, String password) {
        return run(ctx, session -> {
            String host = StringArgumentType.getString(ctx, "host");
            String slot = StringArgumentType.getString(ctx, "slot");
            return session.connect(host, slot, password);
        });
    }

    private static int disconnect(CommandContext<CommandSourceStack> ctx) {
        return run(ctx, ApSession::disconnect);
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        return run(ctx, ApSession::statusLine);
    }

    private static int mailbox(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(ApChat.prefix("Mailbox must be collected by a player."));
            return 0;
        }
        tell(ctx, ReceivedItems.collectMailbox(player));
        return Command.SINGLE_SUCCESS;
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Function<ApSession, String> action) {
        try {
            ApSession session = sessionOrTell(ctx);
            if (session == null) {
                return 0;
            }
            tell(ctx, action.apply(session));
            return Command.SINGLE_SUCCESS;
        } catch (Throwable t) {
            SkyipelagoMod.LOGGER.error("Archipelago command failed", t);
            String detail = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            ctx.getSource().sendFailure(ApChat.prefix("Command failed: " + detail));
            return 0;
        }
    }

    private static ApSession sessionOrTell(CommandContext<CommandSourceStack> ctx) {
        ApSession session = ApSession.get();
        if (session == null) {
            ctx.getSource().sendFailure(ApChat.prefix("Server is not ready."));
            return null;
        }
        return session;
    }

    private static void tell(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendSuccess(() -> ApChat.prefix(message), false);
    }
}
