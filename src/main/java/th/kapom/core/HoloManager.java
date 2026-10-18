package th.kapom.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Floating text (TextDisplay): respawns when the chunk loads, refreshes automatically */
public class HoloManager {
    private static class E {
        Pos pos; double dy; Supplier<Component> text; TextDisplay ent; Component shown;
    }

    private final KapomPlugin plugin;
    private final Map<String, E> map = new ConcurrentHashMap<>();

    public HoloManager(KapomPlugin plugin) { this.plugin = plugin; }

    public void register(String key, Pos pos, double dy, Supplier<Component> text) {
        unregister(key);
        E e = new E();
        e.pos = pos; e.dy = dy; e.text = text;
        map.put(key, e);
    }

    public void unregister(String key) {
        E e = map.remove(key);
        if (e != null && e.ent != null) e.ent.remove();
    }

    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 40L);
    }

    private void tick() {
        for (E e : map.values()) {
            Location base = e.pos.toLocation();
            if (base == null) continue;               // world not loaded (yet)
            World w = base.getWorld();
            Location l = base.add(0, e.dy, 0);
            if (!w.isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
            if (e.ent == null || !e.ent.isValid()) {
                e.ent = w.spawn(l, TextDisplay.class, td -> {
                    td.setBillboard(Display.Billboard.CENTER);
                    td.setPersistent(false);
                    td.setShadowed(true);
                    td.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
                });
                e.shown = null;
            }
            Component c = e.text.get();
            if (!c.equals(e.shown)) {
                e.ent.text(c);
                e.shown = c;
            }
        }
    }

    /** Nearest key within radius r starting with prefix */
    public String nearest(Location l, double r, String prefix) {
        String best = null;
        double bd = r * r;
        for (Map.Entry<String, E> en : map.entrySet()) {
            if (!en.getKey().startsWith(prefix)) continue;
            Pos p = en.getValue().pos;
            if (!p.worldName.equals(l.getWorld().getName())) continue;
            double dx = p.x - l.getX(), dy = p.y + en.getValue().dy - l.getY(), dz = p.z - l.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bd) { bd = d; best = en.getKey(); }
        }
        return best;
    }

    public void removeAll() {
        for (E e : map.values()) if (e.ent != null) e.ent.remove();
    }
}
