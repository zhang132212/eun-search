package com.eunsearch.courier;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Reserve shipment cargo, return boxes to the chest, and pack loose items through the machine. */
final class Recovery {
    static class Config {
        String mode="sequence";
        double[][] walkAreas;
        List<Step> steps;
        double[] position,receipt;
        float yaw,pitch;
        Step loose;
        int batchItems=1728;
        int batchDelayMillis=2000;
    }
    static class Step {
        String action, facing="south", block;
        double[] position;
        double range=4.5;
    }
    final CourierJob job;
    Navigation nav;
    int stepIndex;
    boolean moving;
    final int keep;
    final boolean keepTargetBoxes;
    long readyAt,retryUntil;
    boolean awaitingReplacement;
    BlockPos looseOpen;
    int looseTicks,looseMoved;
    ItemEntity dropped;
    ItemStack droppedStack=ItemStack.EMPTY;
    final Map<BlockPos,Integer> receipts=new HashMap<>();
    int dropTicks;
    Recovery(CourierJob job){this(job,job.got,false);}
    Recovery(CourierJob job,int keep,boolean keepTargetBoxes){this.job=job;this.keep=keep;this.keepTargetBoxes=keepTargetBoxes;}
    int[] amounts(){return InventoryPolicy.returnable(job.bot.getInventory(),job.item,keep,keepTargetBoxes);}
    boolean has(boolean boxes){int[] a=amounts();for(int i=0;i<a.length;i++)if(a[i]>0&&InventoryPolicy.box(job.bot.getInventory().getItem(i))==boxes)return true;return false;}

    static float yaw(String facing){return switch(facing.toLowerCase(Locale.ROOT)){
        case "south"->0;case "west"->90;case "north"->180;case "east"->-90;
        default->throw new IllegalArgumentException("Facing must be south, west, north or east");
    };}
    static void face(ServerPlayer bot,float yaw,float pitch){
        bot.setYRot(yaw);bot.setXRot(pitch);bot.setYHeadRot(yaw);bot.setYBodyRot(yaw);
    }
    static void validate(CourierMod mod){
        Config c=mod.config.recovery;
        if(c==null){
            if(!mod.walkArea().contains(CourierMod.vec(mod.config.drop)))throw new IllegalArgumentException("Drop position outside walk areas");
            CourierMod.vec(mod.config.dropLook);return;
        }
        if("drop".equals(c.mode)){
            if(!mod.walkArea().contains(CourierMod.vec(c.position)))throw new IllegalArgumentException("Recovery drop position outside walk areas");
            CourierMod.vec(c.receipt);
            if(!Float.isFinite(c.yaw)||!Float.isFinite(c.pitch)||Math.abs(c.pitch)>90)throw new IllegalArgumentException("Invalid drop yaw/pitch");
        }else if("sequence".equals(c.mode)){
            if(c.batchItems<1||c.batchItems>1728||c.batchDelayMillis<2000||c.batchDelayMillis>60000)throw new IllegalArgumentException("Invalid recovery batch size or delay");
            if(c.loose!=null){
                if(!mod.walkArea().contains(CourierMod.vec(c.loose.position)))throw new IllegalArgumentException("Loose recovery position outside walk areas");
                if(!"loose".equals(c.loose.action)||!Double.isFinite(c.loose.range)||c.loose.range<=0||c.loose.range>4.5)throw new IllegalArgumentException("Invalid loose recovery step");
                yaw(c.loose.facing);
            }
            if(c.steps==null||c.steps.size()<2||c.steps.size()>32)throw new IllegalArgumentException("Recovery sequence needs 2..32 steps");
            boolean deposited=false;
            for(Step s:c.steps){
                if(s==null||!mod.walkArea().contains(CourierMod.vec(s.position)))throw new IllegalArgumentException("Recovery step position outside walk areas");
                yaw(s.facing);
                if(!Double.isFinite(s.range)||s.range<=0||s.range>4.5)throw new IllegalArgumentException("Scan range must be 0..4.5");
                if("deposit".equals(s.action)){
                    if(deposited)throw new IllegalArgumentException("Only one deposit step per processed box");deposited=true;
                }else if("use".equals(s.action)){
                    if(!deposited||!"minecraft:note_block".equals(s.block))throw new IllegalArgumentException("Use step must follow deposit and target minecraft:note_block");
                }else if(!"walk".equals(s.action))throw new IllegalArgumentException("Unknown recovery action: "+s.action);
            }
            if(!deposited||!"use".equals(c.steps.getLast().action))throw new IllegalArgumentException("Recovery sequence must deposit and finish with use");
        }else throw new IllegalArgumentException("Recovery mode must be sequence or drop");
    }
    // A 90-degree forward sector. Select the nearest matching block first, then require line of sight.
    static boolean inFront(Vec3 delta,float yaw){
        double radians=Math.toRadians(yaw),forward=-Math.sin(radians)*delta.x+Math.cos(radians)*delta.z;
        double side=Math.cos(radians)*delta.x+Math.sin(radians)*delta.z;
        return forward>.05&&Math.abs(side)<=forward;
    }
    static BlockPos target(ServerPlayer bot,Vec3 feet,Step s){
        Vec3 eye=feet.add(0,bot.getEyeHeight(),0);BlockPos origin=BlockPos.containing(eye);
        BlockPos nearest=null;double distance=Double.MAX_VALUE;int radius=(int)Math.ceil(s.range);
        for(BlockPos p:BlockPos.betweenClosed(origin.offset(-radius,-radius,-radius),origin.offset(radius,radius,radius))){
            if(!bot.level().hasChunkAt(p))continue;
            var block=bot.level().getBlockState(p).getBlock();
            boolean matches="deposit".equals(s.action)?block instanceof ChestBlock:"loose".equals(s.action)?block instanceof ShulkerBoxBlock:BuiltInRegistries.BLOCK.getKey(block).toString().equals(s.block);
            Vec3 delta=Vec3.atCenterOf(p).subtract(eye);double d=delta.lengthSqr();
            if(matches&&d<=s.range*s.range&&inFront(delta,yaw(s.facing))&&d<distance){nearest=p.immutable();distance=d;}
        }
        if(nearest==null)throw new IllegalStateException("No "+("deposit".equals(s.action)?"chest":s.block)+" towards "+s.facing+" within "+s.range);
        if(Navigation.interactionHit(bot,feet,nearest)==null)throw new IllegalStateException("Nearest recovery target is obstructed: "+nearest.toShortString());
        return nearest;
    }
    static Vec3 standing(CourierMod mod,ServerPlayer bot,Step step){
        Vec3 wanted=CourierMod.vec(step.position);
        Navigation probe=new Navigation(bot,mod.walkArea(),wanted,p->false);
        Vec3 best=null;double score=Double.MAX_VALUE;
        int bx=(int)Math.floor(wanted.x),bz=(int)Math.floor(wanted.z);
        for(int x=bx-1;x<=bx+1;x++)for(int z=bz-1;z<=bz+1;z++){
            Navigation.Node node=probe.surface(x,z,wanted.y);
            if(node==null)continue;Vec3 p=node.feet();
            if(Math.hypot(p.x-wanted.x,p.z-wanted.z)>=.75||Math.abs(p.y-wanted.y)>1.5)continue;
            if(!"walk".equals(step.action))try{target(bot,p,step);}catch(IllegalStateException e){continue;}
            double d=p.distanceToSqr(wanted);if(d<score){score=d;best=p;}
        }
        if(best==null)throw new IllegalStateException("No safe standing surface with a visible recovery target near "+wanted);
        return best;
    }
    void record(String message){job.detail="Recovery step "+(stepIndex+1)+": "+message;job.milestones.add(job.detail);job.journal();}
    boolean tick(){
        Config config=job.mod.config.recovery;
        if(config==null||"drop".equals(config.mode))return tickDrop();
        if(looseOpen!=null)return tickLoose(config);
        if(System.nanoTime()<readyAt)return false;
        var steps=config.steps;
        if(stepIndex>=steps.size())return true;
        Step step=steps.get(stepIndex);
        if("deposit".equals(step.action)&&!has(true)){stepIndex++;moving=false;return false;}
        if("use".equals(step.action)){
            if(has(true)){stepIndex=0;moving=false;return false;}
            if(has(false)){
                if(config.loose==null)throw new IllegalStateException("Configure recovery.loose before returning loose items");
                step=config.loose;
            }
        }
        if(!moving){
            Vec3 position;
            try{position=standing(job.mod,job.bot,step);}
            catch(IllegalStateException e){if(awaitingReplacement&&System.nanoTime()<retryUntil)return false;throw e;}
            record("walk to "+position+", "+step.action+", facing="+step.facing);
            job.bot.closeContainer();Navigation.actions(job.bot).stopAll();
            nav=new Navigation(job.bot,job.mod.walkArea(),position,p->p.distanceToSqr(position)<.04);
            moving=true;return false;
        }
        if(!nav.done){nav.tick();return false;}
        Navigation.actions(job.bot).stopAll();face(job.bot,yaw(step.facing),0);
        if(!"walk".equals(step.action)){
            BlockPos target;
            try{target=target(job.bot,job.bot.position(),step);}
            catch(IllegalStateException e){if(awaitingReplacement&&System.nanoTime()<retryUntil)return false;throw e;}
            if("deposit".equals(step.action)){
                if(!job.open(target))throw new IllegalStateException("Recovery chest refused opening at "+target.toShortString());
                int moved=depositItems(true,Integer.MAX_VALUE);job.bot.closeContainer();
                record("returned "+moved+" box(es) into "+target.toShortString());
                if(has(true))throw new IllegalStateException("Recovery chest full; remaining boxes retained, note not clicked");
            }else if("loose".equals(step.action)){
                if(awaitingReplacement){
                    if(!(job.bot.level().getBlockEntity(target) instanceof Container c)||!c.isEmpty()){
                        if(System.nanoTime()>retryUntil)throw new IllegalStateException("Machine has not supplied an empty shulker; loose items retained");
                        return false;
                    }
                    awaitingReplacement=false;
                }
                if(!job.open(target))throw new IllegalStateException("Loose recovery shulker refused opening at "+target.toShortString());
                looseOpen=target;looseTicks=0;looseMoved=0;return false;
            }else{
                var hit=Navigation.interactionHit(job.bot,job.bot.position(),target);
                if(hit==null)throw new IllegalStateException("Recovery use target no longer visible");
                Navigation.actions(job.bot).lookAt(hit.getLocation());
                var result=job.useEmptyMainHand(hit);
                if(!result.consumesAction())throw new IllegalStateException("Recovery right-click rejected at "+target.toShortString());
                record("right-click accepted at "+target.toShortString()+"; sequence finished="+(stepIndex==steps.size()-1));
            }
        }
        stepIndex++;moving=false;return stepIndex>=steps.size();
    }
    boolean tickLoose(Config config){
        // Opening and closing in one tick suppresses observable lid animation on redstone machines.
        if(++looseTicks<5)return false;
        if(looseTicks==5){
            looseMoved=depositItems(false,config.batchItems);
            record("inserted "+looseMoved+" loose item(s) into "+looseOpen.toShortString());
            return false;
        }
        if(looseTicks<7)return false;
        job.bot.closeContainer();
        record("closed shulker at "+looseOpen.toShortString()+" for automatic machine collection; batch="+looseMoved);
        looseOpen=null;
        if(looseMoved==0)throw new IllegalStateException("Loose recovery shulker full or rejects items; inventory retained, note not clicked");
        readyAt=System.nanoTime()+config.batchDelayMillis*1_000_000L;
        retryUntil=readyAt+30_000_000_000L;awaitingReplacement=has(false);
        moving=false;stepIndex=0;return false;
    }
    int depositItems(boolean boxes,int limit){
        var menu=job.bot.containerMenu;
        if(menu==job.bot.inventoryMenu||!menu.stillValid(job.bot))throw new IllegalStateException("Recovery container no longer accessible");
        var slots=menu.slots.stream().filter(s->s.container!=job.bot.getInventory()).toList();
        int[] a=amounts();int moved=0;
        try{for(int i=0;i<a.length;i++)if(a[i]>0&&InventoryPolicy.box(job.bot.getInventory().getItem(i))==boxes)
            moved+=InventoryPolicy.transferUpTo(job.bot.getInventory(),i,slots,Math.min(a[i],limit-moved));}
        finally{menu.broadcastChanges();}
        return moved;
    }
    boolean tickDrop(){
        if(dropped!=null){
            dropTicks++;boolean received=false;
            for(var e:receipts.entrySet())if(job.bot.level().getBlockEntity(e.getKey()) instanceof Container c&&CourierJob.countExact(c,droppedStack)>=e.getValue()+droppedStack.getCount())received=true;
            if(received||(receipts.isEmpty()&&dropped.isAlive()&&dropped.onGround()&&dropped.position().distanceTo(job.dropReceipt())<.9&&dropTicks>=20)){
                record("drop receipt verified: "+droppedStack);dropped=null;return false;
            }
            if(dropTicks>200||!dropped.isAlive())throw new IllegalStateException("Recovery drop not verified: "+dropped.getUUID());
            return false;
        }
        int[] a=amounts();int index=-1;for(int i=0;i<a.length;i++)if(a[i]>0){index=i;break;}
        if(index<0)return true;
        if(!moving){Vec3 p=job.dropPosition();nav=new Navigation(job.bot,job.mod.walkArea(),p,v->v.distanceToSqr(p)<.6);moving=true;record("walk to configured drop position "+p);return false;}
        if(!nav.done){nav.tick();return false;}
        Navigation.actions(job.bot).stopAll();
        Config c=job.mod.config.recovery;
        if(c==null)Navigation.actions(job.bot).lookAt(job.dropReceipt());else face(job.bot,c.yaw,c.pitch);
        droppedStack=job.bot.getInventory().getItem(index).copyWithCount(a[index]);receipts.clear();
        for(int y=-1;y<=0;y++){BlockPos p=BlockPos.containing(job.dropReceipt()).offset(0,y,0);if(job.bot.level().getBlockEntity(p) instanceof Container container)receipts.put(p,CourierJob.countExact(container,droppedStack));}
        ItemStack stack=job.bot.getInventory().removeItem(index,a[index]);dropped=job.bot.drop(stack,false,true);
        if(dropped==null){job.bot.getInventory().add(stack);throw new IllegalStateException("Recovery drop rejected");}
        dropTicks=0;record("dropped at configured position; entity="+dropped.getUUID());return false;
    }
    void deposit(){
        var menu=job.bot.containerMenu;
        if(menu==job.bot.inventoryMenu||!menu.stillValid(job.bot))throw new IllegalStateException("Recovery chest is not accessible");
        try{transferBox(job.bot.getInventory(),menu.slots.stream().filter(s->s.container!=job.bot.getInventory()).toList(),job.expectedBox);}
        finally{menu.broadcastChanges();}
    }
    static void transferBox(Container inventory,List<Slot> slots,ItemStack expected){
        if(expected.isEmpty())throw new IllegalStateException("No processed box to deposit");
        int slot=-1;for(int i=0;i<Math.min(36,inventory.getContainerSize());i++)if(ItemStack.isSameItemSameComponents(inventory.getItem(i),expected)){slot=i;break;}
        if(slot<0)throw new IllegalStateException("Exact processed box missing; refusing to deposit cargo or unrelated inventory");
        int before=0;for(Slot s:slots)if(ItemStack.isSameItemSameComponents(s.getItem(),expected))before+=s.getItem().getCount();
        ItemStack remaining=inventory.removeItem(slot,1);
        try{
            for(Slot s:slots)if(!remaining.isEmpty())remaining=s.safeInsert(remaining);
        }finally{
            if(!remaining.isEmpty())inventory.setItem(slot,remaining);
            inventory.setChanged();
        }
        if(!remaining.isEmpty())throw new IllegalStateException("Recovery chest is full or rejects box; box retained, note block not clicked");
        int after=0;for(Slot s:slots)if(ItemStack.isSameItemSameComponents(s.getItem(),expected))after+=s.getItem().getCount();
        if(after!=before+1)throw new IllegalStateException("Recovery deposit receipt mismatch; note block not clicked");
    }
    static void inspect(CourierMod mod,CommandSourceStack source){
        validate(mod);Config c=mod.config.recovery;
        if(c==null||"drop".equals(c.mode)){mod.reply(source,"Recovery mode=drop; position="+CourierMod.vec(c==null?mod.config.drop:c.position));return;}
        var bot=mod.worker();int i=0;
        List<Step> all=new ArrayList<>(c.steps);if(c.loose!=null)all.add(c.loose);
        for(Step s:all){
            String result="walk only";
            try{Vec3 feet=standing(mod,bot,s);result="standing="+feet+("walk".equals(s.action)?"":"; target="+target(bot,feet,s).toShortString());}catch(Exception e){result=e.getMessage();}
            mod.reply(source,"Recovery step "+(++i)+" "+s.action+" at "+CourierMod.vec(s.position)+" facing="+s.facing+"; "+result);
        }
    }
}
