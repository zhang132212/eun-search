const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function deferred() {
    let resolve;
    const promise = new Promise(r => { resolve = r; });
    return { promise, resolve };
}

function fixture() {
    const source = fs.readFileSync(path.join(__dirname, '../bot_direct.js'), 'utf8');
    const context = vm.createContext({
        console: { log() {} }, __dirname, fs: { writeFileSync() {} },
        config: {}, DROP_POS: { x: 0, y: 0, z: 0 }, IDLE_POS: { x: 0, y: 0, z: 0 },
        PLACE_POS: { x: 0, y: 0, z: 0 }, DROP_LOOK: {},
        Vec3: class Vec3 { constructor(x, y, z) { Object.assign(this, { x, y, z }); } },
        Block: class Block { constructor(name, position) { Object.assign(this, { name, position }); } },
        Movements: class {}, goals: { GoalNear: class {} }, setTimeout, clearTimeout,
    });
    vm.runInContext(source.slice(source.indexOf('let bot = null;'), source.lastIndexOf('main().catch')), context);
    vm.runInContext('originalNav = nav; s = async () => {}; nav = async () => {}; findStandPos = () => ({ x: 0, y: 0, z: 0 });', context);
    const chest = { name: 'chest' };
    context.fakeBot = {
        username: 'worker', entity: { position: { x: 0, y: 0, z: 0 } },
        pathfinder: { getPathTo: () => ({ status: 'success' }), setGoal() {} },
        inventory: { items: () => [] }, blockAt: () => chest, lookAt() {}, chat() {},
        setControlState() {}, equip: async () => {}, placeBlock: async () => {},
    };
    vm.runInContext('bot = fakeBot; pending = true; startTask();', context);
    return { context, run: code => vm.runInContext(code, context) };
}

test('stop during navigation does not open or withdraw from a chest', async () => {
    const { context, run } = fixture();
    const navigation = deferred();
    let opens = 0;
    context.navigate = () => navigation.promise;
    context.fakeBot.openContainer = async () => { opens++; return { containerItems: () => [], close() {} }; };
    run('nav = navigate');
    const task = run('processOneContainer({ x: 0, y: 0, z: 0, need_to_take: 2 }, "stone", new Set(), "user", taskEpoch)');
    run('taskEpoch++; stopped = true');
    navigation.resolve();
    await task;
    assert.equal(opens, 0);
});

test('stop after a withdrawal prevents the next stack and closes the chest', async () => {
    const { context, run } = fixture();
    let withdrawals = 0, closes = 0;
    context.fakeBot.openContainer = async () => ({
        containerItems: () => [{ name: 'stone', type: 1, count: 1 }, { name: 'stone', type: 1, count: 1 }],
        withdraw: async () => { withdrawals++; run('taskEpoch++; stopped = true'); },
        close: () => { closes++; },
    });
    await run('processOneContainer({ x: 0, y: 0, z: 0, need_to_take: 2 }, "stone", new Set(), "user", taskEpoch)');
    assert.equal(withdrawals, 1);
    assert.equal(closes, 1);
});

test('stop while opening a shulker prevents withdrawal and closes its window', async () => {
    const { context, run } = fixture();
    let withdrawals = 0, closes = 0;
    context.fakeBot.openContainer = async () => {
        run('taskEpoch++; stopped = true');
        return { containerItems: () => [{ name: 'stone', type: 1, count: 1 }],
            withdraw: async () => { withdrawals++; }, close: () => { closes++; } };
    };
    await run('processShulkerBox({ name: "shulker_box" }, "stone", 1, "user", taskEpoch)');
    assert.equal(withdrawals, 0);
    assert.equal(closes, 1);
});

test('cancelled TCP query does not deliver inventory or clear a newer task', async () => {
    const { context, run } = fixture();
    const query = deferred();
    let deliveries = 0;
    context.query = () => query.promise;
    context.deliver = async () => { deliveries++; };
    context.fakeBot.inventory.items = () => [{ name: 'stone', count: 1 }];
    run('tryTcpFetch = query; deliverItems = deliver');
    const task = run('handleFetch("user", "main", "stone", 2, 0, "fetchAll", taskEpoch)');
    run('taskEpoch++; pending = true');
    query.resolve({ containers: [] });
    await task;
    assert.equal(deliveries, 0);
    assert.equal(run('pending'), true);
});

test('stop keeps the bot busy until cleanup finishes and clears the heartbeat', async () => {
    const { context, run } = fixture();
    const navigation = deferred();
    context.navigate = () => navigation.promise;
    run('nav = navigate; hbStatus = "fetching"; hbTask = { user: "old" };');
    const stopping = run('stopTask()');
    assert.equal(run('Boolean(pending)'), true);
    const epoch = run('taskEpoch');
    await run('handleBotCommand({ action: "fetchAll", player: "new", itemId: "stone", count: 1 })');
    assert.equal(run('taskEpoch'), epoch);
    navigation.resolve();
    await stopping;
    assert.equal(run('Boolean(pending)'), false);
    assert.equal(run('hbStatus'), 'idle');
    assert.equal(run('hbTask'), null);
});

test('stop waits for an in-flight withdrawal before starting cleanup', async () => {
    const { context, run } = fixture();
    const entered = deferred(), withdrawal = deferred();
    let cleanup = 0;
    context.fakeBot.openContainer = async () => ({
        containerItems: () => [{ name: 'stone', type: 1, count: 2 }],
        withdraw: async () => { entered.resolve(); await withdrawal.promise; }, close() {},
    });
    run('pending = null; handleFetch = async (...args) => processOneContainer({ x: 0, y: 0, z: 0, need_to_take: 2 }, "stone", new Set(), "user", args[6]);');
    run('launchFetch("user", "main", "stone", 2, 0, "fetchAll")');
    await entered.promise;
    context.cleanup = async () => { cleanup++; };
    run('nav = cleanup');
    const stopping = run('stopTask()');
    await Promise.resolve();
    assert.equal(cleanup, 0);
    assert.equal(run('Boolean(pending)'), true);
    withdrawal.resolve();
    await stopping;
    assert.equal(cleanup, 2);
    assert.equal(run('Boolean(pending)'), false);
});

test('cancelled navigation rejects promptly and removes its goal listener', async () => {
    const { context, run } = fixture();
    const { EventEmitter } = require('node:events');
    const emitter = new EventEmitter();
    context.fakeBot.once = emitter.once.bind(emitter);
    context.fakeBot.removeListener = emitter.removeListener.bind(emitter);
    const navigation = run('originalNav(bot, 0, 0, 0)');
    const rejection = assert.rejects(navigation, /cancelled/);
    run('cancelNavigation()');
    await rejection;
    assert.equal(emitter.listenerCount('goal_reached'), 0);
    assert.equal(run('cancelNavigation'), null);
});
