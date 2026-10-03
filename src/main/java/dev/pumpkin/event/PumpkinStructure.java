package dev.pumpkin.event;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Огромная тыква из блоков. Все заменённые блоки запоминаются
 * (и дублируются в backup.yml), чтобы в конце ивента вернуть мир как был.
 */
final class PumpkinStructure {

    static final int RX = 6;
    static final int RY = 5;
    static final int RZ = 6;
    /** Примерная высота вместе со стеблем (в блоках над землёй). */
    static final int HEIGHT = RY * 2 + 4;

    private record Placed(Block block, BlockData original) {
    }

    private final Plugin plugin;
    private final World world;
    private final Block ground;

    private final List<Placed> placed = new ArrayList<>();
    private final Set<Long> keys = new HashSet<>();

    private final BlockData concrete = Material.ORANGE_CONCRETE.createBlockData();
    private final BlockData terracotta = Material.ORANGE_TERRACOTTA.createBlockData();
    private final BlockData stem = Material.GREEN_CONCRETE.createBlockData();
    private final BlockData lantern;

    PumpkinStructure(Plugin plugin, World world, Block ground) {
        this.plugin = plugin;
        this.world = world;
        this.ground = ground;

        Directional face = (Directional) Material.JACK_O_LANTERN.createBlockData();
        face.setFacing(BlockFace.SOUTH);
        this.lantern = face;
    }

    void build() {
        int cx = ground.getX();
        int cz = ground.getZ();
        int cy = ground.getY() + RY - 1;
        int minY = ground.getY() + 1;

        for (int dx = -RX; dx <= RX; dx++) {
            for (int dy = -RY; dy <= RY; dy++) {
                for (int dz = -RZ; dz <= RZ; dz++) {
                    if (!inside(dx, dy, dz) || isInterior(dx, dy, dz)) {
                        continue;
                    }
                    int y = cy + dy;
                    if (y < minY) {
                        continue;
                    }
                    place(world.getBlockAt(cx + dx, y, cz + dz), pick(dx, dy, dz));
                }
            }
        }

        // Стебель
        int topY = cy + RY;
        for (int i = 1; i <= 3; i++) {
            place(world.getBlockAt(cx, topY + i, cz), stem);
        }
        place(world.getBlockAt(cx + 1, topY + 3, cz), stem);
        place(world.getBlockAt(cx + 1, topY + 4, cz), stem);

        saveBackup();
    }

    boolean contains(Block block) {
        return block.getWorld().equals(world) && keys.contains(block.getBlockKey());
    }

    void restore() {
        for (Placed p : placed) {
            p.block().setBlockData(p.original(), false);
        }
        placed.clear();
        keys.clear();
        File file = backupFile(plugin);
        if (file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    // ------------------------------------------------------------------

    private static boolean inside(int dx, int dy, int dz) {
        return (double) (dx * dx) / (RX * RX)
                + (double) (dy * dy) / (RY * RY)
                + (double) (dz * dz) / (RZ * RZ) <= 1.0;
    }

    /** Блок внутри эллипсоида, у которого все соседи тоже внутри (т.е. не оболочка). */
    private static boolean isInterior(int dx, int dy, int dz) {
        return inside(dx + 1, dy, dz) && inside(dx - 1, dy, dz)
                && inside(dx, dy + 1, dz) && inside(dx, dy - 1, dz)
                && inside(dx, dy, dz + 1) && inside(dx, dy, dz - 1);
    }

    private BlockData pick(int dx, int dy, int dz) {
        if (dz > 0 && isFace(dx, dy)) {
            return lantern;
        }
        // «Дольки» тыквы: чередуем два оттенка по углу
        double angle = Math.atan2(dz, dx) + Math.PI;
        int segment = (int) (angle / (Math.PI * 2) * 12);
        return (segment % 2 == 0) ? concrete : terracotta;
    }

    /** Лицо смотрит на юг: два глаза-треугольника и зубастый рот. */
    private static boolean isFace(int dx, int dy) {
        int ax = Math.abs(dx);
        boolean eye = (dy == 3 && ax == 3)
                || ((dy == 2 || dy == 1) && ax >= 2 && ax <= 4);
        boolean mouth = (dy == -1 && ax <= 4)
                || (dy == -2 && ax <= 4 && ax % 2 == 0);
        return eye || mouth;
    }

    private void place(Block block, BlockData data) {
        if (!keys.add(block.getBlockKey())) {
            return;
        }
        placed.add(new Placed(block, block.getBlockData()));
        block.setBlockData(data, false);
    }

    // ------------------------------------------------------------------
    // Резервная копия на случай аварийного выключения сервера

    private static File backupFile(Plugin plugin) {
        return new File(plugin.getDataFolder(), "backup.yml");
    }

    private void saveBackup() {
        List<String> lines = new ArrayList<>(placed.size());
        for (Placed p : placed) {
            Block b = p.block();
            lines.add(b.getX() + ";" + b.getY() + ";" + b.getZ() + ";" + p.original().getAsString());
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("world", world.getName());
        yaml.set("blocks", lines);
        try {
            //noinspection ResultOfMethodCallIgnored
            plugin.getDataFolder().mkdirs();
            yaml.save(backupFile(plugin));
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить backup.yml: " + e.getMessage());
        }
    }

    /** Вызывается при старте сервера: если ивент оборвался, возвращает блоки. */
    static void restoreBackup(Plugin plugin) {
        File file = backupFile(plugin);
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        World world = Bukkit.getWorld(yaml.getString("world", ""));
        if (world == null) {
            plugin.getLogger().warning("backup.yml: мир не найден, восстановление отложено.");
            return;
        }
        int restored = 0;
        for (String line : yaml.getStringList("blocks")) {
            String[] parts = line.split(";", 4);
            if (parts.length < 4) {
                continue;
            }
            try {
                int x = Integer.parseInt(parts[0]);
                int y = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[2]);
                world.getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData(parts[3]), false);
                restored++;
            } catch (IllegalArgumentException ignored) {
                // битая строка — пропускаем
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        plugin.getLogger().info("Восстановлено блоков после прерванного ивента: " + restored);
    }
}
