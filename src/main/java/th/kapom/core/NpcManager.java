package th.kapom.core;

import com.destroystokyo.paper.profile.ProfileProperty;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Packet NPC: every viewer sees their own skin */
public class NpcManager {
    public static class Npc {
        public final String name, key;
        public final Pos pos;
        public volatile String target;
        final int id;
        final UUID uuid;

        Npc(String name, Pos pos, String target, int id) {
            this.name = name;
            this.key = name.toLowerCase();
            this.pos = pos;
            this.target = target;
            this.id = id;
            this.uuid = UUID.nameUUIDFromBytes(("kapom:" + key).getBytes());
        }
    }

    private final KapomPlugin plugin;
    private final Map<String, Npc> npcs = new ConcurrentHashMap<>();   // read from the netty thread too
    private final Map<UUID, Set<Integer>> shown = new HashMap<>();
    private final Map<UUID, Long> ready = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final AtomicInteger ids = new AtomicInteger(1_700_000_000);

    public NpcManager(KapomPlugin plugin) { this.plugin = plugin; }

    // ---------- Data ----------
    public void load() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("npcs");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            ConfigurationSection c = s.getConfigurationSection(k);
            Pos pos = Pos.read(c);
            if (pos == null) {
                plugin.getLogger().warning("NPC '" + k + "' has no valid location - skipped.");
                continue;
            }
            Npc n = new Npc(c.getString("name", k), pos, c.getString("target"), ids.getAndIncrement());
            add(n);
            write(n);   // rewrite in the new format (migrates old saves)
        }
        plugin.saveConfig();
    }

    private void add(Npc n) {
        npcs.put(n.key, n);
        plugin.holos.register("npc:" + n.key, n.pos, 2.05, () -> {
            Component line2 = n.target == null
                    ? Msg.mm("<#FF6B6B>Not configured")
                    : Msg.mm("<gray>Click to travel to ").append(Component.text(n.target, TextColor.fromHexString("#4CC9F0")));
            return Msg.mm("<gradient:#FFD166:#FF9F1C><bold>" + Msg.esc(n.name.replace('_', ' ')) + "</bold></gradient>")
                    .append(Component.newline()).append(line2);
        });
    }

    public boolean exists(String name) { return npcs.containsKey(name.toLowerCase()); }
    public Npc get(String name) { return npcs.get(name.toLowerCase()); }
    public Collection<Npc> all() { return npcs.values(); }

    public void create(String name, Location loc) {
        Npc n = new Npc(name, Pos.of(loc), null, ids.getAndIncrement());
        add(n);
        save(n);
    }

    public void setTarget(Npc n, String world) {
        n.target = world;
        save(n);
    }

    private void write(Npc n) {
        String p = "npcs." + n.key;
        plugin.getConfig().set(p + ".name", n.name);
        n.pos.write(plugin.getConfig(), p);
        plugin.getConfig().set(p + ".target", n.target);
        plugin.getConfig().set(p + ".loc", null);
    }

    private void save(Npc n) {
        write(n);
        plugin.saveConfig();
    }

    public void remove(Npc n) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Set<Integer> s = shown.get(p.getUniqueId());
            if (s != null && s.remove(n.id)) hide(p, n);
        }
        npcs.remove(n.key);
        plugin.holos.unregister("npc:" + n.key);
        plugin.getConfig().set("npcs." + n.key, null);
        plugin.saveConfig();
    }

    // ---------- Show / hide by distance ----------
    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
        PacketEvents.getAPI().getEventManager().registerListener(new PacketListenerAbstract() {
            @Override
            public void onPacketReceive(PacketReceiveEvent e) {
                if (e.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) return;
                WrapperPlayClientInteractEntity w = new WrapperPlayClientInteractEntity(e);
                Npc npc = null;
                for (Npc n : npcs.values()) if (n.id == w.getEntityId()) { npc = n; break; }
                if (npc == null) return;
                if (w.getAction() == WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT) return;
                if (w.getAction() == WrapperPlayClientInteractEntity.InteractAction.INTERACT
                        && w.getHand() != InteractionHand.MAIN_HAND) return;
                UUID uuid = e.getUser().getUUID();
                if (uuid == null) return;
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) return;
                final Npc fn = npc;
                Bukkit.getScheduler().runTask(plugin, () -> click(p, fn));
            }
        });
    }

    /** Called on join / world change / respawn (client already dropped entities) */
    public void clear(Player p) {
        shown.remove(p.getUniqueId());
        ready.put(p.getUniqueId(), System.currentTimeMillis() + 1500);
    }

    public void forget(Player p) {
        shown.remove(p.getUniqueId());
        ready.remove(p.getUniqueId());
        cooldown.remove(p.getUniqueId());
    }

    public void hideAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Set<Integer> s = shown.remove(p.getUniqueId());
            if (s == null) continue;
            for (Npc n : npcs.values()) if (s.contains(n.id)) hide(p, n);
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (ready.getOrDefault(p.getUniqueId(), 0L) > now) continue;
            Set<Integer> set = shown.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
            Location pl = p.getLocation();
            String wn = p.getWorld().getName();
            for (Npc n : npcs.values()) {
                boolean near = false;
                if (n.pos.worldName.equals(wn)) {
                    double dx = n.pos.x - pl.getX(), dy = n.pos.y - pl.getY(), dz = n.pos.z - pl.getZ();
                    near = dx * dx + dy * dy + dz * dz < 48 * 48;
                }
                boolean has = set.contains(n.id);
                if (near && !has) { show(p, n); set.add(n.id); }
                else if (!near && has) { hide(p, n); set.remove(n.id); }
            }
        }
    }

    private void send(Player p, PacketWrapper<?> w) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(p, w);
    }

    private void show(Player p, Npc n) {
        // Use the skin of the viewer
        List<TextureProperty> props = new ArrayList<>();
        for (ProfileProperty pp : p.getPlayerProfile().getProperties()) {
            if (pp.getName().equals("textures"))
                props.add(new TextureProperty("textures", pp.getValue(), pp.getSignature()));
        }
        // Blank profile name = no duplicate nametag (real name is a TextDisplay)
        UserProfile prof = new UserProfile(n.uuid, " ", props);
        var info = new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(prof, false, 0, GameMode.CREATIVE, null, null);
        send(p, new WrapperPlayServerPlayerInfoUpdate(
                EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                        WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED), info));
        send(p, new WrapperPlayServerSpawnEntity(n.id, Optional.of(n.uuid), EntityTypes.PLAYER,
                new Vector3d(n.pos.x, n.pos.y, n.pos.z),
                n.pos.pitch, n.pos.yaw, n.pos.yaw, 0, Optional.empty()));
        int idx = plugin.getConfig().getInt("skin-layer-index", -1);
        if (idx >= 0) { // wrong index for your version causes Network Protocol Error, so it is off by default
            List<EntityData<?>> md = new ArrayList<>();
            md.add(new EntityData<>(idx, EntityDataTypes.BYTE, (byte) 0x7F));
            send(p, new WrapperPlayServerEntityMetadata(n.id, md));
        }
        send(p, new WrapperPlayServerEntityHeadLook(n.id, n.pos.yaw));
    }

    private void hide(Player p, Npc n) {
        send(p, new WrapperPlayServerDestroyEntities(n.id));
        send(p, new WrapperPlayServerPlayerInfoRemove(n.uuid));
    }

    // ---------- Click handling ----------
    private void click(Player p, Npc n) {
        long now = System.currentTimeMillis();
        if (cooldown.getOrDefault(p.getUniqueId(), 0L) > now) return;
        cooldown.put(p.getUniqueId(), now + 1000);
        if (plugin.pvp.inCombat(p)) { Msg.err(p, "You can't travel while in combat!"); return; }
        String target = n.target;
        if (target == null) { Msg.err(p, "This NPC has no destination world yet."); return; }
        org.bukkit.World w = Bukkit.getWorld(target);
        if (w == null) { Msg.err(p, "World " + Msg.VALUE + Msg.esc(target) + "<#FF6B6B> is not loaded."); return; }
        Msg.ok(p, "Teleporting to " + Msg.VALUE + Msg.esc(target) + "<#7CFC9A>...");
        // Multiverse worlds are normal Bukkit worlds, so a direct teleport is the most reliable.
        // Multiverse (and Multiverse-Inventories) still handle the world change themselves.
        p.teleportAsync(w.getSpawnLocation()).thenAccept(ok -> {
            if (!ok) Bukkit.getScheduler().runTask(plugin, () -> Msg.err(p, "Teleport failed."));
        });
    }
}
