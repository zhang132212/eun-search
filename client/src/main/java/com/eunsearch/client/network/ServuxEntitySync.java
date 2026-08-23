package com.eunsearch.client.network;

import com.eunsearch.client.config.ClientConfig;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.Queue;

public class ServuxEntitySync {
    private static final ServuxEntitySync INSTANCE = new ServuxEntitySync();
    private boolean servuxServer;
    private boolean registered;
    private final Queue<BlockPos> pending = new ArrayDeque<>();

    private ServuxEntitySync() {}

    public static ServuxEntitySync getInstance() {
        return INSTANCE;
    }

    public void init() {
        // Same payload id is used in both directions by the Servux protocol.
        try {
            PayloadTypeRegistry.clientboundPlay().register(ServuxEntitiesPacket.Payload.TYPE, ServuxEntitiesPacket.Payload.CODEC);
            PayloadTypeRegistry.serverboundPlay().register(ServuxEntitiesPacket.Payload.TYPE, ServuxEntitiesPacket.Payload.CODEC);
        } catch (IllegalArgumentException ignored) {
            // already registered
        }

        ClientPlayNetworking.registerGlobalReceiver(ServuxEntitiesPacket.Payload.TYPE, (payload, context) -> handle(payload.packet()));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset(false));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset(true));
    }

    private void reset(boolean disconnected) {
        this.servuxServer = false;
        this.registered = false;
        this.pending.clear();
        if (!disconnected && Minecraft.getInstance().getConnection() != null) {
            sendMetadataRequest();
        }
    }

    public void sendMetadataRequest() {
        CompoundData nbt = new CompoundData();
        nbt.putInt("version", ServuxEntitiesPacket.PROTOCOL_VERSION);
        ClientPlayNetworking.send(new ServuxEntitiesPacket.Payload(ServuxEntitiesPacket.metadataRequest(nbt)));
    }

    private void handle(ServuxEntitiesPacket packet) {
        switch (packet.getType()) {
            case ServuxEntitiesPacket.TYPE_S2C_METADATA -> {
                CompoundData data = packet.getNbt();
                int version = data.getIntOrDefault("version", -1);
                if (version >= ServuxEntitiesPacket.PROTOCOL_VERSION) {
                    servuxServer = true;
                    registered = true;
                }
            }
            case ServuxEntitiesPacket.TYPE_S2C_BLOCK_NBT_RESPONSE_SIMPLE -> {
                if (registered) {
                    SearchResultSink.acceptBlockEntity(packet.getPos(), packet.getNbt());
                }
            }
            default -> { }
        }
    }

    public boolean isServuxServer() {
        return this.servuxServer;
    }

    public boolean isRegistered() {
        return this.registered;
    }

    public int getPendingCount() {
        return this.pending.size();
    }

    public void requestBlockEntity(BlockPos pos) {
        if (pos != null && !this.pending.contains(pos)) {
            this.pending.add(pos.immutable());
        }
    }

    public void tick() {
        if (Minecraft.getInstance().level == null || Minecraft.getInstance().player == null) {
            return;
        }
        if (!registered) {
            if (!servuxServer && Minecraft.getInstance().getConnection() != null) {
                sendMetadataRequest();
            }
            return;
        }

        int limit = Math.max(1, ClientConfig.get().maxRequestsPerTick);
        for (int i = 0; i < limit && !pending.isEmpty(); i++) {
            BlockPos pos = pending.poll();
            ClientPlayNetworking.send(new ServuxEntitiesPacket.Payload(ServuxEntitiesPacket.blockEntityRequest(pos)));
        }
    }
}
