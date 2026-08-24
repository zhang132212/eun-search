package com.eunsearch.client.render;

import com.mojang.math.Transformation;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Display;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.Map;

public class ClientMarkerRenderer {
    private static final Map<BlockPos, Display.BlockDisplay> MARKERS = new LinkedHashMap<>();

    private ClientMarkerRenderer() {}

    public static void spawn(BlockPos pos, boolean green) {
        Level world = Minecraft.getInstance().level;
        if (world == null || pos == null) return;

        remove(pos);

        Display.BlockDisplay entity = EntityTypes.BLOCK_DISPLAY.create(world, EntitySpawnReason.EVENT);
        if (entity == null) return;

        entity.setPos(pos.getX(), pos.getY(), pos.getZ());
        entity.setBlockState(world.getBlockState(pos));
        entity.setTransformation(Transformation.IDENTITY);
        entity.setViewRange(64.0F);
        entity.setGlowingTag(true);
        entity.setGlowColorOverride(green ? 0x55FF55 : 0xFFFFFF);

        if (world instanceof ClientLevel clientLevel) {
            clientLevel.addEntity(entity);
        }
        MARKERS.put(pos.immutable(), entity);
    }

    public static void remove(BlockPos pos) {
        if (pos == null) return;
        Display.BlockDisplay entity = MARKERS.remove(pos);
        if (entity != null) {
            entity.discard();
        }
    }

    public static void clear() {
        for (Display.BlockDisplay entity : MARKERS.values()) {
            entity.discard();
        }
        MARKERS.clear();
    }

    public static int count() {
        return MARKERS.size();
    }
}
