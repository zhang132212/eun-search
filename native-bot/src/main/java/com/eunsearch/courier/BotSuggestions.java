package com.eunsearch.courier;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.network.chat.Component;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Complete the current argument without changing the existing Chinese-name command syntax. */
final class BotSuggestions {
    static CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder,Collection<String> tags,
            Map<String,String> items,String freshCarrier){
        String remaining=builder.getRemaining();int start=0;
        for(int i=0;i<remaining.length();i++)if(Character.isWhitespace(remaining.charAt(i)))start=i+1;
        String before=remaining.substring(0,start).strip();
        int argument=before.isEmpty()?0:before.split("\\s+").length;
        String prefix=remaining.substring(start).toLowerCase(Locale.ROOT);
        SuggestionsBuilder out=builder.createOffset(builder.getStart()+start);
        switch(argument){
            case 0 -> tags.forEach(tag->offer(out,prefix,tag,"扫描标签"));
            case 1 -> items.forEach((id,name)->{
                String shortId=id.startsWith("minecraft:")?id.substring(10):id;
                offer(out,prefix,shortId,name);offer(out,prefix,id,name);
                if(name.codePoints().noneMatch(Character::isWhitespace))offer(out,prefix,name,id);
            });
            case 2 -> {
                for(int n:new int[]{1,16,64,128,256,512,1024,1728,2304})offer(out,prefix,Integer.toString(n),"需求数量（1–2304）");
            }
            case 3 -> offer(out,prefix,freshCarrier,"新收货假人名（可省略，自动生成）");
            default -> {}
        }
        return out.buildFuture();
    }
    private static void offer(SuggestionsBuilder out,String prefix,String value,String tooltip){
        if(value.toLowerCase(Locale.ROOT).startsWith(prefix))out.suggest(value,Component.literal(tooltip));
    }
}
