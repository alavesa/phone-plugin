package fi.alavesa.phone.camera.assets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Block and item models resolved from an {@link AssetSource}: block states (variants and multipart) to models, model
 * parents and texture references, item definitions to models. Everything is cached; safe to use from any thread.
 *
 * <p>Geometry is kept in model space with coordinates 0..1 (vanilla's 0..16 divided by 16). A {@link Placed} model
 * carries the 90-degree rotation its block state asked for; the ray tracer turns the ray instead of the model, so
 * faces keep their own texture mapping.
 */
public final class Models {

    /** Face order: down, up, north, south, west, east (vanilla's Direction order). */
    public static final String[] DIRS = {"down", "up", "north", "south", "west", "east"};

    /** One face of an element: texture, uv rectangle in 0..1 of the texture, rotation (0, 90, 180, 270), tint index. */
    public record Face(Texture texture, double u0, double v0, double u1, double v1, int rotation, int tint) {}

    /**
     * A box of a model. {@code rotation}: the element's own rotation (3x3, model space) about {@code origin}, or null.
     */
    public record Element(double[] from, double[] to, Face[] faces, double[][] rotation, double[] origin, boolean shade) {}

    public record Model(List<Element> elements) {
        public boolean isEmpty() {
            return elements.isEmpty();
        }
    }

    /** A model as a block state uses it: {@code rotation} (3x3, about the block centre) or null for none. */
    public record Placed(Model model, double[][] rotation) {}

    private static final Model EMPTY = new Model(List.of());

    private final AssetSource assets;
    private final Map<String, Texture> textures = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> rawModels = new ConcurrentHashMap<>();
    private final Map<String, Model> models = new ConcurrentHashMap<>();
    private final Map<String, JsonObject> blockStates = new ConcurrentHashMap<>();
    private final Map<String, List<Placed>> blocks = new ConcurrentHashMap<>();
    private final Map<String, Model> items = new ConcurrentHashMap<>();
    private static final JsonObject NONE = new JsonObject();
    private static final Texture MISSING = new Texture(1, 1, new int[] {0});

    public Models(AssetSource assets) {
        this.assets = assets;
    }

    public boolean ready() {
        return assets.ready();
    }

    // ---- ids --------------------------------------------------------------------------------------------------------

    private static String[] split(String id) {
        int c = id.indexOf(':');
        return c < 0 ? new String[] {"minecraft", id} : new String[] {id.substring(0, c), id.substring(c + 1)};
    }

    /** A texture by id ("minecraft:block/stone", "block/stone"); null when it does not exist. */
    public Texture texture(String id) {
        Texture t = textures.computeIfAbsent(id, k -> {
            String[] ns = split(k);
            Texture loaded = Texture.decode(assets.read(ns[0], "textures/" + ns[1] + ".png"));
            return loaded == null ? MISSING : loaded;
        });
        return t == MISSING ? null : t;
    }

    private JsonObject raw(String id) {
        JsonObject o = rawModels.computeIfAbsent(id, k -> {
            String[] ns = split(k);
            JsonObject json = assets.json(ns[0], "models/" + ns[1] + ".json");
            return json == null ? NONE : json;
        });
        return o == NONE ? null : o;
    }

    // ---- models -----------------------------------------------------------------------------------------------------

    /** A model by id ("minecraft:block/oak_stairs"); an empty model when it is missing or has no geometry. */
    public Model model(String id) {
        return models.computeIfAbsent(id, this::build);
    }

    private Model build(String id) {
        Map<String, String> texRefs = new HashMap<>();
        JsonArray elements = null;
        boolean generated = false;
        String current = id;
        for (int depth = 0; current != null && depth < 16; depth++) {
            if (current.equals("builtin/generated") || current.equals("minecraft:builtin/generated")) {
                generated = true;
                break;
            }
            JsonObject json = raw(current);
            if (json == null) break;
            if (json.has("textures") && json.get("textures").isJsonObject()) {
                for (var e : json.getAsJsonObject("textures").entrySet()) {
                    JsonElement v = e.getValue();
                    // 26.x: a reference may be {"sprite": "...", "force_translucent": true}
                    if (v.isJsonObject() && v.getAsJsonObject().has("sprite")) v = v.getAsJsonObject().get("sprite");
                    if (v.isJsonPrimitive()) texRefs.putIfAbsent(e.getKey(), v.getAsString());
                }
            }
            if (elements == null && json.has("elements") && json.get("elements").isJsonArray()) elements = json.getAsJsonArray("elements");
            current = json.has("parent") ? json.get("parent").getAsString() : null;
        }
        if (elements == null && generated) return sprite(texRefs);
        if (elements == null) return EMPTY;
        List<Element> out = new ArrayList<>();
        for (JsonElement el : elements) {
            Element e = element(el.getAsJsonObject(), texRefs);
            if (e != null) out.add(e);
        }
        return new Model(List.copyOf(out));
    }

    /** An item drawn from its texture ("item/generated"): a 1/16 thin plate, front and back textured. */
    private Model sprite(Map<String, String> texRefs) {
        Texture t = resolveTexture("#layer0", texRefs);
        if (t == null) return EMPTY;
        Face face = new Face(t, 0, 0, 1, 1, 0, 0);
        Face[] faces = new Face[6];
        faces[2] = face;
        faces[3] = new Face(t, 1, 0, 0, 1, 0, 0);
        return new Model(List.of(new Element(new double[] {0, 0, 7.5 / 16}, new double[] {1, 1, 8.5 / 16}, faces, null, null, true)));
    }

    private Texture resolveTexture(String ref, Map<String, String> texRefs) {
        String r = ref;
        for (int i = 0; i < 16 && r != null && r.startsWith("#"); i++) r = texRefs.get(r.substring(1));
        if (r == null || r.startsWith("#")) return null;
        return texture(r);
    }

    private static double[] vec(JsonArray a, double scale) {
        return new double[] {a.get(0).getAsDouble() * scale, a.get(1).getAsDouble() * scale, a.get(2).getAsDouble() * scale};
    }

    private Element element(JsonObject json, Map<String, String> texRefs) {
        if (!json.has("from") || !json.has("to") || !json.has("faces")) return null;
        double[] from = vec(json.getAsJsonArray("from"), 1 / 16.0);
        double[] to = vec(json.getAsJsonArray("to"), 1 / 16.0);
        Face[] faces = new Face[6];
        JsonObject fs = json.getAsJsonObject("faces");
        for (int d = 0; d < 6; d++) {
            if (!fs.has(DIRS[d])) continue;
            JsonObject f = fs.getAsJsonObject(DIRS[d]);
            Texture t = f.has("texture") ? resolveTexture(f.get("texture").getAsString(), texRefs) : null;
            if (t == null) continue;
            double[] uv = f.has("uv") ? new double[] {f.getAsJsonArray("uv").get(0).getAsDouble(), f.getAsJsonArray("uv").get(1).getAsDouble(),
                    f.getAsJsonArray("uv").get(2).getAsDouble(), f.getAsJsonArray("uv").get(3).getAsDouble()} : defaultUv(d, from, to);
            int rot = f.has("rotation") ? Math.floorMod(f.get("rotation").getAsInt(), 360) : 0;
            int tint = f.has("tintindex") ? f.get("tintindex").getAsInt() : -1;
            faces[d] = new Face(t, uv[0] / 16, uv[1] / 16, uv[2] / 16, uv[3] / 16, rot, tint);
        }
        double[][] rotation = null;
        double[] origin = null;
        if (json.has("rotation") && json.get("rotation").isJsonObject()) {
            JsonObject r = json.getAsJsonObject("rotation");
            origin = r.has("origin") ? vec(r.getAsJsonArray("origin"), 1 / 16.0) : new double[] {0.5, 0.5, 0.5};
            if (r.has("axis") && r.has("angle")) {
                double a = Math.toRadians(r.get("angle").getAsDouble());
                rotation = switch (r.get("axis").getAsString()) {
                    case "x" -> rotX(a);
                    case "y" -> rotY(a);
                    default -> rotZ(a);
                };
            } else {
                // 26.x free rotations: Euler x, y, z in that order
                double x = Math.toRadians(r.has("x") ? r.get("x").getAsDouble() : 0);
                double y = Math.toRadians(r.has("y") ? r.get("y").getAsDouble() : 0);
                double z = Math.toRadians(r.has("z") ? r.get("z").getAsDouble() : 0);
                rotation = mul(rotZ(z), mul(rotY(y), rotX(x)));
            }
        }
        boolean shade = !json.has("shade") || json.get("shade").getAsBoolean();
        return new Element(from, to, faces, rotation, origin, shade);
    }

    /** The uv vanilla derives from an element's bounds when a face has none (0..16 units). */
    private static double[] defaultUv(int d, double[] f, double[] t) {
        double fx = f[0] * 16, fy = f[1] * 16, fz = f[2] * 16, tx = t[0] * 16, ty = t[1] * 16, tz = t[2] * 16;
        return switch (d) {
            case 0 -> new double[] {fx, 16 - tz, tx, 16 - fz};
            case 1 -> new double[] {fx, fz, tx, tz};
            case 2 -> new double[] {16 - tx, 16 - ty, 16 - fx, 16 - fy};
            case 3 -> new double[] {fx, 16 - ty, tx, 16 - fy};
            case 4 -> new double[] {fz, 16 - ty, tz, 16 - fy};
            default -> new double[] {16 - tz, 16 - ty, 16 - fz, 16 - fy};
        };
    }

    // ---- block states -----------------------------------------------------------------------------------------------

    /**
     * The models a block state is drawn with. {@code state} is {@code BlockData.getAsString()}, for example
     * {@code minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]}.
     */
    public List<Placed> block(String state) {
        return blocks.computeIfAbsent(state, this::resolveBlock);
    }

    private List<Placed> resolveBlock(String state) {
        int bracket = state.indexOf('[');
        String id = bracket < 0 ? state : state.substring(0, bracket);
        Map<String, String> props = new HashMap<>();
        if (bracket >= 0) {
            for (String kv : state.substring(bracket + 1, state.length() - 1).split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) props.put(kv.substring(0, eq), kv.substring(eq + 1));
            }
        }
        String[] ns = split(id);
        JsonObject json = blockStates.computeIfAbsent(id, k -> {
            JsonObject o = assets.json(ns[0], "blockstates/" + ns[1] + ".json");
            return o == null ? NONE : o;
        });
        List<Placed> out = new ArrayList<>();
        if (json == NONE) return out;
        if (json.has("variants")) {
            for (var e : json.getAsJsonObject("variants").entrySet()) {
                if (matchesVariant(e.getKey(), props)) {
                    out.add(placed(first(e.getValue())));
                    break;
                }
            }
        } else if (json.has("multipart")) {
            for (JsonElement part : json.getAsJsonArray("multipart")) {
                JsonObject p = part.getAsJsonObject();
                if (!p.has("when") || matchesWhen(p.getAsJsonObject("when"), props)) out.add(placed(first(p.get("apply"))));
            }
        }
        out.removeIf(p -> p == null || p.model().isEmpty());
        return List.copyOf(out);
    }

    private static JsonObject first(JsonElement e) {
        if (e.isJsonArray()) return e.getAsJsonArray().isEmpty() ? null : e.getAsJsonArray().get(0).getAsJsonObject();
        return e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static boolean matchesVariant(String key, Map<String, String> props) {
        if (key.isEmpty()) return true;
        for (String kv : key.split(",")) {
            int eq = kv.indexOf('=');
            if (eq < 0) continue;
            if (!kv.substring(eq + 1).equals(props.get(kv.substring(0, eq)))) return false;
        }
        return true;
    }

    private static boolean matchesWhen(JsonObject when, Map<String, String> props) {
        if (when.has("OR")) {
            for (JsonElement e : when.getAsJsonArray("OR")) if (matchesWhen(e.getAsJsonObject(), props)) return true;
            return false;
        }
        if (when.has("AND")) {
            for (JsonElement e : when.getAsJsonArray("AND")) if (!matchesWhen(e.getAsJsonObject(), props)) return false;
            return true;
        }
        for (var e : when.entrySet()) {
            String value = props.get(e.getKey());
            String want = e.getValue().getAsString();
            boolean negate = want.startsWith("!");
            boolean any = false;
            for (String alt : (negate ? want.substring(1) : want).split("\\|")) any |= alt.equals(value);
            if (any == negate) return false;
        }
        return true;
    }

    private Placed placed(JsonObject variant) {
        if (variant == null || !variant.has("model")) return null;
        Model m = model(variant.get("model").getAsString());
        int x = variant.has("x") ? variant.get("x").getAsInt() : 0;
        int y = variant.has("y") ? variant.get("y").getAsInt() : 0;
        double[][] rotation = x == 0 && y == 0 ? null : mul(stateY(y), stateX(x));
        return new Placed(m, rotation);
    }

    /** Block-state rotation about y: clockwise seen from above (north turns to east). */
    private static double[][] stateY(int degrees) {
        double a = Math.toRadians(degrees), c = Math.cos(a), s = Math.sin(a);
        return round(new double[][] {{c, 0, -s}, {0, 1, 0}, {s, 0, c}});
    }

    /** Block-state rotation about x: up turns to north. */
    private static double[][] stateX(int degrees) {
        double a = Math.toRadians(degrees), c = Math.cos(a), s = Math.sin(a);
        return round(new double[][] {{1, 0, 0}, {0, c, s}, {0, -s, c}});
    }

    private static double[][] round(double[][] m) {
        for (double[] row : m) for (int i = 0; i < 3; i++) row[i] = Math.rint(row[i]);
        return m;
    }

    // ---- items ------------------------------------------------------------------------------------------------------

    /** What an item model id (the item_model component, e.g. "minecraft:diamond_sword") looks like. */
    public record ItemLook(Model model, boolean head) {}

    private final Map<String, ItemLook> itemLooks = new ConcurrentHashMap<>();

    public ItemLook item(String itemModel) {
        return itemLooks.computeIfAbsent(itemModel, k -> {
            String[] ns = split(k);
            JsonObject def = assets.json(ns[0], "items/" + ns[1] + ".json");
            JsonObject m = def == null ? null : def.getAsJsonObject("model");
            for (int depth = 0; m != null && depth < 8; depth++) {
                String type = m.has("type") ? m.get("type").getAsString().replace("minecraft:", "") : "model";
                switch (type) {
                    case "model" -> {
                        return new ItemLook(model(m.get("model").getAsString()), false);
                    }
                    case "special" -> {
                        String kind = m.has("model") && m.getAsJsonObject("model").has("type")
                                ? m.getAsJsonObject("model").get("type").getAsString().replace("minecraft:", "") : "";
                        if (kind.equals("player_head") || kind.equals("head")) return new ItemLook(EMPTY, true);
                        return new ItemLook(m.has("base") ? model(m.get("base").getAsString()) : EMPTY, false);
                    }
                    case "composite" -> m = AssetSource.array(m, "models") == null || m.getAsJsonArray("models").isEmpty() ? null
                            : m.getAsJsonArray("models").get(0).getAsJsonObject();
                    case "condition" -> m = m.has("on_false") ? m.getAsJsonObject("on_false") : null;
                    case "select", "range_dispatch" -> {
                        if (m.has("fallback")) m = m.getAsJsonObject("fallback");
                        else {
                            JsonArray cases = AssetSource.array(m, m.has("cases") ? "cases" : "entries");
                            m = cases == null || cases.isEmpty() ? null : cases.get(0).getAsJsonObject().getAsJsonObject("model");
                        }
                    }
                    default -> m = null;
                }
            }
            // no item definition: the old convention item/<name>
            Model legacy = model(ns[0] + ":item/" + ns[1]);
            return new ItemLook(legacy, false);
        });
    }

    // ---- maths ------------------------------------------------------------------------------------------------------

    public static double[][] rotX(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[][] {{1, 0, 0}, {0, c, -s}, {0, s, c}};
    }

    public static double[][] rotY(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[][] {{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
    }

    public static double[][] rotZ(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[][] {{c, -s, 0}, {s, c, 0}, {0, 0, 1}};
    }

    public static double[][] mul(double[][] a, double[][] b) {
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) out[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
        }
        return out;
    }
}
