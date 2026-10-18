package th.kapom.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Tier colors + the tier tag floating above the player's head (above the vanilla nametag) */
public class TierManager {
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");
    private final KapomPlugin plugin;
    private final Map<UUID, TextDisplay> tags = new HashMap<>();

    public TierManager(KapomPlugin plugin) { this.plugin = plugin; }

    // ---------- colors ----------
    /** "red", "#FF0000" or a gradient "#FF0000:#FFD700" */
    public static boolean validColor(String s) {
        if (s == null || s.isEmpty()) return false;
        String[] parts = s.split(":");
        if (parts.length > 2) return false;
        for (String p : parts) {
            if (!(HEX.matcher(p).matches() || NamedTextColor.NAMES.value(p.toLowerCase()) != null)) return false;
        }
        return true;
    }

    public String colorSpec(DataStore.PD d) {
        if (!d.tierColor.isEmpty() && validColor(d.tierColor)) return d.tierColor;
        String key = d.tier.toUpperCase();
        String c = plugin.getConfig().getString("tier-colors." + key);
        if (c == null) c = plugin.getConfig().getString("tier-colors." + key.replaceAll("[^A-Z]", ""));
        return (c != null && validColor(c)) ? c : "#4CC9F0";
    }

    public Component component(DataStore.PD d) {
        if (d.tier.equals("-")) return Component.text("-", NamedTextColor.DARK_GRAY);
        String c = colorSpec(d);
        String t = Msg.esc(d.tier);
        return c.contains(":")
                ? Msg.mm("<bold><gradient:" + c + ">" + t + "</gradient>")
                : Msg.mm("<bold><" + c + ">" + t);
    }

    // ---------- head tag ----------
    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) refresh(p);
        }, 40L, 20L);
    }

    public void refresh(Player p) {
        DataStore.PD d = plugin.data.get(p);
        boolean want = !d.tier.equals("-") && p.isOnline() && !p.isDead()
                && p.getGameMode() != GameMode.SPECTATOR && !p.isInvisible();
        TextDisplay td = tags.get(p.getUniqueId());
        if (!want) { remove(p); return; }

        if (td == null || !td.isValid()) {
            td = spawn(p);
            tags.put(p.getUniqueId(), td);
        } else if (!p.getPassengers().contains(td)) {
            // teleporting ejects passengers -> mount it again
            td.teleport(p.getLocation());
            p.addPassenger(td);
        }
        Component c = component(d);
        if (!c.equals(td.text())) td.text(c);
    }

    private TextDisplay spawn(Player p) {
        float h = (float) plugin.getConfig().getDouble("tier-tag-height", 0.75);
        TextDisplay td = p.getWorld().spawn(p.getLocation(), TextDisplay.class, t -> {
            t.setBillboard(Display.Billboard.CENTER);
            t.setPersistent(false);
            t.setShadowed(true);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setTransformation(new Transformation(new Vector3f(0f, h, 0f), new AxisAngle4f(),
                    new Vector3f(1f, 1f, 1f), new AxisAngle4f()));
        });
        p.hideEntity(plugin, td);   // the owner doesn't need to see their own tag
        p.addPassenger(td);
        return td;
    }

    public void remove(Player p) {
        TextDisplay td = tags.remove(p.getUniqueId());
        if (td != null) td.remove();
    }

    public void removeAll() {
        for (TextDisplay td : tags.values()) td.remove();
        tags.clear();
    }
}
