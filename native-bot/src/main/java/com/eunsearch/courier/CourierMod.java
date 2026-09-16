package com.eunsearch.courier;

import carpet.patches.EntityPlayerMPFake;
import com.google.gson.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.net.*;
import java.util.*;
import static net.minecraft.commands.Commands.*;
import static com.mojang.brigadier.arguments.StringArgumentType.*;

public class CourierMod implements ModInitializer {
    static final Logger LOG=LoggerFactory.getLogger("EunCourier");
    static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    static final Path DIR=FabricLoader.getInstance().getConfigDir().resolve("eun-native-courier");
    static class Config {
        String worker="Courier";
        String dimension="minecraft:overworld";
        double[] idle;
        double[] walk; // minX,maxX,minY,maxY,minZ,maxZ; bounds describe feet positions
        int[] place; // supporting block, not placed-box position
        double[] drop; // feet position while returning the used box
        double[] dropLook; // precise input opening aim point
        String allowedTag="mis";
        int tcpPort=0;
        boolean autoSpawn=false;
        boolean autoRespawn=true;
        Recovery.Config recovery;
    }
    MinecraftServer server;
    Config config;
    CourierJob job;
    volatile boolean running;
    Thread tcpThread;
    Socket tcpSocket;
    String last="No job";
    EntityPlayerMPFake observedWorker;
    boolean respawnPending;
    int respawnDelay;
    boolean startupInventoryCheck;
    int startupInventoryDelay;
    Map<String,String> completionItems;
    @Override public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((d,a,e)->d.register(literal("eunfetch")
            .requires(s->s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
            .executes(c->reply(c.getSource(),"reload | spawn | status | stop | inspect | fetch <tag> <item> <count> [carrier] | chest <tag> <item> <count> [carrier]"))
            .then(argument("args",greedyString()).executes(c->command(c.getSource(),getString(c,"args"))))));
        CommandRegistrationCallback.EVENT.register((d,a,e)->{
            for(String name:new String[]{"botSearchAll","botSearchChest"}) {
                String action=name.equals("botSearchChest")?"chest ":"fetch ";
                d.register(literal(name).requires(s->s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                    .then(argument("args",greedyString())
                        .suggests((context,builder)->BotSuggestions.suggest(builder,
                            config==null||config.allowedTag==null?List.of():List.of(config.allowedTag),
                            completionItems(),"ef"+Long.toString(System.currentTimeMillis(),36)))
                        .executes(c->command(c.getSource(),action+getString(c,"args")))));
            }
            d.register(literal("botStop").requires(s->s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                .executes(c->command(c.getSource(),"stop"))
                .then(argument("worker",word()).executes(c->{
                    if(config==null||!config.worker.equalsIgnoreCase(getString(c,"worker"))){c.getSource().sendFailure(Component.literal("Unknown courier worker"));return 0;}
                    return command(c.getSource(),"stop");
                })));
        });
        ServerLifecycleEvents.SERVER_STARTED.register(s->{server=s;running=true;startupInventoryCheck=true;startupInventoryDelay=60;reload();});
        ServerLifecycleEvents.SERVER_STOPPING.register(s->{running=false;closeTcp();if(job!=null)job.fail("Server stopping; inventory retained, no automatic replay");});
        ServerPlayConnectionEvents.DISCONNECT.register((handler,s)->{
            if(handler.player instanceof EntityPlayerMPFake fake)workerLost(fake);
        });
        ServerTickEvents.END_SERVER_TICK.register(s->{
            if(!running)return;
            maintainWorker();
            if(job!=null&&!job.finished)try{job.tick();}catch(Exception e){job.fail(e.toString());LOG.error("Courier job stopped",e);}
        });
    }
    Map<String,String> completionItems(){
        if(completionItems!=null)return completionItems;
        Map<String,String> result=new LinkedHashMap<>();
        try{
            Map<String,String> names=new HashMap<>();
            var entries=(Set<?>)Class.forName("com.eunsearch.render.ItemNameMap").getMethod("getAllEntries").invoke(null);
            for(Object entry:entries){var e=(Map.Entry<?,?>)entry;names.put((String)e.getKey(),(String)e.getValue());}
            for(var id:net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet()){
                String key=id.toString();if(!key.equals("minecraft:air"))result.put(key,names.getOrDefault(key,key));
            }
            completionItems=Collections.unmodifiableMap(result);
        }catch(ReflectiveOperationException e){LOG.warn("Item completion names unavailable",e);}
        return result;
    }
    void workerLost(EntityPlayerMPFake worker){
        if(!running||config==null||!worker.getGameProfile().name().equalsIgnoreCase(config.worker))return;
        if(job!=null){
            if(job.scan!=null)job.scan.cancel(false);
            job.fail("Worker removed; task cleared, no automatic replay");
            job=null;
        }
        observedWorker=null;respawnPending=config.autoRespawn;respawnDelay=20;
        last=respawnPending?"No job; waiting to respawn "+config.worker+" at idle":"No job; worker offline";
        LOG.info(last);
    }
    void maintainWorker(){
        if(config==null)return;
        if(startupInventoryCheck&&startupInventoryDelay-->0)return;
        ServerPlayer online=server.getPlayerList().getPlayerByName(config.worker);
        if(observedWorker!=null&&(online!=observedWorker||observedWorker.isRemoved()||!observedWorker.isAlive()))workerLost(observedWorker);
        if(online instanceof EntityPlayerMPFake fake&&!fake.isRemoved()&&fake.isAlive()){
            if(startupInventoryCheck){
                try{restoreStartupInventory(fake);startupInventoryCheck=false;}
                catch(Exception e){startupInventoryDelay=100;last="Startup inventory check failed: "+e.getMessage();LOG.error(last,e);return;}
            }
            if(!insideWorkerChunks(fake)){
                workerLost(fake);
                // Operator-requested leash always respawns, independently of the ordinary death setting.
                respawnPending=true;respawnDelay=20;
                fake.kill(Component.literal("EunCourier left permitted chunks; task cleared"));
                respawnPending=true;
                return;
            }
            observedWorker=fake;
            if(respawnPending){respawnPending=false;last="No job; "+config.worker+" ready at idle";LOG.info(last);}
            return;
        }
        if(!respawnPending||online!=null||--respawnDelay>0)return;
        respawnDelay=100; // Back off on login delay or spawn failure; never create duplicate fake players.
        try{spawn();}catch(Exception e){last="No job; respawn pending: "+e.getMessage();LOG.warn(last);}
    }
    void restoreStartupInventory(EntityPlayerMPFake fake){
        // Some resident-player restorers create an empty player during startup without loading its saved inventory.
        // Run once per server start, before accepting jobs; never re-read a stale save during ordinary respawns.
        if(!fake.getInventory().isEmpty())return;
        var saved=server.getPlayerList().loadPlayerData(fake.nameAndId());
        if(saved.isEmpty())return;
        try(var reporter=new net.minecraft.util.ProblemReporter.ScopedCollector(fake.problemPath(),LOG)){
            var input=net.minecraft.world.level.storage.TagValueInput.create(reporter,fake.registryAccess(),saved.get());
            fake.getInventory().load(input.listOrEmpty("Inventory",net.minecraft.world.ItemStackWithSlot.CODEC));
            fake.getInventory().setChanged();fake.inventoryMenu.broadcastChanges();
            if(!fake.getInventory().isEmpty())LOG.info("Restored saved startup inventory for {}",config.worker);
        }
    }
    boolean insideWorkerChunks(ServerPlayer p){
        if(!p.level().dimension().identifier().toString().equals(config.dimension))return false;
        if(Navigation.insideChunks(config.walk,p.getX(),p.getZ()))return true;
        if(config.recovery!=null&&config.recovery.walkAreas!=null)
            for(double[] area:config.recovery.walkAreas)if(Navigation.insideChunks(area,p.getX(),p.getZ()))return true;
        return false;
    }
    int reply(CommandSourceStack s,String message) {s.sendSuccess(()->Component.literal("[EunCourier] "+message),false);return 1;}
    int command(CommandSourceStack source,String line) {
        try {
            String[] a=line.trim().split("\\s+");
            switch(a[0]) {
                case "reload" -> {if(job!=null&&!job.finished)throw new IllegalStateException("Stop current job first");reload();}
                case "spawn" -> spawn();
                case "status" -> {return reply(source,job==null?last:job.describe());}
                case "stop" -> {if(job!=null)job.fail("Stopped by operator; inventory and placed box retained");}
                case "inspect" -> {inspect(source);return 1;}
                case "recovery" -> {Recovery.inspect(this,source);return 1;}
                case "fetch", "chest", "continue" -> {
                    if(a.length<4||a.length>5)throw new IllegalArgumentException("fetch <tag> <item> <count> [carrier]");
                    start(a[1],a[2],Integer.parseInt(a[3]),a.length==5?a[4]:null,source.getTextName(),a[0].equals("chest"),a[0].equals("continue"));
                }
                default -> throw new IllegalArgumentException("Unknown subcommand");
            }
            return reply(source,"OK: "+a[0]);
        }catch(Exception e){source.sendFailure(Component.literal("[EunCourier] "+e.getMessage()));return 0;}
    }
    void reload() {
        try {
            Files.createDirectories(DIR);
            Path file=DIR.resolve("config.json");
            if(!Files.exists(file))Files.writeString(file,JSON.toJson(new Config()));
            config=JSON.fromJson(Files.readString(file),Config.class);
            observedWorker=null;respawnPending=false;respawnDelay=0;
            closeTcp();
            if(config.tcpPort>0){
                Class<?> type=Class.forName("com.eunsearch.EunSearchMod");
                Object search=type.getMethod("getInstance").invoke(null);
                Map<?,?> ports=(Map<?,?>)type.getMethod("getTcpServers").invoke(search);
                if(!ports.containsKey(config.tcpPort))type.getMethod("startTcp",int.class,String.class).invoke(search,config.tcpPort,config.allowedTag);
                startTcp();
            }
            // Other mods initialize player-list hooks during SERVER_STARTED too. Spawn after those hooks finish.
            if(config.autoSpawn){respawnPending=true;respawnDelay=40;}
            LOG.info("Configuration loaded; worker={}, tag={}",config.worker,config.allowedTag);
        }catch(Exception e){last="Config error: "+e.getMessage();LOG.error(last,e);}
    }
    static Vec3 vec(double[] a) {if(a==null||a.length!=3)throw new IllegalArgumentException("Coordinate triple missing in config");for(double v:a)if(!Double.isFinite(v))throw new IllegalArgumentException("Nonfinite coordinate");return new Vec3(a[0],a[1],a[2]);}
    Navigation.Bounds bounds() {
        double[] b=config.walk;
        if(b==null||b.length!=6||b[0]>b[1]||b[2]>b[3]||b[4]>b[5])throw new IllegalArgumentException("Configure walk bounds first");
        return new Navigation.Bounds(b[0],b[1],b[2],b[3],b[4],b[5]);
    }
    Navigation.Area walkArea() {
        List<Navigation.Bounds> areas=new ArrayList<>();areas.add(bounds());
        if(config.recovery!=null&&config.recovery.walkAreas!=null)for(double[] b:config.recovery.walkAreas){
            if(b==null||b.length!=6||java.util.Arrays.stream(b).anyMatch(v->!Double.isFinite(v))||b[0]>b[1]||b[2]>b[3]||b[4]>b[5])throw new IllegalArgumentException("Invalid recovery walk area");
            areas.add(new Navigation.Bounds(b[0],b[1],b[2],b[3],b[4],b[5]));
        }
        return new Navigation.Area(areas);
    }
    EntityPlayerMPFake worker() {
        ServerPlayer p=server.getPlayerList().getPlayerByName(config.worker);
        if(!(p instanceof EntityPlayerMPFake f))throw new IllegalStateException("Worker is not an online Carpet fake player; run eunfetch spawn");
        if(!f.level().dimension().identifier().toString().equals(config.dimension))throw new IllegalStateException("Worker in wrong dimension");
        return f;
    }
    void spawn() {
        if(!config.worker.matches("[A-Za-z0-9_]{1,16}"))throw new IllegalArgumentException("Invalid worker name");
        if(server.getPlayerList().getPlayerByName(config.worker)!=null){worker();return;}
        if(EntityPlayerMPFake.isSpawningPlayer(config.worker))return;
        Vec3 pos=vec(config.idle);
        if(!bounds().contains(pos))throw new IllegalArgumentException("Idle outside walk bounds");
        var level=java.util.stream.StreamSupport.stream(server.getAllLevels().spliterator(),false)
            .filter(l->l.dimension().identifier().toString().equals(config.dimension)).findFirst().orElseThrow();
        EntityPlayerMPFake.createFake(config.worker,server,pos,0,0,level.dimension(),GameType.SURVIVAL,false);
    }
    void start(String tag,String item,int count,String carrier,String requester,boolean chestOnly) throws Exception {
        start(tag,item,count,carrier,requester,chestOnly,false);
    }
    void start(String tag,String item,int count,String carrier,String requester,boolean chestOnly,boolean retained) throws Exception {
        if(startupInventoryCheck)throw new IllegalStateException("Courier is still checking its saved startup inventory");
        if(job!=null&&!job.finished)throw new IllegalStateException("Worker busy");
        if(!Objects.equals(tag,config.allowedTag))throw new IllegalArgumentException("Tag not allowed by this worker config");
        if(count<1||count>2304)throw new IllegalArgumentException("Count must be 1..2304 per shipment");
        if(carrier==null)carrier="ef"+Long.toString(System.currentTimeMillis(),36);
        if(!carrier.matches("[A-Za-z0-9_]{1,16}")||carrier.equalsIgnoreCase(config.worker))throw new IllegalArgumentException("Invalid carrier name");
        if(server.getPlayerList().getPlayerByName(carrier)!=null)throw new IllegalArgumentException("Carrier already online; never take over another player");
        String id=(String)Class.forName("com.eunsearch.render.ItemNameMap").getMethod("getItemId",String.class).invoke(null,item);
        if(id==null)throw new IllegalArgumentException("Unknown item: "+item);
        job=new CourierJob(this,worker(),tag,id,count,carrier,requester,chestOnly,retained);
        job.begin();
    }
    void inspect(CommandSourceStack source) {
        var p=worker();reply(source,"Worker "+p.position()+"; "+p.level().dimension().identifier());
        var center=net.minecraft.core.BlockPos.containing(config.idle[0],config.idle[1],config.idle[2]);
        for(int y=-1;y<=2;y++)for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++) {
            var b=center.offset(x,y,z);var state=p.level().getBlockState(b);
            if(!state.isAir())reply(source,b.toShortString()+" "+state);
        }
    }
    void notifyRequester(String who,String message) {
        notifyRequester(who,Component.literal(message));
    }
    void notifyRequester(String who,Component message) {
        LOG.info(message.getString());last=message.getString();
        var p=server.getPlayerList().getPlayerByName(who);
        if(p!=null)p.sendSystemMessage(Component.literal("[EunCourier] ").append(message));
    }
    void closeTcp() {try{if(tcpSocket!=null)tcpSocket.close();}catch(IOException ignored){}if(tcpThread!=null)tcpThread.interrupt();tcpThread=null;}
    void startTcp() {
        final int port=config.tcpPort;final String workerName=config.worker;
        Thread t=new Thread(()->{
            while(running&&!Thread.currentThread().isInterrupted())try {
                Socket socket=new Socket();tcpSocket=socket;socket.connect(new InetSocketAddress("127.0.0.1",port),3000);
                try(socket;var reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                    var out=new PrintWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8),true)) {
                    JsonObject reg=new JsonObject();reg.addProperty("type","register");reg.addProperty("name",workerName);out.println(new Gson().toJson(reg));
                    String line;while((line=reader.readLine())!=null){
                        JsonObject msg=JsonParser.parseString(line).getAsJsonObject();
                        if(!msg.has("type")||!msg.get("type").getAsString().equals("bot_command"))continue;
                        server.execute(()->{String who=msg.get("player").getAsString();try{
                            String action=msg.get("action").getAsString();
                            if(action.equals("stop")){if(job!=null)job.fail("Stopped from /botStop");}
                            else if(action.equals("fetchAll")||action.equals("fetchChest"))start(msg.get("tag").getAsString(),msg.get("itemId").getAsString(),msg.get("count").getAsInt(),null,who,action.equals("fetchChest"));
                        }catch(Exception e){notifyRequester(who,"Request rejected: "+e.getMessage());}});
                    }
                }
            }catch(Exception e){if(!running||Thread.currentThread().isInterrupted())break;try{Thread.sleep(3000);}catch(InterruptedException stop){break;}}
        },"EunCourier-TCP");t.setDaemon(true);tcpThread=t;t.start();
    }
}
