package rabbitrace.settings;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The settings and their persistence.
 *
 * <p>Settings are not match state: changing one never affects
 * {@code (seed, commands) -> state}. They are
 * saved when Ajustes is left and on app close, never per slider tick
 * The file is schema {@value #VERSION}
 * ({@code {"v":1,"master":80...}}); missing or unreadable means defaults and the
 * game still starts, and unknown keys survive a rewrite.
 */
public final class Settings {

    public static final int VERSION = 1;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> KNOWN =
            Set.of("master", "music", "sfx", "muted", "fullscreen", "lang");

    private final Path path;
    /** Keys this file carried that does not define; written back unchanged. */
    private final Map<String, JsonElement> unknown = new LinkedHashMap<>();

    private int master;
    private int music;
    private int sfx;
    private boolean muted;
    private boolean fullscreen;
    private String lang;

    private Settings(Path path) {
        this.path = path;
        applyDefaults();
    }

    /**   defaults, with no file behind them. */
    public static Settings defaults() {
        return new Settings(null);
    }

    /**
     * {@code -Dsettings.path} when set, else {@code %APPDATA%\RabbitRace\settings.json},
     * else {@code <user.home>/.rabbit-race/settings.json}.
     */
    public static Path defaultPath() {
        String override = System.getProperty("settings.path");
        if (override != null && !override.isBlank()) {
            return Path.of(override.trim());
        }
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, "RabbitRace", "settings.json");
        }
        return Path.of(System.getProperty("user.home"), ".rabbit-race", "settings.json");
    }

    /**
     * Loads the file at {@code path}. A missing file is defaults and is not an
     * error; an unreadable path (including a directory), malformed JSON or an
     * unknown {@code v} is defaults plus one log line, never a failed start
     *
     */
    public static Settings load(Path path) {
        Settings settings = new Settings(path);
        if (path == null || !Files.exists(path)) {
            return settings;
        }
        if (!Files.isRegularFile(path)) {
            unreadable(path, "it is not a regular file");
            return settings;
        }
        try {
            JsonObject json = JsonParser.parseString(
                    Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            int version = json.has("v") ? json.get("v").getAsInt() : -1;
            if (version != VERSION) {
                throw new IllegalArgumentException("unknown v " + version);
            }
            settings.master = volume(json, "master", settings.master);
            settings.music = volume(json, "music", settings.music);
            settings.sfx = volume(json, "sfx", settings.sfx);
            settings.muted = flag(json, "muted", settings.muted);
            settings.fullscreen = flag(json, "fullscreen", settings.fullscreen);
            settings.lang = text(json, "lang", settings.lang);
            for (String key : json.keySet()) {
                if (!"v".equals(key) && !KNOWN.contains(key)) {
                    settings.unknown.put(key, json.get(key));
                }
            }
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            unreadable(path, message);
            return new Settings(path);
        }
        return settings;
    }

    /** one log line: the launch continues on defaults. */
    private static void unreadable(Path path, String reason) {
        System.err.println("settings: " + path + " is unreadable (" + reason
                + "); starting on defaults");
    }

    public Path path() {
        return path;
    }

    public int master() {
        return master;
    }

    public int music() {
        return music;
    }

    public int sfx() {
        return sfx;
    }

    public boolean muted() {
        return muted;
    }

    public boolean fullscreen() {
        return fullscreen;
    }

    public String lang() {
        return lang;
    }

    /** The typed value of one setting, for the {@code settingsChanged} event. */
    public Object value(String key) {
        return switch (key) {
            case "master" -> master;
            case "music" -> music;
            case "sfx" -> sfx;
            case "muted" -> muted;
            case "fullscreen" -> fullscreen;
            case "lang" -> lang;
            default -> throw new IllegalArgumentException("unknown setting '" + key + "'");
        };
    }

    /** {@code state.settings}: the values exactly as persisted, without {@code v}. */
    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("master", master);
        json.addProperty("music", music);
        json.addProperty("sfx", sfx);
        json.addProperty("muted", muted);
        json.addProperty("fullscreen", fullscreen);
        json.addProperty("lang", lang);
        return json;
    }

    /**
     * A change as if made in Ajustes: volumes are 0-100 in steps of 5, the two
     * switches take {@code true}/{@code false}. {@code lang} is not editable in v1.
     */
    public void set(String key, String raw) {
        switch (key) {
            case "master" -> master = volume(key, raw);
            case "music" -> music = volume(key, raw);
            case "sfx" -> sfx = volume(key, raw);
            case "muted" -> muted = flag(key, raw);
            case "fullscreen" -> fullscreen = flag(key, raw);
            default -> throw new IllegalArgumentException("set does not accept '" + key
                    + "'; it takes master, music, sfx, muted or fullscreen");
        }
    }

    /** {@code Restablecer}: 80/40/70, mute off and windowed, without asking. */
    public void reset() {
        master = 80;
        music = 40;
        sfx = 70;
        muted = false;
        fullscreen = false;
    }

    /** Writes the file atomically: a sibling temp file moved over the original. */
    public void save() throws IOException {
        if (path == null) {
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("v", VERSION);
        toJson().entrySet().forEach(entry -> json.add(entry.getKey(), entry.getValue()));
        unknown.forEach(json::add);

        Path file = path.toAbsolutePath();
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // the next save rewrites a valid file even when a
        // directory took the path. Only an empty directory can be removed
        // without destroying something; a non-empty one fails and saveQuietly
        // reports it rather than deleting its contents.
        if (Files.isDirectory(file)) {
            Files.delete(file);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(json) + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Saves and reports a failure instead of letting it end the app. */
    public void saveQuietly() {
        try {
            save();
        } catch (IOException | RuntimeException e) {
            System.err.println("settings: cannot write " + path + ": " + e.getMessage());
        }
    }

    private void applyDefaults() {
        master = 80;
        music = 40;
        sfx = 70;
        muted = false;
        fullscreen = false;
        lang = "es";
    }

    private static int volume(String key, String raw) {
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " takes an integer 0-100 in steps of 5: " + raw);
        }
        if (value < 0 || value > 100 || value % 5 != 0) {
            throw new IllegalArgumentException(key + " takes an integer 0-100 in steps of 5: " + raw);
        }
        return value;
    }

    private static boolean flag(String key, String raw) {
        String value = raw.trim();
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new IllegalArgumentException(key + " takes true or false: " + raw);
        }
        return Boolean.parseBoolean(value);
    }

    private static int volume(JsonObject json, String key, int fallback) {
        if (!json.has(key)) {
            return fallback;
        }
        JsonElement element = json.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(key + " is not a number");
        }
        int value = element.getAsInt();
        if (value < 0 || value > 100 || value % 5 != 0) {
            throw new IllegalArgumentException(key + " is out of range: " + value);
        }
        return value;
    }

    private static boolean flag(JsonObject json, String key, boolean fallback) {
        if (!json.has(key)) {
            return fallback;
        }
        JsonElement element = json.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(key + " is not a boolean");
        }
        return element.getAsBoolean();
    }

    private static String text(JsonObject json, String key, String fallback) {
        if (!json.has(key)) {
            return fallback;
        }
        JsonElement element = json.get(key);
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw new IllegalArgumentException(key + " is not a language tag");
        }
        return element.getAsString();
    }
}
