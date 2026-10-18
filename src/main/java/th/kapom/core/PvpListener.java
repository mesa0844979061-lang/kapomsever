package th.kapom.tier;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static th.kapom.tier.TierPlugin.MM;

/** Combat tag, killstreak, kill reward, spawn protection, stats/top */
public class PvpListener implements Listener {
    private final TierPlugin pl;
    private final Map<UUID, Long> combat = new HashMap<>();
    private final Map<UUID, Long> protect = new HashMap<>();
    private final Map<UUID, Integer> streak = new HashMap<>();
    private final Map<UUID, Integer> bestStreak = new HashMap<>();
    private final Map<UUID, Integer> kills = new HashMap<>();
    private final Map<UUID, Integer> deaths = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final File file;

    PvpListener(TierPlugin pl) {
        this.pl = pl;
        this.file = new File(pl.getDataFolder(), "stats.yml");
        load();
    }

    void startTasks() {
        // แสดง combat timer บน action bar
        Bukkit.getScheduler().runTaskTimer(pl, () -> {
            long now = System.currentTimeMillis();
            combat.entrySet().removeIf(e -> {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p == null) return true;
                long left = (e.getValue() - now) / 1000 + 1;
                if (e.getValue() <= now) {
                    p.sendActionBar(MM.deserialize("<green>ออกจากการต่อสู้แล้ว"));
                    return true;
                }
                p.sendActionBar(MM.deserialize("<red>⚔ Combat <white>" + left + "s"));
                return false;
            });
        }, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(pl, this::save, 6000L, 6000L);
    }

    void clearCombat(UUID id) { combat.remove(id); }

    private boolean tagOn() { return pl.getConfig().getBoolean("combat.enabled", true); }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        names.put(p.getUniqueId(), p.getName());
        protect.put(p.getUniqueId(), System.currentTimeMillis()
                + pl.getConfig().getInt("spawn-protection-seconds", 5) * 1000L);
        Bukkit.getScheduler().runTaskLater(pl, pl::refreshAll, 5L);
    }

    @EventHandler
    public void respawn(PlayerRespawnEvent e) {
        protect.put(e.getPlayer().getUniqueId(), System.currentTimeMillis()
                + pl.getConfig().getInt("spawn-protection-seconds", 5) * 1000L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void hit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player damager = null;
        if (e.getDamager() instanceof Player p) damager = p;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) damager = p;
        if (damager == null || damager.equals(victim)) return;

        long now = System.currentTimeMillis();
        if (protect.getOrDefault(victim.getUniqueId(), 0L) > now
                || protect.getOrDefault(damager.getUniqueId(), 0L) > now) {
            e.setCancelled(true);
            return;
        }
        if (tagOn()) {
            long until = now + pl.getConfig().getInt("combat.seconds", 15) * 1000L;
            combat.put(victim.getUniqueId(), until);
            combat.put(damager.getUniqueId(), until);
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Long until = combat.remove(p.getUniqueId());
        if (until != null && until > System.currentTimeMillis()
                && pl.getConfig().getBoolean("combat.kill-on-logout", true)) {
            p.setHealth(0);
            Bukkit.broadcast(MM.deserialize("<red>" + p.getName() + " <gray>ออกเกมระหว่างต่อสู้!"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void cmd(PlayerCommandPreprocessEvent e) {
        if (!tagOn()) return;
        Long until = combat.get(e.getPlayer().getUniqueId());
        if (until == null || until <= System.currentTimeMillis()) return;
        String base = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        if (pl.getConfig().getStringList("combat.blocked-commands").contains(base)) {
            e.setCancelled(true);
            e.getPlayer().sendMessage(MM.deserialize("<red>ใช้คำสั่งนี้ไม่ได้ขณะอยู่ใน Combat"));
        }
    }

    @EventHandler
    public void death(PlayerDeathEvent e) {
        Player v = e.getEntity();
        UUID vid = v.getUniqueId();
        combat.remove(vid);
        deaths.merge(vid, 1, Integer::sum);
        names.put(vid, v.getName());
        int lost = streak.getOrDefault(vid, 0);
        streak.put(vid, 0);

        Player k = v.getKiller();
        if (k == null || k.equals(v)) return;
        UUID kid = k.getUniqueId();
        names.put(kid, k.getName());
        kills.merge(kid, 1, Integer::sum);
        int s = streak.merge(kid, 1, Integer::sum);
        bestStreak.merge(kid, s, Math::max);
        combat.remove(kid);

        var cfg = pl.getConfig();
        if (cfg.getBoolean("kill-rewards.enabled", true)) {
            for (String c : cfg.getStringList("kill-rewards.commands"))
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{killer}", k.getName()));
            if (cfg.getBoolean("kill-rewards.heal-killer", false))
                k.setHealth(k.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
        }
        if (cfg.getBoolean("killstreak.enabled", true)) {
            ConfigurationSection r = cfg.getConfigurationSection("killstreak.rewards");
            if (r != null && r.isList(String.valueOf(s)))
                for (String c : r.getStringList(String.valueOf(s)))
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", k.getName()));
            int every = cfg.getInt("killstreak.broadcast-every", 5);
            if (every > 0 && s % every == 0)
                Bukkit.broadcast(MM.deserialize("<gold>🔥 <white>" + k.getName() + " <gold>คิลสตรีค <white>" + s + "!"));
            if (lost >= every && every > 0)
                Bukkit.broadcast(MM.deserialize("<gray>" + k.getName() + " ทำลายคิลสตรีค " + lost + " ของ " + v.getName()));
        }
    }

    void sendStats(CommandSender s, Player t) {
        UUID id = t.getUniqueId();
        int k = kills.getOrDefault(id, 0), d = deaths.getOrDefault(id, 0);
        double kdr = d == 0 ? k : Math.round(k * 100.0 / d) / 100.0;
        s.sendMessage(MM.deserialize("<gradient:#00F5A0:#00D9F5><bold>" + t.getName() + "</bold></gradient>"
                + "\n<gray>Kills: <white>" + k + " <gray>Deaths: <white>" + d + " <gray>KDR: <white>" + kdr
                + "\n<gray>Streak: <white>" + streak.getOrDefault(id, 0)
                + " <gray>Best: <white>" + bestStreak.getOrDefault(id, 0)
                + "\n<gray>Tier: <white>" + pl.tierOf(t)));
    }

    void sendTop(CommandSender s, String type) {
        Map<UUID, Integer> src = type.equalsIgnoreCase("streak") ? bestStreak : kills;
        s.sendMessage(MM.deserialize("<gradient:#FF512F:#F09819><bold>TOP 10 " + type.toUpperCase() + "</bold></gradient>"));
        int i = 1;
        for (var e : src.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue()).limit(10).toList()) {
            s.sendMessage(MM.deserialize("<gray>#" + i++ + " <white>" + names.getOrDefault(e.getKey(), "?")
                    + " <gray>- <yellow>" + e.getValue()));
        }
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("p");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                names.put(id, sec.getString(k + ".name", "?"));
                kills.put(id, sec.getInt(k + ".kills"));
                deaths.put(id, sec.getInt(k + ".deaths"));
                bestStreak.put(id, sec.getInt(k + ".best"));
            } catch (IllegalArgumentException ignored) {}
        }
    }

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (UUID id : names.keySet()) {
            y.set("p." + id + ".name", names.get(id));
            y.set("p." + id + ".kills", kills.getOrDefault(id, 0));
            y.set("p." + id + ".deaths", deaths.getOrDefault(id, 0));
            y.set("p." + id + ".best", bestStreak.getOrDefault(id, 0));
        }
        try { pl.getDataFolder().mkdirs(); y.save(file); }
        catch (IOException ex) { pl.getLogger().warning("save failed: " + ex.getMessage()); }
    }
}
