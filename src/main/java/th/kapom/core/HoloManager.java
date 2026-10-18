package th.kapom.core;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

import java.util.*;
import java.util.function.Supplier;

/** ข้อความลอย (TextDisplay) ที่เกิดใหม่เองเมื่อ chunk โหลด และอัปเดตข้อความทุก 5 วิ */
public class HoloManager {
    private static class E {
        Location loc; Supplier<String> text; TextDisplay ent;
    }

    private final KapomPlugin plugin;
    private final Map<String, E> map = new LinkedHashMap<>();

    public HoloManager(KapomPlugin plugin) { this.plugin = plugin; }

    public void register(String key, Location loc, Supplier<String> text) {
        unregister(key);
        E e = new E();
        e.loc = loc; e.text = text;
        map.put(key, e);
    }

    public void unregister(String key) {
        E e = map.remove(key);
        if (e != null && e.ent != null) e.ent.remove();
    }

    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 100L);
    }

    private void tick() {
        for (E e : map.values()) {
            World w = e.loc.getWorld();
            if (w == null) continue;
            if (!w.isChunkLoaded(e.loc.getBlockX() >> 4, e.loc.getBlockZ() >> 4)) continue;
            if (e.ent == null || !e.ent.isValid()) {
                e.ent = w.spawn(e.loc, TextDisplay.class, td -> {
                    td.setBillboard(Display.Billboard.CENTER);
                    td.setPersistent(false);
                    td.setShadowed(true);
                    td.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
                });
            }
            e.ent.text(LegacyComponentSerializer.legacySection().deserialize(e.text.get()));
        }
    }

    /** คีย์ที่ใกล้ที่สุดภายในรัศมี r และขึ้นต้นด้วย prefix */
    public String nearest(Location l, double r, String prefix) {
        String best = null; double bd = r * r;
        for (Map.Entry<String, E> en : map.entrySet()) {
            if (!en.getKey().startsWith(prefix)) continue;
            Location o = en.getValue().loc;
            if (o.getWorld() == null || !o.getWorld().equals(l.getWorld())) continue;
            double d = o.distanceSquared(l);
            if (d <= bd) { bd = d; best = en.getKey(); }
        }
        return best;
    }

    public void removeAll() {
        for (E e : map.values()) if (e.ent != null) e.ent.remove();
    }
}
