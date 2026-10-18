package th.kapom.core;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DataStore {
    public static class PD {
        public String name = "?";
        public int kills, deaths, wins, losses;
        public long seconds;
        public String tier = "-";
    }

    private final KapomPlugin plugin;
    private final File file;
    private final Map<UUID, PD> map = new ConcurrentHashMap<>();

    public DataStore(KapomPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
        plugin.getDataFolder().mkdirs();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("players");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(k);
            if (c == null) continue;
            PD d = new PD();
            d.name = c.getString("name", "?");
            d.kills = c.getInt("kills");
            d.deaths = c.getInt("deaths");
            d.wins = c.getInt("wins");
            d.losses = c.getInt("losses");
            d.seconds = c.getLong("seconds");
            d.tier = c.getString("tier", "-");
            try { map.put(UUID.fromString(k), d); } catch (IllegalArgumentException ignored) {}
        }
    }

    public PD get(Player p) {
        PD d = map.computeIfAbsent(p.getUniqueId(), k -> new PD());
        d.name = p.getName();
        return d;
    }

    /** หาจากชื่อ (ออนไลน์/เคยเข้าเซิร์ฟ) ไม่เจอคืน null */
    public PD byName(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return get(online);
        for (PD d : map.values()) if (d.name.equalsIgnoreCase(name)) return d;
        OfflinePlayer off = Bukkit.getOfflinePlayer(name);
        if (off.hasPlayedBefore()) {
            PD d = map.computeIfAbsent(off.getUniqueId(), k -> new PD());
            d.name = off.getName() == null ? name : off.getName();
            return d;
        }
        return null;
    }

    public Collection<PD> all() { return map.values(); }

    public synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, PD> e : map.entrySet()) {
            String p = "players." + e.getKey();
            PD d = e.getValue();
            y.set(p + ".name", d.name);
            y.set(p + ".kills", d.kills);
            y.set(p + ".deaths", d.deaths);
            y.set(p + ".wins", d.wins);
            y.set(p + ".losses", d.losses);
            y.set(p + ".seconds", d.seconds);
            y.set(p + ".tier", d.tier);
        }
        try { y.save(file); } catch (IOException ex) { plugin.getLogger().warning("save failed: " + ex.getMessage()); }
    }
}
