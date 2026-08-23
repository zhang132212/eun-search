package com.eunsearch.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ClientConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ClientConfig INSTANCE;

    public int scanRangeBlocks = 64;
    public int maxRequestsPerTick = 5;
    public boolean showRangeOverlay = true;
    public int overlayColor = 0x40FFFFFF;
    public boolean autoHighlight = true;
    public int highlightTimeoutSeconds = 10;

    public static ClientConfig load() {
        Path path = getPath();
        if (Files.exists(path)) {
            try {
                INSTANCE = GSON.fromJson(Files.readString(path), ClientConfig.class);
            } catch (Exception e) {
                INSTANCE = new ClientConfig();
            }
        } else {
            INSTANCE = new ClientConfig();
        }
        if (INSTANCE == null) {
            INSTANCE = new ClientConfig();
        }
        INSTANCE.save();
        return INSTANCE;
    }

    public static ClientConfig get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public void save() {
        try {
            Path path = getPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (IOException e) {
            // silently ignore config save errors in this first prototype
        }
    }

    private static Path getPath() {
        return Path.of("config", "eun_search_client.json");
    }
}
