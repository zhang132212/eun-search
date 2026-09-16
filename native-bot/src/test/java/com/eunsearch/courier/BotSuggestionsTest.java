package com.eunsearch.courier;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BotSuggestionsTest {
    List<String> complete(String input){
        CommandDispatcher<Object> dispatcher=new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.<Object>literal("botSearchChest")
            .then(RequiredArgumentBuilder.<Object,String>argument("args",StringArgumentType.greedyString())
                .suggests((c,b)->BotSuggestions.suggest(b,List.of("mis"),
                    Map.of("minecraft:diamond","钻石","minecraft:coal_block","煤炭块"),"efTestNew"))
                .executes(c->1)));
        return dispatcher.getCompletionSuggestions(dispatcher.parse(input,new Object())).join().getList().stream()
            .map(s->s.apply(input)).toList();
    }
    @Test void commandAndAllowedTagComplete(){
        assertEquals(List.of("botSearchChest"),complete("botSearchCh"));
        assertEquals(List.of("botSearchChest mis"),complete("botSearchChest "));
        assertEquals(List.of("botSearchChest mis"),complete("botSearchChest m"));
        assertTrue(complete("botSearchChest unknown").isEmpty());
    }
    @Test void chineseAndEnglishNamesReplaceOnlyItem(){
        assertEquals(List.of("botSearchChest mis 钻石"),complete("botSearchChest mis 钻"));
        assertEquals(List.of("botSearchChest mis coal_block"),complete("botSearchChest mis coal"));
        assertEquals(List.of("botSearchChest mis minecraft:diamond"),complete("botSearchChest mis minecraft:dia"));
        assertEquals(List.of("botSearchChest mis  煤炭块"),complete("botSearchChest mis  煤"));
    }
    @Test void quantitiesAndOptionalCarrierHaveSeparateSuggestions(){
        assertEquals(List.of("botSearchChest mis 钻石 64"),complete("botSearchChest mis 钻石 6"));
        assertTrue(complete("botSearchChest mis 钻石 ").contains("botSearchChest mis 钻石 1728"));
        assertEquals(List.of("botSearchChest mis 钻石 1 efTestNew"),complete("botSearchChest mis 钻石 1 ef"));
        assertTrue(complete("botSearchChest mis 钻石 1 efTestNew ").isEmpty());
    }
}
