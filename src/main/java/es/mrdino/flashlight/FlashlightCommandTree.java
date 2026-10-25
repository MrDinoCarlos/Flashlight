package es.mrdino.flashlight;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Named Brigadier arguments produce Minecraft's syntax guide above the command input. */
public final class FlashlightCommandTree {
    private final FlashlightCommand executor;
    public FlashlightCommandTree(FlashlightCommand executor) { this.executor = executor; }

    public LiteralCommandNode<CommandSourceStack> build() { return build(ArgumentTypes.players()); }

    LiteralCommandNode<CommandSourceStack> build(ArgumentType<PlayerSelectorArgumentResolver> players) {
        var root = Commands.literal("flashlight").executes(context -> run(context, "help"));
        root.then(Commands.literal("help").executes(context -> run(context, "help")));
        root.then(Commands.literal("reload").requires(source -> source.getSender().hasPermission("flashlight.admin"))
                .executes(context -> run(context, "reload")));
        root.then(Commands.literal("pack").requires(source -> source.getSender().hasPermission("flashlight.pack"))
                .executes(context -> run(context, "pack"))
                .then(Commands.literal("export").executes(context -> run(context, "pack", "export"))));
        for (String sub : List.of("give", "battery", "recharge", "on", "off", "flicker")) {
            LiteralArgumentBuilder<CommandSourceStack> branch = Commands.literal(sub)
                    .requires(source -> source.getSender().hasPermission("flashlight.admin"))
                    .executes(context -> run(context, sub));
            var targets = Commands.argument("targets", players).executes(context -> run(context, sub, "targets"));
            if (sub.equals("battery")) targets.then(Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                    .suggests(suggestions("Number of batteries (1-64)", "1", "4", "16", "64"))
                    .executes(context -> run(context, sub, "targets", "amount")));
            if (List.of("on", "off", "flicker").contains(sub)) {
                var duration = duration("duration", "Total duration: seconds (3s) or ticks (40t)")
                        .executes(context -> run(context, sub, "targets", "duration"));
                if (sub.equals("flicker")) {
                    duration.then(Commands.argument("strength_0_100", DoubleArgumentType.doubleArg(0, 100))
                            .suggests(suggestions("Light reduction: 100 = complete blackouts", "25", "50", "85", "100"))
                            .executes(context -> run(context, sub, "targets", "duration", "strength_0_100"))
                            .then(duration("pulse_interval", "Time between pulse changes (minimum 2t)")
                                    .executes(context -> run(context, sub, "targets", "duration", "strength_0_100", "pulse_interval"))));
                }
                targets.then(duration);
            }
            root.then(branch.then(targets));
        }
        return root.build();
    }

    private RequiredArgumentBuilder<CommandSourceStack, String> duration(String name, String tooltip) {
        return Commands.argument(name, StringArgumentType.word()).suggests(suggestions(tooltip,
                name.equals("pulse_interval") ? new String[]{"3t", "5t", "10t"} : new String[]{"0.5s", "3s", "5s", "40t"}));
    }

    private SuggestionProvider<CommandSourceStack> suggestions(String tooltip, String... examples) {
        return (context, builder) -> {
            for (String example : examples) if (example.startsWith(builder.getRemainingLowerCase())) {
                builder.suggest(example, new LiteralMessage(tooltip));
            }
            return builder.buildFuture();
        };
    }

    private int run(CommandContext<CommandSourceStack> context, String sub, String... arguments) throws CommandSyntaxException {
        var values = new ArrayList<String>();
        values.add(sub);
        List<Player> resolved = context.getSource().getExecutor() instanceof Player player ? List.of(player) : null;
        for (String name : arguments) {
            if (name.equals("targets")) {
                resolved = context.getArgument(name, PlayerSelectorArgumentResolver.class).resolve(context.getSource());
                values.add("<targets>");
            } else if (name.equals("export")) values.add("export");
            else values.add(context.getArgument(name, Object.class).toString());
        }
        return executor.execute(context.getSource().getSender(), values.toArray(String[]::new), resolved);
    }
}
