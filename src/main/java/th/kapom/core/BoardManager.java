package th.kapom.core;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.util.*;

/** กระดานข้างขวาจอ */
public class BoardManager {
    private static final int LINES = 7;
    private final KapomPlugin plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, String[]> last = new HashMap<>();
    private final LegacyComponentSerializer sec = LegacyComponentSerializer.legacySection();

    public BoardManager(KapomPlugin plugin) { this.plugin = plugin; }

    public void create(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        String title = plugin.getConfig().getString("scoreboard-title", "&6&lKAPOM");
        Objective o = sb.registerNewObjective("kapom", Criteria.DUMMY,
                LegacyComponentSerializer.legacyAmpersand().deserialize(title));
        o.setDisplaySlot(DisplaySlot.SIDEBAR);
        o.numberFormat(NumberFormat.blank());
        for (int i = 0; i < LINES; i++) {
            Team t = sb.registerNewTeam("l" + i);
            String entry = "§" + Integer.toHexString(i) + "§r";
            t.addEntry(entry);
            o.getScore(entry).setScore(LINES - i);
        }
        p.setScoreboard(sb);
        boards.put(p.getUniqueId(), sb);
        last.put(p.getUniqueId(), new String[LINES]);
        update(p);
    }

    public void remove(Player p) {
        boards.remove(p.getUniqueId());
        last.remove(p.getUniqueId());
    }

    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int online = Bukkit.getOnlinePlayers().size();
            for (Player p : Bukkit.getOnlinePlayers()) {
                plugin.data.get(p).seconds++;   // นับเวลาเล่น
                update(p, online);
            }
        }, 20L, 20L);
    }

    public void update(Player p) { update(p, Bukkit.getOnlinePlayers().size()); }

    private void update(Player p, int online) {
        Scoreboard sb = boards.get(p.getUniqueId());
        if (sb == null) return;
        DataStore.PD d = plugin.data.get(p);
        String[] lines = {
                "§fname : §a" + p.getName(),
                "§fเวลาเล่น : §e" + fmt(d.seconds),
                "§fเทียร์ : §b" + d.tier.replace('&', '§'),
                "§fฆ่า/ตาย : §a" + d.kills + "§7/§c" + d.deaths,
                "§fชนะ/แพ้ : §a" + d.wins + "§7/§c" + d.losses,
                "§7",
                "§fผู้เล่นในเซิฟตอนนี้ : §d" + online
        };
        String[] prev = last.get(p.getUniqueId());
        for (int i = 0; i < LINES; i++) {
            if (lines[i].equals(prev[i])) continue;
            sb.getTeam("l" + i).prefix(sec.deserialize(lines[i]));
            prev[i] = lines[i];
        }
    }

    private static String fmt(long s) {
        long h = s / 3600, m = (s % 3600) / 60;
        return h + "ชม. " + m + "น.";
    }
}
