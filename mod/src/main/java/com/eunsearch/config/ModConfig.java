package com.eunsearch.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.eunsearch.EunSearchMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
        if (tag == null || scans == null) return null;
        return scans.stream()
                .filter(s -> s != null && s.tag != null && s.tag.equalsIgnoreCase(tag))
                .findFirst()
                .orElse(null);
    }

    public void addScan(ScanEntry entry) {
        if (entry == null || entry.tag == null || entry.tag.isBlank()) return;
        if (scans == null) scans = new ArrayList<>();
        scans.removeIf(s -> s != null && s.tag != null && s.tag.equalsIgnoreCase(entry.tag));
        scans.add(entry);
    }

    public boolean removeScan(String tag) {
        if (tag == null || scans == null) return false;
        return scans.removeIf(s -> s != null && s.tag != null && s.tag.equalsIgnoreCase(tag));
    }

    public static ModConfig load() {
        return load(getConfigPath());
    }

    static ModConfig load(Path path) {
        if (Files.exists(path)) {
            try {
                ModConfig parsed = parse(Files.readString(path));
                if (parsed != null) return parsed;
                EunSearchMod.LOGGER.error("[EunSearch] 配置文件内容无效，使用空配置");
            } catch (IOException | RuntimeException e) {
                EunSearchMod.LOGGER.error("[EunSearch] 读取配置文件失败，使用空配置", e);
            }
            // Preserve existing data for repair instead of overwriting it on parse failure.
            return new ModConfig();
        }
        ModConfig config = new ModConfig();
        config.save(path);
        return config;
    }

    public void save() {
        save(getConfigPath());
    }

    private void save(Path path) {
        try {
            Files.createDirectories(path.getParent());
            String json = GSON.toJson(this);
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            EunSearchMod.LOGGER.error("[EunSearch] 保存配置文件失败", e);
        }
    }

    public boolean reload() {
        Path path = getConfigPath();
        if (!Files.exists(path)) return false;
        try {
            ModConfig fresh = parse(Files.readString(path));
            if (fresh == null) {
                EunSearchMod.LOGGER.error("[EunSearch] 热重载配置文件失败：内容无效");
                return false;
            }
            this.scans = fresh.scans;
            EunSearchMod.LOGGER.info("[EunSearch] 配置文件已热重载 ({} 个扫描条目)", this.scans.size());
            return true;
        } catch (IOException | RuntimeException e) {
            EunSearchMod.LOGGER.error("[EunSearch] 热重载配置文件失败", e);
            return false;
        }
    }

    private static ModConfig parse(String json) {
        ModConfig parsed = GSON.fromJson(json, ModConfig.class);
        if (parsed == null) return null;
        if (parsed.scans == null) parsed.scans = new ArrayList<>();
        parsed.scans.removeIf(s -> s == null || s.tag == null || s.tag.isBlank());
        return parsed;
    }

    private static Path getConfigPath() {
        return Path.of("config", "eun_search.json");
    }
}
