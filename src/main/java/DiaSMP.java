package com.diasmp;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.BrewingStand;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

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
    
    homesFile = new File(getDataFolder(), "homes.yml");
    teamsFile = new File(getDataFolder(), "teams.yml");
    homes = YamlConfiguration.loadConfiguration(homesFile);
    teams = YamlConfiguration.loadConfiguration(teamsFile);
    
    // Explicitly listing all 5 of your custom commands safely
    String[] commands = {"team", "home", "sethome", "rtp", "gaps"};
    for (String cmd : commands) {
        var pluginCmd = getCommand(cmd);
        if (pluginCmd != null) {
            pluginCmd.setExecutor(this);
            pluginCmd.setTabCompleter(this);
        } else {
            getLogger().warning("Command /" + cmd + " is registered in code but missing from plugin.yml!");
        }
    }
    
    getServer().getPluginManager().registerEvents(this, this);
    registerRecipes();
    getLogger().info("DiaSMP enabled successfully!");
}


    @Override public void onDisable() { save(homes, homesFile); save(teams, teamsFile); }
    private void save(YamlConfiguration yaml, File file) { try { yaml.save(file); } catch (IOException e) { getLogger().severe("Could not save " + file.getName() + ": " + e.getMessage()); } }

    private void registerRecipes() {
        if (getConfig().getBoolean("recipes.golden-apple.enabled", true)) {
            ShapedRecipe r = new ShapedRecipe(new NamespacedKey(this, "diasmp_golden_apple"), new ItemStack(Material.GOLDEN_APPLE));
            r.shape(" G ", "GAG", " G ");
            // Use the exact requested recipe: one apple + two gold ingots, arranged as a simple 3-slot recipe.
            r.shape(" G ", " A ", " G ");
            r.setIngredient('G', Material.GOLD_INGOT); r.setIngredient('A', Material.APPLE);
            Bukkit.addRecipe(r);
        }
        if (getConfig().getBoolean("recipes.cobweb.enabled", true)) {
            ShapedRecipe r = new ShapedRecipe(new NamespacedKey(this, "diasmp_cobweb"), new ItemStack(Material.COBWEB));
            r.shape("SSS", "SSS", "SSS"); r.setIngredient('S', Material.STRING); Bukkit.addRecipe(r);
        }
    }

    private String teamOf(UUID id) {
        for (String key : teams.getKeys(false)) {
            if (teams.getString(key + ".owner", "").equals(id.toString()) || teams.getStringList(key + ".members").contains(id.toString())) return key;
        }
        return null;
    }
    private List<String> members(String team) {
        List<String> list = new ArrayList<>(teams.getStringList(team + ".members"));
        String owner = teams.getString(team + ".owner", "");
        if (!owner.isBlank() && !list.contains(owner)) list.add(owner);
        return list;
    }
    private boolean isOwner(String team, UUID id) { return teams.getString(team + ".owner", "").equals(id.toString()); }
    private void msg(CommandSender s, String m) { s.sendMessage(ChatColor.AQUA + "[DiaSMP] " + ChatColor.WHITE + m); }
    private Location safeRtp(World w) {
        int radius = Math.max(100, getConfig().getInt("rtp.radius", 5000));
        for (int i=0;i<getConfig().getInt("rtp.attempts",30);i++) {
            int x = random.nextInt(radius * 2 + 1) - radius, z = random.nextInt(radius * 2 + 1) - radius;
            int y = w.getHighestBlockYAt(x,z) + 1;
            Location l = new Location(w,x+0.5,y,z+0.5);
            Material ground = l.clone().subtract(0,1,0).getBlock().getType();
            if (ground.isSolid() && !ground.name().contains("LAVA") && l.getBlock().isEmpty() && l.clone().add(0,1,0).getBlock().isEmpty()) return l;
        }
        return null;
    }
    private void delayedTeleport(Player p, Location dest) {
        int delay = Math.max(0, getConfig().getInt("teleport-delay-seconds",5));
        Location start = p.getLocation().clone();
        msg(p, "Stand still for " + delay + " seconds to teleport.");
        pendingTeleport.put(p.getUniqueId(), dest.clone());
        new BukkitRunnable() {
            int ticks = delay * 20;
            @Override public void run() {
                if (!p.isOnline() || !pendingTeleport.containsKey(p.getUniqueId())) { cancel(); return; }
                if (p.getLocation().distanceSquared(start) > 0.04) {
                    pendingTeleport.remove(p.getUniqueId()); msg(p, "Teleport cancelled because you moved."); cancel(); return;
                }
                if (ticks <= 0) {
                    if (combatUntil.getOrDefault(p.getUniqueId(),0L) > System.currentTimeMillis()) {
                        msg(p, "You cannot teleport while combat-tagged."); pendingTeleport.remove(p.getUniqueId()); cancel(); return;
                    }
                    p.teleport(dest); p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1, 1);
                    pendingTeleport.remove(p.getUniqueId()); msg(p, "Teleported."); cancel(); return;
                }
                ticks--;
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] a) {
        String c = command.getName().toLowerCase(Locale.ROOT);
        if (c.equals("home") || c.equals("sethome") || c.equals("rtp")) {
            if (!(sender instanceof Player p)) { msg(sender,"Players only."); return true; }
            if (c.equals("sethome")) { homes.set(p.getUniqueId()+".world", p.getWorld().getName()); homes.set(p.getUniqueId()+".x",p.getLocation().getX()); homes.set(p.getUniqueId()+".y",p.getLocation().getY()); homes.set(p.getUniqueId()+".z",p.getLocation().getZ()); homes.set(p.getUniqueId()+".yaw",p.getLocation().getYaw()); homes.set(p.getUniqueId()+".pitch",p.getLocation().getPitch()); save(homes,homesFile); msg(p,"Home set."); return true; }
            if (c.equals("home")) {
                String path=p.getUniqueId().toString(); World w=Bukkit.getWorld(homes.getString(path+".world",""));
                if(w==null || !homes.contains(path+".x")) { msg(p,"You have not set a home. Use /sethome."); return true; }
                delayedTeleport(p,new Location(w,homes.getDouble(path+".x"),homes.getDouble(path+".y"),homes.getDouble(path+".z"),(float)homes.getDouble(path+".yaw"),(float)homes.getDouble(path+".pitch"))); return true;
            }
            Location dest=safeRtp(p.getWorld()); if(dest==null){msg(p,"Could not find a safe location. Try again.");return true;} delayedTeleport(p,dest); return true;
        }
        if (c.equals("gaps")) { msg(sender,"Crafting recipe: 1 apple + 2 gold ingots. Use a crafting table."); return true; }
        if (c.equals("team")) {
            if (!(sender instanceof Player p)) { msg(sender,"Players only."); return true; }
            if (a.length==0) { msg(p,"/team create <name>, invite <player>, join <name>, leave, kick <player>, disband, info, list"); return true; }
            String sub=a[0].toLowerCase(Locale.ROOT), current=teamOf(p.getUniqueId());
            switch(sub) {
                case "create" -> {
                    if(a.length<2){msg(p,"Usage: /team create <name>");break;}
                    if(current!=null){msg(p,"You are already in a team.");break;}
                    String name=a[1].toLowerCase(Locale.ROOT);
                    if(!name.matches("[a-z0-9_-]{2,16}") || teams.contains(name)){msg(p,"That team name is invalid or already taken.");break;}
                    teams.set(name+".owner",p.getUniqueId().toString()); teams.set(name+".members",new ArrayList<String>()); save(teams,teamsFile); msg(p,"Created team "+name+"."); 
                }
                case "invite" -> {
                    if(a.length<2 || current==null || !isOwner(current,p.getUniqueId())){msg(p,"Only your team owner can invite players. Usage: /team invite <player>");break;}
                    Player target=Bukkit.getPlayerExact(a[1]); if(target==null){msg(p,"That player must be online.");break;}
                    if(teamOf(target.getUniqueId())!=null){msg(p,"That player is already in a team.");break;}
                    if(members(current).size()>=getConfig().getInt("teams.max-size",5)){msg(p,"Your team is full (max 5 including owner).");break;}
                    invites.put(target.getUniqueId(),current); msg(p,"Invited "+target.getName()+"."); msg(target,"You were invited to team "+current+". Use /team join "+current+".");
                }
                case "join" -> {
                    if(a.length<2){msg(p,"Usage: /team join <name>");break;}
                    String name=a[1].toLowerCase(Locale.ROOT);
                    if(current!=null){msg(p,"Leave your current team first.");break;}
                    if(!name.equals(invites.get(p.getUniqueId())) || !teams.contains(name)){msg(p,"You don't have an invite to that team.");break;}
                    if(members(name).size()>=getConfig().getInt("teams.max-size",5)){msg(p,"That team is full.");break;}
                    List<String> list=teams.getStringList(name+".members"); list.add(p.getUniqueId().toString()); teams.set(name+".members",list); invites.remove(p.getUniqueId()); save(teams,teamsFile); msg(p,"Joined team "+name+".");
                }
                case "leave" -> {
                    if(current==null){msg(p,"You are not in a team.");break;}
                    if(isOwner(current,p.getUniqueId())){msg(p,"Team owner must use /team disband.");break;}
                    List<String> list=teams.getStringList(current+".members"); list.remove(p.getUniqueId().toString()); teams.set(current+".members",list); save(teams,teamsFile); msg(p,"You left team "+current+".");
                }
                case "kick" -> {
                    if(a.length<2 || current==null || !isOwner(current,p.getUniqueId())){msg(p,"Only the owner can kick. Usage: /team kick <player>");break;}
                    Player target=Bukkit.getPlayerExact(a[1]); if(target==null){msg(p,"That player must be online.");break;}
                    if(target.getUniqueId().equals(p.getUniqueId()) || !members(current).contains(target.getUniqueId().toString())){msg(p,"That player is not a kickable member.");break;}
                    List<String> list=teams.getStringList(current+".members"); list.remove(target.getUniqueId().toString()); teams.set(current+".members",list); save(teams,teamsFile); msg(p,"Kicked "+target.getName()+"."); msg(target,"You were removed from team "+current+".");
                }
                case "disband" -> {
                    if(current==null || !isOwner(current,p.getUniqueId())){msg(p,"Only your team owner can disband it.");break;}
                    teams.set(current,null); save(teams,teamsFile); msg(p,"Team disbanded.");
                }
                case "info" -> {
                    if(current==null){msg(p,"You are not in a team.");break;}
                    List<String> names=new ArrayList<>(); for(String id:members(current)){OfflinePlayer op=Bukkit.getOfflinePlayer(UUID.fromString(id)); names.add(op.getName()==null?id:op.getName());}
                    msg(p,"Team "+current+" ("+names.size()+"/"+getConfig().getInt("teams.max-size",5)+"): "+String.join(", ",names));
                }
                case "list" -> msg(p, "Teams: "+(teams.getKeys(false).isEmpty()?"none":String.join(", ",teams.getKeys(false))));
                default -> msg(p,"Unknown subcommand. Use /team for help.");
            }
            return true;
        }
        return false;
    }

    @EventHandler public void onMove(PlayerMoveEvent e) {
        if (!pendingTeleport.containsKey(e.getPlayer().getUniqueId()) || e.getTo()==null) return;
        if (e.getFrom().getBlockX()!=e.getTo().getBlockX() || e.getFrom().getBlockY()!=e.getTo().getBlockY() || e.getFrom().getBlockZ()!=e.getTo().getBlockZ()) {
            pendingTeleport.remove(e.getPlayer().getUniqueId()); msg(e.getPlayer(),"Teleport cancelled because you moved.");
        }
    }
    @EventHandler public void onCombat(EntityDamageByEntityEvent e) {
        Player attacker = e.getDamager() instanceof Player p ? p : (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p ? p : null);
        Player victim = e.getEntity() instanceof Player p ? p : null;
        long until=System.currentTimeMillis()+getConfig().getInt("combat-tag-seconds",60)*1000L;
        if(attacker!=null) combatUntil.put(attacker.getUniqueId(),until);
        if(victim!=null) combatUntil.put(victim.getUniqueId(),until);
    }

@EventHandler
public void onBrewTimeUpdate(org.bukkit.event.block.BlockPhysicsEvent e) {
    if (e.getChangedType() == org.bukkit.Material.BREWING_STAND) {
        org.bukkit.block.Block b = e.getBlock();
        if (b.getState() instanceof org.bukkit.block.BrewingStand) {
            org.bukkit.block.BrewingStand stand = (org.bukkit.block.BrewingStand) b.getState();
            // Accelerate brewing if it's currently active
            if (stand.getBrewingTime() > 1) {
                stand.setBrewingTime(Math.max(1, stand.getBrewingTime() - 10)); // Adjust speed multiplier here
                stand.update();
            }
        }
    }
}


    @EventHandler public void onVillagerInteract(PlayerInteractEntityEvent e) {
        if(!getConfig().getBoolean("villagers.allow-pickup",true) || !(e.getRightClicked() instanceof Villager villager)) return;
        Player p=e.getPlayer();
        if(!p.isSneaking()) return;
        if(carriedVillagers.containsKey(p.getUniqueId())) {
            UUID id=carriedVillagers.remove(p.getUniqueId());
            Entity ent=Bukkit.getEntity(id);
            if(ent instanceof Villager v) { v.teleport(p.getLocation().add(p.getLocation().getDirection().multiply(1.5))); msg(p,"Villager placed down."); }
        } else {
            carriedVillagers.put(p.getUniqueId(),villager.getUniqueId());
            villager.addPassenger(p); // temporary visible carry-style interaction; not a true inventory pickup
            msg(p,"Villager picked up (temporary carry). Sneak-right-click a villager to interact.");
        }
    
@EventHandler
public void onStringPlace(org.bukkit.event.block.BlockPlaceEvent e) {
    Player p = e.getPlayer();
    if (e.getBlockPlaced().getType() == org.bukkit.Material.STRING || e.getBlockPlaced().getType() == org.bukkit.Material.COBWEB) {
        long now = System.currentTimeMillis();
        if (stringCooldown.containsKey(p.getUniqueId()) && stringCooldown.get(p.getUniqueId()) > now) {
            long timeLeft = (stringCooldown.get(p.getUniqueId()) - now) / 1000;
            p.sendMessage(org.bukkit.ChatColor.RED + "You must wait " + timeLeft + "s to place string/cobwebs!");
            e.setCancelled(true);
            return;
        }
        // Set a 1-minute cooldown (60,000 milliseconds)
        stringCooldown.put(p.getUniqueId(), now + 60000);
    }
}

@EventHandler
public void onMaceCraft(org.bukkit.event.inventory.PrepareItemCraftEvent e) {
    if (e.getRecipe() != null && e.getRecipe().getResult().getType() == org.bukkit.Material.MACE) {
        e.getInventory().setResult(null); // Blocks players from taking it out of the crafting slot
    }
}

@EventHandler
public void onMaceDamage(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
    if (e.getDamager() instanceof Player) {
        Player p = (Player) e.getDamager();
        if (p.getInventory().getItemInMainHand().getType() == org.bukkit.Material.MACE) {
            p.sendMessage(org.bukkit.ChatColor.RED + "Maces are disabled on this SMP!");
            e.setCancelled(true); // Cancels the attack completely
        }
    }
}
        
    

