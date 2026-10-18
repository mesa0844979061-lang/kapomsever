package th.kapom.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

/**
 * A saved position that stores the world NAME (not the World object),
 * so NPCs/leaderboards survive even if a Multiverse world is not loaded at startup.
 */
public final class Pos {
    public final String worldName;
    public final double x, y, z;
    public final float yaw, pitch;

    public Pos(String worldName, double x, double y, double z, float yaw, float pitch) {
        this.worldName = worldName;
        this.x = x; this.y = y; this.z = z;
        this.yaw = yaw; this.pitch = pitch;
    }

    public static Pos of(Location l) {
        return new Pos(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    /** null if the world is not loaded */
    public Location toLocation() {
        org.bukkit.World w = Bukkit.getWorld(worldName);
        return w == null ? null : new Location(w, x, y, z, yaw, pitch);
    }

    public void write(ConfigurationSection root, String path) {
        root.set(path + ".world", worldName);
        root.set(path + ".x", x);
        root.set(path + ".y", y);
        root.set(path + ".z", z);
        root.set(path + ".yaw", (double) yaw);
        root.set(path + ".pitch", (double) pitch);
    }

    /** Reads the new format, or the old "loc:" format (auto-migration). */
    public static Pos read(ConfigurationSection c) {
        if (c == null) return null;
        if (c.isString("world")) {
            return new Pos(c.getString("world"), c.getDouble("x"), c.getDouble("y"), c.getDouble("z"),
                    (float) c.getDouble("yaw"), (float) c.getDouble("pitch"));
        }
        Location l = c.getLocation("loc");
        if (l != null && l.getWorld() != null) return of(l);
        ConfigurationSection ls = c.getConfigurationSection("loc");
        if (ls != null && ls.isString("world")) {
            return new Pos(ls.getString("world"), ls.getDouble("x"), ls.getDouble("y"), ls.getDouble("z"),
                    (float) ls.getDouble("yaw"), (float) ls.getDouble("pitch"));
        }
        return null;
    }
}
