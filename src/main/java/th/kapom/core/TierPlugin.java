package th.kapom.tier;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.*;

import java.io.File;
import java.util.*;

public class TierPlugin extends JavaPlugin implements CommandExecutor {
    public static final MiniMessage MM = MiniMessage.miniMessage();
    private final Map<UUID, String> tiers = new HashMap<>();
    PvpListener pvp;
    DuelManager duel;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pvp = new PvpListener(this);
        duel = new DuelManager(this);
        // duel ต้องลงทะเบียนก่อน pvp (ให้ restore ของตอน quit ก่อน combat-log)
        getServer().getPluginManager().registerEvents(duel, this);
        getServer().getPluginManager().registerEvents(pvp, this);
        Objects.requireNonNull(getCommand("pvp")).setExecutor(duel);
        Objects.requireNonNull(getCommand("duel")).setExecutor(duel);
        for (String c : List.of("tierreload", "stats", "top")) {
            Objects.requireNonNull(getCommand(c)).setExecutor(this);
        }
        long period = Math.max(10, getConfig().getLong("refresh-ticks", 40));
        Bukkit.getScheduler().runTaskTimer(this, this::refreshAll, 20L, period);
        pvp.startTasks();
    }

    @Override
    public void onDisable() { duel.shutdown(); pvp.save(); }

    /** อ่าน tier จาก data.yml ของ KapomCore */
    private void loadTiers() {
        tiers.clear();
        File f = new File(getDataFolder().getParentFile(), "KapomCore/data.yml");
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        var sec = y.getConfigurationSection("players");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            try {
                String t = sec.getString(k + ".tier");
                if (t != null) tiers.put(UUID.fromString(k), t.toLowerCase());
            } catch (IllegalArgumentException ignored) {}
        }
    }

    public String tierOf(Player p) {
        return tiers.getOrDefault(p.getUniqueId(), getConfig().getString("default-tier", "member"));
    }

    public void refreshAll() {
        loadTiers();
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();
        for (Player viewer : online) {
            Scoreboard sb = viewer.getScoreboard();
            for (Player target : online) applyTag(sb, target);
            if (getConfig().getBoolean("show-health-below-name", true)) {
                Objective o = sb.getObjective("kt_health");
                if (o == null) {
                    o = sb.registerNewObjective("kt_health", Criteria.HEALTH, Component.text("❤"));
                    o.setDisplaySlot(DisplaySlot.BELOW_NAME);
                }
            }
        }
    }

    /** ป้ายเหนือหัว = team prefix (ทำงานกับ scoreboard แยกรายคนของ KapomCore) */
    private void applyTag(Scoreboard sb, Player target) {
        String tier = tierOf(target);
        String fmt = getConfig().getString("tiers." + tier,
                getConfig().getString("tiers." + getConfig().getString("default-tier", "member"), ""));
        String teamName = ("kt_" + tier);
        if (teamName.length() > 40) teamName = teamName.substring(0, 40);
        // เอาออกจากทีม kt_ อื่น
        for (Team t : sb.getTeams()) {
            if (t.getName().startsWith("kt_") && !t.getName().equals(teamName) && t.hasEntry(target.getName()))
                t.removeEntry(target.getName());
        }
        Team team = sb.getTeam(teamName);
        if (team == null) team = sb.registerNewTeam(teamName);
        team.prefix(MM.deserialize(fmt));
        if (!team.hasEntry(target.getName())) team.addEntry(target.getName());
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        switch (c.getName()) {
            case "tierreload" -> {
                reloadConfig();
                refreshAll();
                s.sendMessage(MM.deserialize("<green>Reloaded."));
            }
            case "stats" -> {
                Player t = a.length > 0 ? Bukkit.getPlayerExact(a[0]) : (s instanceof Player p ? p : null);
                if (t == null) { s.sendMessage(MM.deserialize("<red>ไม่พบผู้เล่น")); return true; }
                pvp.sendStats(s, t);
            }
            case "top" -> pvp.sendTop(s, a.length > 0 ? a[0] : "kills");
        }
        return true;
    }
}
