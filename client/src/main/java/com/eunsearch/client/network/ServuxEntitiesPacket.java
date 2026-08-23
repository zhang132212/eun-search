package com.eunsearch.client.network;

import java.util.Optional;

import fi.dy.masa.malilib.util.data.tag.BaseData;
import fi.dy.masa.malilib.util.data.tag.CompoundData;
import fi.dy.masa.malilib.util.data.tag.converter.DataConverterNbt;
import fi.dy.masa.malilib.util.data.tag.util.DataByteBufUtils;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public class ServuxEntitiesPacket implements CustomPacketPayload {
    public static final int PROTOCOL_VERSION = 2;

    public static final int TYPE_S2C_METADATA = 1;
    public static final int TYPE_C2S_METADATA_REQUEST = 2;
    public static final int TYPE_C2S_BLOCK_ENTITY_REQUEST = 3;
    public static final int TYPE_S2C_BLOCK_NBT_RESPONSE_SIMPLE = 5;

    private int type;
    private BlockPos pos;
    private CompoundData nbt;
    private ByteBuf buffer;

    private ServuxEntitiesPacket(int type) {
        this.type = type;
        this.pos = BlockPos.ZERO;
        this.nbt = new CompoundData();
    }

    public static ServuxEntitiesPacket metadataRequest(CompoundData nbt) {
        ServuxEntitiesPacket p = new ServuxEntitiesPacket(TYPE_C2S_METADATA_REQUEST);
        if (nbt != null) p.nbt.combine(nbt);
        return p;
    }

    public static ServuxEntitiesPacket blockEntityRequest(BlockPos pos) {
        ServuxEntitiesPacket p = new ServuxEntitiesPacket(TYPE_C2S_BLOCK_ENTITY_REQUEST);
        p.pos = pos.immutable();
        return p;
    }

    public static ServuxEntitiesPacket metadataResponse(CompoundData nbt) {
        ServuxEntitiesPacket p = new ServuxEntitiesPacket(TYPE_S2C_METADATA);
        if (nbt != null) p.nbt.combine(nbt);
        return p;
    }

    public static ServuxEntitiesPacket blockEntityResponse(BlockPos pos, CompoundData nbt) {
        ServuxEntitiesPacket p = new ServuxEntitiesPacket(TYPE_S2C_BLOCK_NBT_RESPONSE_SIMPLE);
        p.pos = pos.immutable();
        p.nbt = nbt == null ? new CompoundData() : nbt;
        return p;
    }

    public int getType() {
        return this.type;
    }

    public BlockPos getPos() {
        return this.pos;
    }

    public CompoundData getNbt() {
        return this.nbt;
    }

    public void write(RegistryFriendlyByteBuf output) {
        output.writeVarInt(this.type);
        switch (this.type) {
            case TYPE_C2S_METADATA_REQUEST, TYPE_S2C_METADATA -> {
                output.writeNbt(DataConverterNbt.toVanillaCompound(this.nbt));
            }
            case TYPE_C2S_BLOCK_ENTITY_REQUEST -> {
                output.writeBlockPos(this.pos);
            }
            case TYPE_S2C_BLOCK_NBT_RESPONSE_SIMPLE -> {
                output.writeBlockPos(this.pos);
                try {
                    DataByteBufUtils.toByteBuf(output, this.nbt, "");
                } catch (Exception e) {
                    throw new RuntimeException("Failed to write Servux entity NBT", e);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported Servux entity packet type " + this.type);
        }
    }

    public static ServuxEntitiesPacket read(RegistryFriendlyByteBuf input) {
        int type = input.readVarInt();
        switch (type) {
            case TYPE_C2S_METADATA_REQUEST -> {
                CompoundTag tag = input.readNbt();
                return metadataRequest(DataConverterNbt.fromVanillaCompound(tag));
            }
            case TYPE_S2C_METADATA -> {
                CompoundTag tag = input.readNbt();
                return metadataResponse(DataConverterNbt.fromVanillaCompound(tag));
            }
            case TYPE_C2S_BLOCK_ENTITY_REQUEST -> {
                try {
                    return blockEntityRequest(input.readBlockPos());
                } catch (Exception e) {
                    return null;
                }
            }
            case TYPE_S2C_BLOCK_NBT_RESPONSE_SIMPLE -> {
                try {
                    BlockPos pos = input.readBlockPos();
                    Optional<BaseData> data = DataByteBufUtils.fromByteBuf(input);
                    if (data.isPresent() && data.get() instanceof CompoundData compound) {
                        return blockEntityResponse(pos, compound);
                    }
                } catch (Exception e) {
                    return null;
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return Payload.TYPE;
    }

    public record Payload(ServuxEntitiesPacket packet) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(Identifier.parse("servux:entity_data"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC = StreamCodec.of(
                (buf, p) -> p.packet.write(buf),
                buf -> new Payload(ServuxEntitiesPacket.read(buf))
        );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}