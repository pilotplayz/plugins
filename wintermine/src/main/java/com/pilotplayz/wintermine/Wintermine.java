package com.pilotplayz.wintermine;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class Wintermine extends JavaPlugin {

    private MineRegion region;
    private int taskId = -1;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadRegion();
        startAutoReset();
        getLogger().info("Wintermine Prison Mine Plugin Enabled!");
    }

    @Override
    public void onDisable() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
        getLogger().info("Wintermine Disabled");
    }

    // ======================= COMMANDS ======================= //

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        String name = cmd.getName().toLowerCase();

        if (name.equals("resetmine")) {
            if (!sender.hasPermission("wintermine.reset")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission!");
                return true;
            }

            resetMine();
            Bukkit.broadcastMessage(ChatColor.AQUA + "[Mine] " + ChatColor.YELLOW +
                    "The mine was manually reset!");
            return true;
        }

        // /mine1 – set first corner at player location
        if (name.equals("mine1")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use this command.");
                return true;
            }
            if (!sender.hasPermission("wintermine.setmine")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission!");
                return true;
            }

            Player p = (Player) sender;
            Location loc = p.getLocation();
            FileConfiguration cfg = getConfig();

            cfg.set("mine.world", loc.getWorld().getName());
            cfg.set("mine.pos1.x", loc.getBlockX());
            cfg.set("mine.pos1.y", loc.getBlockY());
            cfg.set("mine.pos1.z", loc.getBlockZ());
            saveConfig();

            sender.sendMessage(ChatColor.GREEN + "Mine corner 1 set to "
                    + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()
                    + " in world '" + loc.getWorld().getName() + "'.");
            // Reload region in case pos2 already exists
            loadRegion();
            return true;
        }

        // /mine2 – set second corner at player location
        if (name.equals("mine2")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use this command.");
                return true;
            }
            if (!sender.hasPermission("wintermine.setmine")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission!");
                return true;
            }

            Player p = (Player) sender;
            Location loc = p.getLocation();
            FileConfiguration cfg = getConfig();

            // assume world already set from /mine1, but set it again just in case
            cfg.set("mine.world", loc.getWorld().getName());
            cfg.set("mine.pos2.x", loc.getBlockX());
            cfg.set("mine.pos2.y", loc.getBlockY());
            cfg.set("mine.pos2.z", loc.getBlockZ());
            saveConfig();

            sender.sendMessage(ChatColor.GREEN + "Mine corner 2 set to "
                    + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()
                    + " in world '" + loc.getWorld().getName() + "'.");

            // Reload region now that both corners may be set
            loadRegion();

            if (region != null && region.valid()) {
                sender.sendMessage(ChatColor.AQUA + "Mine region updated. You can now use /resetmine.");
            } else {
                sender.sendMessage(ChatColor.DARK_GREEN + "Mine region is still invalid – make sure both /mine1 and /mine2 are set in the same world.");
            }
            return true;
        }

        return false;
    }

    // ======================= RESET LOGIC ======================= //

    private void resetMine() {
        if (region == null || !region.valid()) {
            getLogger().warning("Mine region is invalid, cannot reset.");
            return;
        }

        World w = region.world;

        int GAP = 1; // <-- change to 2 if you want a 2-block gap

        for (int x = region.minX; x <= region.maxX; x++) {
            for (int y = region.minY; y <= region.maxY; y++) {
                for (int z = region.minZ; z <= region.maxZ; z++) {

                    Block block = w.getBlockAt(x, y, z);

                    boolean isWall =
                            x == region.minX ||
                                    x == region.maxX ||
                                    z == region.minZ ||
                                    z == region.maxZ ||
                                    y == region.minY;

                    boolean isGap =
                            x <= region.minX + GAP ||
                                    x >= region.maxX - GAP ||
                                    z <= region.minZ + GAP ||
                                    z >= region.maxZ - GAP ||
                                    y <= region.minY + GAP;

                    if (isWall) {
                        block.setType(Material.BEDROCK, false);
                    }
                    else if (isGap) {
                        block.setType(Material.AIR, false);
                    }
                    else {
                        block.setType(Material.SNOW_BLOCK, false);
                    }
                }
            }
        }

        getLogger().info("Mine Reset Successfully (with gap).");
    }


    // ======================= TIMER ======================= //

    private void startAutoReset() {
        int minutes = getConfig().getInt("auto-reset-minutes", 5);
        long ticks = minutes * 60L * 20L;

        if (ticks <= 0) {
            getLogger().warning("auto-reset-minutes <= 0, auto reset disabled.");
            return;
        }

        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                this,
                () -> {
                    resetMine();
                    Bukkit.broadcastMessage(ChatColor.AQUA + "[Mine] " + ChatColor.YELLOW +
                            "Wintermine has automatically reset!");
                },
                ticks,
                ticks
        );
    }

    // ======================= REGION LOADER ======================= //

    private void loadRegion() {
        FileConfiguration cfg = getConfig();

        String worldName = cfg.getString("mine.world");
        if (worldName == null || worldName.isEmpty()) {
            region = null;
            return;
        }

        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            getLogger().severe("Invalid world name in config.yml: " + worldName);
            region = null;
            return;
        }

        if (!cfg.contains("mine.pos1.x") || !cfg.contains("mine.pos2.x")) {
            region = null;
            return;
        }

        int x1 = cfg.getInt("mine.pos1.x");
        int y1 = cfg.getInt("mine.pos1.y");
        int z1 = cfg.getInt("mine.pos1.z");

        int x2 = cfg.getInt("mine.pos2.x");
        int y2 = cfg.getInt("mine.pos2.y");
        int z2 = cfg.getInt("mine.pos2.z");

        region = new MineRegion(world, x1, y1, z1, x2, y2, z2);

        if (region.valid()) {
            getLogger().info("Mine region loaded: (" +
                    region.minX + "," + region.minY + "," + region.minZ + ") to (" +
                    region.maxX + "," + region.maxY + "," + region.maxZ + ") in world " +
                    world.getName());
        } else {
            getLogger().warning("Mine region is invalid after loading.");
        }
    }

    // ======================= REGION CLASS ======================= //

    private static class MineRegion {
        World world;
        int minX, maxX, minY, maxY, minZ, maxZ;

        MineRegion(World w, int x1, int y1, int z1, int x2, int y2, int z2) {
            world = w;
            minX = Math.min(x1, x2);
            maxX = Math.max(x1, x2);
            minY = Math.min(y1, y2);
            maxY = Math.max(y1, y2);
            minZ = Math.min(z1, z2);
            maxZ = Math.max(z1, z2);
        }

        boolean valid() {
            return world != null && minX <= maxX && minY <= maxY && minZ <= maxZ;
        }
    }
}
