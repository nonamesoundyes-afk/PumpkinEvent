package dev.pumpkin.event;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PumpkinCommand implements TabExecutor {

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    private final PumpkinEventPlugin plugin;
    private final PumpkinEvent ev;

    public PumpkinCommand(PumpkinEventPlugin plugin, PumpkinEvent ev) {
        this.plugin = plugin;
        this.ev = ev;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "start", "старт" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                Integer x = null;
                Integer z = null;
                if (args.length >= 3) {
                    try {
                        x = Integer.parseInt(args[1]);
                        z = Integer.parseInt(args[2]);
                    } catch (NumberFormatException ex) {
                        sender.sendMessage(plugin.component("usage"));
                        return true;
                    }
                }
                ev.start(sender, x, z);
            }
            case "stop", "стоп" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                if (!ev.isRunning()) {
                    sender.sendMessage(plugin.component("not-running"));
                } else {
                    ev.finish(true);
                }
            }
            case "reload" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                plugin.reloadConfig();
                sender.sendMessage(plugin.component("reloaded"));
            }
            case "where", "где" -> where(sender);
            case "status", "статус" -> status(sender);
            default -> sender.sendMessage(plugin.component("usage"));
        }
        return true;
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("pumpkinevent.admin")) {
            return true;
        }
        sender.sendMessage(plugin.component("no-permission"));
        return false;
    }

    private void status(CommandSender sender) {
        if (!ev.isRunning()) {
            sender.sendMessage(plugin.component("status-idle"));
            return;
        }
        String phaseKey = ev.isPreparing() ? "phase-prep" : "phase-active";
        String phase = plugin.getConfig().getString("messages." + phaseKey, phaseKey);
        sender.sendMessage(plugin.component("status-running",
                Placeholder.unparsed("phase", phase),
                Placeholder.parsed("time", PumpkinEvent.fmt(ev.timeLeft())),
                Placeholder.parsed("alive", String.valueOf(ev.aliveCount()))));
    }

    private void where(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.component("players-only"));
            return;
        }
        if (!player.hasPermission("pumpkinevent.where")) {
            sender.sendMessage(plugin.component("no-permission"));
            return;
        }
        if (!ev.isRunning()) {
            sender.sendMessage(plugin.component("not-running"));
            return;
        }

        Block ground = ev.getGround();
        String cx = String.valueOf(ground.getX());
        String cy = String.valueOf(ground.getY() + 1);
        String cz = String.valueOf(ground.getZ());

        if (!player.getWorld().equals(ev.getWorld())) {
            sender.sendMessage(plugin.component("where-other-world",
                    Placeholder.unparsed("world", ev.getWorld().getName()),
                    Placeholder.parsed("cx", cx),
                    Placeholder.parsed("cy", cy),
                    Placeholder.parsed("cz", cz)));
            return;
        }

        Location loc = player.getLocation();
        double dx = (ground.getX() + 0.5) - loc.getX();
        double dz = (ground.getZ() + 0.5) - loc.getZ();

        // Угол до цели относительно взгляда игрока (yaw: 0 = юг, 90 = запад)
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = targetYaw - loc.getYaw();
        rel = ((rel + 540) % 360) - 180;
        int index = ((int) Math.round(rel / 45.0) % 8 + 8) % 8;
        int distance = (int) Math.round(Math.sqrt(dx * dx + dz * dz));

        sender.sendMessage(plugin.component("where",
                Placeholder.parsed("cx", cx),
                Placeholder.parsed("cy", cy),
                Placeholder.parsed("cz", cz),
                Placeholder.unparsed("arrow", ARROWS[index]),
                Placeholder.parsed("dist", String.valueOf(distance))));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            List<String> options = new ArrayList<>(List.of("status", "where"));
            if (sender.hasPermission("pumpkinevent.admin")) {
                options.addAll(List.of("start", "stop", "reload"));
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (String option : options) {
                if (option.startsWith(prefix)) {
                    result.add(option);
                }
            }
        }
        return result;
    }
}
