package gg.vape.config;

import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import gg.vape.Vape;
import gg.vape.api.ApiHttpClient;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class LocalConfigStorage {
    private static final String CONFIG_FILE_NAME = "config.json";
    private static final Path CONFIG_DIRECTORY = LocalConfigStorage.resolveConfigDirectory();
    private static final Path CONFIG_FILE = CONFIG_DIRECTORY.resolve(CONFIG_FILE_NAME);
    private static final Path CONFIG_TEMP_FILE = CONFIG_DIRECTORY.resolve(CONFIG_FILE_NAME + ".tmp");

    private LocalConfigStorage() {
    }

    private static Path resolveConfigDirectory() {
        String osName = System.getProperty("os.name", "");
        if (osName.toLowerCase().contains("win")) {
            return Paths.get("C:\\vape\\cfgs");
        }
        return Paths.get(System.getProperty("user.home", ".")).resolve("vape").resolve("cfgs");
    }

    public static Path getConfigFile() {
        return CONFIG_FILE;
    }

    public static synchronized void save(JsonObject config) {
        if (config == null) {
            return;
        }
        try {
            Files.createDirectories(CONFIG_DIRECTORY);
            byte[] data = ApiHttpClient.GSON.toJson(config).getBytes(StandardCharsets.UTF_8);
            Files.write(CONFIG_TEMP_FILE, data);
            try {
                Files.move(CONFIG_TEMP_FILE, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            }
            catch (Exception atomicMoveFailed) {
                Files.move(CONFIG_TEMP_FILE, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
            Vape.debugLog("Saved local config to " + CONFIG_FILE.toAbsolutePath());
        }
        catch (IOException exception) {
            Vape.logThrowable(exception);
        }
    }

    public static synchronized JsonObject load() {
        if (!Files.exists(CONFIG_FILE)) {
            return null;
        }
        try {
            String content = new String(Files.readAllBytes(CONFIG_FILE), StandardCharsets.UTF_8);
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