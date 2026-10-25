package es.mrdino.flashlight;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

public final class FlashlightCommand implements TabExecutor {
    private static final List<String> TARGETED = List.of("give", "battery", "recharge", "on", "off", "flicker");
    private final FlashlightPlugin plugin;

    public FlashlightCommand(FlashlightPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        execute(sender, args, null);
        return true;
    }

    public int execute(CommandSender sender, String[] args, List<Player> resolvedTargets) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("help")) { help(sender); return 1; }
        if (sub.equals("pack")) {
            if (!sender.hasPermission("flashlight.pack")) { error(sender, "You do not have permission."); return 0; }
            if (args.length == 2 && args[1].equalsIgnoreCase("export")) plugin.packs().export(sender);
            else if (args.length == 1 && sender instanceof Player player) plugin.packs().request(player, false);
            else error(sender, "Usage: /flashlight pack [export]");
            return 1;
        }
        if (!sender.hasPermission("flashlight.admin")) { error(sender, "You do not have permission."); return 0; }
        if (sub.equals("reload")) {
            if (args.length != 1) { error(sender, "Usage: /flashlight reload"); return 0; }
            try {
                plugin.reloadFlashlight();
                success(sender, "Configuration reloaded. Active flashlights switched off.");
            } catch (Exception exception) {
                error(sender, "Reload failed. Check config.yml and the server console.");
                plugin.getLogger().log(Level.WARNING, "Could not reload Flashlight.", exception);
            }
            return 1;
        }
        if (!TARGETED.contains(sub)) { error(sender, "Unknown command. Use /flashlight help."); return 0; }
        try {
            int maxArguments = switch (sub) { case "flicker" -> 5; case "on", "off", "battery" -> 3; default -> 2; };
            if (args.length > maxArguments) throw new IllegalArgumentException("Too many arguments. Use /flashlight help.");
            int amount = sub.equals("battery") && args.length >= 3 ? integer(args[2], 1, 64, "Amount") : 1;
            int duration = 0;
            double strength = plugin.settings().flickerStrength();
            int pulse = 0;
            if (List.of("on", "off", "flicker").contains(sub)) {
                duration = args.length >= 3 ? CommandDuration.ticks(args[2]) : sub.equals("flicker") ? 24 : 0;
                if (args.length >= 4) strength = percent(args[3]);
                if (args.length >= 5) {
                    pulse = CommandDuration.ticks(args[4]);
                    if (pulse < 2) throw new IllegalArgumentException("Pulse interval must be at least 2 ticks.");
                }
            }
            List<Player> targets = resolvedTargets == null ? targets(sender, args.length >= 2 ? args[1] : null) : resolvedTargets;
            int affected = 0;
            for (Player target : targets) {
                boolean changed = switch (sub) {
                    case "give" -> { give(target, plugin.items().flashlight()); yield true; }
                    case "battery" -> { give(target, plugin.items().battery(amount)); yield true; }
                    case "recharge" -> plugin.service().recharge(target, true);
                    case "on", "off" -> plugin.service().setPower(target, sub.equals("on"), duration);
                    case "flicker" -> plugin.service().forceFlicker(target, duration, strength, pulse);
                    default -> false;
                };
                if (changed) affected++;
            }
            if (affected == 0) error(sender, sub.equals("flicker")
                    ? "No active held flashlights matched." : "No usable held flashlights matched.");
            else success(sender, sub + ": " + affected + " player(s)"
                    + (duration > 0 ? ", " + duration + " ticks" : "")
                    + (sub.equals("flicker") ? ", strength " + Math.round(strength * 100) + "%" : "") + ".");
            return affected;
        } catch (IllegalArgumentException exception) {
            error(sender, exception.getMessage() == null ? "Invalid target or arguments." : exception.getMessage());
        }
        return 0;
    }

    private List<Player> targets(CommandSender sender, String target) {
        if (target == null) {
            if (sender instanceof Player player) return List.of(player);
            throw new IllegalArgumentException("Specify a player or selector, e.g. @p or @a.");
        }
        List<Player> players;
        if (target.startsWith("@")) {
            // Preserve the command block's world, position and vanilla selector semantics.
            players = Bukkit.selectEntities(sender, target).stream()
                    .filter(Player.class::isInstance).map(Player.class::cast).toList();
        } else {
            Player player = Bukkit.getPlayerExact(target);
            players = player == null ? List.of() : List.of(player);
        }
        if (players.isEmpty()) throw new IllegalArgumentException("No online players matched: " + target);
        return players;
    }

    private int integer(String value, int min, int max, String label) {
        try {
            int number = Integer.parseInt(value);
            if (number >= min && number <= max) return number;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(label + " must be between " + min + " and " + max + ".");
    }

    private double percent(String value) {
        try {
            double number = Double.parseDouble(value.endsWith("%") ? value.substring(0, value.length() - 1) : value);
            if (Double.isFinite(number) && number >= 0 && number <= 100) return number / 100;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("Strength must be between 0 and 100 percent.");
    }

    private void give(Player player, ItemStack item) {
        player.getInventory().addItem(item).values().forEach(leftover ->
                player.getWorld().dropItemNaturally(player.getLocation(), leftover));
    }

    private void help(CommandSender sender) {
        sender.sendMessage(FlashlightItems.text("/flashlight pack [export]", NamedTextColor.GRAY));
        if (!sender.hasPermission("flashlight.admin")) return;
        for (String line : List.of("/flashlight give [targets]", "/flashlight battery [targets] [1-64]",
                "/flashlight recharge [targets]", "/flashlight on|off [targets] [duration]",
                "/flashlight flicker [targets] [duration] [strength 0-100] [pulse interval]",
                "/flashlight reload", "Targets: player name, @p, @a, @s, @r, @a[distance=..10]",
                "Durations: 3s, 0.5s, 40t. Timed on/off locks the switch and restores the previous state.")) {
            sender.sendMessage(FlashlightItems.text(line, NamedTextColor.GRAY));
        }
    }

    private void error(CommandSender sender, String message) { sender.sendMessage(FlashlightItems.text(message, NamedTextColor.RED)); }
    private void success(CommandSender sender, String message) { sender.sendMessage(FlashlightItems.text(message, NamedTextColor.GREEN)); }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            options.addAll(List.of("help", "pack"));
            if (sender.hasPermission("flashlight.admin")) { options.addAll(TARGETED); options.add("reload"); }
        } else if (args.length == 2 && sub.equals("pack")) options.add("export");
        else if (sender.hasPermission("flashlight.admin") && TARGETED.contains(sub)) {
            if (args.length == 2) {
                options.addAll(List.of("@p", "@a", "@s", "@r"));
                Bukkit.getOnlinePlayers().stream().filter(player -> !(sender instanceof Player viewer) || viewer.canSee(player))
                        .map(Player::getName).forEach(options::add);
            } else if (args.length == 3) {
                if (sub.equals("battery")) options.addAll(List.of("1", "4", "16", "64"));
                if (List.of("on", "off", "flicker").contains(sub)) options.addAll(List.of("0.5s", "3s", "5s", "40t"));
            } else if (args.length == 4 && sub.equals("flicker")) options.addAll(List.of("25", "50", "85", "100"));
            else if (args.length == 5 && sub.equals("flicker")) options.addAll(List.of("3t", "5t", "10t"));
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
