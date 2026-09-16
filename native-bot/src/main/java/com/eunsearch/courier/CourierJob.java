package com.eunsearch.courier;

import carpet.patches.EntityPlayerMPFake;
import carpet.helpers.EntityPlayerActionPack;
import com.google.gson.JsonObject;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.*;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

final class CourierJob {
    enum State { SCAN, NEXT, WALK, OPEN, TAKE, PLACE, BOX_OPEN, BOX_TAKE, BREAK, COLLECT_BOX, RECOVERY, SPAWN_CARRIER, WAIT_CARRIER, THROW_CARGO, CONFIRM_CARGO, WAIT_LOGOUT, DONE, FAILED }
    record Hit(BlockPos pos,int count,int direct,String type) {}
    final CourierMod mod;
    final EntityPlayerMPFake bot;
    final String tag,itemId,carrierName,requester;
    final Item item;
    final int requested;
    final boolean chestOnly;
    final String jobId=Long.toString(System.currentTimeMillis(),36);
    State state=State.SCAN,afterWalk;
    Navigation nav;
    Recovery recovery;
    State afterRecovery;
    int cargoThrown,cargoExpectedLeft;
    CompletableFuture<List<Hit>> scan;
    final Deque<Hit> hits=new ArrayDeque<>();
    Hit current;
    int got,delivered,wait,totalTicks,carrierBaseline;
    int pickupAttempts;
    boolean finished,carrierCreated;
    BlockPos placed;
    ItemStack box=ItemStack.EMPTY,expectedBox=ItemStack.EMPTY;
    ItemEntity boxDrop;

    final List<UUID> cargoDrops=new ArrayList<>();
    final List<String> milestones=new ArrayList<>();
    EntityPlayerMPFake carrier;
    String detail="";
    String itemLabel(){
        try{return (String)Class.forName("com.eunsearch.render.ItemNameMap").getMethod("getCnName",String.class).invoke(null,itemId);}
        catch(Exception ignored){return itemId;}
    }
    static Component completionMessage(String detail,String item,int requested,int delivered,String carrier){
        return Component.literal(detail+"；物品："+item+"；需求："+requested+"；已交付："+delivered
            +"；背包回收已完成，Courier 已回到待机点。收货假人："+carrier+" ")
            .append(Component.literal("[点击此处复制指令]").withStyle(s->s.withColor(ChatFormatting.AQUA)
                .withClickEvent(new ClickEvent.CopyToClipboard("/player "+carrier+" spawn"))));
    }

    CourierJob(CourierMod mod,EntityPlayerMPFake bot,String tag,String id,int count,String carrier,String requester,boolean chestOnly,boolean retained) {
        this.mod=mod;this.bot=bot;this.tag=tag;itemId=id;requested=count;carrierName=carrier;this.requester=requester;this.chestOnly=chestOnly;

        item=BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
        if(item==Items.AIR)throw new IllegalArgumentException("Unknown item id: "+id);
        got=Math.min(requested,InventoryPolicy.count(bot.getInventory(),item));
        if(!mod.walkArea().contains(bot.position()))throw new IllegalStateException("Worker outside walk bounds");
        if(bot.gameMode()!=GameType.SURVIVAL)throw new IllegalStateException("Worker must be in survival mode");
    }
    void begin() throws Exception {
        Navigation.actions(bot).stopAll();bot.closeContainer();
        Class<?> api=Class.forName("com.eunsearch.api.EunSearchAPI");
        Object entry=api.getMethod("getScanEntry",String.class).invoke(null,tag);
        if(entry==null)throw new IllegalArgumentException("Unknown scan tag");
        if(!entry.getClass().getField("dimension").get(entry).equals(mod.config.dimension))throw new IllegalArgumentException("Scan dimension differs from worker");
        mod.notifyRequester(requester,"Job "+jobId+": "+itemId+" x"+requested+"; inventory="+got+"; missing="+(requested-got)+"; carrier="+carrierName);
        if(got>=requested){prepareDelivery();return;}
        scan=CompletableFuture.supplyAsync(()->{
            try {
                List<?> raw=(List<?>)api.getMethod("searchItem",MinecraftServer.class,String.class,String.class).invoke(null,mod.server,tag,itemId);
                List<Hit> result=new ArrayList<>();
                for(Object c:raw){Class<?> cl=c.getClass();String type=(String)cl.getField("containerType").get(c);
                    if(chestOnly&&!type.equals("chest")&&!type.equals("trapped_chest"))continue;
                    result.add(new Hit(new BlockPos(cl.getField("x").getInt(c),cl.getField("y").getInt(c),cl.getField("z").getInt(c)),cl.getField("count").getInt(c),cl.getField("directCount").getInt(c),type));}
                result.sort(Comparator.comparingInt(Hit::direct).reversed());return result;
            }catch(Exception e){throw new RuntimeException(e);}
        });
        startRecovery(got,true,State.SCAN);
    }
    String describe(){return "job="+jobId+" state="+state+" collected="+got+"/"+requested+" delivered="+delivered+" carrier="+carrierName+" worker="+bot.position()+" "+detail;}
    void transition(State next){
        if(milestones.size()<256)milestones.add(state+" -> "+next+"; collected="+got+"; "+detail);
        state=next;wait=0;journal();
    }
    void journal(){
        try {
            JsonObject j=new JsonObject();j.addProperty("job",jobId);j.addProperty("worker",bot.getGameProfile().name());j.addProperty("state",state.name());
            j.addProperty("item",itemId);j.addProperty("requested",requested);j.addProperty("collected",got);j.addProperty("delivered",delivered);
            j.addProperty("carrier",carrierName);j.addProperty("carrierCreated",carrierCreated);j.addProperty("detail",detail);
            j.addProperty("workerPosition",bot.position().toString());j.addProperty("placedBox",placed==null?null:placed.toShortString());
            j.add("cargoItemEntities",CourierMod.JSON.toJsonTree(cargoDrops));
            j.add("milestones",CourierMod.JSON.toJsonTree(milestones));
            Path temp=CourierMod.DIR.resolve(jobId+".tmp"),dest=CourierMod.DIR.resolve(jobId+".json");
            Files.writeString(temp,CourierMod.JSON.toJson(j));
            Files.move(temp,dest,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }catch(Exception e){throw new IllegalStateException("Cannot persist courier journal",e);}
    }
    void fail(String error){
        if(finished)return;
        finished=true;detail=error;state=State.FAILED;
        Navigation.actions(bot).stopAll();if(!bot.isRemoved())bot.closeContainer();
        try{journal();}catch(Exception e){CourierMod.LOG.error("Failed to write job failure",e);}
        mod.notifyRequester(requester,describe()+"; retained items must be recovered before retrying");
    }
    void walk(Vec3 target,java.util.function.Predicate<Vec3> goal,State next){
        bot.closeContainer();Navigation.actions(bot).stopAll();
        nav=new Navigation(bot,mod.walkArea(),target,goal);afterWalk=next;transition(State.WALK);
    }
    void walkToBlock(BlockPos target,State next){walk(Vec3.atCenterOf(target),p->Navigation.canReach(bot,p,target),next);}
    void walkToPlacement(){walk(Vec3.atCenterOf(placed),p->Navigation.canReach(bot,p,placed.below())
        &&!new AABB(p.x-.3,p.y,p.z-.3,p.x+.3,p.y+1.8,p.z+.3).intersects(new AABB(placed)),State.PLACE);}
    void walkTo(Vec3 target,State next){walk(target,p->Math.hypot(p.x-target.x,p.z-target.z)<.75&&Math.abs(p.y-target.y)<.35,next);}
    static int count(Container inv,Item item){int n=0;for(int i=0;i<inv.getContainerSize();i++)if(inv.getItem(i).is(item))n+=inv.getItem(i).getCount();return n;}
    static int countExact(Container inv,ItemStack item){int n=0;for(int i=0;i<inv.getContainerSize();i++)if(ItemStack.isSameItemSameComponents(inv.getItem(i),item))n+=inv.getItem(i).getCount();return n;}
    int capacity(ItemStack stack){int n=0;for(int i=0;i<36;i++){ItemStack s=bot.getInventory().getItem(i);if(s.isEmpty())n+=stack.getMaxStackSize();else if(ItemStack.isSameItemSameComponents(s,stack))n+=Math.max(0,s.getMaxStackSize()-s.getCount());}return n;}
    int inventorySlot(ItemStack stack){for(int i=0;i<36;i++)if(ItemStack.isSameItemSameComponents(bot.getInventory().getItem(i),stack))return i;return -1;}
    void equip(int slot){var inv=bot.getInventory();int selected=inv.getSelectedSlot();if(slot!=selected){ItemStack old=inv.getItem(selected);inv.setItem(selected,inv.getItem(slot));inv.setItem(slot,old);inv.setChanged();}}
    boolean open(BlockPos pos){
        var hit=Navigation.interactionHit(bot,bot.position(),pos);
        if(hit==null)throw new IllegalStateException("Container is obstructed or outside reach: "+pos);
        Navigation.actions(bot).stopAll();Navigation.actions(bot).lookAt(hit.getLocation());
        useEmptyMainHand(hit);
        return bot.containerMenu!=bot.inventoryMenu;
    }
    net.minecraft.world.InteractionResult useEmptyMainHand(BlockHitResult hit){
        var inventory=bot.getInventory();
        for(int i=0;i<36;i++)if(inventory.getItem(i).isEmpty()){equip(i);break;}
        // 26.2's default block interaction is MAIN_HAND only; an empty OFF_HAND does not open containers.
        try{return InventoryPolicy.withEmptyMainHand(inventory,inventory.getSelectedSlot(),
            net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND,
            ()->bot.gameMode.useItemOn(bot,bot.level(),bot.getMainHandItem(),InteractionHand.MAIN_HAND,hit));}
        finally{bot.inventoryMenu.broadcastChanges();if(bot.containerMenu!=bot.inventoryMenu)bot.containerMenu.broadcastChanges();}
    }
    int take(Slot slot,int amount){
        if(!slot.mayPickup(bot))return 0;
        amount=Math.min(amount,capacity(slot.getItem()));if(amount<=0)return 0;
        ItemStack taken=slot.safeTake(amount,amount,bot);int n=taken.getCount();
        bot.getInventory().add(taken);
        if(!taken.isEmpty()){slot.safeInsert(taken);throw new IllegalStateException("Inventory changed during extraction");}
        bot.containerMenu.broadcastChanges();return n;
    }
    void checkStation(){
        if(mod.config.place==null||mod.config.place.length!=3)throw new IllegalStateException("Configure place before processing shulker boxes");
        Recovery.validate(mod);
        BlockPos support=new BlockPos(mod.config.place[0],mod.config.place[1],mod.config.place[2]);
        if(!bot.level().hasChunkAt(support)||bot.level().getBlockState(support).getCollisionShape(bot.level(),support).isEmpty())throw new IllegalStateException("Placement support is missing");
        if(!bot.level().getBlockState(support.above()).isAir())throw new IllegalStateException("Shulker processing position is occupied; nothing overwritten");
    }
    void takeContainer(boolean processingBox){
        AbstractContainerMenu menu=bot.containerMenu;
        if(menu==bot.inventoryMenu||!menu.stillValid(bot))throw new IllegalStateException("Container closed or no longer accessible");
        for(Slot slot:menu.slots) {
            if(slot.container==bot.getInventory()||!slot.hasItem())continue;
            if(slot.getItem().is(item)&&got<requested){int amount=take(slot,Math.min(requested-got,slot.getItem().getCount()));if(amount==0)throw new IllegalStateException("Inventory full or slot locked");got+=amount;journal();return;}
        }
        if(!processingBox&&got<requested)for(Slot slot:menu.slots){
            if(slot.container==bot.getInventory()||slot.getItem().isEmpty()||!(Block.byItem(slot.getItem().getItem()) instanceof ShulkerBoxBlock))continue;
            ItemContainerContents contents=slot.getItem().get(DataComponents.CONTAINER);
            if(contents==null||contents.nonEmptyItemCopyStream().noneMatch(s->s.is(item)))continue;
            checkStation();box=slot.getItem().copyWithCount(1);
            if(take(slot,1)!=1)throw new IllegalStateException("Cannot withdraw selected shulker box");
            placed=new BlockPos(mod.config.place[0],mod.config.place[1]+1,mod.config.place[2]);
            walkToPlacement();return;
        }
        bot.closeContainer();
        if(processingBox){
            if(!(bot.level().getBlockEntity(placed) instanceof Container container))throw new IllegalStateException("Placed box disappeared");
            List<ItemStack> remaining=new ArrayList<>();for(int i=0;i<container.getContainerSize();i++)remaining.add(container.getItem(i).copy());
            expectedBox=box.copy();expectedBox.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(remaining));
            transition(State.BREAK);
        } else if(got>=requested||hits.isEmpty())prepareDelivery();else transition(State.NEXT);
    }
    void prepareDelivery(){if(got==0)throw new IllegalStateException("No items could be collected");walkTo(CourierMod.vec(mod.config.idle),State.SPAWN_CARRIER);}
    void startRecovery(int keep,boolean keepTargetBoxes,State next){
        afterRecovery=next;
        if(Arrays.stream(InventoryPolicy.returnable(bot.getInventory(),item,keep,keepTargetBoxes)).sum()==0){transition(next);return;}
        Recovery.validate(mod);recovery=new Recovery(this,keep,keepTargetBoxes);transition(State.RECOVERY);
    }
    Vec3 dropPosition(){return CourierMod.vec(mod.config.recovery==null?mod.config.drop:mod.config.recovery.position);}
    Vec3 dropReceipt(){return CourierMod.vec(mod.config.recovery==null?mod.config.dropLook:mod.config.recovery.receipt);}
    void walkToDroppedBox(){
        var matches=bot.level().getEntitiesOfClass(ItemEntity.class,new AABB(placed).inflate(5),e->e.isAlive()&&ItemStack.isSameItemSameComponents(e.getItem(),expectedBox));
        if(matches.size()!=1)throw new IllegalStateException("Expected exactly one matching dropped box near processing station, found "+matches.size());
        boxDrop=matches.getFirst();pickupAttempts++;
        Vec3 target=boxDrop.position();detail="Picking up processed box entity="+boxDrop.getUUID()+" at "+target;
        walk(target,p->Math.hypot(p.x-target.x,p.z-target.z)<.65&&Math.abs(p.y-target.y)<1.1,State.COLLECT_BOX);
    }
    void tick() throws Exception {
        if(finished)return;
        if(++totalTicks>24000)throw new IllegalStateException("Job exceeded 20 minute limit");
        if(bot.isRemoved()||!bot.isAlive())throw new IllegalStateException("Worker disconnected or died");
        if(bot.gameMode()!=GameType.SURVIVAL)throw new IllegalStateException("Worker game mode changed");
        wait++;
        switch(state){
            case SCAN -> {if(wait>2400)throw new IllegalStateException("Scan timeout");if(scan.isDone()){
                hits.addAll(scan.join());
                if(got>=requested)prepareDelivery();else transition(State.NEXT);
            }}
            case NEXT -> {
                boolean carriedBox=false;
                for(int i=0;i<36;i++)if(InventoryPolicy.contains(bot.getInventory().getItem(i),item)){
                    checkStation();box=bot.getInventory().getItem(i).copyWithCount(1);
                    placed=new BlockPos(mod.config.place[0],mod.config.place[1]+1,mod.config.place[2]);
                    walkToPlacement();carriedBox=true;break;
                }
                if(carriedBox)break;
                current=hits.pollFirst();if(current==null){prepareDelivery();break;}
                try{walkToBlock(current.pos,State.OPEN);}catch(IllegalStateException e){detail="Skipped "+current.pos+": "+e.getMessage();transition(State.NEXT);}
            }
            case WALK -> {nav.tick();if(nav.done)transition(afterWalk);}
            case OPEN -> {if(!open(current.pos))throw new IllegalStateException("Server refused opening container at "+current.pos);transition(State.TAKE);}
            case TAKE -> takeContainer(false);
            case PLACE -> {
                checkStation();int slot=inventorySlot(box);if(slot<0)throw new IllegalStateException("Withdrawn box missing from inventory");equip(slot);
                Navigation.actions(bot).lookAt(Vec3.atCenterOf(placed));Navigation.actions(bot).setSneaking(true);
                var occupants=bot.level().getEntities(bot,new AABB(placed),e->e instanceof net.minecraft.world.entity.player.Player);
                if(!occupants.isEmpty())throw new IllegalStateException("Placement blocked by player(s): "+occupants.stream().map(e->e.getName().getString()).toList());
                var result=bot.gameMode.useItemOn(bot,bot.level(),bot.getMainHandItem(),InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atBottomCenterOf(placed),Direction.UP,placed.below(),false));
                Navigation.actions(bot).setSneaking(false);
                if(!(bot.level().getBlockState(placed).getBlock() instanceof ShulkerBoxBlock))throw new IllegalStateException("Placement rejected ("+result+"); box retained");
                transition(State.BOX_OPEN);
            }
            case BOX_OPEN -> {if(wait<10)break;if(!open(placed))throw new IllegalStateException("Placed box cannot open (blocked, protected or CCE restricted)");transition(State.BOX_TAKE);}
            case BOX_TAKE -> takeContainer(true);
            case BREAK -> {
                if(bot.level().getBlockState(placed).isAir()){
                    Navigation.actions(bot).stopAll();pickupAttempts=0;
                    if(inventorySlot(expectedBox)>=0)transition(State.COLLECT_BOX);else walkToDroppedBox();break;
                }
                if(!(bot.level().getBlockState(placed).getBlock() instanceof ShulkerBoxBlock))throw new IllegalStateException("Processing block replaced; refusing to break it");
                if(wait>600)throw new IllegalStateException("Box breaking timed out");
                if(!Navigation.canReach(bot,placed))throw new IllegalStateException("Placed box no longer in reach");
                Navigation.actions(bot).lookAt(Vec3.atCenterOf(placed));
                if(wait==1)Navigation.actions(bot).start(EntityPlayerActionPack.ActionType.ATTACK,EntityPlayerActionPack.Action.continuous());
            }
            case COLLECT_BOX -> {
                if(inventorySlot(expectedBox)>=0){
                    box=ItemStack.EMPTY;expectedBox=ItemStack.EMPTY;placed=null;
                    if(got>=requested)prepareDelivery();
                    else if(current!=null)walkToBlock(current.pos,State.OPEN);
                    else transition(State.NEXT);
                    break;
                }
                if(wait==40&&pickupAttempts<3&&boxDrop!=null&&boxDrop.isAlive()){walkToDroppedBox();break;}
                if(wait>200)throw new IllegalStateException("Expected box not picked up; no other same-color box will be discarded");
            }
            case RECOVERY -> {if(recovery.tick()){
                if(afterRecovery==State.DONE)walkTo(CourierMod.vec(mod.config.idle),State.DONE);else transition(afterRecovery);
            }}
            case SPAWN_CARRIER -> {
                if(mod.server.getPlayerList().getPlayerByName(carrierName)!=null)throw new IllegalStateException("Carrier name became occupied; aborting");
                EntityPlayerMPFake.createFake(carrierName,mod.server,bot.position(),0,0,bot.level().dimension(),GameType.SURVIVAL,false);
                transition(State.WAIT_CARRIER);
            }
            case WAIT_CARRIER -> {
                var p=mod.server.getPlayerList().getPlayerByName(carrierName);
                if(p==null){if(wait>400)throw new IllegalStateException("Carrier spawn timed out");break;}
                if(!(p instanceof EntityPlayerMPFake f))throw new IllegalStateException("Carrier is not a Carpet fake player");
                carrier=f;carrierCreated=true;Navigation.actions(carrier).stopAll();
                if(!carrier.level().dimension().equals(bot.level().dimension())||carrier.distanceTo(bot)>1.5)throw new IllegalStateException("Carrier did not spawn beside worker");
                // Never append to an existing stored shipment, even when its owner is offline.
                if(!carrier.getInventory().isEmpty())throw new IllegalStateException("Carrier has stored inventory; choose a fresh name, inventory retained");
                carrierBaseline=count(carrier.getInventory(),item);cargoThrown=0;
                cargoExpectedLeft=InventoryPolicy.count(bot.getInventory(),item)-got;
                if(cargoExpectedLeft<0)throw new IllegalStateException("Reserved shipment disappeared before handoff");
                transition(State.THROW_CARGO);
            }
            case THROW_CARGO -> {
                if(carrier.isRemoved()||!carrier.isAlive()||carrier.distanceTo(bot)>2)throw new IllegalStateException("Carrier unavailable for handoff");
                Navigation.actions(bot).stopAll();Navigation.actions(bot).lookAt(carrier.position().add(0,.2,0));
                boolean thrown=false;
                for(int i=0;i<36;i++)if(InventoryPolicy.shipmentAmount(bot.getInventory().getItem(i),item,got-cargoThrown)>0){
                    ItemStack stack=bot.getInventory().removeItem(i,InventoryPolicy.shipmentAmount(bot.getInventory().getItem(i),item,got-cargoThrown));
                    int amount=stack.getCount();
                    ItemEntity drop=bot.drop(stack,false,true);
                    if(drop==null){bot.getInventory().add(stack);throw new IllegalStateException("Cargo drop rejected");}
                    drop.setTarget(carrier.getUUID());drop.setPickUpDelay(10);cargoDrops.add(drop.getUUID());cargoThrown+=amount;thrown=true;
                    journal();break;
                }
                if(!thrown)transition(State.CONFIRM_CARGO);
            }
            case CONFIRM_CARGO -> {
                if(carrier.isRemoved()||!carrier.isAlive())throw new IllegalStateException("Carrier disappeared before receipt confirmation");
                int actual=count(carrier.getInventory(),item)-carrierBaseline;
                if(actual==got&&InventoryPolicy.count(bot.getInventory(),item)==cargoExpectedLeft){
                    delivered=actual;journal();
                    // Carpet kill is a disconnect, distinct from vanilla entity death.
                    carrier.kill(Component.literal("EunCourier shipment saved"));transition(State.WAIT_LOGOUT);break;
                }
                if(wait>300)throw new IllegalStateException("Carrier receipt mismatch "+actual+"/"+got+"; carrier left online for recovery");
            }
            case WAIT_LOGOUT -> {
                if(mod.server.getPlayerList().getPlayerByName(carrierName)==null){startRecovery(0,false,State.DONE);break;}
                if(wait>100)throw new IllegalStateException("Carrier logout not confirmed");
            }
            case DONE -> {finished=true;Navigation.actions(bot).stopAll();detail=delivered==requested?"任务完成":"部分完成：可获取的库存不足";journal();
                mod.notifyRequester(requester,completionMessage(detail,itemLabel(),requested,delivered,carrierName));}
            default -> {}
        }
    }
}
