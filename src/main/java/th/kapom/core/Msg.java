package th.kapom.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;

/** Message helpers (MiniMessage, hex colors / gradients) */
public final class Msg {
    public static final MiniMessage MM = MiniMessage.miniMessage();
    public static final String PREFIX = "<gradient:#00F5A0:#00D9F5><bold>KAPOM</bold></gradient> <dark_gray>» ";
    public static final String VALUE = "<#4CC9F0>";

    private Msg() {}

    public static Component mm(String s) { return MM.deserialize(s); }
    public static String esc(String s) { return MM.escapeTags(s); }

    public static void ok(CommandSender s, String m) { s.sendMessage(mm(PREFIX + "<#7CFC9A>" + m)); }
    public static void err(CommandSender s, String m) { s.sendMessage(mm(PREFIX + "<#FF6B6B>" + m)); }
    public static void info(CommandSender s, String m) { s.sendMessage(mm(PREFIX + "<#FFD166>" + m)); }
}
