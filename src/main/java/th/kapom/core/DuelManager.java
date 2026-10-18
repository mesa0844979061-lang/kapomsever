package th.kapom.tier;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static th.kapom.tier.TierPlugin.MM;

/**
 * เมนู PVP (เข้าโลก) + ดาบท้า duel:
 *  คลิกขวาผู้เล่นด้วยดาบ -> เลือก kit -> อีกฝ่ายคลิกขวากลับด้วยดาบ -> วาร์ปไปโลก duel (1pvp1)
 */
public class DuelManager implements Listener, CommandExecutor {
    private final TierPlugin pl;
    private final NamespacedKey key;
    private final Map<UUID, Request> requests = new HashMap<>();   // key = คนท้า
    private final Map<UUID, Duel> active = new HashMap<>();
    private final Set<String> arenasInUse = new HashSet<>();
    private final File backupFile;
    private final YamlConfiguration backup;

    record Request(UUID from, UUID to, String kit, long expires) {}

    static class State {
        Location loc; ItemStack[] inv; GameMode mode; int level; float exp;
    }

    static class Duel {
        final UUID a, b; final String arena, kit;
        boolean ended = false, frozen = true;
        final Map<UUID, State> states = new HashMap<>();
        Duel(UUID a, UUID b, String arena, String kit) { this.a = a; this.b = b; this.arena = arena; this.kit = kit; }
        UUID other(UUID id) { return id.equals(a) ? b : a; }
    }

    static class Gui implements InventoryHolder {
        final String type; final UUID target;
        final Map<Integer, String> slots = new HashMap<>();
        Inventory inv;
        Gui(String type, UUID target) { this.type = type; this.target = target; }
        @Override public Inventory getInventory() { return inv; }
    }

    DuelManager(TierPlugin pl) {
        this.pl = pl;
        this.key = new NamespacedKey(pl, "special");
        pl.getDataFolder().mkdirs();
        this.backupFile = new File(pl.getDataFolder(), "duel-backup.yml");
        this.backup = YamlConfiguration.loadConfiguration(backupFile);
    }

    private org.bukkit.configuration.file.FileConfiguration cfg() { return pl.getConfig(); }

    /* ---------------- items ---------------- */

    private ItemStack special(String type, Material m, String name, List<String> lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(MM.deserialize("<!italic>" + name));
        meta.lore(lore.stream().map(l -> MM.deserialize("<!italic>" + l)).toList());
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, type);
        it.setItemMeta(meta);
        return it;
    }

    private String specialType(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    private ItemStack swordItem() {
        Material m = Material.matchMaterial(cfg().getString("items.sword.material", "DIAMOND_SWORD"));
        return special("sword", m == null ? Material.DIAMOND_SWORD : m,
                cfg().getString("items.sword.name", "<gradient:#FF512F:#F09819><bold>ดาบท้า DUEL</bold></gradient>"),
                cfg().getStringList("items.sword.lore"));
    }

    private ItemStack menuItem() {
        Material m = Material.matchMaterial(cfg().getString("items.menu.material", "COMPASS"));
        return special("menu", m == null ? Material.COMPASS : m,
                cfg().getString("items.menu.name", "<gradient:#00F5A0:#00D9F5><bold>เมนู PVP</bold></gradient>"),
                cfg().getStringList("items.menu.lore"));
    }

    private void put(Player p, int slot, ItemStack it) {
        if (p.getInventory().getItem(slot) == null) p.getInventory().setItem(slot, it);
        else p.getInventory().addItem(it);
    }

    public void giveItems(Player p) {
        if (active.containsKey(p.getUniqueId())) return;
        List<String> ws = cfg().getStringList("give-items-worlds");
        if (!ws.isEmpty() && !ws.contains(p.getWorld().getName())) return;
        boolean hasSword = false, hasMenu = false;
        for (ItemStack it : p.getInventory().getContents()) {
            String t = specialType(it);
            if ("sword".equals(t)) hasSword = true;
            if ("menu".equals(t)) hasMenu = true;
        }
        if (!hasSword) put(p, cfg().getInt("items.sword.slot", 0), swordItem());
        if (!hasMenu) put(p, cfg().getInt("items.menu.slot", 8), menuItem());
    }

    /* ---------------- GUI ---------------- */

    public void openMenu(Player p) {
        ConfigurationSection ws = cfg().getConfigurationSection("menu.worlds");
        if (ws == null) { p.sendMessage(MM.deserialize("<red>ยังไม่ได้ตั้ง menu.worlds ใน config")); return; }
        Gui g = new Gui("menu", null);
        g.inv = Bukkit.createInventory(g, cfg().getInt("menu.rows", 3) * 9,
                MM.deserialize(cfg().getString("menu.title", "<bold>PVP")));
        int auto = 10;
        for (String k : ws.getKeys(false)) {
            Material m = Material.matchMaterial(ws.getString(k + ".icon", "IRON_SWORD"));
            if (m == null) m = Material.IRON_SWORD;
            World w = Bukkit.getWorld(ws.getString(k + ".world", ""));
            List<String> lore = new ArrayList<>(ws.getStringList(k + ".lore"));
            lore.add(w == null ? "<red>โลกยังไม่พร้อม" : "<gray>ผู้เล่น: <white>" + w.getPlayers().size());
            lore.add("<yellow>คลิกเพื่อเข้า");
            int slot = ws.getInt(k + ".slot", auto++);
            g.inv.setItem(slot, special("icon", m, ws.getString(k + ".name", k), lore));
            g.slots.put(slot, k);
        }
        p.openInventory(g.inv);
    }

    private void openKitGui(Player p, Player target) {
        ConfigurationSection ks = cfg().getConfigurationSection("kits");
        if (ks == null) { p.sendMessage(MM.deserialize("<red>ยังไม่มี kit ใน config")); return; }
        Gui g = new Gui("kit", target.getUniqueId());
        g.inv = Bukkit.createInventory(g, 27,
                MM.deserialize("<dark_gray>เลือก Kit ท้า <white>" + target.getName()));
        int slot = 10;
        for (String k : ks.getKeys(false)) {
            Material m = Material.matchMaterial(ks.getString(k + ".icon", "IRON_SWORD"));
            if (m == null) m = Material.IRON_SWORD;
            List<String> lore = new ArrayList<>(ks.getStringList(k + ".lore"));
            lore.add("<yellow>คลิกเพื่อท้า " + target.getName());
            g.inv.setItem(slot, special("icon", m, ks.getString(k + ".name", k), lore));
            g.slots.put(slot, k);
            slot++;
            if ((slot + 1) % 9 == 0) slot += 2;
            if (slot >= 27) break;
        }
        p.openInventory(g.inv);
    }

    @EventHandler
    public void click(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getView().getTopInventory().getHolder() instanceof Gui g) {
            e.setCancelled(true);
            if (e.getClickedInventory() != e.getView().getTopInventory()) return;
            String k = g.slots.get(e.getSlot());
            if (k == null) return;
            p.closeInventory();
            if (g.type.equals("kit")) sendRequest(p, g.target, k);
            else if (g.type.equals("menu")) enterWorld(p, k);
            return;
        }
        if (!active.containsKey(p.getUniqueId()) && specialType(e.getCurrentItem()) != null)
            e.setCancelled(true);
    }

    @EventHandler
    public void drop(PlayerDropItemEvent e) {
        if (specialType(e.getItemDrop().getItemStack()) != null) e.setCancelled(true);
    }

    private void enterWorld(Player p, String k) {
        if (active.containsKey(p.getUniqueId())) return;
        ConfigurationSection s = cfg().getConfigurationSection("menu.worlds." + k);
        if (s == null) return;
        World w = Bukkit.getWorld(s.getString("world", ""));
        if (w == null) { p.sendMessage(MM.deserialize("<red>ไม่พบโลก " + s.getString("world"))); return; }
        p.teleport(loc(w, s.getString("spawn")));
        giveItems(p);
        p.sendMessage(MM.deserialize("<green>เข้าโลก PVP แล้ว! <gray>คลิกขวาผู้เล่นด้วยดาบเพื่อท้า duel"));
    }

    /* ---------------- ท้า / รับ ---------------- */

    @EventHandler
    public void interact(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if ("menu".equals(specialType(e.getItem()))) {
            e.setCancelled(true);
            openMenu(e.getPlayer());
        }
    }

    @EventHandler
    public void interactEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (!(e.getRightClicked() instanceof Player target)) return;
        Player p = e.getPlayer();
        if (!"sword".equals(specialType(p.getInventory().getItemInMainHand()))) return;
        e.setCancelled(true);
        if (active.containsKey(p.getUniqueId()) || active.containsKey(target.getUniqueId())) {
            p.sendMessage(MM.deserialize("<red>ตอนนี้อยู่ใน duel ไม่สามารถท้าได้"));
            return;
        }
        if (!tryAccept(p, target)) openKitGui(p, target);
    }

    /** p คลิกขวาที่ from: ถ้า from เคยท้า p ไว้ -> รับ */
    private boolean tryAccept(Player p, Player from) {
        Request r = requests.get(from.getUniqueId());
        if (r == null || !r.to().equals(p.getUniqueId()) || r.expires() < System.currentTimeMillis()) return false;
        requests.remove(from.getUniqueId());
        start(from, p, r.kit());
        return true;
    }

    private void sendRequest(Player from, UUID toId, String kit) {
        Player t = Bukkit.getPlayer(toId);
        if (t == null) { from.sendMessage(MM.deserialize("<red>ผู้เล่นออกไปแล้ว")); return; }
        if (active.containsKey(t.getUniqueId()) || active.containsKey(from.getUniqueId())) {
            from.sendMessage(MM.deserialize("<red>อีกฝ่ายกำลังอยู่ใน duel")); return;
        }
        int sec = cfg().getInt("duel.request-seconds", 30);
        requests.put(from.getUniqueId(), new Request(from.getUniqueId(), toId, kit, System.currentTimeMillis() + sec * 1000L));
        String kn = cfg().getString("kits." + kit + ".name", kit);
        from.sendMessage(MM.deserialize("<green>ส่งคำท้าให้ <white>" + t.getName() + " <green>ชุด " + kn + " <gray>(หมดอายุใน " + sec + " วิ)"));
        t.sendMessage(MM.deserialize("<gold>⚔ <white>" + from.getName() + " <gold>ท้าคุณ duel ชุด " + kn
                + "\n<green><click:run_command:'/duel accept " + from.getName() + "'><bold>[คลิกเพื่อรับ]</bold></click>"
                + " <gray>หรือคลิกขวาที่เขาด้วยดาบ"));
        t.playSound(t.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player p)) return true;
        if (c.getName().equals("pvp")) { openMenu(p); return true; }
        if (a.length == 0) { p.sendMessage(MM.deserialize("<gray>/duel <ผู้เล่น> | /duel accept <ผู้เล่น>")); return true; }
        boolean accept = a[0].equalsIgnoreCase("accept") && a.length > 1;
        Player t = Bukkit.getPlayerExact(accept ? a[1] : a[0]);
        if (t == null || t.equals(p)) { p.sendMessage(MM.deserialize("<red>ไม่พบผู้เล่น")); return true; }
        if (active.containsKey(p.getUniqueId()) || active.containsKey(t.getUniqueId())) {
            p.sendMessage(MM.deserialize("<red>อยู่ใน duel อยู่")); return true;
        }
        if (accept) {
            if (!tryAccept(p, t)) p.sendMessage(MM.deserialize("<red>ไม่มีคำท้าจากคนนี้ (หรือหมดอายุแล้ว)"));
        } else openKitGui(p, t);
        return true;
    }

    /* ---------------- เริ่ม / จบ duel ---------------- */

    private void start(Player a, Player b, String kit) {
        ConfigurationSection arenas = cfg().getConfigurationSection("duel.arenas");
        if (arenas == null || arenas.getKeys(false).isEmpty()) {
            a.sendMessage(MM.deserialize("<red>ยังไม่ได้ตั้ง duel.arenas")); return;
        }
        List<String> keys = new ArrayList<>(arenas.getKeys(false));
        Collections.shuffle(keys);
        String arena = keys.stream().filter(k -> !arenasInUse.contains(k)).findFirst().orElse(keys.get(0));
        World w = Bukkit.getWorld(arenas.getString(arena + ".world", ""));
        if (w == null) {
            String m = "<red>ไม่พบโลก " + arenas.getString(arena + ".world");
            a.sendMessage(MM.deserialize(m)); b.sendMessage(MM.deserialize(m)); return;
        }
        Location la = loc(w, arenas.getString(arena + ".a")), lb = loc(w, arenas.getString(arena + ".b"));

        Duel d = new Duel(a.getUniqueId(), b.getUniqueId(), arena, kit);
        for (Player p : List.of(a, b)) {
            State s = save(p);
            d.states.put(p.getUniqueId(), s);
            backupSave(p.getUniqueId(), s);
            active.put(p.getUniqueId(), d);
            pl.pvp.clearCombat(p.getUniqueId());
        }
        arenasInUse.add(arena);

        for (Player p : List.of(a, b)) {
            p.closeInventory();
            for (var ef : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(ef.getType());
            p.getInventory().clear();
            p.setGameMode(GameMode.SURVIVAL);
            p.setFireTicks(0);
            p.setFoodLevel(20);
            p.setHealth(p.getAttribute(Attribute.MAX_HEALTH).getValue());
            applyKit(p, kit);
            p.teleport(p.equals(a) ? la : lb);
        }

        int[] n = {3};
        Bukkit.getScheduler().runTaskTimer(pl, task -> {
            if (d.ended) { task.cancel(); return; }
            Player pa = Bukkit.getPlayer(d.a), pb = Bukkit.getPlayer(d.b);
            if (n[0] == 0) {
                d.frozen = false;
                for (Player p : new Player[]{pa, pb}) if (p != null)
                    p.showTitle(Title.title(MM.deserialize("<red><bold>FIGHT!"), Component.empty()));
                task.cancel();
                return;
            }
            for (Player p : new Player[]{pa, pb}) if (p != null) {
                p.showTitle(Title.title(MM.deserialize("<yellow><bold>" + n[0]), Component.empty()));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
            }
            n[0]--;
        }, 10L, 20L);
    }

    private void end(Duel d, Player loser, boolean loserLeft) {
        if (d.ended) return;
        d.ended = true;
        UUID loserId = loser.getUniqueId();
        UUID winId = d.other(loserId);
        Player winner = Bukkit.getPlayer(winId);
        String kn = cfg().getString("kits." + d.kit + ".name", d.kit);
        String wn = winner != null ? winner.getName() : "?";
        pl.pvp.clearCombat(loserId);
        pl.pvp.clearCombat(winId);

        if (winner != null) {
            winner.setInvulnerable(true);
            winner.setHealth(winner.getAttribute(Attribute.MAX_HEALTH).getValue());
            winner.showTitle(Title.title(MM.deserialize("<green><bold>ชนะ!"), Component.empty()));
            winner.sendMessage(MM.deserialize("<green>คุณชนะ <white>" + loser.getName() + " <gray>(" + kn + "<gray>)"));
        }
        if (loserLeft) {
            restore(loser, d.states.get(loserId));
            active.remove(loserId);
        } else {
            loser.setInvulnerable(true);
            loser.setHealth(loser.getAttribute(Attribute.MAX_HEALTH).getValue());
            loser.showTitle(Title.title(MM.deserialize("<red><bold>แพ้"), Component.empty()));
            loser.sendMessage(MM.deserialize("<red>คุณแพ้ให้ <white>" + wn));
        }
        Bukkit.broadcast(MM.deserialize("<gold>⚔ <white>" + wn + " <gray>ชนะ <white>" + loser.getName() + " <gray>ใน duel (" + kn + "<gray>)"));

        long delay = cfg().getInt("duel.end-delay-seconds", 3) * 20L;
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            for (UUID id : new UUID[]{d.a, d.b}) {
                if (!active.containsKey(id)) continue;
                Player p = Bukkit.getPlayer(id);
                if (p != null) restore(p, d.states.get(id));
                active.remove(id);
            }
            arenasInUse.remove(d.arena);
        }, delay);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void damage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Duel d = active.get(p.getUniqueId());
        if (d == null) return;
        if (d.ended || d.frozen) { e.setCancelled(true); return; }
        if (p.getHealth() - e.getFinalDamage() <= 0) {
            e.setCancelled(true);
            end(d, p, false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void guard(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player v)) return;
        Player dmg = null;
        if (e.getDamager() instanceof Player p) dmg = p;
        else if (e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player p) dmg = p;
        if (dmg == null) return;
        Duel dv = active.get(v.getUniqueId()), dd = active.get(dmg.getUniqueId());
        if (dv == null && dd == null) return;
        if (dv != dd) e.setCancelled(true);   // คนนอกตีคนใน duel ไม่ได้
    }

    @EventHandler
    public void move(PlayerMoveEvent e) {
        Duel d = active.get(e.getPlayer().getUniqueId());
        if (d == null || !d.frozen || e.getTo() == null) return;
        Location f = e.getFrom(), t = e.getTo();
        if (f.getX() != t.getX() || f.getZ() != t.getZ()) {
            Location n = f.clone();
            n.setYaw(t.getYaw());
            n.setPitch(t.getPitch());
            e.setTo(n);
        }
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        Duel d = active.get(p.getUniqueId());
        if (d == null) return;
        pl.pvp.clearCombat(p.getUniqueId());
        if (!d.ended) end(d, p, true);
        else { restore(p, d.states.get(p.getUniqueId())); active.remove(p.getUniqueId()); }
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(pl, () -> {
            if (!p.isOnline()) return;
            // กู้ของคืนถ้าเซิร์ฟล่มกลาง duel
            if (backup.contains("b." + p.getUniqueId()) && !active.containsKey(p.getUniqueId())) {
                State s = backupLoad(p.getUniqueId());
                if (s != null) { restore(p, s); p.sendMessage(MM.deserialize("<yellow>กู้ของและตำแหน่งก่อน duel คืนแล้ว")); }
            }
            giveItems(p);
        }, 10L);
    }

    @EventHandler
    public void respawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(pl, () -> giveItems(p), 2L);
    }

    @EventHandler
    public void worldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(pl, () -> giveItems(p), 2L);
    }

    @EventHandler(ignoreCancelled = true)
    public void cmd(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!active.containsKey(p.getUniqueId()) || p.hasPermission("kapom.admin")) return;
        String base = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        if (!cfg().getStringList("duel.allowed-commands").contains(base)) {
            e.setCancelled(true);
            p.sendMessage(MM.deserialize("<red>ใช้คำสั่งไม่ได้ขณะ duel"));
        }
    }

    public void shutdown() {
        for (var en : new ArrayList<>(active.entrySet())) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null) restore(p, en.getValue().states.get(en.getKey()));
        }
        active.clear();
    }

    /* ---------------- helpers ---------------- */

    private State save(Player p) {
        State s = new State();
        s.loc = p.getLocation();
        ItemStack[] src = p.getInventory().getContents();
        s.inv = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) s.inv[i] = src[i] == null ? null : src[i].clone();
        s.mode = p.getGameMode();
        s.level = p.getLevel();
        s.exp = p.getExp();
        return s;
    }

    private void restore(Player p, State s) {
        if (s == null) return;
        for (var ef : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(ef.getType());
        p.setFireTicks(0);
        p.setInvulnerable(false);
        p.getInventory().clear();
        p.getInventory().setContents(s.inv);
        p.setGameMode(s.mode);
        p.setLevel(s.level);
        p.setExp(s.exp);
        p.setFoodLevel(20);
        p.setHealth(p.getAttribute(Attribute.MAX_HEALTH).getValue());
        if (s.loc != null && s.loc.getWorld() != null) p.teleport(s.loc);
        backupDelete(p.getUniqueId());
    }

    private void backupSave(UUID id, State s) {
        String b = "b." + id;
        backup.set(b + ".loc", s.loc);
        backup.set(b + ".inv", Arrays.asList(s.inv));
        backup.set(b + ".mode", s.mode.name());
        backup.set(b + ".level", s.level);
        backup.set(b + ".exp", (double) s.exp);
        flush();
    }

    private State backupLoad(UUID id) {
        String b = "b." + id;
        List<?> l = backup.getList(b + ".inv");
        if (l == null) return null;
        State s = new State();
        s.loc = backup.getLocation(b + ".loc");
        s.inv = l.stream().map(o -> (ItemStack) o).toArray(ItemStack[]::new);
        try { s.mode = GameMode.valueOf(backup.getString(b + ".mode", "SURVIVAL")); }
        catch (IllegalArgumentException ex) { s.mode = GameMode.SURVIVAL; }
        s.level = backup.getInt(b + ".level");
        s.exp = (float) backup.getDouble(b + ".exp");
        return s;
    }

    private void backupDelete(UUID id) {
        if (!backup.contains("b." + id)) return;
        backup.set("b." + id, null);
        flush();
    }

    private void flush() {
        try { backup.save(backupFile); }
        catch (IOException ex) { pl.getLogger().warning("backup save failed: " + ex.getMessage()); }
    }

    static Location loc(World w, String s) {
        if (s == null || s.isBlank()) return w.getSpawnLocation();
        String[] a = s.split(",");
        try {
            return new Location(w, Double.parseDouble(a[0].trim()), Double.parseDouble(a[1].trim()),
                    Double.parseDouble(a[2].trim()),
                    a.length > 3 ? Float.parseFloat(a[3].trim()) : 0f,
                    a.length > 4 ? Float.parseFloat(a[4].trim()) : 0f);
        } catch (RuntimeException ex) { return w.getSpawnLocation(); }
    }

    private void applyKit(Player p, String kit) {
        ConfigurationSection k = cfg().getConfigurationSection("kits." + kit);
        if (k == null) return;
        PlayerInventory inv = p.getInventory();
        inv.setHelmet(parse(k.getString("helmet")));
        inv.setChestplate(parse(k.getString("chestplate")));
        inv.setLeggings(parse(k.getString("leggings")));
        inv.setBoots(parse(k.getString("boots")));
        for (String s : k.getStringList("items")) {
            ItemStack it = parse(s);
            if (it != null) inv.addItem(it);
        }
    }

    /** รูปแบบ  MATERIAL:จำนวน:ENCHANT=เลเวล,ENCHANT=เลเวล */
    private ItemStack parse(String s) {
        if (s == null || s.isBlank()) return null;
        String[] a = s.split(":");
        Material m = Material.matchMaterial(a[0].trim());
        if (m == null) { pl.getLogger().warning("kit: ไม่รู้จัก material " + a[0]); return null; }
        int amt = 1;
        try { if (a.length > 1) amt = Integer.parseInt(a[1].trim()); } catch (NumberFormatException ignored) {}
        ItemStack it = new ItemStack(m, amt);
        if (a.length > 2) {
            ItemMeta meta = it.getItemMeta();
            for (String e : a[2].split(",")) {
                String[] kv = e.split("=");
                Enchantment en = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(kv[0].trim().toLowerCase()));
                if (en == null) { pl.getLogger().warning("kit: ไม่รู้จัก enchant " + kv[0]); continue; }
                int lv = 1;
                try { if (kv.length > 1) lv = Integer.parseInt(kv[1].trim()); } catch (NumberFormatException ignored) {}
                meta.addEnchant(en, lv, true);
            }
            it.setItemMeta(meta);
        }
        return it;
    }
}
