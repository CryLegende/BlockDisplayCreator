package me.general_breddok.blockdisplaycreator.render;

import me.general_breddok.blockdisplaycreator.BlockDisplayCreator;
import me.general_breddok.blockdisplaycreator.util.ChatUtil;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The texts of the gizmo and of its admin panel, read from messages.yml: a key the server's file lacks is taken from
 * the messages.yml of the plugin itself, so a new text is never missing.
 */
final class Texts {

    private static YamlConfiguration defaults;

    private Texts() {
    }

    private static Object raw(String path) {
        Object value = BlockDisplayCreator.getInstance().getMessagesFile().get(path);
        if (value == null) {
            value = defaults().get(path);
        }
        return value;
    }

    private static synchronized YamlConfiguration defaults() {
        if (defaults == null) {
            defaults = new YamlConfiguration();
            try (InputStream in = BlockDisplayCreator.getInstance().getResource("messages.yml")) {
                if (in != null) {
                    defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                }
            } catch (java.io.IOException ignored) {
                // no defaults: the key is shown instead of a missing text
            }
        }
        return defaults;
    }

    /**
     * A text with its placeholders replaced (pairs of placeholder and value) and its colours applied.
     */
    static String get(String path, Object... pairs) {
        Object value = raw(path);
        String text = value == null ? path : value instanceof List<?> list ? String.join("\n", list.stream().map(String::valueOf).toList()) : String.valueOf(value);
        return ChatUtil.color(replace(text, pairs));
    }

    /**
     * The lines of a list of texts (a single text is one line), placeholders replaced and colours applied. A value
     * holding several lines (a list of commands) gives as many lines.
     */
    static List<String> lines(String path, Object... pairs) {
        Object value = raw(path);
        List<String> lines = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object line : list) {
                for (String part : replace(String.valueOf(line), pairs).split("\n", -1)) {
                    lines.add(ChatUtil.color(part));
                }
            }
        } else if (value != null) {
            for (String part : replace(String.valueOf(value), pairs).split("\n", -1)) {
                lines.add(ChatUtil.color(part));
            }
        }
        return lines;
    }

    /**
     * Whether messages.yml (the server's or the plugin's) has a value at this path.
     */
    static boolean has(String path) {
        return raw(path) != null;
    }

    private static String replace(String text, Object... pairs) {
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            text = text.replace(String.valueOf(pairs[i]), String.valueOf(pairs[i + 1]));
        }
        return text;
    }
}
