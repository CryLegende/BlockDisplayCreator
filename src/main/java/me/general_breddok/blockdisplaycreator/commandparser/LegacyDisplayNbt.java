package me.general_breddok.blockdisplaycreator.commandparser;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import lombok.experimental.UtilityClass;
import me.general_breddok.blockdisplaycreator.BlockDisplayCreator;
import me.general_breddok.blockdisplaycreator.version.MinecraftVersion;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the display NBT of a summon command exported for an older Minecraft version into the form the running
 * server reads.
 * <p>
 * Since Minecraft 26 a block display no longer reads {@code block_state:{Name:"...",Properties:{...}}}: it expects
 * {@code block_state:{id:"...",properties:{...}}}. The old form is dropped with a console warning only, and the
 * display is spawned holding air. Likewise the text of a text display is no longer a JSON string between quotes
 * but an SNBT component; the old form is shown as is, braces included.
 */
@UtilityClass
public class LegacyDisplayNbt {

    private static final Pattern BLOCK_STATE =
            Pattern.compile("block_state:\\{Name:(\"[^\"]+\")(?:,Properties:\\{([^{}]*)})?}");
    private static final Pattern JSON_TEXT = Pattern.compile("text:'((?:[^'\\\\]|\\\\.)*)'");
    private static final Pattern STATE_ID = Pattern.compile("\"?(?:id|Name)\"?:\"([^\"]+)\"");
    private static final Pattern STATE_PROPERTIES = Pattern.compile("\"?(?:properties|Properties)\"?:\\{([^{}]*)}");
    private static final Pattern DISPLAY_TYPE = Pattern.compile("id:\"minecraft:(block|item|text)_display\"");

    /**
     * @return true if the running server only reads the new display NBT (verified on 26.3)
     */
    public boolean serverNeedsModernNbt() {
        return BlockDisplayCreator.getInstance().getVersionManager().getCurrentVersion().isAtLeast(MinecraftVersion.V26_3);
    }

    /**
     * Converts the old block state and text forms found in the NBT of a summon command. Text already in the new
     * form is returned untouched.
     */
    public String modernize(String nbt) {
        Matcher block = BLOCK_STATE.matcher(nbt);
        StringBuilder out = new StringBuilder();
        while (block.find()) {
            String properties = block.group(2);
            String replacement = "block_state:{id:" + block.group(1)
                    + (properties == null || properties.isBlank() ? "" : ",properties:{" + properties + "}") + "}";
            block.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        block.appendTail(out);

        Matcher text = JSON_TEXT.matcher(out.toString());
        StringBuilder result = new StringBuilder();
        while (text.find()) {
            String replacement = text.group();
            try {
                replacement = "text:" + toSnbt(JsonParser.parseString(text.group(1).replace("\\'", "'")));
            } catch (RuntimeException ignored) {
                // not JSON: left as written
            }
            text.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        text.appendTail(result);
        return result.toString();
    }

    private String toSnbt(JsonElement element) {
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            List<String> parts = new ArrayList<>();
            array.forEach(child -> parts.add(toSnbt(child)));
            return "[" + String.join(",", parts) + "]";
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                parts.add(entry.getKey() + ":" + toSnbt(entry.getValue()));
            }
            return "{" + String.join(",", parts) + "}";
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isString()) {
            return "\"" + primitive.getAsString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return primitive.getAsString();
    }

    /**
     * One passenger of a summon command: its display kind and, for a block display, the block it must hold.
     *
     * @param kind  {@code block}, {@code item} or {@code text}; null when the passenger is not a display
     * @param block block data string such as {@code minecraft:oak_stairs[facing=east]}, or null
     */
    public record Passenger(String kind, String block) {
    }

    /**
     * Lists the passengers of the root entity of a summon command, in order. The command is modernized first.
     */
    public List<Passenger> passengers(String command) {
        String nbt = modernize(command);
        List<Passenger> passengers = new ArrayList<>();
        int start = nbt.indexOf("Passengers:[");
        if (start < 0) {
            return passengers;
        }
        int depth = 0;
        char quote = 0;
        int elementStart = -1;
        for (int i = start + "Passengers:[".length(); i < nbt.length(); i++) {
            char c = nbt.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '{' || c == '[') {
                if (depth == 0 && c == '{') {
                    elementStart = i;
                }
                depth++;
            } else if (c == '}' || c == ']') {
                if (depth == 0) {
                    break;
                }
                depth--;
                if (depth == 0 && elementStart >= 0) {
                    passengers.add(passenger(nbt.substring(elementStart, i + 1)));
                    elementStart = -1;
                }
            }
        }
        return passengers;
    }

    private Passenger passenger(String element) {
        Matcher type = DISPLAY_TYPE.matcher(element);
        String kind = type.find() ? type.group(1) : null;
        String block = null;
        if ("block".equals(kind)) {
            block = blockOf(element);
        }
        return new Passenger(kind, block);
    }

    /**
     * Reads the block of a block display, whatever the form (old or new keys, or a plain string) and whatever the
     * order of the keys: the NBT of a command is written back in no particular order once the plugin has parsed it.
     */
    private String blockOf(String element) {
        int at = element.indexOf("block_state:");
        if (at < 0) {
            return null;
        }
        String rest = element.substring(at + "block_state:".length());
        if (rest.startsWith("\"")) {
            int end = rest.indexOf('"', 1);
            return end < 0 ? null : rest.substring(1, end);
        }
        if (!rest.startsWith("{")) {
            return null;
        }
        int depth = 0;
        int end = -1;
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            return null;
        }
        String state = rest.substring(0, end + 1);
        Matcher id = STATE_ID.matcher(state);
        if (!id.find()) {
            return null;
        }
        Matcher properties = STATE_PROPERTIES.matcher(state);
        String list = properties.find() ? properties.group(1).replace("\"", "").replace(":", "=").trim() : "";
        return id.group(1) + (list.isEmpty() ? "" : "[" + list + "]");
    }
}
