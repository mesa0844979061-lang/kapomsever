package th.kapom.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;

import java.util.*;
import java.util.stream.Collectors;

public class KapomPlugin extends org.bukkit.plugin.java.JavaPlugin implements Listener, TabExecutor {
    public DataStore data;
    public HoloManager holos;
    public NpcManager npcs;
    public BoardManager boards;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        data = new DataStore(this);
        holos = new HoloManager(this);
        npcs = new NpcManager(this);
        boards = new BoardManager(this);

        npcs.load();
        loadTables();
        npcs.start();
        holos.start();
        boards.start();

        getServer().getPluginManager().registerEvents(this, this);
        for (String c : List.of("kapom", "tier", "npc")) {
            getCommand(c).setExecutor(this);
            getCommand(c).setTabCompleter(this);
        }
        getServer().getScheduler().runTaskTimer(this, data::save, 1200L, 1200L);

        for (Player p : Bukkit.getOnlinePlayers()) { boards.create(p); npcs.clear(p); }
    }

    @Override
    public void onDisable() {
        npcs.hideAll();
        holos.removeAll();
        data.save();
    }

    // ================= Events =================
    @EventHandler public void onJoin(PlayerJoinEvent e) { data.get(e.getPlayer()); boards.create(e.getPlayer()); npcs.clear(e.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { boards.remove(e.getPlayer()); npcs.forget(e.getPlayer()); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { npcs.clear(e.getPlayer()); }
    @EventHandler public void onRespawn(PlayerRespawnEvent e) { npcs.clear(e.getPlayer()); }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player v = e.getEntity();
        data.get(v).deaths++;
        Player k = v.getKiller();
        if (k != null && !k.equals(v)) data.get(k).kills++;
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
        for (String id : s.getKeys(false)) {
            Location l = s.getLocation(id + ".loc");
            if (l != null) registerTable(id, s.getString(id + ".type", "kills"), l);
        }
    }

    private void registerTable(String id, String type, Location loc) {
        holos.register("table:" + id, loc, () -> tableText(type));
    }

    private Component tableText(String type) {
        boolean k = type.equals("kills");
        List<DataStore.PD> list = new ArrayList<>(data.all());
        list.sort(Comparator.comparingInt((DataStore.PD d) -> k ? d.kills : d.wins).reversed());

        Component c = Msg.mm(k
                ? "<gradient:#FF416C:#FF4B2B><bold>⚔ TOP KILLS ⚔</bold></gradient>"
                : "<gradient:#11998E:#38EF7D><bold>★ TOP WINS ★</bold></gradient>")
                .append(Component.newline())
                .append(Msg.mm("<dark_gray>━━━━━━━━━━━━━━━"));
        String[] rankColors = {"#FFD700", "#C0C0C0", "#CD7F32"};
        for (int i = 0; i < 10; i++) {
            String rc = i < 3 ? rankColors[i] : "#8A94A0";
            int v = i < list.size() ? (k ? list.get(i).kills : list.get(i).wins) : 0;
            Component row = Msg.mm("<" + rc + "><bold>#" + (i + 1) + "</bold> ");
            if (v > 0) {
                row = row.append(Component.text(list.get(i).name, i < 3 ? TextColor.fromHexString(rc) : NamedTextColor.WHITE))
                        .append(Msg.mm(" <dark_gray>» <#4CC9F0>" + v));
            } else {
                row = row.append(Msg.mm("<dark_gray>---"));
            }
            c = c.append(Component.newline()).append(row);
        }
        return c;
    }

    private static String tableType(String s) {
        s = s.toLowerCase();
        if (s.equals("kills") || s.equals("kill")) return "kills";
        if (s.equals("wins") || s.equals("win")) return "wins";
        return null;
    }

    // ================= Commands =================
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("kapom.admin")) { Msg.err(s, "You don't have permission."); return true; }
        switch (cmd.getName().toLowerCase()) {
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
                {"/kapom spawn table <kills|wins>", "Create a leaderboard"},
                {"/kapom remove npc <name>", "Remove an NPC"},
                {"/kapom remove table", "Remove the nearest leaderboard"},
                {"/kapom win|lose <player> [n]", "Add wins / losses"},
                {"/npc <name> set <world>", "Set the NPC destination world"},
                {"/tier add <player> <tier>", "Set a player's tier (e.g. MT5)"},
                {"/tier remove <player>", "Clear a player's tier"},
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
                    Msg.ok(s, "NPC " + Msg.VALUE + Msg.esc(name) + "<#7CFC9A> created. Now set its world with <#FFD166>/npc " + Msg.esc(name) + " set <world>");
                } else if (kind.equals("table")) {
                    String type = a.length >= 3 ? tableType(a[2]) : null;
                    if (type == null) { Msg.err(s, "Usage: /kapom spawn table <kills|wins>"); return; }
                    String id = String.valueOf(nextTableId());
                    Location loc = p.getLocation().add(0, 2.0, 0);
                    getConfig().set("tables." + id + ".type", type);
                    getConfig().set("tables." + id + ".loc", loc);
                    saveConfig();
                    registerTable(id, type, loc);
                    Msg.ok(s, "Created the " + Msg.VALUE + type + "<#7CFC9A> leaderboard.");
                } else Msg.err(s, "Usage: /kapom spawn <npc|table> ...");
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

    private void tier(CommandSender s, String[] a) {
        if (a.length >= 3 && a[0].equalsIgnoreCase("add")) {
            DataStore.PD d = data.byName(a[1]);
            if (d == null) { Msg.err(s, "Player not found (they must have joined before)."); return; }
            d.tier = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
            Player p = Bukkit.getPlayerExact(a[1]);
            if (p != null) boards.update(p);
            Msg.ok(s, "Tier of " + Msg.VALUE + Msg.esc(d.name) + "<#7CFC9A> set to " + Msg.VALUE + Msg.esc(d.tier));
        } else if (a.length >= 2 && a[0].equalsIgnoreCase("remove")) {
            DataStore.PD d = data.byName(a[1]);
            if (d == null) { Msg.err(s, "Player not found."); return; }
            d.tier = "-";
            Player p = Bukkit.getPlayerExact(a[1]);
            if (p != null) boards.update(p);
            Msg.ok(s, "Tier cleared.");
        } else Msg.err(s, "Usage: /tier add <player> <tier>  |  /tier remove <player>");
    }

    private void npcCmd(CommandSender s, String[] a) {
        if (a.length < 3 || !a[1].equalsIgnoreCase("set")) { Msg.err(s, "Usage: /npc <name> set <world>"); return; }
        NpcManager.Npc n = npcs.get(a[0]);
        if (n == null) { Msg.err(s, "NPC " + Msg.VALUE + Msg.esc(a[0]) + "<#FF6B6B> not found."); return; }
        if (Bukkit.getWorld(a[2]) == null) {
            Msg.err(s, "World " + Msg.VALUE + Msg.esc(a[2]) + "<#FF6B6B> is not loaded (use /mv load or /mv create first).");
            return;
        }
        npcs.setTarget(n, a[2]);
        Msg.ok(s, "NPC " + Msg.VALUE + Msg.esc(n.name) + "<#7CFC9A> now teleports to " + Msg.VALUE + Msg.esc(a[2]));
    }

    // ================= Tab completion =================
    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        switch (cmd.getName().toLowerCase()) {
            case "kapom" -> {
                if (a.length == 1) out = List.of("spawn", "remove", "win", "lose");
                else if (a.length == 2 && (a[0].equalsIgnoreCase("spawn") || a[0].equalsIgnoreCase("remove"))) out = List.of("table", "npc");
                else if (a.length == 3 && a[0].equalsIgnoreCase("spawn") && a[1].equalsIgnoreCase("table")) out = List.of("kills", "wins");
                else if (a.length == 3 && a[0].equalsIgnoreCase("remove") && a[1].equalsIgnoreCase("npc"))
                    out = npcs.all().stream().map(n -> n.name).collect(Collectors.toList());
                else if (a.length == 2 && (a[0].equalsIgnoreCase("win") || a[0].equalsIgnoreCase("lose")))
                    out = Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
            }
            case "tier" -> {
                if (a.length == 1) out = List.of("add", "remove");
                else if (a.length == 2) out = Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
                else if (a.length == 3 && a[0].equalsIgnoreCase("add")) out = List.of("MT1", "MT2", "MT3", "MT4", "MT5", "HT1", "LT1");
            }
            case "npc" -> {
                if (a.length == 1) out = npcs.all().stream().map(n -> n.name).collect(Collectors.toList());
                else if (a.length == 2) out = List.of("set");
                else if (a.length == 3) out = Bukkit.getWorlds().stream().map(w -> w.getName()).collect(Collectors.toList());
            }
        }
        String last = a[a.length - 1].toLowerCase();
        return out.stream().filter(x -> x.toLowerCase().startsWith(last)).collect(Collectors.toList());
    }
}
