package dev.pumpkin.event;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class EventListener implements Listener {

    private static final String BYPASS = "pumpkinevent.bypass";

    private final PumpkinEventPlugin plugin;
    private final PumpkinEvent ev;

    public EventListener(PumpkinEventPlugin plugin, PumpkinEvent ev) {
        this.plugin = plugin;
        this.ev = ev;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!ev.isRunning()) {
            return;
        }
        Block block = e.getBlock();
        Player player = e.getPlayer();

        // Саму Великую Тыкву ломать нельзя
        if (ev.isStructure(block)) {
            e.setCancelled(true);
            player.sendActionBar(plugin.component("structure-protected"));
            return;
        }

        // Тыквы ивента — единственное, что можно ломать
        if (ev.isEventPumpkin(block)) {
            if (!ev.canCollect(player)) {
                e.setCancelled(true);
                player.sendActionBar(plugin.component("limit-reached",
                        Placeholder.parsed("limit", String.valueOf(ev.limit()))));
                return;
            }
            e.setDropItems(false);
            e.setExpToDrop(0);
            ev.collect(player, block);
            return;
        }

        // Остальное в зоне ивента защищено
        if (ev.inZone(block.getLocation()) && !player.hasPermission(BYPASS)) {
            e.setCancelled(true);
            player.sendActionBar(plugin.component("zone-protected"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Player player = e.getPlayer();
        if (ev.inZone(e.getBlock().getLocation()) && !player.hasPermission(BYPASS)) {
            e.setCancelled(true);
            player.sendActionBar(plugin.component("zone-protected"));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) {
        Player player = e.getPlayer();
        if (ev.inZone(e.getBlock().getLocation()) && !player.hasPermission(BYPASS)) {
            e.setCancelled(true);
            player.sendActionBar(plugin.component("zone-protected"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> ev.inZone(b.getLocation()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> ev.inZone(b.getLocation()));
    }

    /** Эндермены и т.п. не должны уносить тыквы и части структуры. */
    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        Block block = e.getBlock();
        if (ev.isStructure(block) || ev.isEventPumpkin(block)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ev.forgetPlayer(e.getPlayer().getUniqueId());
    }
}
