package com.pilotplayz.duelarenas;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public final class DuelArenasPlugin extends JavaPlugin implements Listener, CommandExecutor {

    private Arena arena1v1;
    private Arena arena2v2;

    // Map of players to the arena they are currently in a MATCH for
    private final Map<UUID, Arena> activeMatchByPlayer = new HashMap<>();

    // Temporary in-memory corners for custom boxes before /confirm
    private final Map<String, Location> pendingBoxCorner1 = new HashMap<>();
    private final Map<String, Location> pendingBoxCorner2 = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadArenas();

        getServer().getPluginManager().registerEvents(this, this);

        registerCommand("arena1");
        registerCommand("arena2");

        getLogger().info("DuelArenas enabled.");
    }

    @Override
    public void onDisable() {
        if (arena1v1 != null) arena1v1.clearGlassBox();
        if (arena2v2 != null) arena2v2.clearGlassBox();
        getLogger().info("DuelArenas disabled.");
    }

    private void registerCommand(String name) {
        org.bukkit.command.PluginCommand cmd = getCommand(name);
        if (cmd == null) {
            getLogger().severe("Command '" + name + "' not found in plugin.yml – it will not be usable.");
            return;
        }
        cmd.setExecutor(this);
    }

    // ==============================
    // Arena loading & config helpers
    // ==============================

    private void loadArenas() {
        this.arena1v1 = loadArena("arena1", 2);
        this.arena2v2 = loadArena("arena2", 4);
    }

    private Arena loadArena(String key, int maxPlayers) {
        ConfigurationSection section = getConfig().getConfigurationSection(key);
        if (section == null) {
            getLogger().warning("No config section for " + key + ". Use /" + key + " commands to set it up.");
            return null;
        }

        double radius = section.getDouble("radius", 0);
        Location center = loadLocation(key + ".center");
        List<Location> spawnPoints = new ArrayList<>();

        for (int i = 1; i <= maxPlayers; i++) {
            Location loc = loadLocation(key + ".spawn" + i);
            spawnPoints.add(loc);
        }

        Location box1 = loadLocation(key + ".box1");
        Location box2 = loadLocation(key + ".box2");

        boolean usingBox = box1 != null && box2 != null
                && box1.getWorld() != null && box1.getWorld().equals(box2.getWorld());

        if (usingBox) {
            // Use middle of box as center if not set
            if (center == null) {
                double midX = (box1.getX() + box2.getX()) / 2.0;
                double midY = (box1.getY() + box2.getY()) / 2.0;
                double midZ = (box1.getZ() + box2.getZ()) / 2.0;
                center = new Location(box1.getWorld(), midX, midY, midZ);
                getLogger().info("Center for " + key + " auto-set to middle of custom box.");
            }
            // No need for radius or spawns when custom box exists
        } else {
            // Fallback: need center, radius, and spawn points configured
            if (center == null || radius <= 0) {
                getLogger().warning("Arena " + key + " is missing center or radius. Configure it using commands.");
                return null;
            }
            for (int i = 0; i < maxPlayers; i++) {
                if (spawnPoints.get(i) == null) {
                    getLogger().warning("Arena " + key + " is missing spawn" + (i + 1) + ". Configure it using commands.");
                    return null;
                }
            }
        }

        if (!usingBox && (box1 != null || box2 != null)) {
            getLogger().warning("Arena " + key + " has invalid custom box (world mismatch or missing). Ignoring box.");
            box1 = null;
            box2 = null;
        }

        getLogger().info("Loaded arena " + key + " (usingBox=" + usingBox + ") with maxPlayers=" + maxPlayers + ".");
        return new Arena(this, key, center, radius, spawnPoints, maxPlayers, box1, box2);
    }

    private void saveLocation(String path, Location loc) {
        getConfig().set(path + ".world", loc.getWorld().getName());
        getConfig().set(path + ".x", loc.getX());
        getConfig().set(path + ".y", loc.getY());
        getConfig().set(path + ".z", loc.getZ());
        getConfig().set(path + ".yaw", loc.getYaw());
        getConfig().set(path + ".pitch", loc.getPitch());
        saveConfig();
    }

    private Location loadLocation(String path) {
        if (!getConfig().contains(path + ".world")) {
            return null;
        }

        String worldName = getConfig().getString(path + ".world");
        if (worldName == null || worldName.isEmpty()) {
            getLogger().warning("Missing world name for " + path + ".world");
            return null;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            getLogger().warning("World '" + worldName + "' for " + path + " is not loaded.");
            return null;
        }

        double x = getConfig().getDouble(path + ".x");
        double y = getConfig().getDouble(path + ".y");
        double z = getConfig().getDouble(path + ".z");
        float yaw = (float) getConfig().getDouble(path + ".yaw", 0.0);
        float pitch = (float) getConfig().getDouble(path + ".pitch", 0.0);

        return new Location(world, x, y, z, yaw, pitch);
    }

    // ==============================
    // Movement detection
    // ==============================

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;

        Player player = event.getPlayer();

        if (arena1v1 != null) {
            checkArenaMovement(player, arena1v1);
        }
        if (arena2v2 != null) {
            checkArenaMovement(player, arena2v2);
        }
    }

    private void checkArenaMovement(Player player, Arena arena) {
        if (arena.isInside(player.getLocation())) {
            arena.onEnterRadius(player);
        } else {
            arena.onLeaveRadius(player);
        }
    }

    // ==============================
    // Damage control
    // ==============================

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;

        Player damagerPlayer = null;
        Entity damager = event.getDamager();

        if (damager instanceof Player) {
            damagerPlayer = (Player) damager;
        } else if (damager instanceof Projectile proj && proj.getShooter() instanceof Player) {
            damagerPlayer = (Player) proj.getShooter();
        }

        if (damagerPlayer == null) return;

        UUID vId = victim.getUniqueId();
        UUID dId = damagerPlayer.getUniqueId();

        Arena aVictim = activeMatchByPlayer.get(vId);
        Arena aDamager = activeMatchByPlayer.get(dId);

        // If neither is in a match but they are inside any arena area, block damage
        if (aVictim == null && aDamager == null) {
            if ((arena1v1 != null && arena1v1.isInside(victim.getLocation())) ||
                    (arena2v2 != null && arena2v2.isInside(victim.getLocation()))) {
                event.setCancelled(true);
            }
            return;
        }

        // If one is in a match and the other isn't, or they are in different arenas -> cancel
        if (aVictim == null || aDamager == null || aVictim != aDamager) {
            event.setCancelled(true);
            return;
        }

        // Same arena match, but check if PVP is enabled yet
        if (!aVictim.isPvpEnabled()) {
            event.setCancelled(true);
        }
    }

    // ==============================
    // Glass protection
    // ==============================

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.BLUE_STAINED_GLASS) return;

        Location loc = block.getLocation();
        boolean cancel = (arena1v1 != null && arena1v1.isGlassBlock(loc))
                || (arena2v2 != null && arena2v2.isGlassBlock(loc));

        if (cancel) {
            event.setCancelled(true);
        }
    }

    // ==============================
    // Cleanup on death / quit
    // ==============================

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Arena arena = activeMatchByPlayer.get(player.getUniqueId());
        if (arena != null) {
            arena.onPlayerEliminated(player);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        Arena arena = activeMatchByPlayer.get(player.getUniqueId());
        if (arena != null) {
            arena.onPlayerEliminated(player);
        } else {
            if (arena1v1 != null) arena1v1.onLeaveRadius(player);
            if (arena2v2 != null) arena2v2.onLeaveRadius(player);
        }
    }

    // ==============================
    // Command handling
    // ==============================

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if (!sender.hasPermission("duelarenas.admin")) {
            sender.sendMessage("You don't have permission to do that.");
            return true;
        }

        Player player = (Player) sender;
        String arenaKey = label.equalsIgnoreCase("arena1") ? "arena1" : "arena2";
        int maxPlayers = label.equalsIgnoreCase("arena1") ? 2 : 4;

        if (args.length < 1) {
            sendUsage(player, label, maxPlayers);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        // /arenaX 1 and /arenaX 2 = custom box corners
        if (sub.equals("1") || sub.equals("2")) {
            int index = Integer.parseInt(sub);
            Location loc = player.getLocation();
            if (index == 1) {
                pendingBoxCorner1.put(arenaKey, loc);
                player.sendMessage("§aSet custom box corner 1 for " + arenaKey + " at your current location.");
            } else {
                pendingBoxCorner2.put(arenaKey, loc);
                player.sendMessage("§aSet custom box corner 2 for " + arenaKey + " at your current location.");
            }
            player.sendMessage("§7Run §e/" + label + " confirm §7to save the custom box.");
            return true;
        }

        // /arenaX confirm = save custom box to config and auto-use as arena area
        if (sub.equalsIgnoreCase("confirm")) {
            Location c1 = pendingBoxCorner1.get(arenaKey);
            Location c2 = pendingBoxCorner2.get(arenaKey);
            if (c1 == null || c2 == null) {
                player.sendMessage("§cYou must set both corners first with /" + label + " 1 and /" + label + " 2.");
                return true;
            }
            if (!Objects.equals(c1.getWorld(), c2.getWorld())) {
                player.sendMessage("§cBoth corners must be in the same world.");
                return true;
            }

            saveLocation(arenaKey + ".box1", c1);
            saveLocation(arenaKey + ".box2", c2);

            // Also store center as middle of box (used as teleport base etc.)
            double midX = (c1.getX() + c2.getX()) / 2.0;
            double midY = (c1.getY() + c2.getY()) / 2.0;
            double midZ = (c1.getZ() + c2.getZ()) / 2.0;
            Location mid = new Location(c1.getWorld(), midX, midY, midZ, player.getLocation().getYaw(), player.getLocation().getPitch());
            saveLocation(arenaKey + ".center", mid);

            // Radius no longer needed when box exists; clear it to avoid confusion
            getConfig().set(arenaKey + ".radius", null);
            saveConfig();

            pendingBoxCorner1.remove(arenaKey);
            pendingBoxCorner2.remove(arenaKey);

            reloadConfig();
            loadArenas();

            player.sendMessage("§aCustom glass box for " + arenaKey + " saved and set as arena area.");
            player.sendMessage("§7You no longer need /" + label + " setradius or /" + label + " setspawn when using this box.");
            return true;
        }

        switch (sub) {
            case "setcenter":
                saveLocation(arenaKey + ".center", player.getLocation());
                player.sendMessage("Center for " + arenaKey + " set to your current location.");
                break;

            case "setradius":
                if (args.length < 2) {
                    player.sendMessage("Usage: /" + label + " setradius <number>");
                    return true;
                }
                try {
                    double r = Double.parseDouble(args[1]);
                    getConfig().set(arenaKey + ".radius", r);
                    saveConfig();
                    player.sendMessage("Radius for " + arenaKey + " set to " + r + ".");
                } catch (NumberFormatException e) {
                    player.sendMessage("Radius must be a number.");
                    return true;
                }
                break;

            case "setspawn1":
            case "setspawn2":
            case "setspawn3":
            case "setspawn4":
                int index = Integer.parseInt(sub.substring(sub.length() - 1)); // last char
                int maxNeeded = maxPlayers;
                if (index > maxNeeded) {
                    player.sendMessage("This arena only needs " + maxNeeded + " spawn points.");
                    return true;
                }
                saveLocation(arenaKey + ".spawn" + index, player.getLocation());
                player.sendMessage("Spawn " + index + " for " + arenaKey + " set to your current location.");
                break;

            default:
                sendUsage(player, label, maxPlayers);
                return true;
        }

        reloadConfig();
        loadArenas();
        return true;
    }

    private void sendUsage(Player player, String label, int maxPlayers) {
        player.sendMessage("§e/" + label + " 1 §7- Set custom box corner 1");
        player.sendMessage("§e/" + label + " 2 §7- Set custom box corner 2");
        player.sendMessage("§e/" + label + " confirm §7- Save custom glass box and use it as arena area");
        player.sendMessage("§7(If no custom box is set, you can still use radius + spawns:)");
        player.sendMessage("§e/" + label + " setcenter §7- Set arena center");
        player.sendMessage("§e/" + label + " setradius <number> §7- Set arena radius");
        for (int i = 1; i <= maxPlayers; i++) {
            player.sendMessage("§e/" + label + " setspawn" + i + " §7- Set spawn point " + i);
        }
    }

    // ==============================
    // Arena inner class
    // ==============================

    public class Arena {

        private final DuelArenasPlugin plugin;
        private final String id;
        private final Location center;
        private final double radius;
        private final double radiusSquared;
        private final List<Location> spawnPoints;
        private final int maxPlayers;

        // Optional custom box corners (if set)
        private final Location box1;
        private final Location box2;

        private final LinkedHashSet<UUID> waiting = new LinkedHashSet<>();
        private final HashSet<UUID> playing = new HashSet<>();

        private final Set<Location> glassBlocks = new HashSet<>();

        private boolean running = false;
        private boolean pvpEnabled = false;

        public Arena(DuelArenasPlugin plugin, String id, Location center, double radius,
                     List<Location> spawnPoints, int maxPlayers, Location box1, Location box2) {
            this.plugin = plugin;
            this.id = id;
            this.center = center;
            this.radius = radius;
            this.radiusSquared = radius * radius;
            this.spawnPoints = spawnPoints;
            this.maxPlayers = maxPlayers;
            this.box1 = box1;
            this.box2 = box2;
        }

        private boolean usingBox() {
            return box1 != null && box2 != null
                    && box1.getWorld() != null
                    && box1.getWorld().equals(box2.getWorld());
        }

        public boolean isInside(Location loc) {
            if (loc == null || loc.getWorld() == null) return false;

            if (usingBox() && loc.getWorld().equals(box1.getWorld())) {
                double x = loc.getX();
                double y = loc.getY();
                double z = loc.getZ();

                double minX = Math.min(box1.getX(), box2.getX());
                double maxX = Math.max(box1.getX(), box2.getX());
                double minY = Math.min(box1.getY(), box2.getY());
                double maxY = Math.max(box1.getY(), box2.getY());
                double minZ = Math.min(box1.getZ(), box2.getZ());
                double maxZ = Math.max(box1.getZ(), box2.getZ());

                return x >= minX && x <= maxX
                        && y >= minY && y <= maxY
                        && z >= minZ && z <= maxZ;
            }

            if (center == null || center.getWorld() == null || !center.getWorld().equals(loc.getWorld())) {
                return false;
            }
            return loc.distanceSquared(center) <= radiusSquared;
        }

        public boolean isPvpEnabled() {
            return pvpEnabled;
        }

        public boolean isGlassBlock(Location loc) {
            for (Location stored : glassBlocks) {
                if (stored.getWorld() == loc.getWorld()
                        && stored.getBlockX() == loc.getBlockX()
                        && stored.getBlockY() == loc.getBlockY()
                        && stored.getBlockZ() == loc.getBlockZ()) {
                    return true;
                }
            }
            return false;
        }

        public void onEnterRadius(Player player) {
            UUID id = player.getUniqueId();

            if (playing.contains(id)) return;
            if (waiting.contains(id)) return;

            waiting.add(id);
            player.sendMessage("§aYou joined the " + this.id + " duel queue! §7(" + waiting.size() + "/" + maxPlayers + ")");

            tryStartMatch();
        }

        public void onLeaveRadius(Player player) {
            UUID id = player.getUniqueId();
            if (waiting.remove(id)) {
                player.sendMessage("§cYou left the " + this.id + " duel queue.");
            }
        }

        private void tryStartMatch() {
            if (running) return;
            if (waiting.size() < maxPlayers) return;

            // Grab first maxPlayers from waiting
            List<UUID> matchPlayers = new ArrayList<>();
            Iterator<UUID> it = waiting.iterator();
            while (it.hasNext() && matchPlayers.size() < maxPlayers) {
                UUID uuid = it.next();
                matchPlayers.add(uuid);
                it.remove();
            }

            running = true;
            pvpEnabled = false;
            playing.clear();
            playing.addAll(matchPlayers);

            buildGlassBox();

            int i = 0;
            for (UUID uuid : matchPlayers) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    Location spawn = getSpawnLocation(i);
                    if (spawn != null) {
                        p.teleport(spawn);
                    } else {
                        p.sendMessage("§cSpawn location " + (i + 1) + " for " + id + " is not configured correctly.");
                    }
                    p.sendMessage("§eMatch starting in " + id + "! §7(2 seconds until fight)");
                    activeMatchByPlayer.put(uuid, this);
                }
                i++;
            }

            new BukkitRunnable() {
                @Override
                public void run() {
                    pvpEnabled = true;
                    for (UUID uuid : playing) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null && p.isOnline()) {
                            p.sendMessage("§cFIGHT!");
                        }
                    }
                }
            }.runTaskLater(plugin, 40L); // 2 seconds
        }

        private Location getSpawnLocation(int index) {
            // If custom box exists, auto-generate spawn points around its center
            if (usingBox()) {
                World world = box1.getWorld();
                double minX = Math.min(box1.getX(), box2.getX());
                double maxX = Math.max(box1.getX(), box2.getX());
                double minY = Math.min(box1.getY(), box2.getY());
                double maxY = Math.max(box1.getY(), box2.getY());
                double minZ = Math.min(box1.getZ(), box2.getZ());
                double maxZ = Math.max(box1.getZ(), box2.getZ());

                double cx = (minX + maxX) / 2.0;
                double cz = (minZ + maxZ) / 2.0;
                double y = minY + 1.0; // just above floor
                double offset = 1.5;

                if (maxPlayers == 2) {
                    double[][] offs = {{-offset, 0}, {offset, 0}};
                    if (index >= offs.length) index = offs.length - 1;
                    return new Location(world, cx + offs[index][0], y, cz + offs[index][1], 0f, 0f);
                } else {
                    double[][] offs = {
                            {-offset, 0},
                            {offset, 0},
                            {0, -offset},
                            {0, offset}
                    };
                    if (index >= offs.length) index = offs.length - 1;
                    return new Location(world, cx + offs[index][0], y, cz + offs[index][1], 0f, 0f);
                }
            }

            // Fallback to configured spawn points if present
            if (spawnPoints != null && index < spawnPoints.size()) {
                Location loc = spawnPoints.get(index);
                if (loc != null) return loc;
            }

            // Final fallback: center
            return center;
        }

        private void buildGlassBox() {
            clearGlassBox();

            if (center == null) return;
            World world = center.getWorld();
            if (world == null) return;

            if (usingBox()) {
                int minX = Math.min(box1.getBlockX(), box2.getBlockX());
                int maxX = Math.max(box1.getBlockX(), box2.getBlockX());
                int minY = Math.min(box1.getBlockY(), box2.getBlockY());
                int maxY = Math.max(box1.getBlockY(), box2.getBlockY());
                int minZ = Math.min(box1.getBlockZ(), box2.getBlockZ());
                int maxZ = Math.max(box1.getBlockZ(), box2.getBlockZ());

                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        for (int y = minY; y <= maxY; y++) {
                            boolean isFloor = (y == minY);
                            boolean isRoof = (y == maxY);
                            boolean isWall = (x == minX || x == maxX || z == minZ || z == maxZ);

                            if (isFloor) continue; // no floor
                            if (!isWall && !isRoof) continue; // only walls and roof

                            Block block = world.getBlockAt(x, y, z);
                            if (block.getType() == Material.AIR || block.isPassable()) {
                                block.setType(Material.BLUE_STAINED_GLASS, false);
                                glassBlocks.add(block.getLocation());
                            }
                        }
                    }
                }
            } else {
                int cx = center.getBlockX();
                int cz = center.getBlockZ();
                int baseY = center.getBlockY();
                int r = (int) Math.round(radius);
                int height = 5;

                for (int x = cx - r; x <= cx + r; x++) {
                    for (int z = cz - r; z <= cz + r; z++) {
                        for (int y = baseY + 1; y <= baseY + height; y++) {
                            boolean isWall = (x == cx - r || x == cx + r || z == cz - r || z == cz + r);
                            boolean isRoof = (y == baseY + height);
                            if (!isWall && !isRoof) continue;

                            Block block = world.getBlockAt(x, y, z);
                            if (block.getType() == Material.AIR || block.isPassable()) {
                                block.setType(Material.BLUE_STAINED_GLASS, false);
                                glassBlocks.add(block.getLocation());
                            }
                        }
                    }
                }
            }
        }

        public void clearGlassBox() {
            if (glassBlocks.isEmpty()) return;

            for (Location loc : glassBlocks) {
                Block b = loc.getBlock();
                if (b.getType() == Material.BLUE_STAINED_GLASS) {
                    b.setType(Material.AIR, false);
                }
            }
            glassBlocks.clear();
        }

        public void onPlayerEliminated(Player player) {
            UUID id = player.getUniqueId();
            if (!playing.contains(id)) return;

            playing.remove(id);
            activeMatchByPlayer.remove(id);

            if (playing.size() <= 1) {
                Player winner = null;
                if (playing.size() == 1) {
                    UUID winnerId = playing.iterator().next();
                    winner = Bukkit.getPlayer(winnerId);
                }

                // Reset state BEFORE scheduling next match
                running = false;
                pvpEnabled = false;
                clearGlassBox();

                for (UUID uuid : new HashSet<>(playing)) {
                    activeMatchByPlayer.remove(uuid);
                }
                playing.clear();

                if (winner != null) {
                    winner.sendMessage("§aYou won the duel in " + this.id + "!");
                    winner.sendMessage("§eYou will be teleported to spawn in 10 seconds.");

                    Player finalWinner = winner;
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (finalWinner.isOnline()) {
                                Location spawn = finalWinner.getWorld().getSpawnLocation();
                                finalWinner.teleport(spawn);
                                finalWinner.sendMessage("§aTeleported to spawn.");
                            }
                            // After 10s cooldown, try to start the next match
                            tryStartMatch();
                        }
                    }.runTaskLater(plugin, 200L); // 10 seconds
                } else {
                    // No winner (everyone died/quit) – still wait 10s before next match
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            tryStartMatch();
                        }
                    }.runTaskLater(plugin, 200L); // 10 seconds
                }
            }
        }
    }
}
