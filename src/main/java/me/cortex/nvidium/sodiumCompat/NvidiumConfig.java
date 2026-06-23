package me.cortex.nvidium.sodiumCompat;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.Expose;
import me.cortex.nvidium.Nvidium;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class NvidiumConfig {
    //The options
    public int extra_rd = 100;
    public boolean enable_temporal_coherence = true;
    public int max_geometry_memory = 2048;
    public boolean automatic_memory = true;

    public int region_keep_distance = 32;

    @Expose(serialize = false, deserialize = false)
    public transient boolean disable_graph_update = false;


    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    private NvidiumConfig() {}
    public static NvidiumConfig loadOrCreate() {
        var path = getConfigPath();
        if (Files.exists(path)) {
            try (FileReader reader = new FileReader(path.toFile())) {
                //fromJson throws JsonParseException (a RuntimeException) on malformed JSON and
                //returns null on an empty/"null" file; either previously crashed startup or left
                //Nvidium.config null. Fall back to defaults in both cases.
                NvidiumConfig parsed = GSON.fromJson(reader, NvidiumConfig.class);
                if (parsed != null) {
                    return parsed;
                }
                Nvidium.LOGGER.error("Config file was empty or invalid, using defaults");
            } catch (IOException | JsonParseException e) {
                Nvidium.LOGGER.error("Could not parse config, using defaults", e);
            }
        }
        return new NvidiumConfig();
    }

    public void save() {
        //Write to a sibling temp file then move it into place, so a crash mid-write
        //leaves the previous config intact instead of a truncated/corrupt file.
        var path = getConfigPath();
        var tmp = path.resolveSibling("nvidium-config.json.tmp");
        try {
            Files.writeString(tmp, GSON.toJson(this));
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException atomicUnsupported) {
                //Some filesystems can't do an atomic rename; fall back to a plain replace.
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Nvidium.LOGGER.error("Failed to write config file", e);
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanupFailure) {
                Nvidium.LOGGER.warn("Could not remove temp config file", cleanupFailure);
            }
        }
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance()
                .getConfigDir()
                .resolve("nvidium-config.json");
    }
}
