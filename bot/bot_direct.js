const mineflayer = require('mineflayer');
const { pathfinder, Movements, goals } = require('mineflayer-pathfinder');
const Vec3 = require('vec3').Vec3;
const net = require('net');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

// Load config
const CONFIG_FILE = path.join(__dirname, 'bot_config.json');
let config = {};
try { config = JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8')); } catch(e) {
    console.log('[Bot] 配置文件为空或不存在，等待watchdog填写...');
}

const MC = { host: config.host || '127.0.0.1', port: config.port || 25565 };
const USE_LOCAL = false;
const VELOCITY_SECRET = 'OPaPuSIeOODU';
const LS = { 
    email: config.player?.email || 'placeholder@qq.com', 
    pass: config.player?.pass || 'placeholder', 
    player: config.player?.name || 'placeholder'
};
const TCP_PORT = parseInt(process.argv[2]) || config.tcp_port || 3002;
const IDLE_POS  = (config.idle_pos && config.idle_pos.y) ? { x: config.idle_pos.x, y: config.idle_pos.y, z: config.idle_pos.z } : { x: 0, y: 64, z: 0 };
const PLACE_POS = config.place_pos ? { x: config.place_pos.x, y: config.place_pos.y, z: config.place_pos.z } : { x: Math.floor(IDLE_POS.x), y: IDLE_POS.y, z: Math.floor(IDLE_POS.z) - 0.5 };
const DROP_POS  = config.drop_pos ? { x: config.drop_pos.x, y: config.drop_pos.y, z: config.drop_pos.z } : { x: IDLE_POS.x - 7, y: IDLE_POS.y, z: IDLE_POS.z - 4.5 };
const DROP_LOOK = config.drop_look ? new Vec3(config.drop_look.x, config.drop_look.y, config.drop_look.z) : new Vec3(DROP_POS.x, DROP_POS.y - 1, DROP_POS.z);

// Fake Block class for mineflayer (constructor.name check)
class Block { constructor(name, position) { this.name = name; this.position = position; this.type = 0; } } // Fixed block to place shulkers on

// ==================== VELOCITY MODERN FORWARDING ====================

function writeVarInt(buf, offset, value) {
    do {
        let temp = value & 0x7F;
        value >>>= 7;
        if (value !== 0) temp |= 0x80;
        buf.writeUInt8(temp, offset++);
    } while (value !== 0);
    return offset;
}

function writeString(buf, offset, str) {
    const strBuf = Buffer.from(str, 'utf8');
    offset = writeVarInt(buf, offset, strBuf.length);
    strBuf.copy(buf, offset);
    return offset + strBuf.length;
}

function writeUUID(buf, offset, uuidStr) {
    const hex = uuidStr.replace(/-/g, '');
    buf.writeBigUInt64BE(BigInt('0x' + hex.slice(0, 16)), offset);
    buf.writeBigUInt64BE(BigInt('0x' + hex.slice(16)), offset + 8);
    return offset + 16;
}

function buildVelocityForwarding(sess) {
    const serverAddr = 'mirror';  // must match velocity.toml [servers] name
    const playerIP = '127.0.0.1';
    const uuid = sess.sp.id;
    const username = sess.sp.name;
    const props = sess.sp.properties || [];

    const buf = Buffer.alloc(4096);
    let off = 0;

    // Protocol version (varint = 1)
    off = writeVarInt(buf, off, 1);
    // Server address
    off = writeString(buf, off, serverAddr);
    // Player IP
    off = writeString(buf, off, playerIP);
    // Player UUID (16 bytes)
    off = writeUUID(buf, off, uuid);
    // Username
    off = writeString(buf, off, username);
    // Properties (JSON string, not binary!)
    off = writeString(buf, off, JSON.stringify(props));

    const payload = buf.slice(0, off);
    const hmac = crypto.createHmac('sha256', Buffer.from(VELOCITY_SECRET, 'utf8'));
    hmac.update(payload);
    const signature = hmac.digest();
    console.log('[Bot] HMAC secret:', VELOCITY_SECRET, 'len:', VELOCITY_SECRET.length);
    console.log('[Bot] Payload (' + payload.length + 'b):', payload.toString('hex'));
    console.log('[Bot] Signature (32b):', signature.toString('hex'));
    return Buffer.concat([signature, payload]);
}

function setupVelocityForwarding(cl, sess) {
    let fwdDone = false;
    cl.on('login_plugin_request', (packet) => {
        if (fwdDone) return;
        if (packet.channel === 'velocity:player_info') {
            fwdDone = true;
            const data = buildVelocityForwarding(sess);
            console.log('[Bot] fwd msgId type:', typeof packet.messageId, 'value:', JSON.stringify(packet.messageId));
            // Pre-1.20.5: messageId is varint, needs successful field
            // 1.20.5+: messageId is UUID, no successful field
            const rsp = typeof packet.messageId === 'number'
                ? { messageId: packet.messageId, successful: true, data: data }
                : { messageId: packet.messageId, data: data };
            cl.write('login_plugin_response', rsp);
            console.log('[Bot] Velocity forward sent (' + data.length + ' bytes)');
        }
    });
}

let bot = null;
let cl = null;
let pending = null;
let stopped = false;

// ==================== STOP & IDLE ====================
async function stopTask() {
    stopped = true; pending = null;
    bot.chat('/msg ' + (bot.username || 'bot') + ' 任务已终止');
    try { await nav(bot, DROP_POS.x + 0.5, DROP_POS.y, DROP_POS.z + 0.5); } catch(e) {}
    bot.lookAt(DROP_LOOK, true); await s(200);
    for (let it of bot.inventory.items()) {
        if (it && isShulkerBox(it.name)) {
            try { await bot.toss(it.type, null, it.count); } catch(e) {}
        }
    }
    await s(500);
    log('待命: 返回待命点');
    try { await nav(bot, IDLE_POS.x, IDLE_POS.y, IDLE_POS.z); } catch(e) {}
    stopped = false;
}

async function goIdle() {
    log('待命: 返回待命点');
    try { await nav(bot, IDLE_POS.x, IDLE_POS.y, IDLE_POS.z); } catch(e) {}
}
let reconnectCount = 0;

// ==================== HEARTBEAT ====================
const HB_FILE = __dirname + '/bot_heartbeat.json';
let hbTimer = null, hbStatus = 'idle', hbTask = null;

function hbWrite() { try { fs.writeFileSync(HB_FILE, JSON.stringify({ pid: process.pid, lastBeat: Date.now(), status: hbStatus, task: hbTask })); } catch (e) {} }
function hbStart(status) { hbStatus = status || 'idle'; hbWrite(); if (hbTimer) clearInterval(hbTimer); hbTimer = setInterval(hbWrite, 2000); }
function hbUpdate(status, task) { hbStatus = status; if (task !== undefined) hbTask = task; hbWrite(); }
function hbStop() { if (hbTimer) { clearInterval(hbTimer); hbTimer = null; } }
function readIncompleteTask() { try { if (fs.existsSync(HB_FILE)) { const hb = JSON.parse(fs.readFileSync(HB_FILE, 'utf8')); if (hb.status === 'fetching' && hb.task) { const age = Date.now() - hb.lastBeat; if (age > 15000 && age < 300000) return hb.task; } } } catch (e) {} return null; }

// ==================== UTILITIES ====================

function s(ms) { return new Promise(r => setTimeout(r, ms)); }
function isShulkerBox(name) { return name && name.includes('shulker_box'); }
function ts() { return '[' + new Date().toISOString().substring(11, 23) + ']'; }
function log(...args) { console.log('[LOG]' + ts(), ...args); }

function itemMatch(slotName, target) {
    if (!slotName || !target) return false;
    if (slotName === target || slotName === 'minecraft:' + target || target === 'minecraft:' + slotName) return true;
    let s = slotName.includes(':') ? slotName.substring(slotName.indexOf(':') + 1) : slotName;
    let t = target.includes(':') ? target.substring(target.indexOf(':') + 1) : target;
    return s === t;
}

function isStandable(bot, bx, by, bz) {
    let feet = bot.blockAt(new Vec3(bx, by, bz));
    let head1 = bot.blockAt(new Vec3(bx, by+1, bz));
    let head2 = bot.blockAt(new Vec3(bx, by+2, bz));
    if (!feet || feet.name === 'air' || feet.boundingBox === 'empty') return false;
    if (head2 && head2.name !== 'air') return false;
    // by+1 can be air or carpet
    if (head1 && head1.name !== 'air' && !head1.name.includes('carpet')) return false;
    return true;
}

function dist(x1, z1, x2, z2) {
    return Math.sqrt((x1-x2)**2 + (z1-z2)**2);
}

function nav(bot, x, y, z) {
    return new Promise((res, rej) => {
        log('导航开始 ->', x.toFixed(1), y.toFixed(1), z.toFixed(1));
        bot.pathfinder.setGoal(new goals.GoalNear(x, y, z, 1));
        let t = setTimeout(() => { bot.pathfinder.setGoal(null); log('导航超时'); rej(new Error('timeout')); }, 600000);
        bot.once('goal_reached', () => { clearTimeout(t); log('导航到达'); res(); });
    });
}

function findStandPos(bot, cx, cy, cz) {
    if (!bot.entity || !bot.blockAt) return null;
    // Search horizontal offsets sorted by distance, then vertical offsets
    let hOffsets = [];
    for (let dx = -5; dx <= 5; dx++) {
        for (let dz = -5; dz <= 5; dz++) {
            let dist = Math.abs(dx) + Math.abs(dz);
            if (dist === 0 || dist > 7) continue;
            hOffsets.push([dx, dz, dist]);
        }
    }
    hOffsets.sort((a, b) => a[2] - b[2]);

    // Search from below up to container level (prefer reachable low positions)
    for (let dy = -10; dy <= 0; dy++) {
        let floorY = cy + dy;
        for (let [dx, dz] of hOffsets) {
            let px = cx + dx, pz = cz + dz;
            let feetBlock = bot.blockAt(new Vec3(px, floorY, pz));
            let headBlock = bot.blockAt(new Vec3(px, floorY + 1, pz));
            let headBlock2 = bot.blockAt(new Vec3(px, floorY + 2, pz));
            // Feet on solid block, head+1 clear
            if (feetBlock && feetBlock.boundingBox === 'block' && feetBlock.name !== 'air' &&
                (!headBlock || headBlock.name === 'air') &&
                (!headBlock2 || headBlock2.name === 'air')) {
                let eyeY = floorY + 1.6;
                let d = Math.sqrt((px + 0.5 - cx - 0.5) ** 2 + (eyeY - cy - 0.5) ** 2 + (pz + 0.5 - cz - 0.5) ** 2);
                if (d <= 4.5) return { x: px + 0.5, y: floorY, z: pz + 0.5 };
            }
            // Also accept slabs/stairs/non-full blocks as standing surface
            if (feetBlock && feetBlock.name !== 'air' && feetBlock.boundingBox !== 'empty' &&
                (!headBlock || headBlock.name === 'air') &&
                (!headBlock2 || headBlock2.name === 'air')) {
                let eyeY = floorY + 1.6;
                let d = Math.sqrt((px + 0.5 - cx - 0.5) ** 2 + (eyeY - cy - 0.5) ** 2 + (pz + 0.5 - cz - 0.5) ** 2);
                if (d <= 4.5) return { x: px + 0.5, y: floorY, z: pz + 0.5 };
            }
        }
    }
    return null;
}

// ==================== SHULKER PROCESSING ====================

// Open a block at (x,y,z) without needing a block reference
async function openBlockAt(x, y, z) {
    console.log('[OPEN] sending use_item_on to', x, y, z);
    return new Promise((resolve) => {
        let timer = setTimeout(() => { cl.removeListener('windowOpen', onOpen); console.log('[OPEN] timeout'); resolve(null); }, 10000);
        function onOpen() { clearTimeout(timer); cl.removeListener('windowOpen', onOpen); console.log('[OPEN] windowOpen received, type:', bot.currentWindow?.type); resolve(bot.currentWindow); }
        cl.once('windowOpen', onOpen);
        cl.write('use_item_on', {
            hand: 0, location: { x, y, z }, face: 1,
            cursorPositionX: 0.5, cursorPositionY: 0.5, cursorPositionZ: 0.5,
            insideBlock: false, worldBorderHit: false, sequence: 0
        });
    });
}

async function processShulkerBox(shulkerInvItem, targetItem, need, user) {
    let taken = 0;
    let sname = shulkerInvItem.name;
    if (!bot || !bot.inventory) return { taken: 0 };
    let refX = PLACE_POS.x, refY = PLACE_POS.y, refZ = PLACE_POS.z;
    let tgtX = refX, tgtY = refY + 1, tgtZ = refZ;

    log('潜影盒处理开始:', sname, '目标:', targetItem, '需要:', need);
    let standPlace = findStandPos(bot, refX, refY, refZ);
    if (standPlace) {
        try { await nav(bot, standPlace.x, standPlace.y, standPlace.z); } catch (e) { log('导航到放置点失败:', e.message); }
    } else {
        try { await nav(bot, refX + 1.5, refY, refZ + 0.5); } catch (e) {}
    }

    // 1. Place — use ref position directly (server validates placement)
    let refBlock = bot.blockAt(new Vec3(refX, refY, refZ)) || new Block('stone', new Vec3(refX, refY, refZ));
    await bot.equip(shulkerInvItem, 'hand'); await s(300);
    log('手持潜影盒, 准备放置');
    bot.lookAt(new Vec3(refX + 0.5, refY + 1 + 0.5, refZ + 0.5), true); await s(200);
    bot.setControlState('sneak', true); await s(200);
    try { await bot.placeBlock(refBlock, new Vec3(0, 1, 0)); } catch (e) {}
    bot.setControlState('sneak', false);
    await s(100);
    if (bot.inventory.items().find(it => it && it.name === sname)) {
        if (user) { try { bot.chat('/msg ' + user + ' 放置盒子失败(可能被玩家遮挡), 已丢到输入口'); } catch(e) {} }
    try { await nav(bot, DROP_POS.x + 0.5, DROP_POS.y, DROP_POS.z + 0.5); } catch(e) {}
    bot.lookAt(DROP_LOOK, true); await s(200);
        let fb = bot.inventory.items().find(it => it && it.name === sname);
        if (fb) { try { await bot.toss(fb.type, null, 1); } catch(e) {} }
        log('放置失败, 盒子已丢到输入口');
        return { taken: 0 };
    }
    log('潜影盒已放置');

    // 2. Open with fake Block (mineflayer checks constructor.name === 'Block')
    let fakeBlock = new Block('shulker_box', new Vec3(tgtX, tgtY, tgtZ));
    let sch;
    bot.setControlState('sneak', true); await s(200);
    try { sch = await bot.openContainer(fakeBlock); } catch (e) { log('打开潜影盒失败:', e.message); bot.setControlState('sneak', false); return { taken: 0 }; }
    bot.setControlState('sneak', false);
    if (!sch) { log('打开潜影盒返回null'); return { taken: 0 }; }
    log('潜影盒已打开, 内部:',
        sch.containerItems().map(i => i.name.includes(':') ? i.name.split(':')[1] : i.name + ' x' + i.count).join(' | ') || '(空)');

    for (let si of sch.containerItems()) {
        if (need <= 0) break;
        if (itemMatch(si.name, targetItem) && !isShulkerBox(si.name)) {
            let a = Math.min(si.count, need);
            try { await sch.withdraw(si.type, null, a); need -= a; taken += a; } catch (e) {}
        }
    }
    sch.close(); await s(200);
    log('从潜影盒取出:', taken, '个, 仍需:', need);

    // 3. 挖掘 + 等待物品掉落
    let blk = bot.blockAt(new Vec3(tgtX, tgtY, tgtZ)) || fakeBlock;
    setTimeout(() => bot.emit('blockUpdate', fakeBlock, { name: 'air', position: fakeBlock.position, type: 0 }), 2000);
    try { await bot.dig(blk); } catch (e) {}
    log('挖掘潜影盒, 等待掉落');
    await s(2000);  // 等待服务器确认挖掘 + 物品掉落
    // 走到潜影盒掉落位置捡回盒子
    try { await nav(bot, tgtX + 0.5, tgtY, tgtZ + 0.5); } catch (e) {}
    await s(1000);  // 等待捡起
    // 导航到丢弃点处理用过的盒子
    try { await nav(bot, DROP_POS.x + 0.5, DROP_POS.y, DROP_POS.z + 0.5); } catch (e) {}
    bot.lookAt(DROP_LOOK, true); await s(200);
    let sb = bot.inventory.items().find(it => it && it.name === sname);
    if (sb) { try { await bot.toss(sb.type, null, 1); log('把用过的潜影盒输入口丢到'); } catch (e) {} }
    log('潜影盒处理完成, 取得:', taken);
    return { taken };
}

// ==================== CONTAINER EXECUTION ====================

async function processOneContainer(planEntry, item, usedSlots, user) {
    let { x, y, z, need_to_take, shulkerSlots } = planEntry;
    let remaining = need_to_take;
    let taken = 0;
    let hasShulkerItems = shulkerSlots && shulkerSlots.length > 0;

    log('处理容器 x=' + x, 'y=' + y, 'z=' + z, '需取:', need_to_take, '有潜影盒:', hasShulkerItems);

    let isDouble = planEntry.isDoubleChest && planEntry.partnerX !== undefined;
    let ranges = planEntry.ranges || [];
    let positions = [[x, y, z]];
    if (isDouble) positions.push([planEntry.partnerX, y, planEntry.partnerZ]);
    else { let ct = planEntry.type || ''; if (ct==='chest'||ct==='trapped_chest') { positions.push([x+1,y,z],[x-1,y,z],[x,y,z+1],[x,y,z-1]); } }

    let stand = null, containerPos = null, blk = null;

    // 如果有预设范围，尝试范围寻路
    if (ranges.length > 0) {
        log('预设范围寻路, 范围数:', ranges.length, isDouble ? '双箱' : '单箱');
        let candidates = [];
        if (isDouble) {
            let a1 = [x, y, z], a2 = [planEntry.partnerX, y, planEntry.partnerZ];
            let axAligned = a1[0] === a2[0]; // true=x对齐, false=z对齐
            let alignedVal = axAligned ? a1[0] : a1[2];
            log('双箱对齐:', axAligned ? ('x='+alignedVal) : ('z='+alignedVal));
            for (let r of ranges) {
                let mx1 = Math.min(r.x1, r.x2), mx2 = Math.max(r.x1, r.x2);
                let my1 = Math.min(r.y1, r.y2), my2 = Math.max(r.y1, r.y2);
                let mz1 = Math.min(r.z1, r.z2), mz2 = Math.max(r.z1, r.z2);
                log('  搜索范围:', mx1, my1, mz1, '~', mx2, my2, mz2);
                for (let bx = mx1; bx <= mx2; bx++) {
                    for (let bz = mz1; bz <= mz2; bz++) {
                        let cv = axAligned ? bx : bz;
                        if (cv !== alignedVal) continue;
                        for (let by = my1; by <= my2; by++) {
                            if (isStandable(bot, bx, by, bz)) {
                                candidates.push({x: bx+0.5, y: by, z: bz+0.5, bx, by, bz});
                            }
                        }
                    }
                }
            }
            log('  双箱候选:', candidates.length, '个');
            if (candidates.length === 1) {
                // 找离候选最近的伙伴箱
                let c = candidates[0];
                let d1 = dist(c.bx, c.bz, a1[0], a1[2]);
                let d2 = dist(c.bx, c.bz, a2[0], a2[2]);
                let target = d1 <= d2 ? a1 : a2;
                let tPos = new Vec3(target[0], target[1], target[2]);
                stand = c; containerPos = tPos;
                log('  单候选, 选伙伴箱(' + target[0] + ',' + target[2] + '), 站立(' + c.bx + ',' + c.by + ',' + c.bz + ')');
                try { await nav(bot, c.x, c.y, c.z); } catch(e) {}
            } else if (candidates.length >= 2) {
                // 找离任意伙伴箱最近的候选
                let bestC = null, bestDist = Infinity;
                for (let c of candidates) {
                    let d = Math.min(dist(c.bx, c.bz, a1[0], a1[2]), dist(c.bx, c.bz, a2[0], a2[2]));
                    if (d < bestDist) { bestDist = d; bestC = c; }
                }
                if (bestC) {
                    let d1 = dist(bestC.bx, bestC.bz, a1[0], a1[2]);
                    let d2 = dist(bestC.bx, bestC.bz, a2[0], a2[2]);
                    let target = d1 <= d2 ? a1 : a2;
                    let tPos = new Vec3(target[0], target[1], target[2]);
                    stand = bestC; containerPos = tPos;
                    log('  多候选, 选伙伴箱(' + target[0] + ',' + target[2] + '), 站立(' + bestC.bx + ',' + bestC.by + ',' + bestC.bz + ')');
                    try { await nav(bot, bestC.x, bestC.y, bestC.z); } catch(e) {}
                }
            }
        } else {
            // 单箱：找最近的站立位
            let bestDist = Infinity, bestC = null;
            for (let r of ranges) {
                let mx1 = Math.min(r.x1, r.x2), mx2 = Math.max(r.x1, r.x2);
                let my1 = Math.min(r.y1, r.y2), my2 = Math.max(r.y1, r.y2);
                let mz1 = Math.min(r.z1, r.z2), mz2 = Math.max(r.z1, r.z2);
                for (let bx = mx1; bx <= mx2; bx++) {
                    for (let bz = mz1; bz <= mz2; bz++) {
                        if (bx !== x || bz !== z) continue;  // 单箱对齐X和Z
                        for (let by = my1; by <= my2; by++) {
                            if (isStandable(bot, bx, by, bz)) {
                                let d = Math.sqrt((bx-x)**2 + (by-y)**2 + (bz-z)**2);
                                candidates.push({x: bx+0.5, y: by, z: bz+0.5, bx, by, bz});
                                if (d < bestDist) { bestDist = d; bestC = {x: bx+0.5, y: by, z: bz+0.5, bx, by, bz}; }
                            }
                        }
                    }
                }
            }
            if (bestC) {
                stand = bestC; containerPos = new Vec3(x, y, z);
                log('  单箱选站立(' + bestC.bx + ',' + bestC.by + ',' + bestC.bz + ')');
                try { await nav(bot, bestC.x, bestC.y, bestC.z); } catch(e) {}
            }
        }
        if (stand && containerPos) {
            blk = bot.blockAt(containerPos);
            if (!blk || !blk.name.includes('chest') && !blk.name.includes('barrel')) {
                log('  范围寻路blockAt失败, 退回当前方式');
                stand = null; containerPos = null; blk = null;
            }
        }
    }

    // Fallback: 当前方式
    if (!stand || !blk) {
        log('退回当前寻路方式');
        let botPos = bot.entity.position;
        positions.sort((a, b) => {
            let da = Math.abs(a[0]-botPos.x)+Math.abs(a[1]-botPos.y)+Math.abs(a[2]-botPos.z);
            let db = Math.abs(b[0]-botPos.x)+Math.abs(b[1]-botPos.y)+Math.abs(b[2]-botPos.z);
            return da - db;
        });
        for (let [px, py, pz] of positions) {
            stand = findStandPos(bot, px, py, pz);
            if (!stand) continue;
            let pathGoal = new goals.GoalNear(stand.x, stand.y, stand.z, 1);
            let pathResult = bot.pathfinder.getPathTo(new Movements(bot), pathGoal, 2000);
            if (pathResult.status !== 'success') { log('(' + px + ',' + py + ',' + pz + ') 不可达(' + pathResult.status + '), 跳过'); stand = null; continue; }
            try { await nav(bot, stand.x, stand.y, stand.z); } catch (e) { log('导航到(' + px + ',' + py + ',' + pz + ')失败:', e.message); stand = null; continue; }
            if (!bot.entity) return { taken: 0, remaining };
            containerPos = new Vec3(px, py, pz);
            blk = bot.blockAt(containerPos);
            if (blk && (blk.name.includes('chest') || blk.name.includes('barrel') || blk.name.includes('shulker'))) {
                if (px !== x || pz !== z) log('使用双箱另一半: (' + px + ',' + py + ',' + pz + ')');
                break;
            }
            stand = null;
        }
    }
    if (!stand || !blk) { log('所有位置均无法到达'); return { taken: 0, remaining }; }
    let cx = containerPos.x, cy = containerPos.y, cz = containerPos.z;
    log('容器方块:', blk.name, '位置:', cx, cy, cz, '站立:', stand.x.toFixed(1), stand.y.toFixed(1), stand.z.toFixed(1));

    let eyeY = stand.y + 1.6;
    let lookY = cy - 0.5;
    log('眼睛Y:', eyeY.toFixed(1), '容器Y:', cy, '瞄准Y:', lookY.toFixed(1));
    bot.lookAt(new Vec3(cx + 0.5, lookY, cz + 0.5), true);
    await s(500);

    let ch;
    // 尝试打开，失败则调整瞄准点重试
    let openAttempts = 0;
    while (openAttempts < 3) {
        try { ch = await bot.openContainer(blk); if (ch) break; } catch (e) { log('打开失败(' + (openAttempts+1) + '/3):', e.message); }
        openAttempts++;
        if (openAttempts < 3) {
            // 调整瞄准: 容器底部偏上一点，确保视线命中方块面
            bot.lookAt(new Vec3(x + 0.5, y + 0.2, z + 0.5), true);
            await s(300);
        }
    }
    if (!ch) { log('无法打开容器'); return { taken: 0, remaining }; }
    log('容器已打开, 物品数:', ch.containerItems().length);
    // 扫描日志: 列出各类物品和数量
    let scanSummary = ch.containerItems().map(i => {
        let name = i.name.includes(':') ? i.name.split(':')[1].replace(/_/g, ' ') : i.name.replace(/_/g, ' ');
        return name + ' x' + i.count + '[槽' + i.slot + ']';
    }).join(' | ');
    log('容器扫描:', scanSummary.substring(0, 400));

    try {
        let allItems = ch.containerItems();
        let directs = allItems.filter(i => itemMatch(i.name, item) && !isShulkerBox(i.name));
        log('容器直接物品:', directs.reduce((s, i) => s + i.count, 0), '个, 仍需:', remaining);

        for (let it of directs) {
            if (remaining <= 0) break;
            let a = Math.min(it.count, remaining);
            await ch.withdraw(it.type, null, a);
            remaining -= a; taken += a;
            log('取走直接物品:', it.name, 'x' + a, '剩余需:', remaining);
        }

        if (remaining > 0 && hasShulkerItems) {
            let sortedSlots = [...shulkerSlots].sort((a, b) => {
                let sa = (typeof a.slot === 'number') ? a.slot : a[0];
                let sb = (typeof b.slot === 'number') ? b.slot : b[0];
                return sa - sb;
            });
            log('按槽位取盒:', sortedSlots.map(ss => (typeof ss.slot === 'number') ? ss.slot : ss[0]).join(','), '仍需:', remaining);

            for (let i = 0; i < sortedSlots.length && remaining > 0; i++) {
                let ss = sortedSlots[i];
                let targetSlot = (typeof ss.slot === 'number') ? ss.slot : ss[0];
                let slotKey = x + ',' + y + ',' + z + ',' + targetSlot;

                if (usedSlots && usedSlots.has(slotKey)) {
                    log('槽位', targetSlot, '已取过, 跳过');
                    continue;
                }

                let slotItem = ch.slots[targetSlot];
                if (!slotItem || !isShulkerBox(slotItem.name)) {
                    log('槽位', targetSlot, '不匹配 (期望潜影盒, 实际:', slotItem ? slotItem.name : '空', '), 重新扫描');
                    try { ch.close(); } catch (e) {}
                    return { taken, remaining, rescan: true };
                }

                let sname = slotItem.name;
                log('clickWindow取盒 槽位:', targetSlot, sname);
                try { await bot.clickWindow(targetSlot, 0, 1); } catch (e) { log('clickWindow失败:', e.message); continue; }
                if (usedSlots) usedSlots.add(slotKey);
                await s(300);
                ch.close(); ch = null;
                await s(500);

                let shInv = bot.inventory.items().find(it => it && it.name === sname);
                if (!shInv) {
                    log('背包未找到盒子, 跳过');
                    try { await nav(bot, stand.x, stand.y, stand.z); } catch (e) {}
                    let rb = bot.blockAt(containerPos);
                    if (rb) try { ch = await bot.openContainer(rb); } catch (e) {}
                    continue;
                }

                let result = await processShulkerBox(shInv, item, remaining, user);
                remaining -= result.taken; taken += result.taken;
                log('潜影盒结果:', result.taken, '个, 累计:', taken, '剩余需:', remaining);

                try { await nav(bot, stand.x, stand.y, stand.z); } catch (e) {}
                await s(300);
                let rb = bot.blockAt(containerPos);
                if (!rb) break;
                try { ch = await bot.openContainer(rb); } catch (e) { break; }
                if (!ch) break;
            }
        }
    } catch (e) {
        log('容器处理异常:', e.message);
    } finally {
        if (ch && !ch.closed) { try { ch.close(); } catch (e) {} }
    }
    log('容器处理完成, 取得:', taken, '剩余:', remaining);
    return { taken, remaining };
}

// ==================== WAREHOUSE PLANNING ====================

function planContainers(containers, stillNeed, ranges) {
    // 按散装数量降序排列，优先取散装物品多的容器
    let sorted = [...containers].sort((a, b) => (b.directCount || 0) - (a.directCount || 0));
    let plan = [];
    for (let c of sorted) {
        plan.push({ x: c.x, y: c.y, z: c.z, count: c.count, directCount: c.directCount || 0, type: c.type, need_to_take: Math.min(stillNeed, c.count), shulkerSlots: c.shulkerSlots || [], isDoubleChest: c.isDoubleChest || false, partnerX: c.partnerX, partnerZ: c.partnerZ, ranges: ranges });
    }
    return plan;
}

// ==================== DELIVERY ====================

async function deliverItems(player, item, totalGot, totalCount) {
    if (totalGot <= 0) return;
    log('出货给', player, '物品:', item, '数量:', totalGot + '/' + totalCount);
    bot.chat('/msg ' + player + ' ' + totalGot + '/' + totalCount + ' ' + item + ', 传输中...');
    bot.look(bot.entity.yaw, Math.PI / 2, true); await s(300);

    let suffix = 1;
    while (true) {
        let remaining = bot.inventory.items().filter(it => it && itemMatch(it.name, item)).reduce((s, i) => s + i.count, 0);
        if (remaining <= 0) break;
        if (suffix > 99) {
            log('假人召唤全部失败(suffix>99), 物品掉在地上');
            bot.chat('/msg ' + player + ' 假人召满99个全部失败，物品掉在地上');
            for (let it of bot.inventory.items()) {
                if (it && itemMatch(it.name, item)) {
                    try { await bot.toss(it.type, null, it.count); } catch (e) {}
                }
            }
            break;
        }

        let dn = player + suffix;
        bot.chat('/player ' + dn + ' spawn'); await s(2500);
        log('等待假人', dn, '生成 (剩余', remaining, '个)');
        let fp = null;
        for (let a = 0; a < 8; a++) { fp = Object.values(bot.entities).find(e => e.username === dn); if (fp) break; await s(1000); }
        if (!fp) {
            log('假人', dn, '召唤失败, 尝试下一个');
            suffix++;
            continue;
        }
        log('假人已生成, 开始转交物品');
        try {
            let fc = await bot.openContainer(fp);
            if (fc) {
                let deposited = 0;
                for (let it of bot.inventory.items()) {
                    if (it && itemMatch(it.name, item)) {
                        try { await fc.deposit(it.type, null, it.count); deposited += it.count; } catch (e) { log('deposit失败:', e.message); }
                    }
                }
                log('假人' + dn + '转交', deposited, '个');
                fc.close();
            }
        } catch (e) { log('出货异常:', e.message); }
        await s(500);
        bot.chat('/player ' + dn + ' kill');
        log('假人' + dn + '已移除');
        suffix++;
    }
    bot.look(bot.entity.yaw, 0, true);
    bot.chat('/msg ' + player + ' 完成! ' + totalGot + ' ' + item);
    log('出货完成');
}

// ==================== TCP CLIENT ====================

let tcpClient = null, tcpBuffer = '', tcpPendingReq = null;

function tcpConnect() {
    return new Promise((resolve, reject) => {
        if (tcpClient && !tcpClient.destroyed) { resolve(tcpClient); return; }
        let timer = setTimeout(() => { tcpClient = null; reject(new Error('timeout')); }, 10000);
        tcpClient = new net.Socket();
        tcpClient.connect(TCP_PORT, MC.host, () => { clearTimeout(timer); console.log('[Bot] TCP connected'); 
            tcpClient.write(JSON.stringify({ type: 'register', name: LS.player }) + '\n');
            resolve(tcpClient); });
        tcpClient.on('data', (data) => {
            tcpBuffer += data.toString('utf-8');
            let lines = tcpBuffer.split('\n'); tcpBuffer = lines.pop() || '';
            for (let line of lines) {
                if (!line.trim()) continue;
                try {
                    let msg = JSON.parse(line);
                    // 处理来自模组的 bot 指令
                    if (msg.type === 'bot_command') {
                        handleBotCommand(msg).catch(e => log('bot_command失败:', e.message));
                        continue;
                    }
                    if (tcpPendingReq) { let r = tcpPendingReq; tcpPendingReq = null; clearTimeout(r.timer);
                        if (msg.type === 'error') r.reject(new Error(msg.message)); else r.resolve(msg); }
                } catch (e) {}
            }
        });
        tcpClient.on('error', (e) => { clearTimeout(timer); if (tcpPendingReq) { let r = tcpPendingReq; tcpPendingReq = null; clearTimeout(r.timer); r.reject(e); } });
        tcpClient.on('close', () => { clearTimeout(timer); tcpClient = null; if (tcpPendingReq) { let r = tcpPendingReq; tcpPendingReq = null; clearTimeout(r.timer); r.reject(new Error('disconnected')); } });
    });
}

async function handleBotCommand(msg) {
    if (!bot || !bot.entity) { log('bot未就绪, 忽略指令'); return; }
    if (msg.action === 'stop') {
        log('收到bot指令: stop');
        await stopTask();
    } else if (msg.action === 'fetchAll' || msg.action === 'fetchChest') {
        let player = msg.player || 'unknown';
        let tag = msg.tag || '';
        let item = msg.itemId || '';
        let count = msg.count || 1;
        let fetchType = msg.action;
        log('收到bot指令:', fetchType, player, tag, item, 'x' + count);
        if (pending) { bot.chat('/msg ' + player + ' 正在执行任务'); return; }
        pending = true;
        try { bot.pathfinder.setGoal(null); } catch(e) {}
        handleFetch(player, tag, item, count, 0, fetchType).catch(e => { log('fetch失败:', e.message); pending = null; });
    }
}

function tcpFetch(fetchType, tag, itemId, count) {
    return new Promise((resolve, reject) => {
        if (!tcpClient || tcpClient.destroyed) { reject(new Error('not connected')); return; }
        if (tcpPendingReq) { reject(new Error('busy')); return; }
        let timer = setTimeout(() => { tcpPendingReq = null; reject(new Error('timeout')); }, 120000);
        tcpPendingReq = { resolve, reject, timer };
        tcpClient.write(JSON.stringify({ type: fetchType, tag, itemId, count }) + '\n', () => console.log('[Bot] TCP sent'));
    });
}

async function tryTcpFetch(fetchType, tag, itemId, count) {
    if (!tcpClient || tcpClient.destroyed) await tcpConnect();
    return tcpFetch(fetchType, tag, itemId, count);
}

// ==================== MAIN FETCH ====================

    async function handleFetch(user, tag, item, totalNeed, resumeGot, fetchType) {
        fetchType = fetchType || 'fetchAll';
        // 清掉任何正在进行的导航，避免并发冲突
        try { bot.pathfinder.setGoal(null); } catch(e) {}
        log('========== FETCH 开始 ==========');
    log('请求者:', user, '标签:', tag, '物品:', item, '数量:', totalNeed, resumeGot ? '续接:已取' + resumeGot : '');
    let stillNeed = totalNeed, totalGot = resumeGot || 0;
    hbUpdate('fetching', { user, tag, item, totalNeed, totalGot: 0 });

    if (!resumeGot) {
        let ownDir = bot.inventory.items().filter(i => i && itemMatch(i.name, item) && !isShulkerBox(i.name));
        let ownCount = ownDir.reduce((s, i) => s + i.count, 0);
        log('自身直接物品:', ownCount);
        if (ownCount >= stillNeed) { totalGot = stillNeed; stillNeed = 0; }
        else { totalGot = ownCount; stillNeed -= ownCount; }

        if (stillNeed > 0) {
            let ownSh = bot.inventory.items().filter(i => i && isShulkerBox(i.name));
            log('自身潜影盒:', ownSh.length, '个');
            for (let sh of ownSh) { if (stillNeed <= 0) break; let r = await processShulkerBox(sh, item, stillNeed, user); stillNeed -= r.taken; totalGot += r.taken; }
        }
    } else {
        stillNeed = totalNeed - resumeGot;
        // 续接时检查背包里有没有之前取的残留物品
        let ownDir = bot.inventory.items().filter(i => i && itemMatch(i.name, item) && !isShulkerBox(i.name));
        let ownCount = ownDir.reduce((s, i) => s + i.count, 0);
        if (ownCount > 0) { totalGot += ownCount; stillNeed -= ownCount; log('续接: 背包已有', ownCount, '个'); }
    }
    hbUpdate('fetching', { user, tag, item, totalNeed, totalGot });
    log('自身总计:', totalGot, '仍需:', stillNeed);
    if (stillNeed <= 0) { await deliverItems(user, item, totalGot, totalNeed); hbUpdate('idle', null); pending = null; return; }

    let containers = [], whTotal = 0, respRanges = [];
    try {
        log('查询仓库 TCP...');
        let resp = await tryTcpFetch(fetchType, tag, item, totalNeed);
        containers = resp.containers || [];
        respRanges = resp.ranges || [];
        whTotal = containers.reduce((s, c) => s + (c.count || 0), 0);
        log('仓库返回:', containers.length, '个容器, 总计', whTotal, '范围:', respRanges.length, '个');
    } catch (e) { log('TCP查询失败:', e.message); hbUpdate('idle', null); pending = null; return; }

    if (totalGot + whTotal < totalNeed) {
        bot.chat('/msg ' + user + ' 物品不足');
        if (totalGot > 0) await deliverItems(user, item, totalGot, totalNeed);
        hbUpdate('idle', null); pending = null; return;
    }

    let plan = planContainers(containers, stillNeed, respRanges);
    log('容器计划:', plan.length, '个容器');
    let rescanCount = 0;
    const MAX_RESCANS = 3;
    let usedSlots = new Set();
    for (let pi = 0; pi < plan.length && stillNeed > 0; pi++) {
        hbUpdate('fetching', { user, tag, item, totalNeed, totalGot });
        plan[pi].need_to_take = Math.min(stillNeed, plan[pi].count);
        let r = await processOneContainer(plan[pi], item, usedSlots, user);
        totalGot += r.taken; stillNeed -= r.taken;
        if (r.rescan && rescanCount < MAX_RESCANS) {
            rescanCount++;
            log('槽位不匹配, 第' + rescanCount + '次重新扫描...');
            try {
                let resp = await tryTcpFetch(fetchType, tag, item, stillNeed);
                containers = resp.containers || [];
                plan = planContainers(containers, stillNeed, respRanges);
                pi = -1;
                log('重新扫描完成, 新计划:', plan.length, '个容器');
            } catch (e) { log('重新扫描失败:', e.message); break; }
        }
    }
    hbUpdate('fetching', { user, tag, item, totalNeed, totalGot });

    if (totalGot > 0) await deliverItems(user, item, totalGot, totalNeed);
    else bot.chat('/msg ' + user + ' 未取到 ' + item);
    hbUpdate('idle', null);
    pending = null;
    log('========== FETCH 完成 ==========');
    goIdle().catch(() => {});
}

// ==================== MAIN ====================

async function main() {
    let mc = require('minecraft-protocol');
    const https = require('https');

    // LittleSkin auth
    let ygg = require('yggdrasil')({
        host: 'https://littleskin.cn/api/yggdrasil/authserver',
        sessionHost: 'https://littleskin.cn/api/yggdrasil/sessionserver'
    });
    let ct = 'qs-' + Date.now();
    const AUTH_URL = 'https://littleskin.cn/api/yggdrasil/authserver';

    let sess = await new Promise((res, rej) => {
        ygg.auth({ user: LS.email, pass: LS.pass, token: ct, agent: 'Minecraft', requestUser: true }, (err, r) => {
            if (err) return rej(err);
            if (!r.selectedProfile) r.selectedProfile = r.availableProfiles?.find(p => p.name === LS.player) || r.availableProfiles?.[0];
            if (!r.selectedProfile) return rej(new Error('No profile found'));
            // Keep UUID exactly as returned by authserver (no dashes)
            console.log('[Auth] ' + r.selectedProfile.name, 'UUID:', r.selectedProfile.id);
            console.log('[Auth] token selectedProfile:', JSON.stringify(r.selectedProfile));
            res({ at: r.accessToken, ct: r.clientToken, sp: r.selectedProfile });
        });
    });

    // CRITICAL: LittleSkin returns JWT token with selectedProfile=null.
    // Must call /refresh with selectedProfile to bind profile to token,
    // otherwise /join always returns 403.
    console.log('[Auth] refreshing token to bind profile...');
    const refreshBody = JSON.stringify({
        accessToken: sess.at,
        clientToken: sess.ct,
        selectedProfile: { id: sess.sp.id, name: sess.sp.name },
        requestUser: false
    });
    const freshSess = await new Promise((res2, rej2) => {
        const u = new URL(AUTH_URL + '/refresh');
        const req = https.request({
            hostname: u.hostname, port: u.port || 443, path: u.pathname,
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(refreshBody) }
        }, (resp) => {
            let data = '';
            resp.on('data', c => data += c);
            resp.on('end', () => {
                if (resp.statusCode === 200) {
                    const r = JSON.parse(data);
                    console.log('[Auth] refresh OK, new token:', r.accessToken?.substring(0, 50) + '...');
                    // Update token but keep original profile
                    res2({ at: r.accessToken, ct: sess.ct, sp: sess.sp });
                } else {
                    rej2(new Error('refresh failed: ' + resp.statusCode + ' ' + data));
                }
            });
        });
        req.on('error', e => rej2(e));
        req.write(refreshBody);
        req.end();
    });
    sess = freshSess;

    let host = MC.host;
    let port = MC.port;

    console.log('[Bot] connecting to', host + ':' + port, '(VC)');
    cl = mc.createClient({
        host, port, username: sess.sp.name,
        version: false,
        auth: 'mojang',
        session: { accessToken: sess.at, clientToken: sess.ct, selectedProfile: sess.sp },
        skipValidation: true,
        checkTimeoutInterval: 120000,
        authServer: 'https://littleskin.cn/api/yggdrasil/authserver',
        sessionServer: 'https://littleskin.cn/api/yggdrasil/sessionserver'
    });
    cl.setMaxListeners(50);

    if (USE_LOCAL) {
        setupVelocityForwarding(cl, sess);
    }

    bot = mineflayer.createBot({ client: cl });
    bot.loadPlugin(pathfinder);

    cl.on('error', e => console.error('[MC] err:', e.message || e));
    cl.on('end', (reason) => log('连接断开:', reason));
    cl.on('kick', (reason) => log('被踢出:', JSON.stringify(reason)));
    cl.on('connect', () => log('TCP已连接'));
    cl.on('success', () => log('登录成功'));
    cl.on('login', (packet) => log('登录包:', JSON.stringify(packet).substring(0, 200)));
    cl.on('keep_alive', (pk) => { /* 心跳正常 */ });

    bot.on('death', () => { log('bot被杀死, 退出等待重启'); setTimeout(() => process.exit(0), 1000); });

    let firstSpawn = true;
    bot.on('spawn', () => {
        console.log('[Bot] SPAWNED');
        reconnectCount = 0;
        hbStart('idle');
        pending = null;
        // Only go idle if config has valid coordinates
        if (IDLE_POS.y > 0) {
            setTimeout(() => stopTask().catch(() => {}), 2000);
        } else {
            log('配置文件无有效待命坐标，跳过stopTask');
        }
        if (!firstSpawn) return;
        firstSpawn = false;

        // Anti-idle: look around every 60s to prevent server idle kick
        // Anti-idle: 待机时持续小幅转头防止被踢
        let antiIdleTimer = setInterval(() => {
            if (bot.entity && !pending && (!bot.pathfinder.goal || bot.pathfinder.goal === null)) {
                bot.look(bot.entity.yaw + (Math.random() - 0.5) * 1.0, bot.entity.pitch + (Math.random() - 0.5) * 0.6, true);
            }
        }, 3000);
        cl.on('end', () => clearInterval(antiIdleTimer));
        // Fix teleport: redirect accept_teleportation through proto767 (1.21.11 via proxy)
        let proto767 = mc.createSerializer({ version: '1.21.1', state: 'play', direction: 'toServer' });
        let _origWrite = cl.write.bind(cl);
        cl.write = function(name, params) {
            if (name === 'accept_teleportation' || name === 'teleport_confirm') {
                let buf = proto767.createPacketBuffer('teleport_confirm', params);
                try { cl.framer.writeFrame(buf); } catch (e) {}
                return;
            }
            return _origWrite(name, params);
        };
        let md = require('minecraft-data')(bot.version);
        let m = new Movements(bot, md);
        m.canDig = false; m.allowParkour = true; m.allow1by1towers = false;
        bot.pathfinder.setMovements(m);
        (async function connectLoop() {
            while (true) {
                try { await tcpConnect(); log('TCP已连接'); break; }
                catch (e) { log('TCP连接失败:', e.message, '2秒后重试...'); await s(2000); }
            }
        })();

        // Auto-fetch: set env AUTO_FETCH=player,tag,item,count (runs once only)
        const autoFetch = process.env.AUTO_FETCH;
        if (autoFetch && !process.env._AUTO_FETCH_DONE) {
            process.env._AUTO_FETCH_DONE = '1';
            const parts = autoFetch.split(',');
            const afUser = parts[0]?.trim() || bot.username;
            const afTag = parts[1]?.trim() || 'main';
            const afItem = parts[2]?.trim() || 'carrot';
            const afCount = parseInt(parts[3]) || 64;
            log('自动FETCH触发:', afUser, afTag, afItem, 'x' + afCount);
            setTimeout(() => {
                if (!pending) {
                    pending = true;
                    handleFetch(afUser, afTag, afItem, afCount, 0, "fetchAll").catch(e => { log('自动FETCH失败:', e.message); pending = null; });
                }
            }, 3000);
        }
    });

    bot.on('chat', (user, msg) => {
        if (user === bot.username) return;
        let fm = msg.trim().match(/^!fetch\s+(.+?)\s+(\S+)\s*(\d+)?/);
        if (fm) {
            if (pending) { bot.chat('/msg ' + user + ' 正在执行任务'); return; }
            let tag = fm[1].replace(/^"/, '').replace(/"$/, '');
            let item = fm[2], c = parseInt(fm[3]) || 64;
            pending = true;
            handleFetch(user, tag, item, c, 0, "fetchAll").catch(e => { console.error('[Bot] fetch err:', e); pending = null; });
        }
        if (msg.trim() === '!come') {
            let p = bot.players[user];
            if (p) nav(bot, p.entity.position.x, p.entity.position.y, p.entity.position.z).catch(() => {});
        }
        if (msg.trim() === '!stop') {
            stopTask().catch(e => log('stop失败:', e.message));
        }
        let cm = msg.trim().match(/^!ceshi\s+(\d+)/);
        if (cm) {
            let slot = parseInt(cm[1]);
            testSlot(slot).catch(e => log('ceshi失败:', e.message));
        }
    });

    async function testSlot(targetSlot) {
        if (!bot.entity) return log('bot未就绪');
        let nearest = null, nearestDist = Infinity;
        let botPos = bot.entity.position;
        for (let x = Math.floor(botPos.x) - 10; x <= Math.floor(botPos.x) + 10; x++) {
            for (let y = Math.floor(botPos.y) - 5; y <= Math.floor(botPos.y) + 5; y++) {
                for (let z = Math.floor(botPos.z) - 10; z <= Math.floor(botPos.z) + 10; z++) {
                    let blk = bot.blockAt(new Vec3(x, y, z));
                    if (blk && (blk.name === 'chest' || blk.name === 'trapped_chest' || blk.name === 'barrel')) {
                        let d = botPos.distanceTo(new Vec3(x + 0.5, y + 0.5, z + 0.5));
                        if (d < nearestDist) { nearestDist = d; nearest = { x, y, z, name: blk.name }; }
                    }
                }
            }
        }
        if (!nearest) return log('附近无容器');
        log('最近容器:', nearest.name, nearest.x, nearest.y, nearest.z, '距离:', nearestDist.toFixed(1));
        await nav(bot, nearest.x + 0.5, nearest.y, nearest.z + 1.5);
        if (!bot.entity) return;

        let blk = bot.blockAt(new Vec3(nearest.x, nearest.y, nearest.z));
        if (!blk) return log('方块不存在');
        let ch = await bot.openContainer(blk);
        if (!ch) return log('无法打开');
        let allItems = ch.containerItems();
        log('容器物品数:', allItems.length, '现有槽位:', allItems.map(i => i.slot).sort((a,b)=>a-b).join(','));

        let targetItem = allItems.find(i => i.slot === targetSlot);
        if (!targetItem) { log('槽位' + targetSlot + '为空'); ch.close(); return; }

        // 底层 click_container 发包
        log('槽位' + targetSlot + ':', targetItem.name, 'x' + targetItem.count, '→ click_container');
        try {
            let empty = null;
            for (let s = 9; s <= 44; s++) { if (!bot.inventory.slots[s]) { empty = s; break; } }
            if (empty == null) { log('背包满'); ch.close(); return; }
            let winEmpty = ch.inventoryEnd + 1 + (empty - 9);
            let wid = ch.window?.id || 0;
            cl.write('click_container', { windowId: wid, stateId: 0, slot: targetSlot, button: 0, mode: 0, slots: [] });
            await s(300);
            cl.write('click_container', { windowId: wid, stateId: 0, slot: winEmpty, button: 0, mode: 0, slots: [] });
            await s(500);
            ch.close();
            if (isShulkerBox(targetItem.name)) {
                let shInv = bot.inventory.items().find(it => it && it.name === targetItem.name);
                if (shInv) await processShulkerBox(shInv, '', 0, undefined);
            }
        } catch (e) { log('click失败:', e.message); ch.close(); }
        log('测试完成');
    }

    bot.on('end', () => {
        hbStop();
        if (tcpPendingReq) { let r = tcpPendingReq; tcpPendingReq = null; clearTimeout(r.timer); r.reject(new Error('disconnected')); }
        reconnectCount++;
        let delay = Math.min(5000 * Math.pow(2, Math.min(reconnectCount - 1, 4)), 60000);
        log('重连 #' + reconnectCount, '延迟', (delay/1000).toFixed(0) + 's');
        setTimeout(main, delay);
    });
}

main().catch(e => { console.error(e); process.exit(1); });














