package com.eunsearch.courier;

import carpet.fakes.ServerPlayerInterface;
import carpet.helpers.EntityPlayerActionPack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;
import java.util.*;
import java.util.function.Predicate;

/** Bounded, loaded-chunk-only search. Movement uses Carpet inputs, never teleportation. */
final class Navigation {
    static boolean insideChunks(double[] area,double x,double z){
        if(area==null||area.length!=6)return false;
        int cx=((int)Math.floor(x))>>4,cz=((int)Math.floor(z))>>4;
        return cx>=(((int)Math.floor(area[0]))>>4)&&cx<=(((int)Math.floor(area[1]))>>4)
            &&cz>=(((int)Math.floor(area[4]))>>4)&&cz<=(((int)Math.floor(area[5]))>>4);
    }
    record Bounds(double minX, double maxX, double minY, double maxY, double minZ, double maxZ) {
        boolean contains(Vec3 p) {
            return p.x >= minX && p.x <= maxX && p.z >= minZ && p.z <= maxZ
                && p.y >= minY - .12 && p.y <= maxY + .12;
        }
    }
    record Node(int x, int y16, int z) {
        Vec3 feet() { return new Vec3(x+.5, y16/16.0, z+.5); }
    }
    record Area(List<Bounds> zones) {
        boolean contains(Vec3 p){return zones.stream().anyMatch(b->b.contains(p));}
        boolean containsMovement(Vec3 p,boolean onGround){return contains(p)||(!onGround&&zones.stream().anyMatch(b->
            p.x>=b.minX&&p.x<=b.maxX&&p.z>=b.minZ&&p.z<=b.maxZ&&p.y>=b.minY-.12&&p.y<=b.maxY+1.25));}
    }
    private record Open(Node node, double score) {}
    final ServerPlayer bot;
    final Area bounds;
    final Vec3 target;
    final Predicate<Vec3> goal;
    final PriorityQueue<Open> open = new PriorityQueue<>(Comparator.comparingDouble(Open::score));
    final Map<Node, Node> parent = new HashMap<>();
    final Map<Node, Double> costs = new HashMap<>();
    final Set<Node> closed = new HashSet<>();
    final Deque<Node> path = new ArrayDeque<>();
    boolean planned, done;
    int ticks, stagnant;
    double bestDistance = Double.MAX_VALUE;
    Node current;

    Navigation(ServerPlayer bot, Area bounds, Vec3 target, Predicate<Vec3> goal) {
        this.bot=bot; this.bounds=bounds; this.target=target; this.goal=goal;
        if (!bounds.contains(bot.position())) throw new IllegalStateException("Bot outside configured walk bounds: " + bot.position());
        Node start=surface((int)Math.floor(bot.getX()), (int)Math.floor(bot.getZ()), bot.getY()+.1);
        if(start==null) throw new IllegalStateException("No safe standing surface under bot");
        costs.put(start,0.0); open.add(new Open(start, heuristic(start)));
    }
    static EntityPlayerActionPack actions(ServerPlayer p) { return ((ServerPlayerInterface)p).getActionPack(); }
    static boolean canReach(ServerPlayer p, BlockPos block) { return canReach(p, p.position(), block); }
    static boolean canReach(ServerPlayer p, Vec3 feet, BlockPos block) {
        return interactionHit(p,feet,block)!=null;
    }
    static BlockHitResult interactionHit(ServerPlayer p, Vec3 feet, BlockPos block) {
        var state=p.level().getBlockState(block);
        List<BlockPos> targets=new ArrayList<>();targets.add(block);
        if(state.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
            &&state.getValue(net.minecraft.world.level.block.ChestBlock.TYPE)!=net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
            BlockPos partner=net.minecraft.world.level.block.ChestBlock.getConnectedBlockPos(block,state);
            if(p.level().getBlockState(partner).getBlock()==state.getBlock())targets.add(partner);
        }
        for(BlockPos targetBlock:targets) {
        Vec3 eye=feet.add(0,p.getEyeHeight(),0), center=Vec3.atCenterOf(block);
        center=Vec3.atCenterOf(targetBlock);
        if(eye.distanceTo(center)>4.45) continue;
        var hit=p.level().clip(new ClipContext(eye,center,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,p));
        if(hit.getType()==HitResult.Type.BLOCK && targets.contains(hit.getBlockPos()))return hit;
        }
        return null;
    }
    private double heuristic(Node n) { Vec3 f=n.feet(); return Math.hypot(f.x-target.x,f.z-target.z); }
    Node surface(int x,int z,double nearY) {
        var level=bot.level();
        for(int y=(int)Math.floor(nearY+1.1); y>=(int)Math.floor(nearY-3.1); y--) {
            BlockPos pos=new BlockPos(x,y,z);
            if(!level.hasChunkAt(pos)) continue;
            var state=level.getBlockState(pos);
            if(!state.getFluidState().isEmpty()) continue;
            String id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            if(id.contains("fire")||id.contains("magma")||id.contains("cactus")||id.contains("campfire")) continue;
            double top=-1;
            for(AABB box:state.getCollisionShape(level,pos,CollisionContext.of(bot)).toAabbs()) {
                if(box.minX<=.5 && box.maxX>=.5 && box.minZ<=.5 && box.maxZ>=.5) top=Math.max(top,box.maxY);
            }
            if(top<=0) continue;
            double feetY=y+top;
            if(feetY>nearY+1.1||feetY<nearY-3.01) continue;
            Vec3 feet=new Vec3(x+.5,feetY,z+.5);
            if(!bounds.contains(feet)) continue;
            AABB body=new AABB(feet.x-.299,feetY+.002,feet.z-.299,feet.x+.299,feetY+1.799,feet.z+.299);
            if(!level.noCollision(bot,body)||level.containsAnyLiquid(body)) continue;
            return new Node(x,(int)Math.round(feetY*16),z);
        }
        return null;
    }
    void tick() {
        if(done) return;
        if(++ticks>2400) throw new IllegalStateException("Navigation timeout");
        if(!planned) {
            for(int i=0;i<180;i++) {
                if(open.isEmpty()||closed.size()>16000) throw new IllegalStateException("No reachable route inside walk bounds");
                Node n=open.remove().node;
                if(!closed.add(n)) continue;
                if(goal.test(n.feet())) {
                    while(n!=null) {path.addFirst(n);n=parent.get(n);}
                    planned=true;break;
                }
                for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                    Node next=surface(n.x+d[0],n.z+d[1],n.y16/16.0);
                    if(next==null||closed.contains(next)) continue;
                    double high=Math.max(n.feet().y,next.feet().y);
                    Vec3 mid=n.feet().add(next.feet()).scale(.5);
                    AABB sweep=new AABB(Math.min(n.feet().x,next.feet().x)-.29,high+.002,Math.min(n.feet().z,next.feet().z)-.29,
                        Math.max(n.feet().x,next.feet().x)+.29,high+1.8,Math.max(n.feet().z,next.feet().z)+.29);
                    if(!bot.level().noCollision(bot,sweep)) continue;
                    double cost=costs.get(n)+1+Math.abs(next.y16-n.y16)/16.0;
                    if(cost>=costs.getOrDefault(next,Double.MAX_VALUE)) continue;
                    parent.put(next,n);costs.put(next,cost);open.add(new Open(next,cost+heuristic(next)));
                }
            }
            return;
        }
        var a=actions(bot);
        if(current==null) {current=path.pollFirst();bestDistance=Double.MAX_VALUE;stagnant=0;}
        if(current==null) {a.stopAll();done=true;return;}
        Vec3 p=current.feet(); double dist=Math.hypot(p.x-bot.getX(),p.z-bot.getZ());
        if(dist<.21&&Math.abs(bot.getY()-p.y)<.3) {current=null;a.stopMovement();return;}
        if(dist<bestDistance-.025) {bestDistance=dist;stagnant=0;} else if(++stagnant>120) throw new IllegalStateException("Bot stuck; retaining inventory");
        if(!bounds.containsMovement(bot.position(),bot.onGround()))
            throw new IllegalStateException("Movement left configured walk bounds");
        a.lookAt(new Vec3(p.x,bot.getEyeY(),p.z));
        a.setForward(dist<.55?.35f:1f);
        a.setSprinting(false);
        a.start(EntityPlayerActionPack.ActionType.JUMP,p.y>bot.getY()+.25?EntityPlayerActionPack.Action.continuous():null);
    }
}
