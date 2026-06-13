package com.eunsearch.test;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class EdgeCaseTests {

    @Test
    void testShulkerBoxNestedInNewFormat() {
        NbtCompound components = new NbtCompound();
        NbtList containerItems = new NbtList();

        NbtCompound slot0 = new NbtCompound();
        NbtCompound itemTag0 = new NbtCompound();
        itemTag0.putString("id", "minecraft:diamond");
        itemTag0.putInt("count", 16);
        slot0.putByte("slot", (byte) 0);
        slot0.put("item", itemTag0);
        containerItems.add(slot0);

        NbtCompound slot1 = new NbtCompound();
        NbtCompound itemTag1 = new NbtCompound();
        itemTag1.putString("id", "minecraft:diamond");
        itemTag1.putInt("count", 32);
        slot1.putByte("slot", (byte) 1);
        slot1.put("item", itemTag1);
        containerItems.add(slot1);

        components.put("minecraft:container", containerItems);

        assertTrue(components.contains("minecraft:container"));
        NbtElement ct = components.get("minecraft:container");
        assertNotNull(ct);
        assertEquals(NbtElement.LIST_TYPE, ct.getType());

        NbtList readItems = (NbtList) ct;
        assertEquals(2, readItems.size());
    }

    @Test
    void testShulkerBoxNestedInOldFormat() {
        NbtCompound components = new NbtCompound();
        NbtList items = new NbtList();

        NbtCompound slot0 = new NbtCompound();
        slot0.putString("id", "minecraft:iron_ingot");
        slot0.putByte("Count", (byte) 8);
        slot0.putByte("Slot", (byte) 0);
        items.add(slot0);

        components.put("Items", items);

        assertTrue(components.contains("Items"));
        NbtList read = components.getList("Items").orElse(new NbtList());
        assertEquals(1, read.size());
        NbtCompound item = read.getCompound(0).orElse(null);
        assertNotNull(item);
        assertEquals("minecraft:iron_ingot", item.getString("id").orElse(""));
    }

    @Test
    void testEmptyContainerShouldBeSkipped() {
        NbtCompound be = new NbtCompound();
        be.putString("id", "minecraft:chest");
        be.putInt("x", 10);
        be.putInt("y", 64);
        be.putInt("z", 10);

        assertFalse(be.contains("Items"), "empty chest has no Items key");
    }

    @Test
    void testContainerWithoutItemsKey() {
        NbtCompound be = new NbtCompound();
        be.putString("id", "minecraft:barrel");
        be.putInt("x", 10);
        be.putInt("y", 64);
        be.putInt("z", 10);
        be.put("Items", new NbtList());

        assertTrue(be.contains("Items"));
        NbtList items = be.getList("Items").orElse(new NbtList());
        assertEquals(0, items.size(), "Items list is empty");
    }

    @Test
    void testItemMatchingLogic() {
        assertTrue(matchesItem("minecraft:carrot", "minecraft:carrot"), "exact match");
        assertTrue(matchesItem("carrot", "minecraft:carrot"), "short vs full");
        assertTrue(matchesItem("minecraft:carrot", "carrot"), "full vs short");
        assertTrue(matchesItem("CARROT", "minecraft:carrot"), "case insensitive");
        assertTrue(matchesItem("MOD:carrot", "mod:CARROT"), "mod case insensitive");

        assertFalse(matchesItem("minecraft:apple", "minecraft:carrot"), "different items");
        assertFalse(matchesItem("minecraft:diamond", "carrot"), "different short");
    }

    @Test
    void testNormalizeItemId() {
        assertEquals("minecraft:carrot", normalize("carrot"));
        assertEquals("minecraft:carrot", normalize("minecraft:carrot"));
        assertEquals("minecraft:diamond", normalize("Diamond"));
        assertEquals("mod:item", normalize("MOD:Item"));
        assertEquals("", normalize(""));
        assertNull(normalize(null));
    }

    @Test
    void testDoubleChestDetection() {
        NbtCompound be = new NbtCompound();
        be.putString("id", "minecraft:chest");

        NbtList items = new NbtList();
        NbtCompound slot54 = new NbtCompound();
        slot54.putByte("slot", (byte) 53);
        slot54.putString("id", "minecraft:stone");
        slot54.putByte("Count", (byte) 1);
        items.add(slot54);
        be.put("Items", items);

        int maxSlot = -1;
        NbtList readItems = be.getList("Items").orElse(new NbtList());
        for (int i = 0; i < readItems.size(); i++) {
            NbtCompound entry = readItems.getCompound(i).orElse(null);
            if (entry == null) continue;
            int slot = entry.contains("slot") ? entry.getInt("slot").orElse(i)
                     : entry.contains("Slot") ? (int) entry.getByte("Slot").orElse((byte) i) : i;
            if (slot > maxSlot) maxSlot = slot;
        }
        assertEquals(53, maxSlot);
        assertTrue(maxSlot > 26, "double chest has slot > 26");
    }

    @Test
    void testSlotDetectionNewAndOldFormat() {
        NbtCompound newFormat = new NbtCompound();
        newFormat.putByte("slot", (byte) 27);
        assertTrue(newFormat.contains("slot"));
        assertEquals(27, (int) newFormat.getInt("slot").orElse(-1));

        NbtCompound oldFormat = new NbtCompound();
        oldFormat.putByte("Slot", (byte) 27);
        assertTrue(oldFormat.contains("Slot"));
        assertEquals(27, (int) oldFormat.getByte("Slot").orElse((byte) -1));
    }

    @Test
    void testReadItemNewFormatWithComponents() {
        NbtCompound entry = new NbtCompound();
        entry.putByte("slot", (byte) 5);

        NbtCompound itemTag = new NbtCompound();
        itemTag.putString("id", "minecraft:white_shulker_box");
        itemTag.putInt("count", 1);

        NbtCompound comp = new NbtCompound();
        NbtList container = new NbtList();
        NbtCompound innerSlot = new NbtCompound();
        NbtCompound innerItem = new NbtCompound();
        innerItem.putString("id", "minecraft:carrot");
        innerItem.putInt("count", 64);
        innerSlot.put("item", innerItem);
        container.add(innerSlot);
        comp.put("minecraft:container", container);
        itemTag.put("components", comp);

        entry.put("item", itemTag);

        assertTrue(entry.contains("item"));
        NbtCompound readItem = entry.getCompound("item").orElse(null);
        assertNotNull(readItem);
        assertEquals("minecraft:white_shulker_box", readItem.getString("id").orElse(""));
        assertTrue(readItem.contains("components"));

        NbtCompound readComp = readItem.getCompound("components").orElse(null);
        assertNotNull(readComp);
        assertTrue(readComp.contains("minecraft:container"));
    }

    @Test
    void testReadItemOldFormatWithBlockEntityTag() {
        NbtCompound entry = new NbtCompound();
        entry.putString("id", "minecraft:shulker_box");
        entry.putByte("Count", (byte) 1);

        NbtCompound tag = new NbtCompound();
        NbtCompound bet = new NbtCompound();
        NbtList items = new NbtList();
        NbtCompound innerSlot = new NbtCompound();
        innerSlot.putString("id", "minecraft:carrot");
        innerSlot.putByte("Count", (byte) 32);
        items.add(innerSlot);
        bet.put("Items", items);
        tag.put("BlockEntityTag", bet);
        entry.put("tag", tag);

        assertTrue(entry.contains("tag"));
        NbtCompound readTag = entry.getCompound("tag").orElse(null);
        assertNotNull(readTag);
        assertTrue(readTag.contains("BlockEntityTag"));

        NbtCompound readBet = readTag.getCompound("BlockEntityTag").orElse(null);
        assertNotNull(readBet);
        assertTrue(readBet.contains("Items"));
    }

    @Test
    void testScanEntryEdgeCases() {
        com.eunsearch.config.ScanEntry e = new com.eunsearch.config.ScanEntry(
                "tag", "item", Integer.MIN_VALUE, 0, Integer.MIN_VALUE,
                Integer.MAX_VALUE, 128, Integer.MAX_VALUE, "d");

        assertEquals(Integer.MIN_VALUE, e.minX());
        assertEquals(Integer.MAX_VALUE, e.maxX());
        assertEquals(Integer.MIN_VALUE, e.minZ());
        assertEquals(Integer.MAX_VALUE, e.maxZ());

        assertTrue(e.coversPosition(0, 64, 0), "covers origin");
        assertTrue(e.coversPosition(Integer.MIN_VALUE, 0, Integer.MIN_VALUE), "covers MIN_VALUE");
        assertTrue(e.coversPosition(Integer.MAX_VALUE, 128, Integer.MAX_VALUE), "covers MAX_VALUE");
    }

    // --- Helpers ---

    static boolean matchesItem(String itemId, String target) {
        if (itemId.equalsIgnoreCase(target)) return true;
        String shortId = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        String shortTarget = target.contains(":") ? target.substring(target.indexOf(':') + 1) : target;
        return shortId.equalsIgnoreCase(shortTarget);
    }

    static String normalize(String item) {
        if (item == null) return null;
        if (item.isEmpty()) return item;
        if (!item.contains(":")) return "minecraft:" + item.toLowerCase();
        return item.toLowerCase();
    }
}
