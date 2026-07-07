package com.boshys.bteutils.console;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Manages the console message detection configuration.
 * Stored in config/boshysbteutils/console_messages.json
 *
 * Format:
 * {
 *   "patterns": [
 *     "Teleported to",
 *     "other pattern"
 *   ]
 * }
 */
public class ConsoleMessageConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = Path.of("config/boshysbteutils/console_messages.json");

    private final List<String> patterns = new ArrayList<>();

    public ConsoleMessageConfig() {
        load();
    }

    /**
     * Returns an unmodifiable view of the current patterns.
     */
    public List<String> getPatterns() {
        return Collections.unmodifiableList(patterns);
    }

    /**
     * Adds a new pattern if it doesn't already exist.
     * @return true if the pattern was added, false if it already existed
     */
    public boolean addPattern(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) {
            return false;
        }
        String trimmed = pattern.trim();
        if (patterns.contains(trimmed)) {
            return false;
        }
        patterns.add(trimmed);
        save();
        return true;
    }

    /**
     * Removes a pattern.
     * @return true if the pattern was removed
     */
    public boolean removePattern(String pattern) {
        boolean removed = patterns.remove(pattern);
        if (removed) {
            save();
        }
        return removed;
    }

    /**
     * Loads patterns from disk. Creates default config if missing.
     */
    public void load() {
        patterns.clear();

        File file = CONFIG_PATH.toFile();
        if (!file.exists()) {
            // Create default config
            patterns.add("Teleported to 0");
            patterns.add("Teleported to 1");
            patterns.add("Teleported to 2");
            patterns.add("Teleported to 3");
            patterns.add("Teleported to 4");
            patterns.add("Teleported to 5");
            patterns.add("Teleported to 6");
            patterns.add("Teleported to 7");
            patterns.add("Teleported to 8");
            patterns.add("Teleported to 9");
            patterns.add("Teleported to -");
            save();
            return;
        }

        try (Reader reader = new FileReader(file)) {
            Map<String, List<String>> data = GSON.fromJson(reader, new TypeToken<Map<String, List<String>>>(){}.getType());
            if (data != null && data.containsKey("patterns")) {
                List<String> loaded = data.get("patterns");
                if (loaded != null) {
                    for (String p : loaded) {
                        if (p != null && !p.trim().isEmpty()) {
                            patterns.add(p.trim());
                        }
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[Boshys-bt-utils] Failed to load console message config: " + e.getMessage());
        }

        // Ensure default pattern exists
        if (patterns.isEmpty()) {
            patterns.add("Teleported to 0");
            patterns.add("Teleported to 1");
            patterns.add("Teleported to 2");
            patterns.add("Teleported to 3");
            patterns.add("Teleported to 4");
            patterns.add("Teleported to 5");
            patterns.add("Teleported to 6");
            patterns.add("Teleported to 7");
            patterns.add("Teleported to 8");
            patterns.add("Teleported to 9");
            patterns.add("Teleported to -");
            save();
        }
    }

    /**
     * Saves patterns to disk.
     */
    public void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Map<String, List<String>> data = new LinkedHashMap<>();
            data.put("patterns", new ArrayList<>(patterns));
            try (Writer writer = new FileWriter(CONFIG_PATH.toFile())) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            System.err.println("[Boshys-bt-utils] Failed to save console message config: " + e.getMessage());
        }
    }
}