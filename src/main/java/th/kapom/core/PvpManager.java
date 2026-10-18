package th.kapom.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Combat tag, combat-log punishment, killstreaks, anti-farm, kill effects and rewards */
public class PvpManager implements Listener {
    private final KapomPlugin plugin;
    private final Map<UUID, Long> combatUntil = new HashMap<>();
    private final Map<UUID, UUID> lastAttacker = new HashMap<>();
    private final Map<String, Long> lastKill = new HashMap<>();
    private final Set<UUID> tagged = new HashSet<>();

    public PvpManager(KapomPlugin plugin) { this.plugin = plugin; }

    private boolean combatEnabled() { return plugin.getConfig().getBoolean("combat.enabled", true); }

    public boolean inCombat(Player p) {
        if (!combatEnabled()) return false;
        Long u = combatUntil.get(p.getUniqueId());
        return u != null && u > System.currentTimeMillis();
    }

    private long secondsLeft(Player p) {
        Long u = combatUntil.get(p.getUniqueId());
        return u == null ? 0 : Math.max(0, (u - System.currentTimeMillis() + 999) / 1000);
    }

    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (inCombat(p)) {
                    p.sendActionBar(Msg.mm("<#FF6B6B><bold>⚔ IN COMBAT <dark_gray>» <#FFD166>" + secondsLeft(p) + "s"));
                } else if (tagged.remove(p.getUniqueId())) {
                    combatUntil.remove(p.getUniqueId());
                    p.sendActionBar(Msg.mm("<#7CFC9A><bold>You are no longer in combat"));
                }
            }
        }, 10L, 10L);
    }

    private void tag(Player p, long until) {
        combatUntil.put(p.getUniqueId(), until);
        if (tagged.add(p.getUniqueId())) Msg.err(p, "You are now in combat! Don't log out.");
    }

    // ---------- combat tag ----------
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        if (e.getDamager() instanceof Player p1) attacker = p1;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p2) attacker = p2;
        if (attacker == null || attacker.equals(victim)) return;
        lastAttacker.put(victim.getUniqueId(), attacker.getUniqueId());
        if (!combatEnabled()) return;
        long until = System.currentTimeMillis() + plugin.getConfig().getLong("combat.seconds", 15) * 1000L;
        tag(attacker, until);
        tag(victim, until);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!inCombat(p) || p.hasPermission("kapom.combat.bypass")) return;
        String m = e.getMessage();
        if (m.length() < 2) return;
        String base = m.substring(1).split(" ")[0].toLowerCase();
        int colon = base.indexOf(':');
        if (colon >= 0) base = base.substring(colon + 1);
        List<String> blocked = plugin.getConfig().getStringList("combat.blocked-commands");
        if (blocked.contains(base)) {
            e.setCancelled(true);
            Msg.err(p, "You can't use /" + Msg.esc(base) + " in combat! <#FFD166>(" + secondsLeft(p) + "s)");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (inCombat(p) && plugin.getConfig().getBoolean("combat.kill-on-logout", true) && !p.isDead()) {
            Bukkit.broadcast(Msg.mm("<#FF6B6B>☠ <white>" + Msg.esc(p.getName()) + " <gray>logged out in combat!"));
            p.setHealth(0);   // fires PlayerDeathEvent -> killer is credited below
        }
        combatUntil.remove(p.getUniqueId());
        tagged.remove(p.getUniqueId());
    }

    // ---------- kills / streaks ----------
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player v = e.getEntity();
        long now = System.currentTimeMillis();
        DataStore.PD vd = plugin.data.get(v);

        Player k = v.getKiller();
        if (k == null) {   // e.g. combat logout: use the last attacker while still in combat
            UUID a = lastAttacker.get(v.getUniqueId());
            Long until = combatUntil.get(v.getUniqueId());
            if (a != null && until != null && until > now) k = Bukkit.getPlayer(a);
        }
        if (k != null && k.equals(v)) k = null;

        int lostStreak = vd.streak;
        vd.deaths++;
        vd.streak = 0;
        combatUntil.remove(v.getUniqueId());
        tagged.remove(v.getUniqueId());
        lastAttacker.remove(v.getUniqueId());
        if (k == null) return;

        // anti-farm: same victim killed again too quickly = no credit
        String key = k.getUniqueId() + ":" + v.getUniqueId();
        long cd = plugin.getConfig().getLong("kills.same-victim-cooldown", 30) * 1000L;
        Long last = lastKill.get(key);
        if (last != null && now - last < cd) {
            Msg.info(k, "No kill credit: you killed that player too recently.");
            return;
        }
        lastKill.put(key, now);

        DataStore.PD kd = plugin.data.get(k);
        kd.kills++;
        kd.streak++;
        kd.bestStreak = Math.max(kd.bestStreak, kd.streak);

        e.deathMessage(Msg.mm("<#FF6B6B>☠ <white>" + Msg.esc(v.getName()) + " <gray>was slain by <#7CFC9A>"
                + Msg.esc(k.getName()) + " <dark_gray>[<#FFD166>" + kd.streak + "<dark_gray>]"));

        int step = plugin.getConfig().getInt("kills.streak-milestone", 5);
        if (step > 0 && kd.streak % step == 0) {
            Bukkit.broadcast(Msg.mm("<gradient:#F7971E:#FFD200><bold>🔥 " + Msg.esc(k.getName())
                    + " is on a " + kd.streak + " killstreak!</bold></gradient>"));
        }
        if (step > 0 && lostStreak >= step) {
            Bukkit.broadcast(Msg.mm("<#7CFC9A>" + Msg.esc(k.getName()) + " <gray>ended <#FF6B6B>"
                    + Msg.esc(v.getName()) + "<gray>'s killstreak of <#FFD166>" + lostStreak));
        }

        if (plugin.getConfig().getBoolean("kills.effects", true)) {
            v.getWorld().strikeLightningEffect(v.getLocation());
            k.playSound(k.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
        }
        for (String cmd : plugin.getConfig().getStringList("kills.reward-commands")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    cmd.replace("%killer%", k.getName()).replace("%victim%", v.getName()));
        }
        plugin.boards.update(k);
    }
}
