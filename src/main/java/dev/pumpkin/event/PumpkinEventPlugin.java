package dev.pumpkin.event;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PumpkinEventPlugin extends JavaPlugin {

    private final MiniMessage mm = MiniMessage.miniMessage();
    private PumpkinEvent event;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        event = new PumpkinEvent(this);
        getServer().getPluginManager().registerEvents(new EventListener(this, event), this);

        PumpkinCommand command = new PumpkinCommand(this, event);
        PluginCommand pluginCommand = Objects.requireNonNull(
                getCommand("pumpkin"), "Команда pumpkin не найдена в plugin.yml");
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);

        // Если сервер выключился во время ивента — возвращаем блоки на место
        getServer().getScheduler().runTask(this, () -> PumpkinStructure.restoreBackup(this));

        event.startAutoScheduler();
        getLogger().info("PumpkinEvent включён.");
    }

    @Override
    public void onDisable() {
        if (event != null) {
            event.shutdown();
        }
    }

    private TagResolver resolver(TagResolver[] extra) {
        TagResolver prefix = Placeholder.parsed("prefix", getConfig().getString("messages.prefix", ""));
        return TagResolver.resolver(TagResolver.resolver(extra), prefix);
    }

    /** Одно сообщение из секции messages. */
    public Component component(String path, TagResolver... extra) {
        String raw = getConfig().getString("messages." + path);
        if (raw == null) {
            raw = "<red>Нет сообщения: messages." + path;
        }
        return mm.deserialize(raw, resolver(extra));
    }

    /** Многострочное сообщение (список строк) из секции messages. */
    public List<Component> lines(String path, TagResolver... extra) {
        TagResolver resolver = resolver(extra);
        List<Component> result = new ArrayList<>();
        for (String raw : getConfig().getStringList("messages." + path)) {
            result.add(mm.deserialize(raw, resolver));
        }
        return result;
    }
}
