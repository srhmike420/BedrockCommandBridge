package com.flamebourne.bedrockcommandbridge;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.event.EventRegistrar;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.GeyserApi;
import org.geysermc.geyser.api.event.java.ServerDefineCommandsEvent;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class BedrockCommandBridge extends JavaPlugin implements Listener, EventRegistrar {
    private final Map<UUID, RuleSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> lastGeyserRoots = new ConcurrentHashMap<>();
    private volatile YamlConfiguration standalone;
    private volatile YamlConfiguration plHide;
    private volatile boolean plHideHookEnabled;
    private volatile boolean standaloneEnabled;
    private volatile boolean diagnostics;
    private volatile int refreshTask = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResource("commands.yml", false);
        reloadBridge();

        Bukkit.getPluginManager().registerEvents(this, this);
        GeyserApi.api().eventBus().register(this, this);

        long refresh = Math.max(20L, getConfig().getLong("refresh-interval-ticks", 100L));
        refreshTask = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, this::refreshOnlinePlayers, 20L, refresh);

        for (Player player : Bukkit.getOnlinePlayers()) refreshPlayer(player, false);
        getLogger().info("Enabled. Pl-Hide-Pro hook: " + plHideHookEnabled + ", standalone: " + standaloneEnabled + ". Geyser command suggestions must be enabled.");
    }

    @Override
    public void onDisable() {
        if (refreshTask != -1) Bukkit.getScheduler().cancelTask(refreshTask);
        snapshots.clear();
        lastGeyserRoots.clear();
    }

    @Subscribe
    public void onServerDefineCommands(ServerDefineCommandsEvent event) {
        UUID uuid = event.connection().javaUuid();
        if (diagnostics) {
            Set<String> roots = event.commands().stream()
                    .map(ServerDefineCommandsEvent.CommandInfo::name)
                    .map(s -> s.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toCollection(TreeSet::new));
            lastGeyserRoots.put(uuid, Collections.unmodifiableSet(roots));
        }

        RuleSnapshot snapshot = snapshots.get(uuid);
        if (snapshot == null) snapshot = fallbackSnapshot();
        if (snapshot.passthrough()) return;

        RuleSnapshot finalSnapshot = snapshot;
        event.commands().removeIf(info -> !finalSnapshot.allows(info.name()));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        // Permissions are most reliable after join; update the command tree one tick later.
        Bukkit.getScheduler().runTask(this, () -> refreshPlayer(event.getPlayer(), true));
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        refreshPlayer(event.getPlayer(), true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        snapshots.remove(uuid);
        lastGeyserRoots.remove(uuid);
    }

    private void refreshOnlinePlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) refreshPlayer(player, true);
    }

    private void refreshPlayer(Player player, boolean updateIfChanged) {
        RuleSnapshot next = calculateSnapshot(player);
        RuleSnapshot old = snapshots.put(player.getUniqueId(), next);
        if (updateIfChanged && !next.equals(old)) player.updateCommands();
    }

    private RuleSnapshot calculateSnapshot(Player player) {
        if (plHideHookEnabled && standaloneEnabled) return merge(resolvePlHide(player), resolveStandalone(player));
        if (plHideHookEnabled) return resolvePlHide(player);
        if (standaloneEnabled) return resolveStandalone(player);
        return RuleSnapshot.passthrough("no-sources-enabled");
    }

    private RuleSnapshot fallbackSnapshot() {
        RuleSnapshot pl = plHideHookEnabled ? resolveGroupWithoutPlayer(plHide, "default", "Pl-Hide-Pro") : null;
        RuleSnapshot standaloneFallback = standaloneEnabled ? resolveGroupWithoutPlayer(standalone, "default", "standalone") : null;
        if (pl != null && standaloneFallback != null) return merge(pl, standaloneFallback);
        if (pl != null) return pl;
        if (standaloneFallback != null) return standaloneFallback;
        return RuleSnapshot.passthrough("fallback");
    }

    private RuleSnapshot resolveStandalone(Player player) {
        YamlConfiguration cfg = standalone;
        if (cfg == null) return RuleSnapshot.passthrough("standalone-missing");
        if (cfg.getString("mode", "passthrough").equalsIgnoreCase("passthrough")) {
            return RuleSnapshot.passthrough("standalone");
        }
        return resolveConfiguredGroup(player, cfg, "bedrockcommandbridge.group.", true, "standalone");
    }

    private RuleSnapshot resolvePlHide(Player player) {
        YamlConfiguration cfg = plHide;
        if (cfg == null) {
            getLogger().warning("Pl-Hide-Pro hook is enabled, but its config could not be loaded." + (standaloneEnabled ? " Using standalone rules." : " Passing commands through."));
            return standaloneEnabled ? resolveStandalone(player) : RuleSnapshot.passthrough("Pl-Hide-Pro-missing");
        }
        return resolveConfiguredGroup(player, cfg, "plhide.group.", true, "Pl-Hide-Pro");
    }

    private RuleSnapshot resolveConfiguredGroup(Player player, YamlConfiguration cfg, String permissionPrefix,
                                                boolean opGroupAutomatic, String source) {
        ConfigurationSection groupsSection = cfg.getConfigurationSection("groups");
        if (groupsSection == null) return RuleSnapshot.passthrough(source + "-no-groups");
        Map<String, CommandRules.GroupDef> groups = readGroups(groupsSection);
        String selected = "default";
        int best = groups.getOrDefault("default", new CommandRules.GroupDef(0, List.of(), List.of())).priority();

        for (Map.Entry<String, CommandRules.GroupDef> entry : groups.entrySet()) {
            String group = entry.getKey();
            boolean applies = (opGroupAutomatic && group.equalsIgnoreCase("op") && player.isOp())
                    || player.hasPermission(permissionPrefix + group);
            if (applies && entry.getValue().priority() >= best) {
                selected = group;
                best = entry.getValue().priority();
            }
        }
        Set<String> roots = CommandRules.resolveInherited(selected, groups);
        return new RuleSnapshot(selected, roots, roots.contains("*"), source);
    }

    private RuleSnapshot resolveGroupWithoutPlayer(YamlConfiguration cfg, String group, String source) {
        if (cfg == null) return null;
        if (source.equals("standalone") && cfg.getString("mode", "passthrough").equalsIgnoreCase("passthrough")) {
            return RuleSnapshot.passthrough(source);
        }
        ConfigurationSection section = cfg.getConfigurationSection("groups");
        if (section == null) return null;
        Set<String> roots = CommandRules.resolveInherited(group, readGroups(section));
        return new RuleSnapshot(group, roots, roots.contains("*"), source);
    }

    private Map<String, CommandRules.GroupDef> readGroups(ConfigurationSection section) {
        LinkedHashMap<String, CommandRules.GroupDef> result = new LinkedHashMap<>();
        for (String name : section.getKeys(false)) {
            String path = name + ".";
            result.put(name, new CommandRules.GroupDef(
                    section.getInt(path + "priority", 0),
                    List.copyOf(section.getStringList(path + "inherited-groups")),
                    List.copyOf(section.getStringList(path + "tabcomplete"))));
        }
        return result;
    }

    private RuleSnapshot merge(RuleSnapshot a, RuleSnapshot b) {
        if (a.passthrough() || b.passthrough()) return RuleSnapshot.passthrough(a.source() + "+" + b.source());
        LinkedHashSet<String> merged = new LinkedHashSet<>(a.allowedRoots());
        merged.addAll(b.allowedRoots());
        return new RuleSnapshot(a.group() + "+" + b.group(), Collections.unmodifiableSet(merged), merged.contains("*"), a.source() + "+" + b.source());
    }

    private void reloadBridge() {
        reloadConfig();
        plHideHookEnabled = getConfig().getBoolean("plhide-hook.enabled", false);
        standaloneEnabled = getConfig().getBoolean("standalone.enabled", true);
        diagnostics = getConfig().getBoolean("diagnostics", true);

        String standaloneName = getConfig().getString("standalone.config-file", "commands.yml");
        standalone = YamlConfiguration.loadConfiguration(new File(getDataFolder(), standaloneName));

        String plPath = getConfig().getString("plhide-hook.config-path", "plugins/Pl-Hide-Pro/config.yml");
        File plFile = new File(plPath);
        plHide = plFile.isFile() ? YamlConfiguration.loadConfiguration(plFile) : null;

        snapshots.clear();
        for (Player player : Bukkit.getOnlinePlayers()) refreshPlayer(player, false);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("§6BedrockCommandBridge §fv" + getDescription().getVersion());
            sender.sendMessage("§7Pl-Hide-Pro hook: " + (plHideHookEnabled ? "§aENABLED" : "§cDISABLED") + " §8| §7Config: " + (plHide != null ? "§aFOUND" : "§cNOT FOUND"));
            sender.sendMessage("§7Standalone: " + (standaloneEnabled ? "§aENABLED" : "§cDISABLED"));
            sender.sendMessage("§7Cached players: §f" + snapshots.size() + " §8| §7Diagnostics: §f" + diagnostics);
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            reloadBridge();
            for (Player player : Bukkit.getOnlinePlayers()) player.updateCommands();
            sender.sendMessage("§aBedrockCommandBridge reloaded.");
            return true;
        }
        if (args[0].equalsIgnoreCase("dump")) {
            if (args.length < 2) {
                sender.sendMessage("§cUsage: /" + label + " dump <player>");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§cThat player is not online.");
                return true;
            }
            UUID uuid = target.getUniqueId();
            RuleSnapshot snapshot = snapshots.get(uuid);
            Set<String> roots = lastGeyserRoots.get(uuid);
            sender.sendMessage("§6Bedrock command dump for §f" + target.getName());
            sender.sendMessage("§7Rule: §f" + (snapshot == null ? "none" : snapshot.source() + "/" + snapshot.group()));
            if (roots == null) {
                sender.sendMessage("§eNo Geyser command event captured yet. The player may be Java, or commands have not been resent yet.");
            } else {
                sender.sendMessage("§7Geyser supplied §f" + roots.size() + "§7 roots:");
                sender.sendMessage("§f" + String.join(", ", roots));
            }
            return true;
        }
        sender.sendMessage("§cUsage: /" + label + " <reload|status|dump>");
        return true;
    }
}
