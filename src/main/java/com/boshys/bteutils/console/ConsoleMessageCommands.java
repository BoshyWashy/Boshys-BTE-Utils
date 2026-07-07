package com.boshys.bteutils.console;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Commands for managing console message detection patterns.
 */
public class ConsoleMessageCommands {

    private final ConsoleMessageConfig config;

    public ConsoleMessageCommands(ConsoleMessageConfig config) {
        this.config = config;
    }

    public LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return ClientCommandManager.literal("ManualTPLLMsgDetectAdd")
                .then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String message = StringArgumentType.getString(ctx, "message");
                            return addPattern(ctx, message);
                        }));
    }

    /**
     * Adds a new detection pattern.
     * The message argument can contain spaces - it's parsed as a greedy string.
     * Multiple messages can be added by running the command multiple times.
     */
    private int addPattern(CommandContext<FabricClientCommandSource> ctx, String message) {
        if (message == null || message.trim().isEmpty()) {
            ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.console_msg.empty"));
            return 0;
        }

        String trimmed = message.trim();

        boolean added = config.addPattern(trimmed);
        if (added) {
            ctx.getSource().sendFeedback(Text.translatable(
                    "command.boshysbteutils.console_msg.added",
                    trimmed
            ));
            ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.console_msg.view_patterns_hint"));
            return 1;
        } else {
            ctx.getSource().sendFeedback(Text.translatable(
                    "command.boshysbteutils.console_msg.already_exists",
                    trimmed
            ));
            return 0;
        }
    }

    /**
     * Builds the listPatterns command.
     */
    public LiteralArgumentBuilder<FabricClientCommandSource> buildListCommand() {
        return ClientCommandManager.literal("listManualTPLLMsgDetects")
                .executes(ctx -> {
                    List<String> patterns = config.getPatterns();
                    if (patterns.isEmpty()) {
                        ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.console_msg.no_patterns"));
                        return 0;
                    }

                    ctx.getSource().sendFeedback(Text.translatable("command.boshysbteutils.console_msg.list_title"));
                    for (int i = 0; i < patterns.size(); i++) {
                        ctx.getSource().sendFeedback(Text.translatable(
                                "command.boshysbteutils.console_msg.list_entry",
                                i + 1,
                                patterns.get(i)
                        ));
                    }
                    return 1;
                });
    }

    /**
     * Builds the removePattern command.
     */
    public LiteralArgumentBuilder<FabricClientCommandSource> buildRemoveCommand() {
        return ClientCommandManager.literal("removeManualTPLLMsgDetect")
                .then(ClientCommandManager.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> {
                            String message = StringArgumentType.getString(ctx, "message");
                            String trimmed = message.trim();

                            boolean removed = config.removePattern(trimmed);
                            if (removed) {
                                ctx.getSource().sendFeedback(Text.translatable(
                                        "command.boshysbteutils.console_msg.removed",
                                        trimmed
                                ));
                                return 1;
                            } else {
                                ctx.getSource().sendFeedback(Text.translatable(
                                        "command.boshysbteutils.console_msg.not_found",
                                        trimmed
                                ));
                                return 0;
                            }
                        }));
    }
}