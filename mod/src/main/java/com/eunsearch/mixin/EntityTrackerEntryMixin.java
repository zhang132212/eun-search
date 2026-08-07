package com.eunsearch.mixin;

import com.eunsearch.command.ScanCommand;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * 荧光标记实体仅对执行者可见:
 * 拦截 EntityTrackerEntry 对非 owner 玩家的 spawn/update/remove 包发送,
 * 其他玩家的客户端不会知道该实体存在 (渲染仍由原版客户端完成, 无需客户端mod)。
 */
@Mixin(EntityTrackerEntry.class)
public class EntityTrackerEntryMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("eun_search-tracker");

    @Shadow
    private Entity entity;

    /** 该实体是否是本 mod 的荧光标记, 且不是 owner 玩家 */
    private static boolean shouldHideFrom(Entity entity, ServerPlayerEntity player) {
        if (entity == null || player == null) return false;
        if (!entity.getCommandTags().contains(ScanCommand.MARKER_TAG)) return false;
        if (ScanCommand.isOwner(entity.getId(), player.getUuid())) return false;
        return true;
    }

    @Inject(method = "startTracking", at = @At("HEAD"), cancellable = true)
    private void eun_hideStartTracking(ServerPlayerEntity player, CallbackInfo ci) {
        if (shouldHideFrom(entity, player)) {
            LOGGER.debug("[TrackerFilter] 阻止向 {} 发送标记实体{}的spawn包", player.getName().getString(), entity.getId());
            ci.cancel();
        }
    }

    @Inject(method = "sendPackets", at = @At("HEAD"), cancellable = true)
    private void eun_hideSendPackets(ServerPlayerEntity player, Consumer<Packet<?>> ignored, CallbackInfo ci) {
        if (shouldHideFrom(entity, player)) {
            ci.cancel();
        }
    }
    // 注意: stopTracking 不做过滤 —— remove 包对没有该实体的客户端无害(会忽略),
    // 且不能依赖 MARKERS 查询 (实体移除时记录可能已被删除), 过滤会导致标记残留无法消失
}
