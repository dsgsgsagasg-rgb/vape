package gg.vape.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import gg.vape.Vape;
import gg.vape.api.ApiHttpClient;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class LocalConfigStorage {
    private static final String PROFILE_FILE_EXTENSION = ".vape";
    private static final String GLOBALS_FILE_NAME = "config.json";
    private static final Path CONFIG_DIRECTORY = LocalConfigStorage.resolveConfigDirectory();
    private static final Pattern INVALID_FILE_NAME_CHARS = Pattern.compile("[<>:\"/\\\\|?*\\u0000-\\u001f]");

    private LocalConfigStorage() {
    }

    private static Path resolveConfigDirectory() {
        String osName = System.getProperty("os.name", "");
        if (osName.toLowerCase().contains("win")) {
            return Paths.get("C:\\vape\\cfg");
        }
        return Paths.get(System.getProperty("user.home", ".")).resolve("vape").resolve("cfg");
    }

    public static synchronized void save(JsonObject config) {
        if (config == null) {
            return;
        }
        try {
            Files.createDirectories(CONFIG_DIRECTORY);
            deleteIfExists(CONFIG_DIRECTORY.resolve(GLOBALS_FILE_NAME));
            Set<String> activeProfileUuids = new HashSet<String>();
            Map<String, String> expectedFileNames = new HashMap<String, String>();
            Set<String> usedFileNames = new HashSet<String>();
            JsonObject profiles = config.has("profiles") ? config.getAsJsonObject("profiles") : null;
            if (profiles != null) {
                for (Map.Entry<String, JsonElement> entry : profiles.entrySet()) {
                    JsonElement value = entry.getValue();
                    if (value == null || !value.isJsonObject()) {
                        continue;
                    }
                    JsonObject profile = value.getAsJsonObject();
                    String uuid = ConfigJsonUtils.getString(profile, "uuid");
                    if (uuid == null) {
                        uuid = entry.getKey();
                    }
                    if (uuid != null) {
                        activeProfileUuids.add(uuid);
                    }
                    JsonObject snapshot = config.deepCopy();
                    snapshot.addProperty("uuid", uuid);
                    snapshot.addProperty("name", ConfigJsonUtils.getString(profile, "name"));
                    String baseName = profileFileName(ConfigJsonUtils.getString(profile, "name"), uuid);
                    String fileName = baseName;
                    int suffix = 2;
                    while (!usedFileNames.add(fileName)) {
                        String stripped = baseName.substring(0, baseName.length() - PROFILE_FILE_EXTENSION.length());
                        fileName = stripped + "-" + suffix + PROFILE_FILE_EXTENSION;
                        ++suffix;
                    }
                    if (uuid != null) {
                        expectedFileNames.put(uuid, fileName);
                    }
                    try {
                        saveJson(CONFIG_DIRECTORY.resolve(fileName), snapshot);
                    }
                    catch (IOException exception) {
                        Vape.logThrowable(exception);
                    }
                }
            }
            pruneProfileFiles(activeProfileUuids, expectedFileNames);
            Vape.debugLog("Saved local config to " + CONFIG_DIRECTORY.toAbsolutePath() + " (" + activeProfileUuids.size()
                    + " profile file(s))");
        }
        catch (Exception exception) {
            Vape.logThrowable(exception);
        }
    }

    public static synchronized JsonObject load() {
        if (!Files.exists(CONFIG_DIRECTORY)) {
            return null;
        }
        Path newestFile = null;
        long newestTimestamp = -1L;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(CONFIG_DIRECTORY, "*" + PROFILE_FILE_EXTENSION)) {
            for (Path file : stream) {
                try {
                    long timestamp = Files.getLastModifiedTime(file).toMillis();
                    if (timestamp > newestTimestamp) {
                        newestTimestamp = timestamp;
                        newestFile = file;
                    }
                }
                catch (Exception ignored) {
                }
            }
        }
        catch (Exception exception) {
            Vape.logThrowable(exception);
        }
        if (newestFile == null) {
            return null;
        }
        JsonObject config = readJson(newestFile);
        if (config == null) {
            return null;
        }
        if (config.has("profiles")) {
            config.remove("uuid");
            config.remove("name");
            return config;
        }
        JsonObject wrapped = new JsonObject();
        JsonObject profiles = new JsonObject();
        String uuid = ConfigJsonUtils.getString(config, "uuid");
        profiles.add(uuid == null ? "single" : uuid, config);
        wrapped.add("profiles", profiles);
        return wrapped;
    }

    private static void pruneProfileFiles(Set<String> activeProfileUuids, Map<String, String> expectedFileNames) {
        if (activeProfileUuids == null || activeProfileUuids.isEmpty()) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(CONFIG_DIRECTORY, "*" + PROFILE_FILE_EXTENSION)) {
            for (Path file : stream) {
                JsonObject loaded = readJson(file);
                if (loaded == null) {
                    continue;
                }
                String uuid = ConfigJsonUtils.getString(loaded, "uuid");
                if (uuid == null) {
                    continue;
                }
                String expectedName = expectedFileNames.get(uuid);
                if (!activeProfileUuids.contains(uuid)
                        || expectedName != null && !file.getFileName().toString().equals(expectedName)) {
                    try {
                        Files.delete(file);
                    }
                    catch (IOException ignored) {
                    }
                }
            }
        }
        catch (Exception ignored) {
        }
    }

    private static String profileFileName(String name, String uuid) {
        String safeName = name == null ? null : name.trim();
        if (safeName == null || safeName.isEmpty()) {
            safeName = uuid != null && !uuid.isEmpty() ? "profile-" + uuid.substring(0, Math.min(8, uuid.length())) : "profile";
        }
        safeName = INVALID_FILE_NAME_CHARS.matcher(safeName).replaceAll("_");
        safeName = safeName.replaceAll("[. ]+$", "");
        if (safeName.isEmpty()) {
            safeName = "profile";
        }
        return safeName + PROFILE_FILE_EXTENSION;
    }

    private static void saveJson(Path target, JsonObject json) throws IOException {
        if (json == null || target == null) {
            return;
        }
        byte[] data = ApiHttpClient.GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
        Path tempFile = CONFIG_DIRECTORY.resolve(target.getFileName().toString() + ".tmp");
        Files.write(tempFile, data);
        try {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (Exception atomicMoveFailed) {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteIfExists(Path target) {
        try {
            Files.deleteIfExists(target);
        }
        catch (Exception ignored) {
        }
    }

    private static JsonObject readJson(Path file) {
        try {
            if (file == null || !Files.exists(file)) {
                return null;
            }
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            JsonReader reader = new JsonReader(new StringReader(content));
            reader.setLenient(true);
            return ApiHttpClient.GSON.fromJson(reader, JsonObject.class);
        }
        catch (Exception exception) {
            Vape.logThrowable(exception);
            return null;
        }
    }
}