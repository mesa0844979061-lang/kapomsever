package th.kapom.core;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.*;

import java.util.*;

/** Sidebar scoreboard */
public class BoardManager {
    private static final int LINES = 10;
    private static final TextColor LABEL = TextColor.fromHexString("#9AA5B1");
    private static final TextColor SEP = TextColor.fromHexString("#4A5568");

    private final KapomPlugin plugin;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final Map<UUID, Component[]> last = new HashMap<>();

    public BoardManager(KapomPlugin plugin) { this.plugin = plugin; }

    private Component title() {
        String t = plugin.getConfig().getString("scoreboard-title", "<gradient:#FF512F:#F09819><bold>KAPOM</bold></gradient>");
        return t.contains("&") ? LegacyComponentSerializer.legacyAmpersand().deserialize(t) : Msg.mm(t);
    }

    public void create(Player p) {
        Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
        Objective o = sb.registerNewObjective("kapom", Criteria.DUMMY, title());
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
        last.put(p.getUniqueId(), new Component[LINES]);
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
                plugin.data.get(p).seconds++;   // playtime counter
                update(p, online);
            }
        }, 20L, 20L);
    }

    public void update(Player p) { update(p, Bukkit.getOnlinePlayers().size()); }

    private static Component row(String label, Component value) {
        return Component.text(label + " ", LABEL).append(value);
    }

    private void update(Player p, int online) {
        Scoreboard sb = boards.get(p.getUniqueId());
        if (sb == null) return;
        if (p.getScoreboard() == Bukkit.getScoreboardManager().getMainScoreboard()) p.setScoreboard(sb);
        DataStore.PD d = plugin.data.get(p);

        Component tier = plugin.tiers.component(d);
        Component sepSmall = Component.text("/", NamedTextColor.DARK_GRAY);

        Component[] lines = {
                Component.text("━━━━━━━━━━━━━━", SEP),
                row("Name »", Component.text(p.getName(), TextColor.fromHexString("#7CFC9A"))),
                row("Playtime »", Component.text(fmt(d.seconds), TextColor.fromHexString("#FFD166"))),
                row("Tier »", tier),
                row("K/D »", Component.text(d.kills, TextColor.fromHexString("#7CFC9A")).append(sepSmall)
                        .append(Component.text(d.deaths, TextColor.fromHexString("#FF6B6B")))),
                row("Streak »", Component.text(d.streak, TextColor.fromHexString("#FFA45B"))),
                row("W/L »", Component.text(d.wins, TextColor.fromHexString("#7CFC9A")).append(sepSmall)
                        .append(Component.text(d.losses, TextColor.fromHexString("#FF6B6B")))),
                Component.text(" "),
                row("Players Online »", Component.text(online, TextColor.fromHexString("#C77DFF"))),
                Component.text("━━━━━━━━━━━━━━", SEP)
        };
        Component[] prev = last.get(p.getUniqueId());
        for (int i = 0; i < LINES; i++) {
            if (lines[i].equals(prev[i])) continue;
            sb.getTeam("l" + i).prefix(lines[i]);
            prev[i] = lines[i];
        }
    }

    public static String fmt(long s) {
        long h = s / 3600, m = (s % 3600) / 60;
        return h + "h " + m + "m";
    }
}
