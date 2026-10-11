const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('watchdog removes a legacy password even when environment authentication skips prompts', async () => {
    const source = fs.readFileSync(path.join(__dirname, '../watchdog_direct.js'), 'utf8');
    let saved;
    const config = {
        tcp_port: 3002, player: { email: 'test@example.com', name: 'worker', pass: 'legacy-password' },
        idle_pos: { x: 0, y: 64, z: 0 }, place_pos: {}, drop_pos: {}, drop_look: {},
    };
    const context = vm.createContext({ config,
        process: { env: { LITTLESKIN_PASSWORD: 'environment-password' } },
        fs: { writeFileSync: (_file, json) => { saved = JSON.parse(json); } }, CONFIG_FILE: 'mock-config',
        log() {}, ask() { throw new Error('unexpected prompt'); },
    });
    vm.runInContext(source.slice(source.indexOf('async function setupConfig()'), source.indexOf('let botProcess = null;')), context);
    await vm.runInContext('setupConfig()', context);
    assert.ok(saved);
    assert.equal(Object.hasOwn(saved.player, 'pass'), false);
    assert.equal(Object.hasOwn(config.player, 'pass'), false);
    assert.equal(saved.player.email, 'test@example.com');
    assert.equal(context.process.env.LITTLESKIN_PASSWORD, 'environment-password');
});
