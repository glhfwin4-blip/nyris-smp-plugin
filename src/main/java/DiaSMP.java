package com.diasmp;

import org.bukkit.*;
import org.bukkit.block.BrewingStand;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

public final class DiaSMP extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private File homesFile, teamsFile;
    private YamlConfiguration homes, teams;
    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final Map<UUID, Long> stringCooldown = new HashMap<>();
    private final Map<UUID, String> invites = new HashMap<>();
    private final Map<UUID, UUID> carriedVillagers = new HashMap<>();
    private final Map<UUID, Location> pendingTeleport = new HashMap<>();
    private final Random random = new Random();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();

        homesFile = new File(getDataFolder(), "homes.yml");
        teamsFile = new File(getDataFolder(), "teams.yml");
        homes = YamlConfiguration.loadConfiguration(homesFile);
        teams = YamlConfiguration.loadConfiguration(teamsFile);

        for (String name : new String[]{"team", "home", "sethome", "rtp", "gaps", "string"}) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(this);
                cmd.setTabCompleter(this);
            }
        }

        getServer().getPluginManager().registerEvents(this, this);
        registerRecipes();
        getLogger().info("DiaSMP enabled successfully!");
    }

    @Override
    public void onDisable() {
        save(homes, homesFile);
        save(teams, teamsFile);
    }

    private void save(YamlConfiguration yaml, File file) {
        try {
            yaml.save(file);
        } catch (IOException e) {
            getLogger().severe("Could not save " + file.getName() + ": " + e.getMessage());
        }
    }

    private void registerRecipes() {
        if (getConfig().getBoolean("recipes.golden-apple.enabled", true)) {
            // 1 apple + 2 gold ingots, as a simple vertical 3-slot recipe
            ShapedRecipe r = new ShapedRecipe(new NamespacedKey(this, "diasmp_golden_apple"), new ItemStack(Material.GOLDEN_APPLE));
            r.shape(" G ", " A ", " G ");
            r.setIngredient('G', Material.GOLD_INGOT);
            r.setIngredient('A', Material.APPLE);
            Bukkit.addRecipe(r);
        }
        if (getConfig().getBoolean("recipes.cobweb.enabled", true)) {
            ShapedRecipe r = new ShapedRecipe(new NamespacedKey(this, "diasmp_cobweb"), new ItemStack(Material.COBWEB));
            r.shape("SSS", "SSS", "SSS");
            r.setIngredient('S', Material.STRING);
            Bukkit.addRecipe(r);
        }
    }

    // ---------- teams ----------

    private String teamOf(UUID id) {
        for (String key : teams.getKeys(false)) {
            if (teams.getString(key + ".owner", "").equals(id.toString())
                    || teams.getStringList(key + ".members").contains(id.toString())) {
                return key;
            }
        }
        return null;
    }

    private List<String> members(String team) {
        List<String> list = new ArrayList<>(teams.getStringList(team + ".members"));
        String owner = teams.getString(team + ".owner", "");
        if (!owner.isBlank() && !list.contains(owner)) list.add(owner);
        return list;
    }

    private boolean isOwner(String team, UUID id) {
        return teams.getString(team + ".owner", "").equals(id.toString());
    }

    private void msg(CommandSender s, String m) {
        s.sendMessage(ChatColor.AQUA + "[DiaSMP] " + ChatColor.WHITE + m);
    }

    // ---------- teleporting ----------

    private Location safeRtp(World w) {
        int radius = Math.max(100, getConfig().getInt("rtp.radius", 5000));
        for (int i = 0; i < getConfig().getInt("rtp.attempts", 30); i++) {
            int x = random.nextInt(radius * 2 + 1) - radius;
            int z = random.nextInt(radius * 2 + 1) - radius;
            int y = w.getHighestBlockYAt(x, z) + 1;
            Location l = new Location(w, x + 0.5, y, z + 0.5);
            Material ground = l.clone().subtract(0, 1, 0).getBlock().getType();
            if (ground.isSolid() && ground != Material.MAGMA_BLOCK && ground != Material.CACTUS
                    && l.getBlock().isEmpty() && l.clone().add(0, 1, 0).getBlock().isEmpty()) {
                return l;
            }
        }
        return null;
    }

    private void delayedTeleport(Player p, Location dest) {
        int delay = Math.max(0, getConfig().getInt("teleport-delay-seconds", 5));
        Location start = p.getLocation().clone();
        Location target = dest.clone();
        msg(p, "Stand still for " + delay + " seconds to teleport.");
        pendingTeleport.put(p.getUniqueId(), target);
        new BukkitRunnable() {
            int ticks = delay * 20;

            @Override
            public void run() {
                // cancelled, or replaced by a newer teleport request
                if (!p.isOnline() || pendingTeleport.get(p.getUniqueId()) != target) {
                    cancel();
                    return;
                }
                if (p.getLocation().distanceSquared(start) > 0.04) {
                    pendingTeleport.remove(p.getUniqueId());
                    msg(p, "Teleport cancelled because you moved.");
                    cancel();
                    return;
                }
                if (ticks <= 0) {
                    pendingTeleport.remove(p.getUniqueId());
                    if (combatUntil.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis()) {
                        msg(p, "You cannot teleport while combat-tagged.");
                        cancel();
                        return;
                    }
                    p.teleport(target);
                    p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1, 1);
                    msg(p, "Teleported.");
                    cancel();
                    return;
                }
                ticks--;
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    // ---------- commands ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        String c = command.getName().toLowerCase(Locale.ROOT);

        if (c.equals("home") || c.equals("sethome") || c.equals("rtp")) {
            if (!(sender instanceof Player p)) {
                msg(sender, "Players only.");
                return true;
            }
            String id = p.getUniqueId().toString();
            if (c.equals("sethome")) {
                Location l = p.getLocation();
                homes.set(id + ".world", p.getWorld().getName());
                homes.set(id + ".x", l.getX());
                homes.set(id + ".y", l.getY());
                homes.set(id + ".z", l.getZ());
                homes.set(id + ".yaw", l.getYaw());
                homes.set(id + ".pitch", l.getPitch());
                save(homes, homesFile);
                msg(p, "Home set.");
                return true;
            }
            if (c.equals("home")) {
                World w = Bukkit.getWorld(homes.getString(id + ".world", ""));
                if (w == null || !homes.contains(id + ".x")) {
                    msg(p, "You have not set a home. Use /sethome.");
                    return true;
                }
                delayedTeleport(p, new Location(w, homes.getDouble(id + ".x"), homes.getDouble(id + ".y"), homes.getDouble(id + ".z"),
                        (float) homes.getDouble(id + ".yaw"), (float) homes.getDouble(id + ".pitch")));
                return true;
            }
            Location dest = safeRtp(p.getWorld());
            if (dest == null) {
                msg(p, "Could not find a safe location. Try again.");
                return true;
            }
            delayedTeleport(p, dest);
            return true;
        }

        if (c.equals("gaps")) {
            msg(sender, "Crafting recipe: 1 apple + 2 gold ingots. Use a crafting table.");
            return true;
        }

        if (c.equals("team")) {
            if (!(sender instanceof Player p)) {
                msg(sender, "Players only.");
                return true;
            }
            if (a.length == 0) {
                msg(p, "/team create <name>, invite <player>, join <name>, leave, kick <player>, disband, info, list");
                return true;
            }
            String sub = a[0].toLowerCase(Locale.ROOT);
            String current = teamOf(p.getUniqueId());
            int maxSize = getConfig().getInt("teams.max-size", 5);

            switch (sub) {
                case "create" -> {
                    if (a.length < 2) { msg(p, "Usage: /team create <name>"); return true; }
                    if (current != null) { msg(p, "You are already in a team."); return true; }
                    String name = a[1].toLowerCase(Locale.ROOT);
                    if (!name.matches("[a-z0-9_-]{2,16}") || teams.contains(name)) { msg(p, "That team name is invalid or already taken."); return true; }
                    teams.set(name + ".owner", p.getUniqueId().toString());
                    teams.set(name + ".members", new ArrayList<String>());
                    save(teams, teamsFile);
                    msg(p, "Created team " + name + ".");
                }
                case "invite" -> {
                    if (a.length < 2 || current == null || !isOwner(current, p.getUniqueId())) { msg(p, "Only your team owner can invite players. Usage: /team invite <player>"); return true; }
                    Player target = Bukkit.getPlayerExact(a[1]);
                    if (target == null) { msg(p, "That player must be online."); return true; }
                    if (teamOf(target.getUniqueId()) != null) { msg(p, "That player is already in a team."); return true; }
                    if (members(current).size() >= maxSize) { msg(p, "Your team is full (max " + maxSize + " including owner)."); return true; }
                    invites.put(target.getUniqueId(), current);
                    msg(p, "Invited " + target.getName() + ".");
                    msg(target, "You were invited to team " + current + ". Use /team join " + current + ".");
                }
                case "join" -> {
                    if (a.length < 2) { msg(p, "Usage: /team join <name>"); return true; }
                    String name = a[1].toLowerCase(Locale.ROOT);
                    if (current != null) { msg(p, "Leave your current team first."); return true; }
                    if (!name.equals(invites.get(p.getUniqueId())) || !teams.contains(name)) { msg(p, "You don't have an invite to that team."); return true; }
                    if (members(name).size() >= maxSize) { msg(p, "That team is full."); return true; }
                    List<String> list = teams.getStringList(name + ".members");
                    list.add(p.getUniqueId().toString());
                    teams.set(name + ".members", list);
                    invites.remove(p.getUniqueId());
                    save(teams, teamsFile);
                    msg(p, "Joined team " + name + ".");
                }
                case "leave" -> {
                    if (current == null) { msg(p, "You are not in a team."); return true; }
                    if (isOwner(current, p.getUniqueId())) { msg(p, "Team owner must use /team disband."); return true; }
                    List<String> list = teams.getStringList(current + ".members");
                    list.remove(p.getUniqueId().toString());
                    teams.set(current + ".members", list);
                    save(teams, teamsFile);
                    msg(p, "You left team " + current + ".");
                }
                case "kick" -> {
                    if (a.length < 2 || current == null || !isOwner(current, p.getUniqueId())) { msg(p, "Only the owner can kick. Usage: /team kick <player>"); return true; }
                    Player target = Bukkit.getPlayerExact(a[1]);
                    if (target == null) { msg(p, "That player must be online."); return true; }
                    if (target.getUniqueId().equals(p.getUniqueId()) || !members(current).contains(target.getUniqueId().toString())) { msg(p, "That player is not a kickable member."); return true; }
                    List<String> list = teams.getStringList(current + ".members");
                    list.remove(target.getUniqueId().toString());
                    teams.set(current + ".members", list);
                    save(teams, teamsFile);
                    msg(p, "Kicked " + target.getName() + ".");
                    msg(target, "You were removed from team " + current + ".");
                }
                case "disband" -> {
                    if (current == null || !isOwner(current, p.getUniqueId())) { msg(p, "Only your team owner can disband it."); return true; }
                    teams.set(current, null);
                    save(teams, teamsFile);
                    msg(p, "Team disbanded.");
                }
                case "info" -> {
                    if (current == null) { msg(p, "You are not in a team."); return true; }
                    List<String> names = new ArrayList<>();
                    for (String id : members(current)) {
                        OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(id));
                        names.add(op.getName() == null ? id : op.getName());
                    }
                    msg(p, "Team " + current + " (" + names.size() + "/" + maxSize + "): " + String.join(", ", names));
                }
                case "list" -> msg(p, "Teams: " + (teams.getKeys(false).isEmpty() ? "none" : String.join(", ", teams.getKeys(false))));
                default -> msg(p, "Unknown subcommand. Use /team for help.");
            }
            return true;
        }
        return false;
    }

    // Required because this class is registered as a TabCompleter
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("team")) return Collections.emptyList();
        if (args.length == 1) {
            return matching(List.of("create", "invite", "join", "leave", "kick", "disband", "info", "list"), args[0]);
        }
        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("invite") || sub.equals("kick")) {
                List<String> names = new ArrayList<>();
                for (Player online : Bukkit.getOnlinePlayers()) names.add(online.getName());
                return matching(names, args[1]);
            }
            if (sub.equals("join")) return matching(new ArrayList<>(teams.getKeys(false)), args[1]);
        }
        return Collections.emptyList();
    }

    private List<String> matching(List<String> options, String typed) {
        String t = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(t)) out.add(o);
        return out;
    }

    // ---------- events ----------

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!pendingTeleport.containsKey(e.getPlayer().getUniqueId()) || e.getTo() == null) return;
        if (e.getFrom().getBlockX() != e.getTo().getBlockX()
                || e.getFrom().getBlockY() != e.getTo().getBlockY()
                || e.getFrom().getBlockZ() != e.getTo().getBlockZ()) {
            pendingTeleport.remove(e.getPlayer().getUniqueId());
            msg(e.getPlayer(), "Teleport cancelled because you moved.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCombat(EntityDamageByEntityEvent e) {
        Player attacker = null;
        if (e.getDamager() instanceof Player dp) {
            attacker = dp;
        } else if (e.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player sp) {
            attacker = sp;
        }
        Player victim = e.getEntity() instanceof Player vp ? vp : null;
        long until = System.currentTimeMillis() + getConfig().getInt("combat-tag-seconds", 60) * 1000L;
        if (attacker != null) combatUntil.put(attacker.getUniqueId(), until);
        if (victim != null) combatUntil.put(victim.getUniqueId(), until);
    }

    // NOTE: BlockPhysicsEvent is not tied to brewing progress, so this speed-up may be inconsistent.
    @EventHandler
    public void onBrewTimeUpdate(BlockPhysicsEvent e) {
        if (e.getChangedType() != Material.BREWING_STAND) return;
        if (e.getBlock().getState() instanceof BrewingStand stand && stand.getBrewingTime() > 1) {
            stand.setBrewingTime(Math.max(1, stand.getBrewingTime() - 10)); // adjust speed here
            stand.update();
        }
    }

    @EventHandler
    public void onVillagerInteract(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return; // the event fires once per hand
        if (!getConfig().getBoolean("villagers.allow-pickup", true)) return;
        if (!(e.getRightClicked() instanceof Villager villager)) return;
        Player p = e.getPlayer();
        if (!p.isSneaking()) return;
        e.setCancelled(true); // don't open the trade menu

        UUID carriedId = carriedVillagers.remove(p.getUniqueId());
        if (carriedId != null) {
            Entity carried = Bukkit.getEntity(carriedId);
            if (carried != null) {
                p.removePassenger(carried);
                carried.teleport(p.getLocation().add(p.getLocation().getDirection().multiply(1.5)));
                msg(p, "Villager placed down.");
            }
        } else {
            carriedVillagers.put(p.getUniqueId(), villager.getUniqueId());
            p.addPassenger(villager);
            msg(p, "Villager picked up. Sneak and right-click a villager to put it down.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        pendingTeleport.remove(p.getUniqueId());
        UUID carriedId = carriedVillagers.remove(p.getUniqueId());
        if (carriedId != null) {
            Entity carried = Bukkit.getEntity(carriedId);
            if (carried != null) p.removePassenger(carried);
        }
    }

    @EventHandler
    public void onStringPlace(BlockPlaceEvent e) {
        Material type = e.getBlockPlaced().getType();
        // placing string creates a TRIPWIRE block, not Material.STRING
        if (type != Material.TRIPWIRE && type != Material.COBWEB) return;
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        Long until = stringCooldown.get(p.getUniqueId());
        if (until != null && until > now) {
            long timeLeft = (until - now + 999) / 1000;
            p.sendMessage(ChatColor.RED + "You must wait " + timeLeft + "s to place string/cobwebs!");
            e.setCancelled(true);
            return;
        }
        stringCooldown.put(p.getUniqueId(), now + getConfig().getInt("string-cooldown-seconds", 60) * 1000L);
    }

    @EventHandler
    public void onMaceCraft(PrepareItemCraftEvent e) {
        Recipe r = e.getRecipe();
        if (r != null && r.getResult().getType() == Material.MACE) {
            e.getInventory().setResult(null); // blocks taking it out of the crafting slot
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onMaceDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && p.getInventory().getItemInMainHand().getType() == Material.MACE) {
            p.sendMessage(ChatColor.RED + "Maces are disabled on this SMP!");
            e.setCancelled(true);
        }
    }
}
            
