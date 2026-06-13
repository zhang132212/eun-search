package com.eunsearch.test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.eunsearch.config.ModConfig;
import com.eunsearch.config.ScanEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ModConfigTest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static int passed = 0, failed = 0;

    public static void main(String[] args) throws Exception {
        runAll();
        System.out.println("\n========== RESULTS ==========");
        System.out.printf("Passed: %d  Failed: %d%n", passed, failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    static void runAll() throws IOException {
        testScanEntryMinMax();
        testScanEntryMinMaxSwapped();
        testAddDuplicateScan();
        testRemoveNonExistentScan();
        testFindScanByTag();
        testItemIdNormalization();
        testCountFormatting();
    }

    static void testScanEntryMinMax() {
        ScanEntry e = new ScanEntry("t", "minecraft:stone", 0, 0, 0, 100, 64, 100, "dim");
        assertThat(e.minX() == 0, "minX");
        assertThat(e.maxX() == 100, "maxX");
        assertThat(e.minY() == 0, "minY");
        assertThat(e.maxY() == 64, "maxY");
        assertThat(e.minZ() == 0, "minZ");
        assertThat(e.maxZ() == 100, "maxZ");
        assertThat(e.coversPosition(50, 32, 50), "covers center");
        assertThat(!e.coversPosition(-1, 0, 0), "outside X");
        assertThat(!e.coversPosition(0, 65, 0), "outside Y");
        assertThat(!e.coversPosition(0, 0, 101), "outside Z");
        pass("testScanEntryMinMax");
    }

    static void testScanEntryMinMaxSwapped() {
        ScanEntry e = new ScanEntry("t", "item", 100, 80, 100, 0, 0, 0, "dim");
        assertThat(e.minX() == 0, "swapped minX");
        assertThat(e.maxX() == 100, "swapped maxX");
        assertThat(e.minY() == 0, "swapped minY");
        assertThat(e.maxY() == 80, "swapped maxY");
        assertThat(e.minZ() == 0, "swapped minZ");
        assertThat(e.maxZ() == 100, "swapped maxZ");
        pass("testScanEntryMinMaxSwapped");
    }

    static void testAddDuplicateScan() throws IOException {
        Path tmpDir = Files.createTempDirectory("eun_search_test");

        ModConfig cfg = new ModConfig();
        cfg.addScan(new ScanEntry("dup", "item1", 0, 0, 0, 1, 1, 1, "d"));
        cfg.addScan(new ScanEntry("dup", "item2", 0, 0, 0, 1, 1, 1, "d"));
        assertThat(cfg.getScans().size() == 1, "duplicate tag replaced");
        assertThat(cfg.getScans().get(0).item.equals("item2"), "latest entry kept");

        pass("testAddDuplicateScan");
        cleanup(tmpDir);
    }

    static void testRemoveNonExistentScan() {
        ModConfig cfg = new ModConfig();
        assertThat(!cfg.removeScan("nonexistent"), "remove nonexistent returns false");
        pass("testRemoveNonExistentScan");
    }

    static void testFindScanByTag() {
        ModConfig cfg = new ModConfig();
        cfg.addScan(new ScanEntry("CARROT", "item", 0, 0, 0, 1, 1, 1, "d"));
        assertThat(cfg.findScanByTag("carrot") != null, "case-insensitive find");
        assertThat(cfg.findScanByTag("CARROT") != null, "exact case find");
        assertThat(cfg.findScanByTag("none") == null, "missing returns null");
        pass("testFindScanByTag");
    }

    static void testItemIdNormalization() {
        assertThat(normalize("carrot").equals("minecraft:carrot"), "add namespace");
        assertThat(normalize("minecraft:carrot").equals("minecraft:carrot"), "keep namespace");
        assertThat(normalize("Diamond").equals("minecraft:diamond"), "lowercase");
        assertThat(normalize("MOD:Item").equals("mod:item"), "lowercase with mod");
        pass("testItemIdNormalization");
    }

    static void testCountFormatting() {
        assertThat(formatCount(0, 0).contains("0"), "zero count");
        assertThat(formatCount(64, 1).contains("64"), "small count");
        assertThat(formatCount(1728, 27).contains("1"), "exact box");
        assertThat(formatCount(4000, 63).contains("2"), "multi box");
        assertThat(formatCount(3456, 54).contains("2"), "exact 2 boxes");
        pass("testCountFormatting");
    }

    static String normalize(String item) {
        if (item == null || item.isEmpty()) return item;
        if (!item.contains(":")) return "minecraft:" + item.toLowerCase();
        return item.toLowerCase();
    }

    static String formatCount(int total, int slots) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%,d", total)).append("个");
        int boxes = total / 1728;
        int remain = total % 1728;
        if (total >= 1728) {
            sb.append(" (").append(boxes).append("盒");
            if (remain > 0) sb.append("+").append(remain);
            sb.append(")");
        }
        sb.append(" | 占").append(slots).append("格");
        return sb.toString();
    }

    static void assertThat(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAIL: " + msg);
    }

    static void pass(String name) { passed++; System.out.println("  PASS " + name); }
    static void fail(String name, Throwable e) { failed++; System.out.println("  FAIL " + name + ": " + e.getMessage()); }

    static void cleanup(Path dir) {
        if (dir != null) {
            try { Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} }); } catch (Exception ignored) {}
        }
    }
}
