package th.kapom.core;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;

import java.util.*;
import java.util.stream.Collectors;

public class KapomPlugin extends org.bukkit.plugin.java.JavaPlugin implements Listener, TabExecutor {
    public DataStore data;
    public HoloManager holos;
    public NpcManager npcs;
    public BoardManager boards;
    public TierManager tiers;
    public PvpManager pvp;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        data = new DataStore(this);
        holos = new HoloManager(this);
        tiers = new TierManager(this);
        pvp = new PvpManager(this);
        npcs = new NpcManager(this);
        boards = new BoardManager(this);

        npcs.load();
        loadTables();
        npcs.start();
        holos.start();
        boards.start();
        tiers.start();
        pvp.start();

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(pvp, this);
        for (String c : List.of("kapom", "tier", "npc", "stats", "top")) {
            getCommand(c).setExecutor(this);
            getCommand(c).setTabCompleter(this);
        }
        getServer().getScheduler().runTaskTimer(this, data::save, 1200L, 1200L);

        for (Player p : Bukkit.getOnlinePlayers()) { boards.create(p); npcs.clear(p); }
    }

    @Override
    public void onDisable() {
        npcs.hideAll();
        tiers.removeAll();
        holos.removeAll();
        data.save();
    }

    // ================= Events =================
    private void later(Player p, long ticks) {
        Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) tiers.refresh(p); }, ticks);
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        data.get(e.getPlayer()); boards.create(e.getPlayer()); npcs.clear(e.getPlayer()); later(e.getPlayer(), 10L);
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) { boards.remove(e.getPlayer()); npcs.forget(e.getPlayer()); tiers.remove(e.getPlayer()); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { npcs.clear(e.getPlayer()); later(e.getPlayer(), 2L); }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) { npcs.clear(e.getPlayer()); later(e.getPlayer(), 3L); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) { later(e.getPlayer(), 2L); }

    /** [TIER] Name » message */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!getConfig().getBoolean("chat-format", true)) return;
        DataStore.PD d = data.get(e.getPlayer());
        Component prefix = d.tier.equals("-") ? Component.empty()
                : Msg.mm("<dark_gray>[").append(tiers.component(d)).append(Msg.mm("<dark_gray>] "));
        e.renderer((source, name, message, viewer) ->
                prefix.append(name).append(Msg.mm(" <dark_gray>» <white>")).append(message));
    }

    // ================= Stats helpers =================
    static double stat(DataStore.PD d, String t) {
        return switch (t) {
            case "kills" -> d.kills;
            case "deaths" -> d.deaths;
            case "wins" -> d.wins;
            case "streak" -> d.bestStreak;
            case "kdr" -> d.deaths == 0 ? d.kills : (double) d.kills / d.deaths;
            case "playtime" -> d.seconds;
            default -> -1;
        };
    }

    static String fmtStat(String t, double v) {
        if (t.equals("kdr")) return String.format(Locale.US, "%.2f", v);
        if (t.equals("playtime")) return BoardManager.fmt((long) v);
        return String.valueOf((long) v);
    }

    // ================= Leaderboards =================
    private int nextTableId() {
        ConfigurationSection s = getConfig().getConfigurationSection("tables");
        int max = 0;
        if (s != null) for (String k : s.getKeys(false)) try { max = Math.max(max, Integer.parseInt(k)); } catch (Exception ignored) {}
        return max + 1;
    }

    private void loadTables() {
        ConfigurationSection s = getConfig().getConfigurationSection("tables");
        if (s == null) return;
        boolean changed = false;
        for (String id : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(id);
            Pos pos = Pos.read(c);
            if (pos == null) {
                getLogger().warning("Leaderboard '" + id + "' has no valid location - skipped.");
                continue;
            }
            registerTable(id, c.getString("type", "kills"), pos);
            pos.write(getConfig(), "tables." + id);      // migrate old "loc:" saves
            getConfig().set("tables." + id + ".loc", null);
            changed = true;
        }
        if (changed) saveConfig();
    }

    private void registerTable(String id, String type, Pos pos) {
        holos.register("table:" + id, pos, 0, () -> tableText(type));
    }

    private Component tableText(String type) {
        List<DataStore.PD> list = new ArrayList<>(data.all());
        list.sort((x, y) -> Double.compare(stat(y, type), stat(x, type)));
        String title = switch (type) {
            case "wins" -> "<gradient:#11998E:#38EF7D><bold>★ TOP WINS ★</bold></gradient>";
            case "streak" -> "<gradient:#F7971E:#FFD200><bold>✦ TOP KILLSTREAK ✦</bold></gradient>";
            case "kdr" -> "<gradient:#7B2FF7:#C77DFF><bold>◆ TOP K/D ◆</bold></gradient>";
            default -> "<gradient:#FF416C:#FF4B2B><bold>⚔ TOP KILLS ⚔</bold></gradient>";
        };
        Component c = Msg.mm(title).append(Component.newline()).append(Msg.mm("<dark_gray>━━━━━━━━━━━━━━━"));
        String[] rankColors = {"#FFD700", "#C0C0C0", "#CD7F32"};
        for (int i = 0; i < 10; i++) {
            String rc = i < 3 ? rankColors[i] : "#8A94A0";
            double v = i < list.size() ? stat(list.get(i), type) : 0;
            Component row = Msg.mm("<" + rc + "><bold>#" + (i + 1) + "</bold> ");
            if (v > 0) {
                row = row.append(Component.text(list.get(i).name, i < 3 ? TextColor.fromHexString(rc) : NamedTextColor.WHITE))
                        .append(Msg.mm(" <dark_gray>» <#4CC9F0>" + fmtStat(type, v)));
            } else {
                row = row.append(Msg.mm("<dark_gray>---"));
            }
            c = c.append(Component.newline()).append(row);
        }
        return c;
    }

    private static String tableType(String s) {
        s = s.toLowerCase();
        return switch (s) {
            case "kills", "kill" -> "kills";
            case "wins", "win" -> "wins";
            case "streak", "ks", "killstreak" -> "streak";
            case "kdr", "kd" -> "kdr";
            default -> null;
        };
    }

    // ================= Commands =================
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String name = cmd.getName().toLowerCase();
        if (name.equals("stats")) { stats(s, a); return true; }
        if (name.equals("top")) { top(s, a); return true; }
        if (!s.hasPermission("kapom.admin")) { Msg.err(s, "You don't have permission."); return true; }
        switch (name) {
            case "kapom" -> kapom(s, a);
            case "tier" -> tier(s, a);
            case "npc" -> npcCmd(s, a);
        }
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage(Msg.mm("<gradient:#00F5A0:#00D9F5><bold>━━ KapomCore ━━</bold></gradient>"));
        String[][] h = {
                {"/kapom spawn npc <name>", "Create an NPC where you stand"},
                {"/kapom spawn table <kills|wins|streak|kdr>", "Create a leaderboard"},
                {"/kapom npc <name> set <world>", "Set the NPC destination world"},
                {"/kapom remove npc <name>", "Remove an NPC"},
                {"/kapom remove table", "Remove the nearest leaderboard"},
                {"/kapom win|lose <player> [n]", "Add wins / losses"},
                {"/kapom list | reload", "List NPCs & boards / reload config"},
                {"/tier add <player> <tier> [color]", "Set tier (shown above head)"},
                {"/tier color <player> <color|reset>", "Color: red, #FF0000 or #FF0000:#FFD700"},
                {"/tier remove <player>", "Clear a player's tier"},
                {"/stats [player]  /top [stat]", "Stats & rankings (everyone)"},
        };
        for (String[] r : h)
            s.sendMessage(Msg.mm("<#4CC9F0>" + Msg.esc(r[0]) + " <dark_gray>- <gray>" + Msg.esc(r[1])));
    }

    private void kapom(CommandSender s, String[] a) {
        if (a.length == 0) { help(s); return; }
        switch (a[0].toLowerCase()) {
            case "spawn" -> {
                if (!(s instanceof Player p)) { Msg.err(s, "Players only."); return; }
                if (a.length < 2) { Msg.err(s, "Usage: /kapom spawn <npc|table> ..."); return; }
                String kind = a[1].toLowerCase();
                if (kind.equals("npc")) {
                    if (a.length < 3) { Msg.err(s, "Usage: /kapom spawn npc <name>"); return; }
                    String name = a[2];
                    if (name.contains(".")) { Msg.err(s, "The name can't contain a dot."); return; }
                    if (npcs.exists(name)) { Msg.err(s, "An NPC with that name already exists."); return; }
                    npcs.create(name, p.getLocation());
                    Msg.ok(s, "NPC " + Msg.VALUE + Msg.esc(name) + "<#7CFC9A> created. Now set its world with <#FFD166>/kapom npc " + Msg.esc(name) + " set <world>");
                } else if (kind.equals("table")) {
                    String type = a.length >= 3 ? tableType(a[2]) : null;
                    if (type == null) { Msg.err(s, "Usage: /kapom spawn table <kills|wins|streak|kdr>"); return; }
                    String id = String.valueOf(nextTableId());
                    Pos pos = Pos.of(p.getLocation().add(0, 2.0, 0));
                    getConfig().set("tables." + id + ".type", type);
                    pos.write(getConfig(), "tables." + id);
                    saveConfig();
                    registerTable(id, type, pos);
                    Msg.ok(s, "Created the " + Msg.VALUE + type + "<#7CFC9A> leaderboard.");
                } else Msg.err(s, "Usage: /kapom spawn <npc|table> ...");
            }
            case "npc" -> {
                if (a.length < 4 || !a[2].equalsIgnoreCase("set")) { Msg.err(s, "Usage: /kapom npc <name> set <world>"); return; }
                setNpcWorld(s, a[1], a[3]);
            }
            case "remove" -> {
                if (a.length < 2) { Msg.err(s, "Usage: /kapom remove <npc <name>|table>"); return; }
                if (a[1].equalsIgnoreCase("npc")) {
                    NpcManager.Npc n = a.length > 2 ? npcs.get(a[2]) : null;
                    if (n == null) { Msg.err(s, "NPC not found."); return; }
                    npcs.remove(n);
                    Msg.ok(s, "NPC removed.");
                } else if (a[1].equalsIgnoreCase("table")) {
                    if (!(s instanceof Player p)) { Msg.err(s, "Players only."); return; }
                    String key = holos.nearest(p.getLocation(), 6, "table:");
                    if (key == null) { Msg.err(s, "No leaderboard within 6 blocks."); return; }
                    holos.unregister(key);
                    getConfig().set("tables." + key.substring(6), null);
                    saveConfig();
                    Msg.ok(s, "Leaderboard removed.");
                } else Msg.err(s, "Usage: /kapom remove <npc <name>|table>");
            }
            case "list" -> {
                Msg.info(s, "NPCs: " + Msg.VALUE + npcs.all().size());
                for (NpcManager.Npc n : npcs.all())
                    s.sendMessage(Msg.mm("<dark_gray> - <#FFD166>" + Msg.esc(n.name) + " <dark_gray>→ " + Msg.VALUE
                            + Msg.esc(n.target == null ? "(not set)" : n.target)));
                ConfigurationSection t = getConfig().getConfigurationSection("tables");
                Msg.info(s, "Leaderboards: " + Msg.VALUE + (t == null ? 0 : t.getKeys(false).size()));
            }
            case "reload" -> { reloadConfig(); Msg.ok(s, "Config reloaded."); }
            case "win", "lose" -> {
                if (a.length < 2) { Msg.err(s, "Usage: /kapom " + a[0] + " <player> [amount]"); return; }
                DataStore.PD d = data.byName(a[1]);
                if (d == null) { Msg.err(s, "Player not found."); return; }
                int n = 1;
                if (a.length > 2) try { n = Integer.parseInt(a[2]); } catch (NumberFormatException ignored) {}
                if (a[0].equalsIgnoreCase("win")) d.wins += n; else d.losses += n;
                Msg.ok(s, "Updated " + Msg.VALUE + Msg.esc(d.name) + "<#7CFC9A>.");
            }
            default -> help(s);
        }
    }

    private void refreshPlayer(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) { boards.update(p); tiers.refresh(p); }
    }

    private void tier(CommandSender s, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        switch (sub) {
            case "add" -> {
                if (a.length < 3) { Msg.err(s, "Usage: /tier add <player> <tier> [color]"); return; }
                DataStore.PD d = data.byName(a[1]);
                if (d == null) { Msg.err(s, "Player not found (they must have joined before)."); return; }
                d.tier = a[2];
                d.tierColor = "";
                if (a.length >= 4) {
                    if (!TierManager.validColor(a[3])) { Msg.err(s, "Invalid color. Use red, #FF0000 or #FF0000:#FFD700"); return; }
                    d.tierColor = a[3];
                }
                refreshPlayer(a[1]);
                s.sendMessage(Msg.mm(Msg.PREFIX + "<#7CFC9A>Tier of " + Msg.VALUE + Msg.esc(d.name) + "<#7CFC9A> set to ").append(tiers.component(d)));
            }
            case "color" -> {
                if (a.length < 3) { Msg.err(s, "Usage: /tier color <player> <color|reset>"); return; }
                DataStore.PD d = data.byName(a[1]);
                if (d == null) { Msg.err(s, "Player not found."); return; }
                if (a[2].equalsIgnoreCase("reset")) d.tierColor = "";
                else if (TierManager.validColor(a[2])) d.tierColor = a[2];
                else { Msg.err(s, "Invalid color. Use red, #FF0000 or #FF0000:#FFD700"); return; }
                refreshPlayer(a[1]);
                s.sendMessage(Msg.mm(Msg.PREFIX + "<#7CFC9A>Tier color updated: ").append(tiers.component(d)));
            }
            case "remove" -> {
                if (a.length < 2) { Msg.err(s, "Usage: /tier remove <player>"); return; }
                DataStore.PD d = data.byName(a[1]);
                if (d == null) { Msg.err(s, "Player not found."); return; }
                d.tier = "-";
                d.tierColor = "";
                refreshPlayer(a[1]);
                Msg.ok(s, "Tier cleared.");
            }
            default -> Msg.err(s, "Usage: /tier add <player> <tier> [color]  |  /tier color <player> <color>  |  /tier remove <player>");
        }
    }

    private void npcCmd(CommandSender s, String[] a) {
        if (a.length < 3 || !a[1].equalsIgnoreCase("set")) { Msg.err(s, "Usage: /npc <name> set <world>"); return; }
        setNpcWorld(s, a[0], a[2]);
    }

    private void setNpcWorld(CommandSender s, String npcName, String world) {
        NpcManager.Npc n = npcs.get(npcName);
        if (n == null) { Msg.err(s, "NPC " + Msg.VALUE + Msg.esc(npcName) + "<#FF6B6B> not found."); return; }
        if (Bukkit.getWorld(world) == null) {
            Msg.err(s, "World " + Msg.VALUE + Msg.esc(world) + "<#FF6B6B> is not loaded (use /mv load or /mv create first).");
            return;
        }
        npcs.setTarget(n, world);
        Msg.ok(s, "NPC " + Msg.VALUE + Msg.esc(n.name) + "<#7CFC9A> now teleports to " + Msg.VALUE + Msg.esc(world));
    }

    // ---------- public commands ----------
    private void stats(CommandSender s, String[] a) {
        DataStore.PD d;
        if (a.length > 0) d = data.byName(a[0]);
        else if (s instanceof Player p) d = data.get(p);
        else { Msg.err(s, "Usage: /stats <player>"); return; }
        if (d == null) { Msg.err(s, "Player not found."); return; }
        s.sendMessage(Msg.mm("<gradient:#00F5A0:#00D9F5><bold>━━ " + Msg.esc(d.name) + " ━━</bold></gradient>"));
        s.sendMessage(Msg.mm("<#9AA5B1>Tier <dark_gray>» ").append(tiers.component(d)));
        s.sendMessage(Msg.mm("<#9AA5B1>Kills <dark_gray>» <#7CFC9A>" + d.kills
                + " <dark_gray>| <#9AA5B1>Deaths <dark_gray>» <#FF6B6B>" + d.deaths
                + " <dark_gray>| <#9AA5B1>K/D <dark_gray>» <#FFD166>" + fmtStat("kdr", stat(d, "kdr"))));
        s.sendMessage(Msg.mm("<#9AA5B1>Streak <dark_gray>» <#FFA45B>" + d.streak
                + " <dark_gray>(best <#FFA45B>" + d.bestStreak + "<dark_gray>)"));
        s.sendMessage(Msg.mm("<#9AA5B1>Wins/Losses <dark_gray>» <#7CFC9A>" + d.wins + "<dark_gray>/<#FF6B6B>" + d.losses));
        s.sendMessage(Msg.mm("<#9AA5B1>Playtime <dark_gray>» <#FFD166>" + BoardManager.fmt(d.seconds)));
    }

    private void top(CommandSender s, String[] a) {
        String type = a.length > 0 ? a[0].toLowerCase() : "kills";
        if (type.equals("kill")) type = "kills";
        if (type.equals("win")) type = "wins";
        if (stat(new DataStore.PD(), type) < 0) {
            Msg.err(s, "Usage: /top <kills|deaths|wins|streak|kdr|playtime>");
            return;
        }
        final String t = type;
        List<DataStore.PD> list = new ArrayList<>(data.all());
        list.sort((x, y) -> Double.compare(stat(y, t), stat(x, t)));
        s.sendMessage(Msg.mm("<gradient:#00F5A0:#00D9F5><bold>━━ TOP " + t.toUpperCase() + " ━━</bold></gradient>"));
        String[] rankColors = {"#FFD700", "#C0C0C0", "#CD7F32"};
        int shown = 0;
        for (int i = 0; i < list.size() && shown < 10; i++) {
            double v = stat(list.get(i), t);
            if (v <= 0) break;
            String rc = shown < 3 ? rankColors[shown] : "#8A94A0";
            s.sendMessage(Msg.mm("<" + rc + "><bold>#" + (shown + 1) + "</bold> <white>" + Msg.esc(list.get(i).name)
                    + " <dark_gray>» <#4CC9F0>" + fmtStat(t, v)));
            shown++;
        }
        if (shown == 0) Msg.info(s, "Nobody is on this list yet.");
    }

    // ================= Tab completion =================
    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        List<String> players = Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
        List<String> colors = List.of("red", "gold", "yellow", "green", "aqua", "light_purple", "white", "#FF0000", "#FF0000:#FFD700", "#00F5A0:#00D9F5");
        switch (cmd.getName().toLowerCase()) {
            case "kapom" -> {
                if (!s.hasPermission("kapom.admin")) break;
                if (a.length == 1) out = List.of("spawn", "remove", "npc", "list", "reload", "win", "lose");
                else if (a.length == 2 && (a[0].equalsIgnoreCase("spawn") || a[0].equalsIgnoreCase("remove"))) out = List.of("table", "npc");
                else if (a.length == 3 && a[0].equalsIgnoreCase("spawn") && a[1].equalsIgnoreCase("table")) out = List.of("kills", "wins", "streak", "kdr");
                else if (a.length == 2 && a[0].equalsIgnoreCase("npc"))
                    out = npcs.all().stream().map(n -> n.name).collect(Collectors.toList());
                else if (a.length == 3 && a[0].equalsIgnoreCase("npc")) out = List.of("set");
                else if (a.length == 4 && a[0].equalsIgnoreCase("npc"))
                    out = Bukkit.getWorlds().stream().map(w -> w.getName()).collect(Collectors.toList());
                else if (a.length == 3 && a[0].equalsIgnoreCase("remove") && a[1].equalsIgnoreCase("npc"))
                    out = npcs.all().stream().map(n -> n.name).collect(Collectors.toList());
                else if (a.length == 2 && (a[0].equalsIgnoreCase("win") || a[0].equalsIgnoreCase("lose"))) out = players;
            }
            case "tier" -> {
                if (!s.hasPermission("kapom.admin")) break;
                if (a.length == 1) out = List.of("add", "color", "remove");
                else if (a.length == 2) out = players;
                else if (a.length == 3 && a[0].equalsIgnoreCase("add")) out = List.of("HT1", "HT2", "HT3", "HT4", "HT5", "MT1", "MT2", "MT3", "MT4", "MT5", "LT1", "LT2", "LT3", "LT4", "LT5");
                else if (a.length == 4 && a[0].equalsIgnoreCase("add")) out = colors;
                else if (a.length == 3 && a[0].equalsIgnoreCase("color")) out = new ArrayList<>(colors) {{ add("reset"); }};
            }
            case "npc" -> {
                if (!s.hasPermission("kapom.admin")) break;
                if (a.length == 1) out = npcs.all().stream().map(n -> n.name).collect(Collectors.toList());
                else if (a.length == 2) out = List.of("set");
                else if (a.length == 3) out = Bukkit.getWorlds().stream().map(w -> w.getName()).collect(Collectors.toList());
            }
            case "stats" -> { if (a.length == 1) out = players; }
            case "top" -> { if (a.length == 1) out = List.of("kills", "deaths", "wins", "streak", "kdr", "playtime"); }
        }
        String last = a[a.length - 1].toLowerCase();
        return out.stream().filter(x -> x.toLowerCase().startsWith(last)).collect(Collectors.toList());
    }
}
