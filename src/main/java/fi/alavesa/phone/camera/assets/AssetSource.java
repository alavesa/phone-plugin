package fi.alavesa.phone.camera.assets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Where photos get block textures and models from. A server has none of the client's assets, so the vanilla ones
 * come from the client jar of the same Minecraft version, downloaded once from Mojang (the same files every player
 * already has) into {@code plugins/Phone/cache/}. Resource packs listed in {@code camera.textures.extra-packs} are
 * searched first, so custom item models (display-entity props, ragdolls, RP Engine models) and retextures show in
 * photos too. Nothing of it is ever sent anywhere.
 *
 * <p>Thread safety: open sources are read-only {@link ZipFile}s or folders, safe to read from any thread.
 */
public final class AssetSource {

    private static final String MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private final Logger log;
    private final List<ZipFile> zips = new ArrayList<>();
    private final List<Path> folders = new ArrayList<>();
    private volatile boolean vanilla;

    public AssetSource(Logger log) {
        this.log = log;
    }

    /** True once the vanilla assets are available (photos are then textured). */
    public boolean ready() {
        return vanilla;
    }

    /**
     * Opens the extra packs and the cached client jar, downloading it first when allowed. Blocks: run it off the
     * main thread.
     */
    public void open(File cacheDir, String version, boolean download, List<File> extraPacks) {
        for (File pack : extraPacks) {
            try {
                if (pack.isDirectory()) folders.add(pack.toPath());
                else if (pack.isFile()) zips.add(new ZipFile(pack));
                else log.warning("camera.textures.extra-packs: " + pack + " does not exist");
            } catch (IOException e) {
                log.warning("camera.textures.extra-packs: " + pack + " is not a zip (" + e.getMessage() + ")");
            }
        }
        File jar = new File(cacheDir, "client-" + version + ".jar");
        try {
            if (!jar.isFile()) {
                if (!download) {
                    log.info("Photos use block colours: " + jar + " is missing and camera.textures.download is off");
                    return;
                }
                fetch(version, jar);
            }
            zips.add(new ZipFile(jar));
            vanilla = true;
        } catch (Exception e) {
            log.log(Level.WARNING, "Photos use block colours: the " + version + " client assets could not be loaded (" + e.getMessage() + ")");
        }
    }

    public void close() {
        vanilla = false;
        for (ZipFile z : zips) {
            try {
                z.close();
            } catch (IOException ignored) {
                // closing on shutdown
            }
        }
        zips.clear();
        folders.clear();
    }

    private void fetch(String version, File jar) throws Exception {
        log.info("Downloading the Minecraft " + version + " client jar from Mojang for photo textures (once, about 40 MB)...");
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
        JsonObject manifest = json(http, MANIFEST);
        String url = null;
        for (JsonElement v : manifest.getAsJsonArray("versions")) {
            if (v.getAsJsonObject().get("id").getAsString().equals(version)) url = v.getAsJsonObject().get("url").getAsString();
        }
        if (url == null) throw new IOException("Mojang does not list version " + version);
        JsonObject client = json(http, url).getAsJsonObject("downloads").getAsJsonObject("client");
        String sha1 = client.get("sha1").getAsString();
        Files.createDirectories(jar.getParentFile().toPath());
        Path tmp = Files.createTempFile(jar.getParentFile().toPath(), "client", ".part");
        HttpResponse<Path> response = http.send(HttpRequest.newBuilder(URI.create(client.get("url").getAsString()))
                .timeout(Duration.ofMinutes(10)).build(), HttpResponse.BodyHandlers.ofFile(tmp));
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        try (InputStream in = Files.newInputStream(tmp)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        if (!HexFormat.of().formatHex(md.digest()).equals(sha1)) {
            Files.deleteIfExists(tmp);
            throw new IOException("the download does not match Mojang's SHA-1");
        }
        Files.move(tmp, jar.toPath(), StandardCopyOption.REPLACE_EXISTING);
        log.info("Saved " + jar + "; photos are textured from now on");
    }

    private static JsonObject json(HttpClient http, String url) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    /** The bytes of {@code assets/<namespace>/<path>}, from the first source that has it; null if none. */
    public byte[] read(String namespace, String path) {
        String name = "assets/" + namespace + "/" + path;
        for (Path folder : folders) {
            Path p = folder.resolve(name);
            try {
                if (Files.isRegularFile(p)) return Files.readAllBytes(p);
            } catch (IOException ignored) {
                // try the next source
            }
        }
        for (ZipFile zip : zips) {
            ZipEntry e = zip.getEntry(name);
            if (e == null) continue;
            try (InputStream in = zip.getInputStream(e)) {
                return in.readAllBytes();
            } catch (IOException ignored) {
                // try the next source
            }
        }
        return null;
    }

    /** A JSON file of the assets, or null. */
    public JsonObject json(String namespace, String path) {
        byte[] bytes = read(namespace, path);
        if (bytes == null) return null;
        try (InputStreamReader r = new InputStreamReader(new java.io.ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            JsonElement e = JsonParser.parseReader(r);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static JsonArray array(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }
}
