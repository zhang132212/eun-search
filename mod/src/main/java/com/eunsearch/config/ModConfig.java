package com.eunsearch.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.eunsearch.EunSearchMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public class ModConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private List<ScanEntry> scans = new ArrayList<>();

    public List<ScanEntry> getScans() {
        return scans;
    }

    public ScanEntry findScanByTag(String tag) {
        return scans.stream()
                .filter(s -> s.tag.equalsIgnoreCase(tag))
                .findFirst()
                .orElse(null);
    }

    public void addScan(ScanEntry entry) {
        scans.removeIf(s -> s.tag.equalsIgnoreCase(entry.tag));
        scans.add(entry);
    }

    public boolean removeScan(String tag) {
        return scans.removeIf(s -> s.tag.equalsIgnoreCase(tag));
    }

    public static ModConfig load() {
        Path path = getConfigPath();
        if (Files.exists(path)) {
            try {
                String json = Files.readString(path);
                return GSON.fromJson(json, ModConfig.class);
            } catch (IOException e) {
                EunSearchMod.LOGGER.error("[EunSearch] 读取配置文件失败", e);
            }
        }
        ModConfig config = new ModConfig();
        config.save();
        return config;
    }

    public void save() {
        Path path = getConfigPath();
        try {
            Files.createDirectories(path.getParent());
            String json = GSON.toJson(this);
            Files.writeString(path, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            EunSearchMod.LOGGER.error("[EunSearch] 保存配置文件失败", e);
        }
    }

    public boolean reload() {
        Path path = getConfigPath();
        if (!Files.exists(path)) return false;
        try {
            String json = Files.readString(path);
            ModConfig fresh = GSON.fromJson(json, ModConfig.class);
            this.scans = fresh.scans != null ? fresh.scans : new ArrayList<>();
            EunSearchMod.LOGGER.info("[EunSearch] 配置文件已热重载 ({} 个扫描条目)", this.scans.size());
            return true;
        } catch (IOException e) {
            EunSearchMod.LOGGER.error("[EunSearch] 热重载配置文件失败", e);
            return false;
        }
    }

    private static Path getConfigPath() {
        return Path.of("config", "eun_search.json");
    }
}
