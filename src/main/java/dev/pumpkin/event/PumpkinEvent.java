package dev.pumpkin.event;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Вся логика ивента «Великая Тыква». */
public final class PumpkinEvent {

    private record Spawned(Block block, BlockData original) {
    }

    private record Reward(Material material, int min, int max, int weight) {
    }

    private final PumpkinEventPlugin plugin;
    private final Random random = new Random();

    private boolean running;
    private boolean opened;
    private int elapsed;
    private int duration;
    private int prepSeconds;

    private World world;
    private Block ground;
    private PumpkinStructure structure;
    private BossBar bar;
    private BukkitTask task;
    private BukkitTask autoTask;
    private int autoCounter;

    private final Map<Long, Spawned> pumpkins = new HashMap<>();
    private final Map<UUID, Integer> counts = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final List<Reward> rewards = new ArrayList<>();
    private final List<int[]> tickets = new ArrayList<>();
    private final Set<UUID> barViewers = new HashSet<>();

    public PumpkinEvent(PumpkinEventPlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    // ------------------------------------------------------------------
    // Геттеры для команд и слушателей

    public boolean isRunning() {
        return running;
    }

    public boolean isPreparing() {
        return running && !opened;
    }

    public World getWorld() {
        return world;
    }

    public Block getGround() {
        return ground;
    }

    public int timeLeft() {
        return Math.max(0, duration - elapsed);
    }

    public int aliveCount() {
        return pumpkins.size();
    }

    public int limit() {
        return cfg().getInt("rewards.per-player-limit", 20);
    }

    public boolean isStructure(Block block) {
        return running && structure != null && structure.contains(block);
    }

    public boolean isEventPumpkin(Block block) {
        return running && block.getWorld().equals(world) && pumpkins.containsKey(block.getBlockKey());
    }

    public boolean inZone(Location loc) {
        if (!running || !cfg().getBoolean("protection.enabled", true)) {
            return false;
        }
        if (loc.getWorld() == null || !loc.getWorld().equals(world)) {
            return false;
        }
        double r = cfg().getDouble("protection.radius", 30);
        double dx = loc.getX() - (ground.getX() + 0.5);
        double dz = loc.getZ() - (ground.getZ() + 0.5);
        return dx * dx + dz * dz <= r * r;
    }

    public void forgetPlayer(UUID id) {
        barViewers.remove(id);
    }

    // ------------------------------------------------------------------
    // Запуск

    public boolean start(CommandSender sender, Integer fixedX, Integer fixedZ) {
        if (running) {
            sender.sendMessage(plugin.component("already-running"));
            return false;
        }
        World w = Bukkit.getWorld(cfg().getString("event.world", "world"));
        if (w == null) {
            sender.sendMessage(plugin.component("no-world"));
            return false;
        }

        Block g = (fixedX != null && fixedZ != null) ? groundAt(w, fixedX, fixedZ) : findSpot(w);
        if (g == null) {
            sender.sendMessage(plugin.component("no-spot"));
            return false;
        }

        loadRewards();
        this.world = w;
        this.ground = g;
        this.duration = Math.max(60, cfg().getInt("event.duration-seconds", 900));
        this.prepSeconds = Math.min(duration - 30, Math.max(5, cfg().getInt("event.prepare-seconds", 180)));
        this.elapsed = 0;
        this.opened = false;
        pumpkins.clear();
        counts.clear();
        names.clear();
        barViewers.clear();

        structure = new PumpkinStructure(plugin, w, g);
        structure.build();
        addTickets();

        bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
        running = true;

        TagResolver[] coords = coordResolvers();
        w.strikeLightningEffect(g.getLocation().add(0.5, 1, 0.5));
        for (Component line : plugin.lines("spawned", coords)) {
            Bukkit.broadcast(line);
        }
        showTitle(Bukkit.getOnlinePlayers(), "title-spawned", "subtitle-spawned", coords);
        play(Bukkit.getOnlinePlayers(), "appear", 1f, 0.8f);

        updateBar(true);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        return true;
    }

    private TagResolver[] coordResolvers() {
        return new TagResolver[]{
                Placeholder.parsed("cx", String.valueOf(ground.getX())),
                Placeholder.parsed("cy", String.valueOf(ground.getY() + 1)),
                Placeholder.parsed("cz", String.valueOf(ground.getZ())),
                Placeholder.parsed("prep", fmt(prepSeconds)),
                Placeholder.parsed("total", fmt(duration))
        };
    }

    // ------------------------------------------------------------------
    // Поиск места

    private Block groundAt(World w, int x, int z) {
        return w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
    }

    private Block findSpot(World w) {
        double cx = cfg().getDouble("location.center-x", 0);
        double cz = cfg().getDouble("location.center-z", 0);
        double minR = cfg().getDouble("location.min-radius", 300);
        double maxR = Math.max(minR + 1, cfg().getDouble("location.max-radius", 2000));

        for (int i = 0; i < 40; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double r = minR + random.nextDouble() * (maxR - minR);
            int x = (int) Math.round(cx + Math.cos(angle) * r);
            int z = (int) Math.round(cz + Math.sin(angle) * r);
            if (suitable(w, x, z)) {
                return groundAt(w, x, z);
            }
        }
        return null;
    }

    private boolean suitable(World w, int x, int z) {
        if (!w.getWorldBorder().isInside(new Location(w, x, 64, z))) {
            return false;
        }
        int maxDiff = cfg().getInt("location.max-height-difference", 3);
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int[][] points = {{0, 0}, {8, 0}, {-8, 0}, {0, 8}, {0, -8}, {6, 6}, {-6, -6}, {6, -6}, {-6, 6}};
        for (int[] p : points) {
            Block b = groundAt(w, x + p[0], z + p[1]);
            if (b.isLiquid() || !b.getType().isSolid() || Tag.LOGS.isTagged(b.getType())) {
                return false;
            }
            min = Math.min(min, b.getY());
            max = Math.max(max, b.getY());
        }
        return max - min <= maxDiff;
    }

    private void addTickets() {
        int r = (int) cfg().getDouble("pumpkins.max-distance", 20) + 8;
        for (int cx = (ground.getX() - r) >> 4; cx <= (ground.getX() + r) >> 4; cx++) {
            for (int cz = (ground.getZ() - r) >> 4; cz <= (ground.getZ() + r) >> 4; cz++) {
                world.addPluginChunkTicket(cx, cz, plugin);
                tickets.add(new int[]{cx, cz});
            }
        }
    }

    private void removeTickets() {
        for (int[] t : tickets) {
            world.removePluginChunkTicket(t[0], t[1], plugin);
        }
        tickets.clear();
    }

    // ------------------------------------------------------------------
    // Главный цикл (раз в секунду)

    private void tick() {
        if (!running) {
            return;
        }
        elapsed++;
        if (elapsed >= duration) {
            finish(false);
            return;
        }

        boolean prep = elapsed < prepSeconds;
        if (!prep && !opened) {
            open();
        }

        updateBar(prep);
        spawnEffects();

        if (prep) {
            int left = prepSeconds - elapsed;
            if (left <= 10) {
                play(Bukkit.getOnlinePlayers(), "countdown", 0.8f, left <= 3 ? 1.6f : 1.0f);
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendActionBar(plugin.component("actionbar-countdown",
                            Placeholder.parsed("time", String.valueOf(left))));
                }
            }
            if (left == 60 || left == 30) {
                Bukkit.broadcast(plugin.component("prep-reminder", Placeholder.parsed("time", fmt(left))));
            }
            return;
        }

        int active = elapsed - prepSeconds;

        int every = Math.max(1, cfg().getInt("pumpkins.spawn-interval-seconds", 4));
        if (active % every == 0 && pumpkins.size() < cfg().getInt("pumpkins.max-alive", 15)) {
            spawnPumpkin();
        }

        int interval = Math.max(10, cfg().getInt("waves.interval-seconds", 180));
        int warn = Math.max(0, cfg().getInt("waves.warning-seconds", 5));
        if (warn > 0 && active > 0 && (active + warn) % interval == 0 && elapsed + warn < duration) {
            warnWave(warn);
        }
        if (active > 0 && active % interval == 0) {
            wave();
        }

        if (duration - elapsed == 60) {
            Bukkit.broadcast(plugin.component("last-minute"));
            play(Bukkit.getOnlinePlayers(), "last-minute", 1f, 1f);
        }
    }

    private void open() {
        opened = true;
        int initial = cfg().getInt("pumpkins.initial", 8);
        for (int i = 0; i < initial; i++) {
            spawnPumpkin();
        }
        TagResolver time = Placeholder.parsed("time", fmt(duration - prepSeconds));
        for (Component line : plugin.lines("opened", time)) {
            Bukkit.broadcast(line);
        }
        showTitle(Bukkit.getOnlinePlayers(), "title-opened", "subtitle-opened", time);
        play(Bukkit.getOnlinePlayers(), "open", 1f, 1f);
        world.strikeLightningEffect(ground.getLocation().add(0.5, 1, 0.5));
    }

    private void updateBar(boolean prep) {
        if (prep) {
            int left = prepSeconds - elapsed;
            bar.name(plugin.component("bossbar-prep", Placeholder.parsed("time", fmt(left))));
            bar.progress(clamp((float) left / prepSeconds));
            bar.color(BossBar.Color.PURPLE);
        } else {
            int left = duration - elapsed;
            bar.name(plugin.component("bossbar-active",
                    Placeholder.parsed("time", fmt(left)),
                    Placeholder.parsed("alive", String.valueOf(pumpkins.size()))));
            bar.progress(clamp((float) left / (duration - prepSeconds)));
            bar.color(left <= 60 ? BossBar.Color.RED : BossBar.Color.YELLOW);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (barViewers.add(p.getUniqueId())) {
                p.showBossBar(bar);
            }
        }
    }

    private void spawnEffects() {
        Location top = ground.getLocation().add(0.5, PumpkinStructure.HEIGHT + 2, 0.5);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, top, 10, 1.2, 1.0, 1.2, 0.02);
        for (Spawned s : pumpkins.values()) {
            world.spawnParticle(Particle.FLAME, s.block().getLocation().add(0.5, 1.3, 0.5),
                    2, 0.15, 0.15, 0.15, 0.01);
        }
    }

    // ------------------------------------------------------------------
    // Тыквы

    private boolean spawnPumpkin() {
        double min = cfg().getDouble("pumpkins.min-distance", 9);
        double max = Math.max(min + 1, cfg().getDouble("pumpkins.max-distance", 20));

        for (int i = 0; i < 25; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double r = min + random.nextDouble() * (max - min);
            int x = ground.getX() + (int) Math.round(Math.cos(angle) * r);
            int z = ground.getZ() + (int) Math.round(Math.sin(angle) * r);

            Block base = groundAt(world, x, z);
            if (base.isLiquid() || !base.getType().isSolid()) {
                continue;
            }
            if (Math.abs(base.getY() - ground.getY()) > 6) {
                continue;
            }
            Block spot = base.getRelative(BlockFace.UP);
            boolean free = spot.getType().isAir() || (spot.isPassable() && !spot.isLiquid());
            if (!free || pumpkins.containsKey(spot.getBlockKey())
                    || (structure != null && structure.contains(spot))) {
                continue;
            }

            pumpkins.put(spot.getBlockKey(), new Spawned(spot, spot.getBlockData()));
            spot.setType(Material.PUMPKIN, false);
            world.spawnParticle(Particle.SOUL, spot.getLocation().add(0.5, 0.5, 0.5), 15, 0.3, 0.3, 0.3, 0.02);
            return true;
        }
        return false;
    }

    public boolean canCollect(Player player) {
        int cap = limit();
        return cap <= 0 || counts.getOrDefault(player.getUniqueId(), 0) < cap;
    }

    /** Игрок сломал тыкву ивента — выдаём награду. */
    public void collect(Player player, Block block) {
        Spawned s = pumpkins.remove(block.getBlockKey());
        if (s == null) {
            return;
        }
        UUID id = player.getUniqueId();
        int count = counts.merge(id, 1, Integer::sum);
        names.put(id, player.getName());

        int rolls = Math.max(1, cfg().getInt("rewards.rolls-per-pumpkin", 2));
        Map<Material, Integer> got = new LinkedHashMap<>();
        for (int i = 0; i < rolls; i++) {
            Reward reward = rollReward();
            if (reward == null) {
                break;
            }
            int amount = reward.min() + random.nextInt(Math.max(1, reward.max() - reward.min() + 1));
            got.merge(reward.material(), amount, Integer::sum);
        }

        Component list = Component.empty();
        boolean first = true;
        for (Map.Entry<Material, Integer> e : got.entrySet()) {
            giveItem(player, e.getKey(), e.getValue());
            if (!first) {
                list = list.append(Component.text(", ", NamedTextColor.GRAY));
            }
            list = list.append(Component.text("+" + e.getValue() + " ", NamedTextColor.WHITE))
                    .append(Component.translatable(e.getKey().translationKey(), NamedTextColor.YELLOW));
            first = false;
        }

        int xp = cfg().getInt("rewards.xp-per-pumpkin", 3);
        if (xp > 0) {
            player.giveExp(xp);
        }

        int cap = limit();
        player.sendActionBar(plugin.component("collected",
                Placeholder.parsed("count", String.valueOf(count)),
                Placeholder.parsed("limit", cap <= 0 ? "∞" : String.valueOf(cap)),
                Placeholder.component("reward", list)));
        play(List.of(player), "collect", 1f, 1.2f);
        world.spawnParticle(Particle.FLAME, block.getLocation().add(0.5, 0.5, 0.5), 20, 0.3, 0.3, 0.3, 0.05);
    }

    private void giveItem(Player player, Material material, int amount) {
        Map<Integer, ItemStack> left = player.getInventory().addItem(new ItemStack(material, amount));
        for (ItemStack rest : left.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
    }

    private void loadRewards() {
        rewards.clear();
        for (Map<?, ?> m : cfg().getMapList("rewards.items")) {
            Material mat = Material.matchMaterial(String.valueOf(m.get("material")));
            if (mat == null || mat.isAir() || !mat.isItem()) {
                plugin.getLogger().warning("rewards.items: неизвестный предмет " + m.get("material"));
                continue;
            }
            int min = Math.max(1, num(m.get("min"), 1));
            int max = Math.min(64, Math.max(min, num(m.get("max"), min)));
            int weight = Math.max(1, num(m.get("weight"), 1));
            rewards.add(new Reward(mat, min, max, weight));
        }
        if (rewards.isEmpty()) {
            rewards.add(new Reward(Material.COAL, 1, 3, 1));
        }
    }

    private Reward rollReward() {
        int total = 0;
        for (Reward r : rewards) {
            total += r.weight();
        }
        if (total <= 0) {
            return null;
        }
        int roll = random.nextInt(total);
        for (Reward r : rewards) {
            roll -= r.weight();
            if (roll < 0) {
                return r;
            }
        }
        return rewards.get(0);
    }

    private static int num(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    // ------------------------------------------------------------------
    // Волны: подсветка + тьма

    private List<Player> targets() {
        boolean onlyWorld = cfg().getBoolean("waves.only-event-world", true);
        List<Player> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!onlyWorld || p.getWorld().equals(world)) {
                list.add(p);
            }
        }
        return list;
    }

    private void warnWave(int seconds) {
        TagResolver time = Placeholder.parsed("time", String.valueOf(seconds));
        Bukkit.broadcast(plugin.component("warning", time));
        List<Player> list = targets();
        showTitle(list, "title-warning", "subtitle-warning", time);
        play(list, "warning", 1f, 0.7f);
    }

    private void wave() {
        int glow = cfg().getInt("waves.glow-seconds", 20);
        int dark = cfg().getInt("waves.darkness-seconds", 12);
        List<Player> list = targets();
        for (Player p : list) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, glow * 20, 0, false, false, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, dark * 20, 0, false, false, true));
        }
        TagResolver[] r = {
                Placeholder.parsed("glow", String.valueOf(glow)),
                Placeholder.parsed("dark", String.valueOf(dark))
        };
        for (Component line : plugin.lines("wave", r)) {
            Bukkit.broadcast(line);
        }
        showTitle(list, "title-wave", "subtitle-wave", r);
        play(list, "wave", 1f, 0.8f);
    }

    // ------------------------------------------------------------------
    // Завершение

    public void finish(boolean byAdmin) {
        if (!running) {
            return;
        }
        running = false;
        if (task != null) {
            task.cancel();
            task = null;
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (barViewers.contains(p.getUniqueId())) {
                p.hideBossBar(bar);
            }
            if (byAdmin) {
                p.removePotionEffect(PotionEffectType.GLOWING);
                p.removePotionEffect(PotionEffectType.DARKNESS);
            }
        }
        barViewers.clear();

        if (byAdmin) {
            Bukkit.broadcast(plugin.component("stopped"));
        } else {
            for (Component line : plugin.lines("finished")) {
                Bukkit.broadcast(line);
            }
            List<Map.Entry<UUID, Integer>> top = new ArrayList<>(counts.entrySet());
            top.sort(Map.Entry.<UUID, Integer>comparingByValue().reversed());
            if (top.isEmpty()) {
                Bukkit.broadcast(plugin.component("no-participants"));
            } else {
                int place = 1;
                for (Map.Entry<UUID, Integer> e : top.subList(0, Math.min(3, top.size()))) {
                    Bukkit.broadcast(plugin.component("top-line",
                            Placeholder.parsed("place", String.valueOf(place++)),
                            Placeholder.unparsed("player", names.getOrDefault(e.getKey(), "???")),
                            Placeholder.parsed("count", String.valueOf(e.getValue()))));
                }
            }
            for (Component line : plugin.lines("finished-footer")) {
                Bukkit.broadcast(line);
            }
            play(Bukkit.getOnlinePlayers(), "finish", 1f, 1f);
        }
        cleanup();
    }

    private void cleanup() {
        for (Spawned s : pumpkins.values()) {
            if (s.block().getType() == Material.PUMPKIN) {
                s.block().setBlockData(s.original(), false);
            }
        }
        pumpkins.clear();
        if (structure != null) {
            structure.restore();
            structure = null;
        }
        removeTickets();
    }

    /** Вызывается при выключении плагина/сервера. */
    public void shutdown() {
        if (autoTask != null) {
            autoTask.cancel();
            autoTask = null;
        }
        if (running) {
            running = false;
            if (task != null) {
                task.cancel();
                task = null;
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.hideBossBar(bar);
            }
            barViewers.clear();
            cleanup();
        }
    }

    public void startAutoScheduler() {
        autoTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (running || !cfg().getBoolean("auto-start.enabled", false)) {
                return;
            }
            autoCounter++;
            if (autoCounter < cfg().getInt("auto-start.interval-minutes", 120)) {
                return;
            }
            if (Bukkit.getOnlinePlayers().size() < cfg().getInt("auto-start.min-players", 1)) {
                return;
            }
            autoCounter = 0;
            start(Bukkit.getConsoleSender(), null, null);
        }, 1200L, 1200L);
    }

    // ------------------------------------------------------------------
    // Утилиты

    private void showTitle(Collection<? extends Player> players, String title, String subtitle,
                           TagResolver... resolvers) {
        Title t = Title.title(
                plugin.component(title, resolvers),
                plugin.component(subtitle, resolvers),
                Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofMillis(800)));
        for (Player p : players) {
            p.showTitle(t);
        }
    }

    private void play(Collection<? extends Player> players, String key, float volume, float pitch) {
        String sound = cfg().getString("sounds." + key);
        if (sound == null || sound.isBlank()) {
            return;
        }
        for (Player p : players) {
            p.playSound(p.getLocation(), sound, SoundCategory.MASTER, volume, pitch);
        }
    }

    private static float clamp(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    static String fmt(int seconds) {
        return String.format("%02d:%02d", seconds / 60, seconds % 60);
    }
}
