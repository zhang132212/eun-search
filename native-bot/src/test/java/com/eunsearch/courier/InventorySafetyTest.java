package com.eunsearch.courier;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class InventorySafetyTest {
    @BeforeAll static void bootstrap(){
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        // Unit fixtures only: 26.2 binds item defaults during datapack loading on a real server.
        for(var item:List.of(Items.AIR,Items.DIAMOND,Items.SHULKER_BOX))
            if(!item.builtInRegistryHolder().areComponentsBound())item.builtInRegistryHolder().bindComponents(
                net.minecraft.core.component.DataComponentMap.builder().addAll(DataComponents.COMMON_ITEM_COMPONENTS)
                    .set(DataComponents.MAX_STACK_SIZE,item==Items.SHULKER_BOX?1:64).build());
    }
    @Test void neverSelectsAnotherSameColorShulker(){
        ItemStack processed=new ItemStack(Items.SHULKER_BOX);
        processed.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND,3))));
        ItemStack other=processed.copy();
        other.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND,64))));
        SimpleContainer inv=new SimpleContainer(3);inv.setItem(0,other);
        assertEquals(0,CourierJob.countExact(inv,processed));
        inv.setItem(1,processed.copy());assertEquals(1,CourierJob.countExact(inv,processed));
        ItemStack named=processed.copy();named.set(DataComponents.CUSTOM_NAME,Component.literal("Another customer's box"));
        inv.setItem(1,named);assertEquals(0,CourierJob.countExact(inv,processed));
    }
    @Test void carrierReceiptCountsActualLooseCargoOnly(){
        SimpleContainer inv=new SimpleContainer(3);
        ItemStack box=new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND,64))));
        inv.setItem(0,box);inv.setItem(1,new ItemStack(Items.DIAMOND,7));inv.setItem(2,new ItemStack(Items.DIAMOND,2));
        assertEquals(9,CourierJob.count(inv,Items.DIAMOND));
    }
    @Test void narrowMisWalkwayRejectsChestAndOutsidePositions(){
        Navigation.Bounds b=new Navigation.Bounds(-142.67,-111.57,77,77.0625,-150.54,-146.45);
        assertTrue(b.contains(new Vec3(-126.62,77,-148.40)));
        assertFalse(b.contains(new Vec3(-129,78,-154)));
        assertFalse(b.contains(new Vec3(-111.5,77,-148.4)));
        assertFalse(b.contains(new Vec3(-126.62,76,-148.4)));
    }
    @Test void recoveryDepositsOnlyExactBoxAndPreservesCargo(){
        ItemStack box=new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND,63))));
        ItemStack other=new ItemStack(Items.SHULKER_BOX);
        SimpleContainer inventory=new SimpleContainer(3),chest=new SimpleContainer(1);
        inventory.setItem(0,new ItemStack(Items.DIAMOND,1));inventory.setItem(1,other);inventory.setItem(2,box.copy());
        Recovery.transferBox(inventory,List.of(new net.minecraft.world.inventory.Slot(chest,0,0,0)),box);
        assertEquals(1,CourierJob.count(inventory,Items.DIAMOND));
        assertEquals(1,CourierJob.countExact(inventory,other));
        assertEquals(0,CourierJob.countExact(inventory,box));
        assertEquals(1,CourierJob.countExact(chest,box));
    }
    @Test void fullRecoveryChestRetainsBox(){
        ItemStack box=new ItemStack(Items.SHULKER_BOX);
        SimpleContainer inventory=new SimpleContainer(2),chest=new SimpleContainer(1);
        inventory.setItem(0,box.copy());inventory.setItem(1,new ItemStack(Items.DIAMOND,1));chest.setItem(0,new ItemStack(Items.DIAMOND,64));
        assertThrows(IllegalStateException.class,()->Recovery.transferBox(inventory,List.of(new net.minecraft.world.inventory.Slot(chest,0,0,0)),box));
        assertEquals(1,CourierJob.countExact(inventory,box));assertEquals(1,CourierJob.count(inventory,Items.DIAMOND));assertEquals(64,CourierJob.count(chest,Items.DIAMOND));
    }
    @Test void recoveryDirectionAndSeparateWalkAreas(){
        assertTrue(Recovery.inFront(new Vec3(0,-2,3),Recovery.yaw("south")));
        assertFalse(Recovery.inFront(new Vec3(0,0,-1),Recovery.yaw("south")));
        assertFalse(Recovery.inFront(new Vec3(3,0,1),Recovery.yaw("south")));
        var area=new Navigation.Area(List.of(new Navigation.Bounds(-142.87,-111.31,77,78.67,-151.63,-146.54),new Navigation.Bounds(-121,-115,77,78.67,-147,-142.9)));
        assertTrue(area.contains(new Vec3(-117.45,78.37,-143.94)));
        assertFalse(area.contains(new Vec3(-130,78,-144)));
    }
    @Test void inventoryFirstReservesOnlyShipmentAndReturnsExcess(){
        SimpleContainer inventory=new SimpleContainer(4);
        inventory.setItem(0,new ItemStack(Items.DIAMOND,64));inventory.setItem(1,new ItemStack(Items.DIAMOND,6));
        inventory.setItem(2,new ItemStack(Items.SHULKER_BOX));
        assertEquals(70,InventoryPolicy.count(inventory,Items.DIAMOND));
        assertArrayEquals(new int[]{0,5,1,0},InventoryPolicy.returnable(inventory,Items.DIAMOND,65,false));
        assertEquals(1,InventoryPolicy.shipmentAmount(inventory.getItem(0),Items.DIAMOND,1));
        assertEquals(0,InventoryPolicy.shipmentAmount(inventory.getItem(0),Items.DIAMOND,0));
    }
    @Test void fullInventoryUsesEmptyMainHandAndRestoresAll2304Items(){
        SimpleContainer inventory=new SimpleContainer(41);
        for(int i=0;i<36;i++)inventory.setItem(i,new ItemStack(Items.DIAMOND,64));
        ItemStack selected=inventory.getItem(4);selected.set(DataComponents.CUSTOM_NAME,Component.literal("Keep components"));
        assertEquals("opened",InventoryPolicy.withEmptyMainHand(inventory,4,40,()->{
            assertTrue(inventory.getItem(4).isEmpty());assertSame(selected,inventory.getItem(40));
            assertEquals(2304,CourierJob.count(inventory,Items.DIAMOND));return "opened";
        }));
        assertSame(selected,inventory.getItem(4));assertTrue(inventory.getItem(40).isEmpty());
        assertEquals(2304,InventoryPolicy.count(inventory,Items.DIAMOND));
    }
    @Test void failedMainHandInteractionRestoresBufferedStack(){
        SimpleContainer inventory=new SimpleContainer(41);ItemStack original=new ItemStack(Items.SHULKER_BOX);
        original.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND,31))));
        inventory.setItem(0,original);
        assertThrows(IllegalStateException.class,()->InventoryPolicy.withEmptyMainHand(inventory,0,40,()->{throw new IllegalStateException("Rejected");}));
        assertSame(original,inventory.getItem(0));assertTrue(inventory.getItem(40).isEmpty());
        assertEquals(31,original.get(DataComponents.CONTAINER).nonEmptyItemCopyStream().mapToInt(ItemStack::getCount).sum());
    }
    @Test void occupiedOffhandIsNeverOverwritten(){
        SimpleContainer inventory=new SimpleContainer(41);ItemStack main=new ItemStack(Items.DIAMOND,64),off=new ItemStack(Items.SHULKER_BOX);
        inventory.setItem(0,main);inventory.setItem(40,off);
        assertThrows(IllegalStateException.class,()->InventoryPolicy.withEmptyMainHand(inventory,0,40,()->{fail("Must not interact");return false;}));
        assertSame(main,inventory.getItem(0));assertSame(off,inventory.getItem(40));
    }
    @Test void completionIncludesChineseAquaCopyAction(){
        var message=CourierJob.completionMessage("任务完成","钻石",1,1,"efTest");
        assertTrue(message.getString().contains("已交付：1"));
        var link=message.getSiblings().getLast();
        assertEquals("[点击此处复制指令]",link.getString());
        assertEquals(net.minecraft.network.chat.TextColor.fromLegacyFormat(net.minecraft.ChatFormatting.AQUA),link.getStyle().getColor());
        assertEquals(new net.minecraft.network.chat.ClickEvent.CopyToClipboard("/player efTest spawn"),link.getStyle().getClickEvent());
    }
    @Test void chunkLeashUsesFloorForNegativeCoordinatesAndIncludesRecoveryAreas(){
        double[] main={-142.87,-111.31,77,78.67,-151.63,-146.54};
        assertTrue(Navigation.insideChunks(main,-144,-160));
        assertTrue(Navigation.insideChunks(main,-96.01,-144.01));
        assertFalse(Navigation.insideChunks(main,-144.01,-150));
        assertFalse(Navigation.insideChunks(main,-96,-150));
        assertFalse(Navigation.insideChunks(main,-120,-144));
        assertTrue(Navigation.insideChunks(new double[]{-121,-115,76.875,78.67,-147,-144.4},-118.51,-145.36));
    }
    @Test void recoveryRespectsNonStackableCapacityAndSlotRejection(){
        SimpleContainer inventory=new SimpleContainer(36),box=new SimpleContainer(27);
        for(int i=0;i<30;i++){
            ItemStack single=new ItemStack(Items.DIAMOND);single.set(DataComponents.MAX_STACK_SIZE,1);inventory.setItem(i,single);
        }
        var slots=java.util.stream.IntStream.range(0,27).mapToObj(i->new net.minecraft.world.inventory.Slot(box,i,0,0)).toList();
        int moved=0;for(int i=0;i<36;i++)moved+=InventoryPolicy.transferUpTo(inventory,i,slots,1728-moved);
        assertEquals(27,moved);assertEquals(3,InventoryPolicy.count(inventory,Items.DIAMOND));
        var reject=new net.minecraft.world.inventory.Slot(new SimpleContainer(1),0,0,0){@Override public boolean mayPlace(ItemStack s){return false;}};
        assertEquals(0,InventoryPolicy.transferUpTo(inventory,27,List.of(reject),1));
        assertEquals(3,InventoryPolicy.count(inventory,Items.DIAMOND));
    }
    @Test void targetBoxesRemainAvailableUntilDeficitIsFilled(){
        SimpleContainer inventory=new SimpleContainer(2);ItemStack box=new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND,3))));
        inventory.setItem(0,box);inventory.setItem(1,new ItemStack(Items.DIAMOND,1));
        assertArrayEquals(new int[]{0,0},InventoryPolicy.returnable(inventory,Items.DIAMOND,1,true));
        assertArrayEquals(new int[]{1,0},InventoryPolicy.returnable(inventory,Items.DIAMOND,1,false));
    }
    @Test void looseRecoveryFillsOneBoxAndRetainsNextBatch(){
        SimpleContainer inventory=new SimpleContainer(36),box=new SimpleContainer(27);
        for(int i=0;i<30;i++)inventory.setItem(i,new ItemStack(Items.DIAMOND,64));
        var slots=java.util.stream.IntStream.range(0,27).mapToObj(i->new net.minecraft.world.inventory.Slot(box,i,0,0)).toList();
        int moved=0;for(int i=0;i<36;i++)moved+=InventoryPolicy.transferUpTo(inventory,i,slots,1728-moved);
        assertEquals(1728,moved);assertEquals(192,InventoryPolicy.count(inventory,Items.DIAMOND));assertEquals(1728,CourierJob.count(box,Items.DIAMOND));
        assertEquals(0,InventoryPolicy.transferUpTo(inventory,27,slots,64));
        assertEquals(192,InventoryPolicy.count(inventory,Items.DIAMOND));
        SimpleContainer next=new SimpleContainer(27);var nextSlots=java.util.stream.IntStream.range(0,27).mapToObj(i->new net.minecraft.world.inventory.Slot(next,i,0,0)).toList();
        int second=0;for(int i=0;i<36;i++)second+=InventoryPolicy.transferUpTo(inventory,i,nextSlots,1728-second);
        assertEquals(192,second);assertTrue(inventory.isEmpty());
    }
}
