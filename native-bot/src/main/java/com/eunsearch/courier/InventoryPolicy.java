package com.eunsearch.courier;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import java.util.List;

/** Keeps shipment quantities separate from recyclable inventory, including partial stacks. */
final class InventoryPolicy {
    static <T> T withEmptyMainHand(Container inventory,int selected,int offhand,java.util.function.Supplier<T> interaction){
        if(inventory.getItem(selected).isEmpty())return interaction.get();
        if(offhand<0||offhand>=inventory.getContainerSize()||!inventory.getItem(offhand).isEmpty())
            throw new IllegalStateException("Inventory and both hands full; cannot safely empty main hand");
        swap(inventory,selected,offhand);
        // Both swaps and the interaction run on the same server tick. No item is dropped or copied.
        try{return interaction.get();}
        finally{swap(inventory,selected,offhand);}
    }
    private static void swap(Container inventory,int a,int b){
        ItemStack first=inventory.getItem(a),second=inventory.getItem(b);
        inventory.setItem(a,second);inventory.setItem(b,first);inventory.setChanged();
    }
    static int size(Container inventory){return Math.min(36,inventory.getContainerSize());}
    static boolean box(ItemStack stack){return !stack.isEmpty()&&Block.byItem(stack.getItem()) instanceof ShulkerBoxBlock;}
    static boolean contains(ItemStack stack,Item item){
        ItemContainerContents contents=stack.get(DataComponents.CONTAINER);
        return box(stack)&&contents!=null&&contents.nonEmptyItemCopyStream().anyMatch(s->s.is(item));
    }
    static int count(Container inventory,Item item){
        int count=0;for(int i=0;i<size(inventory);i++)if(inventory.getItem(i).is(item))count+=inventory.getItem(i).getCount();return count;
    }
    static int[] returnable(Container inventory,Item item,int keep,boolean keepTargetBoxes){
        int[] amounts=new int[size(inventory)];int remaining=Math.max(0,keep);
        for(int i=0;i<amounts.length;i++){
            ItemStack stack=inventory.getItem(i);if(stack.isEmpty())continue;
            int reserved=0;
            if(stack.is(item)){reserved=Math.min(remaining,stack.getCount());remaining-=reserved;}
            if(keepTargetBoxes&&contains(stack,item))continue;
            amounts[i]=stack.getCount()-reserved;
        }
        return amounts;
    }
    static int shipmentAmount(ItemStack stack,Item item,int remaining){return stack.is(item)?Math.min(stack.getCount(),Math.max(0,remaining)):0;}
    static int transferUpTo(Container inventory,int index,List<Slot> destination,int limit){
        ItemStack source=inventory.getItem(index);
        int offered=Math.min(source.getCount(),Math.max(0,limit));if(offered==0)return 0;
        ItemStack exact=source.copyWithCount(1),remaining=source.copyWithCount(offered);
        int before=countSlots(destination,exact);
        for(Slot slot:destination){
            if(remaining.isEmpty())break;
            int old=remaining.getCount();
            remaining=slot.safeInsert(remaining);
            int inserted=old-remaining.getCount();
            if(inserted<0||inserted>old)throw new IllegalStateException("Invalid recovery insertion result");
            source.shrink(inserted);inventory.setChanged();
        }
        int moved=offered-remaining.getCount();
        if(countSlots(destination,exact)!=before+moved)throw new IllegalStateException("Recovery container receipt mismatch");
        return moved;
    }
    static int countSlots(List<Slot> slots,ItemStack exact){
        int count=0;for(Slot slot:slots)if(ItemStack.isSameItemSameComponents(slot.getItem(),exact))count+=slot.getItem().getCount();return count;
    }
}
