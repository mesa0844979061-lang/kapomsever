package th.kapom.core;

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

    // ================= ตาราง =================
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

    private String tableText(String type) {
        boolean k = type.equals("kills");
        List<DataStore.PD> list = new ArrayList<>(data.all());
        list.sort(Comparator.comparingInt((DataStore.PD d) -> k ? d.kills : d.wins).reversed());
        StringBuilder sb = new StringBuilder(k ? "§c§lตารางฆ่า §7(TOP 10)" : "§a§lตารางชนะ §7(TOP 10)");
        for (int i = 0; i < 10; i++) {
            int v = i < list.size() ? (k ? list.get(i).kills : list.get(i).wins) : 0;
            if (v > 0) sb.append("\n§e#").append(i + 1).append(" §f").append(list.get(i).name).append(" §7- §6").append(v);
            else sb.append("\n§e#").append(i + 1).append(" §8---");
        }
        return sb.toString();
    }

    private static String tableType(String s) {
        s = s.toLowerCase();
        if (s.equals("ฆ่า") || s.equals("kills") || s.equals("kill")) return "kills";
        if (s.equals("ชนะ") || s.equals("wins") || s.equals("win")) return "wins";
        return null;
    }

    // ================= Commands =================
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("kapom.admin")) { s.sendMessage("§cไม่มีสิทธิ์"); return true; }
        switch (cmd.getName().toLowerCase()) {
            case "kapom" -> kapom(s, a);
            case "tier" -> tier(s, a);
            case "npc" -> npcCmd(s, a);
        }
        return true;
    }

    private void kapom(CommandSender s, String[] a) {
        if (a.length == 0) {
            s.sendMessage("§e/kapom spawn npc <ชื่อ>\n/kapom spawn ตาราง <ฆ่า|ชนะ>\n/kapom remove npc <ชื่อ>\n/kapom remove ตาราง\n/kapom win|lose <ผู้เล่น> [จำนวน]");
            return;
        }
        switch (a[0].toLowerCase()) {
            case "spawn" -> {
                if (!(s instanceof Player p)) { s.sendMessage("§cใช้ในเกมเท่านั้น"); return; }
                if (a.length < 2) { s.sendMessage("§c/kapom spawn <ตาราง|npc> ..."); return; }
                String kind = a[1].toLowerCase();
                if (kind.equals("npc")) {
                    if (a.length < 3) { s.sendMessage("§c/kapom spawn npc <ชื่อ>"); return; }
                    String name = a[2];
                    if (name.contains(".")) { s.sendMessage("§cชื่อห้ามมีจุด"); return; }
                    if (npcs.exists(name)) { s.sendMessage("§cมี NPC ชื่อนี้แล้ว"); return; }
                    npcs.create(name, p.getLocation());
                    s.sendMessage("§aสร้าง NPC §e" + name + " §aแล้ว ตั้งโลกด้วย §f/npc " + name + " set <ชื่อโลก>");
                } else if (kind.startsWith("ตาราง") || kind.startsWith("table")) {
                    String rest = kind.startsWith("ตาราง") ? kind.substring(5) : kind.substring(5);
                    if (rest.isEmpty() && a.length >= 3) rest = a[2];
                    String type = tableType(rest);
                    if (type == null) { s.sendMessage("§c/kapom spawn ตาราง <ฆ่า|ชนะ>"); return; }
                    String id = String.valueOf(nextTableId());
                    Location loc = p.getLocation().add(0, 2.0, 0);
                    getConfig().set("tables." + id + ".type", type);
                    getConfig().set("tables." + id + ".loc", loc);
                    saveConfig();
                    registerTable(id, type, loc);
                    s.sendMessage("§aสร้างตาราง" + (type.equals("kills") ? "ฆ่า" : "ชนะ") + "แล้ว");
                } else s.sendMessage("§c/kapom spawn <ตาราง|npc> ...");
            }
            case "remove" -> {
                if (a.length < 2) { s.sendMessage("§c/kapom remove <npc ชื่อ|ตาราง>"); return; }
                if (a[1].equalsIgnoreCase("npc")) {
                    NpcManager.Npc n = a.length > 2 ? npcs.get(a[2]) : null;
                    if (n == null) { s.sendMessage("§cไม่พบ NPC"); return; }
                    npcs.remove(n);
                    s.sendMessage("§aลบ NPC แล้ว");
                } else if (s instanceof Player p) {
                    String key = holos.nearest(p.getLocation(), 6, "table:");
                    if (key == null) { s.sendMessage("§cไม่พบตารางในรัศมี 6 บล็อก"); return; }
                    holos.unregister(key);
                    getConfig().set("tables." + key.substring(6), null);
                    saveConfig();
                    s.sendMessage("§aลบตารางแล้ว");
                }
            }
            case "win", "lose" -> {
                if (a.length < 2) { s.sendMessage("§c/kapom " + a[0] + " <ผู้เล่น> [จำนวน]"); return; }
                DataStore.PD d = data.byName(a[1]);
                if (d == null) { s.sendMessage("§cไม่พบผู้เล่น"); return; }
                int n = 1;
                if (a.length > 2) try { n = Integer.parseInt(a[2]); } catch (NumberFormatException ignored) {}
                if (a[0].equalsIgnoreCase("win")) d.wins += n; else d.losses += n;
                s.sendMessage("§aอัปเดตแล้ว");
            }
            default -> s.sendMessage("§cไม่รู้จักคำสั่ง");
        }
    }

    private void tier(CommandSender s, String[] a) {
        if (a.length >= 3 && a[0].equalsIgnoreCase("add")) {
            DataStore.PD d = data.byName(a[1]);
            if (d == null) { s.sendMessage("§cไม่พบผู้เล่น (ต้องเคยเข้าเซิร์ฟ)"); return; }
            d.tier = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
            Player p = Bukkit.getPlayerExact(a[1]);
            if (p != null) boards.update(p);
            s.sendMessage("§aตั้งเทียร์ " + a[1] + " เป็น §b" + d.tier.replace('&', '§'));
        } else if (a.length >= 2 && a[0].equalsIgnoreCase("remove")) {
            DataStore.PD d = data.byName(a[1]);
            if (d == null) { s.sendMessage("§cไม่พบผู้เล่น"); return; }
            d.tier = "-";
            s.sendMessage("§aลบเทียร์แล้ว");
        } else s.sendMessage("§c/tier add <ผู้เล่น> <เทียร์>  |  /tier remove <ผู้เล่น>");
    }

    private void npcCmd(CommandSender s, String[] a) {
        if (a.length < 3 || !a[1].equalsIgnoreCase("set")) { s.sendMessage("§c/npc <ชื่อnpc> set <ชื่อโลก>"); return; }
        NpcManager.Npc n = npcs.get(a[0]);
        if (n == null) { s.sendMessage("§cไม่พบ NPC ชื่อ " + a[0]); return; }
        if (Bukkit.getWorld(a[2]) == null) { s.sendMessage("§cโลก " + a[2] + " ยังไม่ถูกโหลด (ใช้ /mv load หรือ /mv create ก่อน)"); return; }
        npcs.setTarget(n, a[2]);
        s.sendMessage("§aNPC §e" + n.name + " §aจะวาร์ปไปโลก §b" + a[2]);
    }

    // ================= Tab =================
    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        switch (cmd.getName().toLowerCase()) {
            case "kapom" -> {
                if (a.length == 1) out = List.of("spawn", "remove", "win", "lose");
                else if (a.length == 2 && a[0].equalsIgnoreCase("spawn")) out = List.of("ตาราง", "npc");
                else if (a.length == 2 && a[0].equalsIgnoreCase("remove")) out = List.of("ตาราง", "npc");
                else if (a.length == 3 && a[0].equalsIgnoreCase("spawn") && a[1].equals("ตาราง")) out = List.of("ฆ่า", "ชนะ");
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
