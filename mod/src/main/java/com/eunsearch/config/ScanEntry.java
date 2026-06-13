package com.eunsearch.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ScanEntry {

    public String tag;
    public String item;
    public List<String> items;
    public boolean allItems = false;
    public int x1, y1, z1;
    public int x2, y2, z2;
    public String dimension = "minecraft:overworld";
    public List<int[]> ranges = new ArrayList<>();

    public ScanEntry() {}

    public ScanEntry(String tag, String item, int x1, int y1, int z1, int x2, int y2, int z2, String dimension) {
        this.tag = tag;
        this.item = item;
        this.x1 = x1;
        this.y1 = y1;
        this.z1 = z1;
        this.x2 = x2;
        this.y2 = y2;
        this.z2 = z2;
        this.dimension = dimension;
    }

    public List<String> getItems() {
        if (items != null && !items.isEmpty()) return items;
        if (item != null && !item.isEmpty()) return List.of(item);
        return List.of();
    }

    public int minX() { return Math.min(x1, x2); }
    public int minY() { return Math.min(y1, y2); }
    public int minZ() { return Math.min(z1, z2); }
    public int maxX() { return Math.max(x1, x2); }
    public int maxY() { return Math.max(y1, y2); }
    public int maxZ() { return Math.max(z1, z2); }

    public boolean coversPosition(int x, int y, int z) {
        return x >= minX() && x <= maxX()
                && y >= minY() && y <= maxY()
                && z >= minZ() && z <= maxZ();
    }
}
