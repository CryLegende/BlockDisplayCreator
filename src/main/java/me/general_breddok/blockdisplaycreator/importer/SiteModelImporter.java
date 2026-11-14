package me.general_breddok.blockdisplaycreator.importer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.general_breddok.blockdisplaycreator.commandparser.LegacyDisplayNbt;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Turns a model published on block-display.com into the file of a custom block: its summon command, a click box
 * fitted to it and, when the model is animated, its animation.
 * <p>
 * The site serves the bdengine project of a model to its own preview: a tree of groups holding block and item
 * displays, each with a matrix local to its group. An animation belongs to groups: keys of position, rotation and
 * scale every tenth of a second. It is replayed here as frames that move each animated group as a whole, which
 * the clients interpolate.
 * <p>
 * Everything in this class runs off the main thread. The project is only ever read as data.
 */
public final class SiteModelImporter {

    private static final String PROJECT_URL = "https://www.block-display.com/api/v1/bde/project-model";
    private static final String FILE_URL = "https://www.block-display.com/api/v1/bde/project-file";
    private static final String INFO_URL = "https://block-display.com/api/v1/models/";
    private static final String PAGE_URL = "https://block-display.com/bd/";
    /**
     * Folder of the imported blocks, in the custom block repository.
     */
    public static final String FOLDER = "import";
    /**
     * An animation is replayed in about this many steps at most: a long one is sampled less often.
     */
    private static final int MAX_STEPS = 60;
    private static final float MIN_WIDTH = 0.3f;
    private static final float MIN_HEIGHT = 0.25f;
    /**
     * An end of the model holding this share of its volume at most does not count in its click box...
     */
    private static final float TAIL_SHARE = 0.025f;
    /**
     * ...when it is at least this share of the length of the model on a side, or of its height at the top.
     */
    private static final float TAIL_LENGTH = 0.15f;
    private static final float TAIL_HEIGHT = 0.25f;

    private SiteModelImporter() {
    }

    /**
     * What an import produced.
     *
     * @param elements         display entities of the model
     * @param skipped          elements of the project left out (texts)
     * @param animatedElements display entities the animation moves, 0 without animation
     * @param ticks            length of the animation
     * @param notes            sounds the animation plays
     */
    public record Imported(String name, Path file, String title, String author, int elements, int skipped, int animatedElements, int frames, int ticks, int notes) {
    }

    private record Leaf(JsonObject node, Matrix4f world, String group) {
    }

    /**
     * @param repository the custom block repository
     * @param id         the number of the model on the site
     * @param name       the name of the custom block to create
     * @param trigger    what plays the animation: loop, click or place
     * @param turn       degrees the model is turned around the vertical axis
     * @throws IOException when the site cannot be reached, does not know the model, or the file cannot be written
     */
    public static Imported importModel(Path repository, int id, String name, String trigger, int turn) throws IOException {
        JsonObject root = project(id);
        playedAnimation(root);
        String title = "";
        String author = "";
        try {
            JsonObject info = JsonParser.parseString(new String(request(INFO_URL + id, null), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject data = info.has("data") && info.get("data").isJsonObject() ? info.getAsJsonObject("data") : info;
            title = text(data, "title");
            if (data.has("author") && data.get("author").isJsonObject()) {
                author = text(data.getAsJsonObject("author"), "username");
            }
            String licence = text(data, "license");
            if (!licence.isEmpty() && !licence.equalsIgnoreCase("none")) {
                author = author + ", licence " + licence;
            }
        } catch (IOException | RuntimeException ignored) {
            // the credit line is then left without them
        }

        List<Leaf> leaves = new ArrayList<>();
        // an animated group rests where its animation starts: the model is then the one the site gives as a command
        walk(root, new Matrix4f(), new Vector3f(), "", 0f, leaves);
        int skipped = (int) leaves.stream().filter(leaf -> kind(leaf.node()) == null).count();
        leaves.removeIf(leaf -> kind(leaf.node()) == null);
        if (leaves.isEmpty()) {
            throw new IOException("the model has no block or item display");
        }

        // turned to face whoever places it, and centred on its block
        Matrix4f turned = new Matrix4f().rotateY((float) Math.toRadians(turn));
        float[] bounds = bounds(leaves, turned, false);
        Matrix4f fit = new Matrix4f().translate(-(bounds[0] + bounds[3]) / 2, 0, -(bounds[2] + bounds[5]) / 2).mul(turned);

        StringBuilder summon = new StringBuilder("/summon block_display ~-0.5 ~ ~-0.5 {Passengers:[");
        for (int i = 0; i < leaves.size(); i++) {
            summon.append(i == 0 ? "" : ",").append(element(leaves.get(i).node(), new Matrix4f(fit).mul(leaves.get(i).world())));
        }
        summon.append("]}");

        Animation animation = animation(root, leaves, fit, trigger);
        float[] core = bounds(leaves, fit, true);
        String file = file(id, title, author, name, summon.toString(), core, animation, trigger);

        Path folder = repository.resolve(FOLDER);
        Files.createDirectories(folder);
        Path target = folder.resolve(name + ".yml");
        Path temporary = folder.resolve(name + ".yml.tmp");
        Files.writeString(temporary, file, StandardCharsets.UTF_8);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        return new Imported(name, target, title, author, leaves.size(), skipped,
                animation == null ? 0 : animation.parts, animation == null ? 0 : animation.frames, animation == null ? 0 : animation.ticks, animation == null ? 0 : animation.notes);
    }

    // ---------- The site ----------

    /**
     * The site keeps a project either in its answer, as text, or as a file of its own to be asked for next: the
     * larger and the more recent ones. Both are the same data, zipped.
     */
    private static JsonObject project(int id) throws IOException {
        JsonObject answer;
        try {
            answer = JsonParser.parseString(new String(request(PROJECT_URL, Map.of("modelID", String.valueOf(id))), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("the site did not answer with a model");
        }
        JsonObject data = answer.has("data") && answer.get("data").isJsonObject() ? answer.getAsJsonObject("data") : null;
        String inline = data == null ? "" : text(data, "projectBDE").trim();
        byte[] packed;
        if (!inline.isEmpty()) {
            try {
                packed = Base64.getDecoder().decode(inline);
            } catch (IllegalArgumentException e) {
                throw new IOException("the project of the model " + id + " is stored in a form this import does not read");
            }
        } else if (data != null && flag(data, "projectFileReady")) {
            Map<String, String> file = new LinkedHashMap<>();
            file.put("kind", text(data, "projectFileKind").isEmpty() ? "model" : text(data, "projectFileKind"));
            file.put("id", text(data, "projectFileId").isEmpty() ? String.valueOf(id) : text(data, "projectFileId"));
            packed = request(FILE_URL, file);
        } else {
            throw new IOException("the site has no project for the model " + id);
        }
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            return scene(in.readAllBytes());
        } catch (IOException | RuntimeException e) {
            throw new IOException("the project of the model " + id + " could not be read (" + e + ")");
        }
    }

    /**
     * The scene of an unzipped project: the project itself, or the entry "scene.json" of a container holding
     * several files.
     */
    private static JsonObject scene(byte[] project) throws IOException {
        byte[] json = project;
        if (project.length > 9 && project[0] == 'P' && project[1] == 'R' && project[2] == 'J') {
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(project).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            buffer.position(5);
            int entries = buffer.getInt();
            json = null;
            for (int i = 0; i < entries && buffer.remaining() >= 2; i++) {
                byte[] name = new byte[buffer.getShort() & 0xFFFF];
                buffer.get(name);
                int size = buffer.getInt();
                if (size < 0 || size > buffer.remaining()) {
                    break;
                }
                if (new String(name, StandardCharsets.UTF_8).equals("scene.json")) {
                    json = new byte[size];
                    buffer.get(json);
                    break;
                }
                buffer.position(buffer.position() + size);
            }
            if (json == null) {
                throw new IOException("it holds no scene");
            }
        }
        JsonElement scene = JsonParser.parseString(new String(json, StandardCharsets.UTF_8));
        return scene.isJsonArray() ? scene.getAsJsonArray().get(0).getAsJsonObject() : scene.getAsJsonObject();
    }

    /**
     * @param fields posted as a form when given, a plain GET otherwise
     */
    private static byte[] request(String url, Map<String, String> fields) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", "BlockDisplayCreator");
        if (fields != null) {
            String boundary = "----bdc" + Long.toHexString(System.nanoTime());
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            StringBuilder body = new StringBuilder();
            for (Map.Entry<String, String> field : fields.entrySet()) {
                body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(field.getKey())
                        .append("\"\r\n\r\n").append(field.getValue()).append("\r\n");
            }
            body.append("--").append(boundary).append("--\r\n");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = connection.getResponseCode();
        if (code != 200) {
            throw new IOException(code == 404 ? "the site does not know this model" : "the site answered with the code " + code);
        }
        try (InputStream in = connection.getInputStream()) {
            return in.readAllBytes();
        }
    }

    // ---------- The project ----------

    /**
     * Collects the displays of a project in the order of its summon command.
     *
     * @param time  the moment of the animation to pose the groups at, null for their resting pose
     * @param group the animated groups above the node, which move it as one
     */
    private static void walk(JsonObject node, Matrix4f world, Vector3f parentPivot, String group, Float time, List<Leaf> out) {
        if (flag(node, "isCollection")) {
            if (flag(node, "isBackCollection")) {
                return;
            }
            Matrix4f local = groupMatrix(node, parentPivot, time);
            String below = keys(node).size() > 1 ? group + "/" + System.identityHashCode(node) : group;
            Vector3f pivot = vector(node.get("pivotCustom"), 0);
            if (node.has("children") && node.get("children").isJsonArray()) {
                for (JsonElement child : node.getAsJsonArray("children")) {
                    walk(child.getAsJsonObject(), new Matrix4f(world).mul(local), pivot, below, time, out);
                }
            }
        } else if (node.has("transforms")) {
            out.add(new Leaf(node, new Matrix4f(world).mul(rowMajor(node.getAsJsonArray("transforms"))), group));
        }
    }

    private static Matrix4f groupMatrix(JsonObject node, Vector3f parentPivot, Float time) {
        JsonArray keys = keys(node);
        Vector3f position;
        Vector3f rotation;
        Vector3f scale;
        if (time != null && keys.size() > 1) {
            Vector3f[] sampled = sample(keys, time);
            position = sampled[0];
            rotation = sampled[1];
            scale = sampled[2];
        } else if (node.has("defaultTransform") && node.get("defaultTransform").isJsonObject()) {
            JsonObject rest = node.getAsJsonObject("defaultTransform");
            position = vector(rest.get("position"), 0);
            rotation = vector(rest.get("rotation"), 0);
            scale = vector(rest.get("scale"), 1);
        } else {
            return node.has("transforms") && node.get("transforms").isJsonArray() ? rowMajor(node.getAsJsonArray("transforms")) : new Matrix4f();
        }
        Vector3f own = vector(node.get("pivotCustom"), 0);
        return new Matrix4f().translate(parentPivot).translate(position).rotateXYZ(rotation.x, rotation.y, rotation.z)
                .scale(scale).translate(-own.x, -own.y, -own.z);
    }

    /**
     * Position, rotation and scale of a group at a moment: between two keys, along the curve of the second.
     */
    private static Vector3f[] sample(JsonArray keys, float time) {
        JsonObject previous = keys.get(0).getAsJsonObject();
        if (time <= previous.get("time").getAsFloat()) {
            return pose(previous);
        }
        for (int i = 1; i < keys.size(); i++) {
            JsonObject next = keys.get(i).getAsJsonObject();
            float from = previous.get("time").getAsFloat();
            float to = next.get("time").getAsFloat();
            if (time <= to) {
                float progress = ease(next, to > from ? (time - from) / (to - from) : 1);
                Vector3f[] a = pose(previous);
                Vector3f[] b = pose(next);
                return new Vector3f[]{a[0].lerp(b[0], progress), a[1].lerp(b[1], progress), a[2].lerp(b[2], progress)};
            }
            previous = next;
        }
        return pose(previous);
    }

    private static Vector3f[] pose(JsonObject key) {
        return new Vector3f[]{vector(key.get("position"), 0), vector(key.get("rotation"), 0), vector(key.get("scale"), 1)};
    }

    /**
     * The curve of a key, stored by the editor as a hundred points.
     */
    private static float ease(JsonObject key, float progress) {
        try {
            JsonArray points = JsonParser.parseString(key.get("curveFuncSave").getAsString()).getAsJsonArray();
            for (int i = 1; i < points.size(); i++) {
                JsonObject a = points.get(i - 1).getAsJsonObject();
                JsonObject b = points.get(i).getAsJsonObject();
                float ax = a.get("x").getAsFloat();
                float bx = b.get("x").getAsFloat();
                if (progress >= ax && progress <= bx) {
                    float ay = a.get("y").getAsFloat();
                    return bx <= ax ? ay : ay + (b.get("y").getAsFloat() - ay) * (progress - ax) / (bx - ax);
                }
            }
        } catch (RuntimeException ignored) {
            // no curve: a straight line
        }
        return progress;
    }

    /**
     * A project can hold several animations, listed in "listAnim" with a number each: the keys of the first are
     * stored in "animation", those of number n in "animation_n". A project whose first animation was deleted only has
     * the latter. The first listed animation that has keys is the one imported: its keys are moved to "animation",
     * where the rest of this class reads them.
     */
    private static void playedAnimation(JsonObject root) {
        if (!root.has("listAnim") || !root.get("listAnim").isJsonArray()) {
            return;
        }
        for (JsonElement listed : root.getAsJsonArray("listAnim")) {
            if (!listed.isJsonObject() || !listed.getAsJsonObject().has("id")) {
                continue;
            }
            String number = text(listed.getAsJsonObject(), "id");
            String field = number.equals("1") ? "animation" : "animation_" + number;
            if (hasKeys(root, field)) {
                if (!field.equals("animation")) {
                    moveKeys(root, field);
                }
                return;
            }
        }
    }

    private static boolean hasKeys(JsonObject node, String field) {
        if (node.has(field) && node.get(field).isJsonArray() && node.getAsJsonArray(field).size() > 1) {
            return true;
        }
        if (node.has("children") && node.get("children").isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray("children")) {
                if (child.isJsonObject() && hasKeys(child.getAsJsonObject(), field)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void moveKeys(JsonObject node, String field) {
        node.remove("animation");
        if (node.has(field)) {
            node.add("animation", node.get(field));
        }
        if (node.has("children") && node.get("children").isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray("children")) {
                if (child.isJsonObject()) {
                    moveKeys(child.getAsJsonObject(), field);
                }
            }
        }
    }

    private static JsonArray keys(JsonObject node) {
        return node.has("animation") && node.get("animation").isJsonArray() ? node.getAsJsonArray("animation") : new JsonArray();
    }

    private static boolean flag(JsonObject node, String name) {
        return node.has(name) && node.get(name).isJsonPrimitive() && node.get(name).getAsJsonPrimitive().isBoolean() && node.get(name).getAsBoolean();
    }

    private static String text(JsonObject node, String name) {
        return node.has(name) && node.get(name).isJsonPrimitive() ? node.get(name).getAsString() : "";
    }

    private static Vector3f vector(JsonElement value, float byDefault) {
        if (value != null && value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            return new Vector3f(object.get("x").getAsFloat(), object.get("y").getAsFloat(), object.get("z").getAsFloat());
        }
        if (value != null && value.isJsonArray() && value.getAsJsonArray().size() >= 3) {
            JsonArray array = value.getAsJsonArray();
            return new Vector3f(array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat());
        }
        return new Vector3f(byDefault);
    }

    /**
     * A matrix written row by row, as in a summon command.
     */
    private static Matrix4f rowMajor(JsonArray values) {
        float[] m = new float[16];
        for (int i = 0; i < 16; i++) {
            m[i] = values.get(i).getAsFloat();
        }
        return new Matrix4f().set(m).transpose();
    }

    /**
     * "block", "item", or null for what this import leaves out.
     */
    private static String kind(JsonObject node) {
        return flag(node, "isBlockDisplay") ? "block" : flag(node, "isItemDisplay") ? "item" : null;
    }

    // ---------- The summon command ----------

    private static String element(JsonObject node, Matrix4f matrix) {
        String name = text(node, "name");
        int bracket = name.indexOf('[');
        String id = (bracket < 0 ? name : name.substring(0, bracket)).trim();
        id = LegacyDisplayNbt.idForServer(id); // a project keeps the names of its time, the server wants its own
        boolean modern = LegacyDisplayNbt.serverNeedsModernNbt();
        String properties = bracket < 0 ? "" : name.substring(bracket + 1, name.lastIndexOf(']') < 0 ? name.length() : name.lastIndexOf(']'));
        StringBuilder out = new StringBuilder();
        if ("item".equals(kind(node))) {
            String display = "none";
            for (String property : properties.split(",")) {
                if (property.trim().startsWith("display=")) {
                    display = property.trim().substring("display=".length());
                }
            }
            out.append("{id:\"minecraft:item_display\",item:{id:\"minecraft:").append(id).append("\",count:1");
            String texture = node.has("tagHead") && node.get("tagHead").isJsonObject() ? text(node.getAsJsonObject("tagHead"), "Value") : "";
            if (!texture.isEmpty()) {
                out.append(",components:{\"minecraft:profile\":{properties:[{name:\"textures\",value:\"").append(texture).append("\"}]}}");
            }
            out.append("},item_display:\"").append(display).append('"');
        } else {
            out.append("{id:\"minecraft:block_display\",block_state:{").append(modern ? "id" : "Name").append(":\"minecraft:").append(id).append('"');
            if (!properties.isBlank()) {
                out.append(modern ? ",properties:{" : ",Properties:{");
                boolean first = true;
                for (String property : properties.split(",")) {
                    int equals = property.indexOf('=');
                    if (equals > 0) {
                        out.append(first ? "" : ",").append(property, 0, equals).append(":\"").append(property.substring(equals + 1)).append('"');
                        first = false;
                    }
                }
                out.append('}');
            }
            out.append('}');
        }
        return out.append(",transformation:[").append(matrixText(matrix, "f")).append("]}").toString();
    }

    private static String matrixText(Matrix4f matrix, String suffix) {
        StringBuilder out = new StringBuilder();
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                out.append(out.length() == 0 ? "" : ",").append(number(matrix.get(column, row), 5)).append(suffix);
            }
        }
        return out.toString();
    }

    private static String number(float value, int decimals) {
        String text = String.format(Locale.ROOT, "%." + decimals + "f", value);
        text = text.contains(".") ? text.replaceAll("0+$", "").replaceAll("\\.$", "") : text;
        return text.equals("-0") ? "0" : text;
    }

    // ---------- The animation ----------

    private static final class Animation {
        final StringBuilder text = new StringBuilder();
        final StringBuilder sounds = new StringBuilder();
        int frames;
        int ticks;
        int parts;
        int notes;
    }

    private static Animation animation(JsonObject root, List<Leaf> rest, Matrix4f fit, String trigger) {
        Map<String, List<Integer>> groups = new LinkedHashMap<>();
        for (int i = 0; i < rest.size(); i++) {
            if (!rest.get(i).group().isEmpty()) {
                groups.computeIfAbsent(rest.get(i).group(), key -> new ArrayList<>()).add(i);
            }
        }
        int length = (int) Math.ceil(length(root));
        if (groups.isEmpty() || length <= 0) {
            return null;
        }
        Animation animation = new Animation();
        int step = Math.max(1, (int) Math.ceil(length / (double) MAX_STEPS));
        Map<String, String> last = new LinkedHashMap<>();
        Matrix4f unfit = new Matrix4f(fit).invert();
        int previous = 0;
        for (int time = Math.min(step, length); ; time = Math.min(time + step, length)) {
            List<Leaf> posed = new ArrayList<>();
            walk(root, new Matrix4f(), new Vector3f(), "", (float) time, posed);
            posed.removeIf(leaf -> kind(leaf.node()) == null);
            for (Map.Entry<String, List<Integer>> group : groups.entrySet()) {
                int first = group.getValue().get(0);
                // how the group has moved since its resting pose, in the space of the custom block
                Matrix4f moved = new Matrix4f(fit).mul(posed.get(first).world()).mul(new Matrix4f(rest.get(first).world()).invert()).mul(unfit);
                String head = "      - at: " + previous * 2 + "\n        over: " + (time - previous) * 2 + "\n";
                String body = similarity(moved);
                if (body == null) {
                    // the group is stretched: each of its displays is given its matrix
                    for (int member : group.getValue()) {
                        animation.text.append(head).append("        parts: [").append(member + 1).append("]\n        transformation: [")
                                .append(matrixText(new Matrix4f(fit).mul(posed.get(member).world()), "").replace(",", ", ")).append("]\n");
                        animation.frames++;
                    }
                    last.remove(group.getKey());
                } else if (!body.equals(last.get(group.getKey()))) {
                    last.put(group.getKey(), body);
                    animation.text.append(head).append("        parts: ").append(ranges(group.getValue())).append('\n').append(body);
                    animation.frames++;
                }
            }
            previous = time;
            if (time >= length) {
                break;
            }
        }
        animation.ticks = length * 2;
        if (!"loop".equals(trigger)) {
            // a loop starts over from its last frame, which is also its first; otherwise the model goes back to rest
            for (List<Integer> members : groups.values()) {
                animation.text.append("      - at: ").append(length * 2).append("\n        over: 2\n        parts: ").append(ranges(members)).append('\n');
                animation.frames++;
            }
            animation.ticks += 2;
        }
        animation.parts = groups.values().stream().mapToInt(List::size).sum();
        sounds(root, animation);
        return animation;
    }

    /**
     * The sounds of a project are tracks of notes: a sound of the game, played at given ticks with a pitch and a
     * volume. They are written as the sounds of the animation.
     */
    private static void sounds(JsonObject root, Animation animation) {
        if (!root.has("listSound") || !root.get("listSound").isJsonArray()) {
            return;
        }
        List<float[]> notes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (JsonElement entry : root.getAsJsonArray("listSound")) {
            JsonObject sound = entry.getAsJsonObject();
            float tick = sound.has("tick") && sound.get("tick").isJsonPrimitive() ? Math.max(0.05f, sound.get("tick").getAsFloat()) : 1;
            if (!sound.has("tracks") || !sound.get("tracks").isJsonArray()) {
                continue;
            }
            for (JsonElement trackEntry : sound.getAsJsonArray("tracks")) {
                JsonObject track = trackEntry.getAsJsonObject();
                String id = text(track, "id");
                if (id.isEmpty() || !track.has("piano") || !track.get("piano").isJsonArray()) {
                    continue;
                }
                for (JsonElement noteEntry : track.getAsJsonArray("piano")) {
                    JsonObject note = noteEntry.getAsJsonObject();
                    float pitch = note.has("pitch") ? note.get("pitch").getAsFloat() : 1;
                    float volume = note.has("volume") ? note.get("volume").getAsFloat() : 1;
                    String played = id;
                    // The game plays a sound from half to twice its pitch. A harp note out of that range goes to the
                    // note block instrument that sounds one or two octaves away; any other is brought back by octaves.
                    if (id.endsWith("note_block.harp") && pitch > 0) {
                        String family = id.substring(0, id.length() - "harp".length());
                        if (pitch < 0.25f) {
                            played = family + "bass";
                            pitch *= 4;
                        } else if (pitch < 0.5f) {
                            played = family + "guitar";
                            pitch *= 2;
                        } else if (pitch > 4) {
                            played = family + "bell";
                            pitch /= 4;
                        } else if (pitch > 2) {
                            played = family + "flute";
                            pitch /= 2;
                        }
                    }
                    while (pitch > 0 && pitch < 0.5f) {
                        pitch *= 2;
                    }
                    while (pitch > 2) {
                        pitch /= 2;
                    }
                    notes.add(new float[]{Math.round(note.get("time").getAsFloat() * tick), pitch, volume});
                    names.add(played.contains(":") ? played : "minecraft:" + played);
                }
            }
        }
        Integer[] order = new Integer[notes.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, java.util.Comparator.comparingDouble(i -> notes.get(i)[0]));
        for (int i : order) {
            float[] note = notes.get(i);
            animation.sounds.append("      - at: ").append((int) note[0]).append("\n        sound: ").append(names.get(i))
                    .append("\n        pitch: ").append(number(note[1], 4)).append('\n');
            if (Math.abs(note[2] - 1) > 1.0E-3f) {
                animation.sounds.append("        volume: ").append(number(note[2], 2)).append('\n');
            }
            animation.notes++;
            animation.ticks = Math.max(animation.ticks, (int) note[0] + 1);
        }
    }

    private static float length(JsonObject node) {
        float length = 0;
        JsonArray keys = keys(node);
        if (flag(node, "isCollection") && keys.size() > 1) {
            length = keys.get(keys.size() - 1).getAsJsonObject().get("time").getAsFloat();
        }
        if (node.has("children") && node.get("children").isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray("children")) {
                length = Math.max(length, length(child.getAsJsonObject()));
            }
        }
        return length;
    }

    /**
     * A motion that keeps the proportions of what it moves, written as the move, rotate and scale of a frame
     * around the origin of the model. Null for a motion that stretches.
     */
    private static String similarity(Matrix4f moved) {
        Vector3f scale = moved.getScale(new Vector3f());
        if (Math.abs(scale.x - scale.y) > 1.0E-3f || Math.abs(scale.y - scale.z) > 1.0E-3f || scale.x < 1.0E-4f) {
            return null;
        }
        Quaternionf rotation = moved.getNormalizedRotation(new Quaternionf());
        Vector3f move = moved.getTranslation(new Vector3f());
        if (!new Matrix4f().translate(move).rotate(rotation).scale(scale.x).equals(moved, 2.0E-3f)) {
            return null;
        }
        Vector3f angles = rotation.getEulerAnglesXYZ(new Vector3f());
        StringBuilder body = new StringBuilder("        move: [").append(number(move.x, 4)).append(", ").append(number(move.y, 4)).append(", ").append(number(move.z, 4))
                .append("]\n        rotate: [").append(number((float) Math.toDegrees(angles.x), 3)).append(", ").append(number((float) Math.toDegrees(angles.y), 3))
                .append(", ").append(number((float) Math.toDegrees(angles.z), 3)).append("]\n");
        if (Math.abs(scale.x - 1) > 1.0E-4f) {
            body.append("        scale: ").append(number(scale.x, 4)).append('\n');
        }
        return body.append("        pivot: [0, 0, 0]\n").toString();
    }

    /**
     * Element numbers as a frame writes them: the root of the model is 0, its displays follow.
     */
    private static String ranges(List<Integer> members) {
        StringBuilder out = new StringBuilder("[");
        int i = 0;
        while (i < members.size()) {
            int start = members.get(i);
            int end = start;
            while (i + 1 < members.size() && members.get(i + 1) == end + 1) {
                end = members.get(++i);
            }
            out.append(out.length() == 1 ? "" : ", ").append(start == end ? String.valueOf(start + 1) : "\"" + (start + 1) + "-" + (end + 1) + "\"");
            i++;
        }
        return out.append(']').toString();
    }

    // ---------- The click box ----------

    /**
     * The box around the displays of the model: min x, y, z then max x, y, z.
     *
     * @param trimmed whether a thin end of the model, one that holds next to nothing of its volume, is left out
     */
    private static float[] bounds(List<Leaf> leaves, Matrix4f fit, boolean trimmed) {
        List<float[]> boxes = new ArrayList<>();
        List<Float> volumes = new ArrayList<>();
        Vector3f corner = new Vector3f();
        for (Leaf leaf : leaves) {
            Matrix4f matrix = new Matrix4f(fit).mul(leaf.world());
            // a block display draws the unit cube; a player head half a block resting on the origin; other items a cube around it
            boolean block = "block".equals(kind(leaf.node()));
            boolean head = !block && text(leaf.node(), "name").startsWith("player_head");
            float[] local = block ? blockShape(text(leaf.node(), "name")) : head ? new float[]{-0.25f, -0.5f, -0.25f, 0.25f, 0, 0.25f} : new float[]{-0.5f, -0.5f, -0.5f, 0.5f, 0.5f, 0.5f};
            float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (int i = 0; i < 8; i++) {
                matrix.transformPosition(corner.set(local[(i & 1) == 0 ? 0 : 3], local[(i & 2) == 0 ? 1 : 4], local[(i & 4) == 0 ? 2 : 5]));
                box[0] = Math.min(box[0], corner.x);
                box[1] = Math.min(box[1], corner.y);
                box[2] = Math.min(box[2], corner.z);
                box[3] = Math.max(box[3], corner.x);
                box[4] = Math.max(box[4], corner.y);
                box[5] = Math.max(box[5], corner.z);
            }
            boxes.add(box);
            volumes.add(Math.abs(matrix.determinant3x3()) * (local[3] - local[0]) * (local[4] - local[1]) * (local[5] - local[2]));
        }
        float[] full = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        float total = 0;
        for (int i = 0; i < boxes.size(); i++) {
            for (int axis = 0; axis < 3; axis++) {
                full[axis] = Math.min(full[axis], boxes.get(i)[axis]);
                full[axis + 3] = Math.max(full[axis + 3], boxes.get(i)[axis + 3]);
            }
            total += volumes.get(i);
        }
        if (!trimmed || boxes.size() < 4 || total <= 0) {
            return full;
        }
        float[] core = full.clone();
        for (int axis = 0; axis < 3; axis++) {
            float length = full[axis + 3] - full[axis];
            if (length <= 0) {
                continue;
            }
            for (boolean fromLow : new boolean[]{true, false}) {
                if (axis == 1 && fromLow) {
                    // the box stays standing on its base
                    continue;
                }
                // the point where the end holds TAIL_SHARE of the volume, each display spreading its own along its length
                float low = full[axis];
                float high = full[axis + 3];
                for (int step = 0; step < 40; step++) {
                    float middle = (low + high) / 2;
                    float mass = 0;
                    for (int i = 0; i < boxes.size(); i++) {
                        float from = boxes.get(i)[axis];
                        float span = boxes.get(i)[axis + 3] - from;
                        float share = span <= 1.0E-6f ? ((fromLow ? from <= middle : from >= middle) ? 1 : 0)
                                : Math.min(1, Math.max(0, fromLow ? (middle - from) / span : (from + span - middle) / span));
                        mass += volumes.get(i) * share;
                    }
                    boolean over = mass > TAIL_SHARE * total;
                    if (fromLow == over) {
                        high = middle;
                    } else {
                        low = middle;
                    }
                }
                float cut = (low + high) / 2;
                float tail = fromLow ? cut - full[axis] : full[axis + 3] - cut;
                if (tail / length >= (axis == 1 ? TAIL_HEIGHT : TAIL_LENGTH)) {
                    core[fromLow ? axis : axis + 3] = fromLow ? cut - 0.03f : cut + 0.03f;
                }
            }
        }
        return core;
    }

    /**
     * The part of its cube a block fills, for the blocks models are commonly built with that fill little of it:
     * min x, y, z then max x, y, z. A block not listed here counts as a full cube.
     *
     * @param state the block as a project names it: {@code black_candle[candles=1,lit=false]}
     */
    private static float[] blockShape(String state) {
        int bracket = state.indexOf('[');
        String id = (bracket < 0 ? state : state.substring(0, bracket)).trim();
        Map<String, String> properties = new LinkedHashMap<>();
        if (bracket >= 0) {
            for (String property : state.substring(bracket + 1).replace("]", "").split(",")) {
                int equals = property.indexOf('=');
                if (equals > 0) {
                    properties.put(property.substring(0, equals).trim(), property.substring(equals + 1).trim());
                }
            }
        }
        String facing = properties.getOrDefault("facing", "");
        float px = 1 / 16f;
        if (id.endsWith("_button")) {
            boolean alongX = facing.equals("north") || facing.equals("south");
            String face = properties.getOrDefault("face", "wall");
            if (!face.equals("wall")) {
                float w = (alongX ? 6 : 4) * px;
                float d = (alongX ? 4 : 6) * px;
                float y = face.equals("floor") ? 0 : 1 - 2 * px;
                return new float[]{0.5f - w / 2, y, 0.5f - d / 2, 0.5f + w / 2, y + 2 * px, 0.5f + d / 2};
            }
            float[] plate = against(facing, 2 * px);
            return alongX ? new float[]{5 * px, 6 * px, plate[2], 11 * px, 10 * px, plate[5]} : new float[]{plate[0], 6 * px, 5 * px, plate[3], 10 * px, 11 * px};
        }
        if (id.endsWith("_pressure_plate")) {
            return new float[]{px, 0, px, 1 - px, px, 1 - px};
        }
        if (id.endsWith("_carpet")) {
            return new float[]{0, 0, 0, 1, px, 1};
        }
        if (id.endsWith("_slab")) {
            String type = properties.getOrDefault("type", "bottom");
            return type.equals("double") ? new float[]{0, 0, 0, 1, 1, 1} : type.equals("top") ? new float[]{0, 0.5f, 0, 1, 1, 1} : new float[]{0, 0, 0, 1, 0.5f, 1};
        }
        if (id.equals("candle") || id.endsWith("_candle")) {
            int[] base = switch (properties.getOrDefault("candles", "1")) {
                case "1" -> new int[]{7, 7, 9, 9};
                case "2" -> new int[]{5, 7, 11, 10};
                case "3" -> new int[]{5, 6, 10, 11};
                default -> new int[]{5, 5, 11, 10};
            };
            return new float[]{base[0] * px, 0, base[1] * px, base[2] * px, 6 * px, base[3] * px};
        }
        if (id.endsWith("_trapdoor")) {
            if (properties.getOrDefault("open", "false").equals("true")) {
                return against(facing, 3 * px);
            }
            return properties.getOrDefault("half", "bottom").equals("top") ? new float[]{0, 1 - 3 * px, 0, 1, 1, 1} : new float[]{0, 0, 0, 1, 3 * px, 1};
        }
        if (id.endsWith("_door")) {
            return against(switch (facing) {
                case "north" -> "south";
                case "south" -> "north";
                case "east" -> "west";
                default -> "east";
            }, 3 * px);
        }
        if (id.equals("ladder")) {
            return against(facing, 3 * px);
        }
        if (id.endsWith("_fence") || id.equals("bamboo")) {
            return new float[]{6 * px, 0, 6 * px, 10 * px, 1, 10 * px};
        }
        if (id.endsWith("_wall")) {
            return new float[]{4 * px, 0, 4 * px, 12 * px, 1, 12 * px};
        }
        if (id.equals("end_rod") || id.endsWith("lightning_rod") || id.equals("chain") || id.endsWith("_chain")) {
            String axis = properties.getOrDefault("axis", facing.equals("north") || facing.equals("south") ? "z" : facing.equals("east") || facing.equals("west") ? "x" : "y");
            return axis.equals("z") ? new float[]{6 * px, 6 * px, 0, 10 * px, 10 * px, 1}
                    : axis.equals("x") ? new float[]{0, 6 * px, 6 * px, 1, 10 * px, 10 * px} : new float[]{6 * px, 0, 6 * px, 10 * px, 1, 10 * px};
        }
        if (id.equals("lantern") || id.endsWith("_lantern")) {
            return new float[]{5 * px, 0, 5 * px, 11 * px, 10 * px, 11 * px};
        }
        if (id.startsWith("potted_") || id.equals("flower_pot")) {
            return new float[]{5 * px, 0, 5 * px, 11 * px, 14 * px, 11 * px};
        }
        if (id.equals("cake") || id.endsWith("_bed")) {
            return new float[]{px, 0, px, 1 - px, 0.5f, 1 - px};
        }
        return new float[]{0, 0, 0, 1, 1, 1};
    }

    /**
     * A plate of the given depth against the side of its cube a block faces away from.
     */
    private static float[] against(String facing, float depth) {
        return switch (facing) {
            case "north" -> new float[]{0, 0, 1 - depth, 1, 1, 1};
            case "south" -> new float[]{0, 0, 0, 1, 1, depth};
            case "west" -> new float[]{1 - depth, 0, 0, 1, 1, 1};
            case "east" -> new float[]{0, 0, 0, depth, 1, 1};
            default -> new float[]{0, 0, 0, 1, 1, 1};
        };
    }

    // ---------- The file ----------

    private static String file(int id, String title, String author, String name, String summon, float[] box, Animation animation, String trigger) {
        float base = Math.abs(box[1]) < 0.06f ? 0 : box[1];
        float height = Math.max(MIN_HEIGHT, box[4] - base);
        float dx = Math.max(MIN_WIDTH, box[3] - box[0]);
        float dz = Math.max(MIN_WIDTH, box[5] - box[2]);
        float centreX = (box[0] + box[3]) / 2;
        float centreZ = (box[2] + box[5]) / 2;
        boolean turning = animation != null && "loop".equals(trigger);

        StringBuilder out = new StringBuilder();
        out.append("# Imported from ").append(PAGE_URL).append(id);
        if (!title.isEmpty()) {
            out.append(" - ").append(title.replace('\n', ' '));
        }
        if (!author.isEmpty()) {
            out.append(", by ").append(author.replace('\n', ' '));
        }
        out.append('\n');
        // the size of the whole model, click boxes and animations included
        out.append("scale: 1\n");
        out.append("display:\n  spawn-command:\n  - ").append(summon).append("\n\n");
        out.append("central-material: AIR\n\nsides-count: 8\n\n");
        out.append("item:\n  material: LANTERN\n  name: '&e").append(name.replace('_', ' ').replace("'", "''")).append("'\n")
                .append("  enchantments:\n    LURE: 1\n  item-flags:\n  - HIDE_ENCHANTS\n\n");

        out.append("interactions:\n");
        float longSide = Math.max(dx, dz);
        float shortSide = Math.min(dx, dz);
        int count = turning || longSide / shortSide < 1.45f ? 1 : Math.min(3, Math.round(longSide / shortSide));
        if (turning) {
            // a model that turns on itself is clicked around its axis: this box is not to be recomputed
            out.append("  # zone cliquable réglée à la main\n");
        }
        float width = count == 1 ? (turning ? Math.min(Math.max(shortSide, longSide / 2), 1.5f) : longSide) : Math.max(shortSide, longSide / count);
        for (int i = 0; i < count; i++) {
            float along = count == 1 ? 0 : Math.max(-longSide / 2 + width / 2, Math.min(longSide / 2 - width / 2, -longSide / 2 + (i + 0.5f) * longSide / count));
            out.append("  interaction").append(i == 0 ? "" : "_" + (i + 1)).append(":\n")
                    .append("    height: ").append(number(height, 2)).append("\n    width: ").append(number(width, 2)).append("\n    offset:\n")
                    .append("      x: ").append(offset(turning ? 0 : centreX + (dx >= dz ? along : 0))).append("\n      y: ").append(offset(base))
                    .append("\n      z: ").append(offset(turning ? 0 : centreZ + (dz > dx ? along : 0))).append("\n    command: []\n    command-source: CONSOLE\n");
        }
        float size = Math.max(0.1f, Math.min(1, Math.min(shortSide, height)));
        out.append("\ncollisions:\n  main:\n    size: ").append(number(size, 2)).append("\n    offset:\n      x: ").append(offset(turning ? 0 : centreX))
                .append("\n      y: ").append(number(Math.max(0.01f, base), 2)).append("\n      z: ").append(offset(turning ? 0 : centreZ)).append("\n    disable-below-1_20_5: true\n\n");
        out.append("sound:\n  break:\n    sound-type: BLOCK_WOOD_BREAK\n    volume: 2.5\n    pitch: 0.6\n  place:\n    sound-type: BLOCK_BARREL_OPEN\n    volume: 1.7\n    pitch: 3\n");
        if (animation != null) {
            out.append("\nanimations:\n  animation:\n    trigger: ").append(trigger).append("\n    duration: ").append(animation.ticks).append("\n    frames:\n").append(animation.text);
            if (animation.notes > 0) {
                out.append("    sounds:\n").append(animation.sounds);
            }
        }
        return out.toString();
    }

    /**
     * An offset under four centimetres is the rounding noise of the model.
     */
    private static String offset(float value) {
        return Math.abs(value) < 0.04f ? "0" : number(value, 2);
    }
}
