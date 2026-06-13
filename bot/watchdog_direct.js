const { spawn, execSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const readline = require('readline');

const BOT_SCRIPT = path.join(__dirname, 'bot_direct.js');
const CONFIG_FILE = path.join(__dirname, 'bot_config.json');
const HB_FILE = path.join(__dirname, 'bot_heartbeat.json');
const HEARTBEAT_TIMEOUT = 15000;
const CHECK_INTERVAL = 5000;
const RESTART_DELAY = 3000;

// Load config
let config = {};
if (fs.existsSync(CONFIG_FILE)) {
    try { config = JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8')); } catch (e) { log('配置解析失败:', e.message); }
} else {
    config = { host: '127.0.0.1', port: 25565 };
    fs.writeFileSync(CONFIG_FILE, JSON.stringify(config, null, 4), 'utf8');
    log('已创建默认配置文件');
}

function ask(q) {
    return new Promise(resolve => {
        const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
        rl.question(q, ans => { rl.close(); resolve(ans.trim()); });
    });
}

async function setupConfig() {
    let needSave = false;

    if (!config.tcp_port) {
        let p = await ask('请输入TCP端口 (默认3002): ');
        config.tcp_port = parseInt(p) || 3002;
        needSave = true;
    }

    if (!config.player || !config.player.email || !config.player.pass || !config.player.name) {
        log('\n--- LittleSkin 账号 ---');
        const ygg = require('yggdrasil')({
            host: 'https://littleskin.cn/api/yggdrasil/authserver',
            sessionHost: 'https://littleskin.cn/api/yggdrasil/sessionserver'
        });
        while (true) {
            let email = await ask('邮箱: ');
            let pass = await ask('密码: ');
            let name = await ask('玩家名: ');
            log('正在验证账号...');
            try {
                let sess = await new Promise((res, rej) => {
                    ygg.auth({ user: email, pass: pass, token: 'wd-' + Date.now(), agent: 'Minecraft', requestUser: true }, (err, r) => {
                        if (err) return rej(err);
                        if (!r.selectedProfile) r.selectedProfile = r.availableProfiles?.find(p => p.name === name) || r.availableProfiles?.[0];
                        if (!r.selectedProfile) return rej(new Error('No profile found'));
                        res(r);
                    });
                });
                log('账号验证成功! UUID:', sess.selectedProfile.id, '玩家:', sess.selectedProfile.name);
                config.player = { email, pass, name };
                needSave = true;
                break;
            } catch (e) {
                log('账号验证失败:', e.message, '- 请重新输入');
            }
        }
    }

    if (!config.idle_pos) {
        log('\n--- 机器人待命点 ---');
        let answer = await ask('请输入坐标 x y z (例: 1702 124 -289.5): ');
        let parts = answer.split(/\s+/);
        if (parts.length === 3) {
            config.idle_pos = { x: parseFloat(parts[0]), y: parseFloat(parts[1]), z: parseFloat(parts[2]) };
        } else {
            log('坐标格式错误，跳过');
        }
        needSave = true;
    }

    if (!config.place_pos) {
        while (true) {
            let answer = await ask('请输入放置盒子坐标 x y z (必填，例: 1702 124 -290): ');
            let parts = answer.split(/\s+/);
            if (parts.length === 3) { config.place_pos = { x: parseInt(parts[0]), y: parseInt(parts[1]), z: parseInt(parts[2]) }; break; }
            log('格式错误，请重新输入');
        }
        needSave = true;
    }

    if (!config.drop_pos) {
        while (true) {
            let answer = await ask('请输入丢弃盒子坐标 x y z (必填，例: 1695 124 -294): ');
            let parts = answer.split(/\s+/);
            if (parts.length === 3) { config.drop_pos = { x: parseInt(parts[0]), y: parseInt(parts[1]), z: parseInt(parts[2]) }; break; }
            log('格式错误，请重新输入');
        }
        needSave = true;
    }

    if (!config.drop_look) {
        let answer = await ask('请输入丢物品时看向的坐标 x y z (必填，例: 1695 123 -294): ');
        let parts = answer.split(/\s+/);
        if (parts.length === 3) config.drop_look = { x: parseInt(parts[0]), y: parseInt(parts[1]), z: parseInt(parts[2]) };
        else { config.drop_look = { x: 0, y: 0, z: 0 }; }
        needSave = true;
    }

    if (needSave) {
        fs.writeFileSync(CONFIG_FILE, JSON.stringify(config, null, 4), 'utf8');
        log('配置已保存\n');
    }

    // Validate required fields
    let missing = [];
    if (!config.tcp_port) missing.push('tcp_port');
    if (!config.player || !config.player.email || !config.player.pass || !config.player.name) missing.push('LittleSkin账号');
    if (!config.idle_pos || !config.idle_pos.y) missing.push('idle_pos(待命点)');
    if (!config.place_pos) missing.push('place_pos(放盒子点)');
    if (!config.drop_pos) missing.push('drop_pos(丢盒子点)');
    if (!config.drop_look) missing.push('drop_look(丢物品看向坐标)');

    if (missing.length > 0) {
        log('========================================');
        log('配置不完整，缺少: ' + missing.join(', '));
        log('请重新运行 watchdog 填写配置');
        log('========================================');
        process.exit(1);
    }
}

let botProcess = null;
let restartCount = 0;
let shuttingDown = false;

function log(...args) {
    console.log('[WD]', new Date().toISOString().substring(11, 23), ...args);
}

function killPid(pid) {
    if (!pid) return;
    try { execSync('taskkill /F /PID ' + pid + ' 2>nul', { timeout: 3000 }); } catch (e) {}
    try { process.kill(pid, 'SIGTERM'); } catch (e) {}
}

function killBot() {
    if (!botProcess) return;
    const pid = botProcess.pid;
    try { botProcess.kill('SIGTERM'); log('发送 SIGTERM -> pid', pid); } catch (e) {}
    setTimeout(() => killPid(pid), 5000);
}

function startBot() {
    if (shuttingDown) return;
    
    // 杀上次可能残留的 bot 进程
    try {
        if (fs.existsSync(HB_FILE)) {
            const hb = JSON.parse(fs.readFileSync(HB_FILE, 'utf8'));
            if (hb.pid && hb.pid !== process.pid) {
                log('清理旧进程 pid=' + hb.pid);
                killPid(hb.pid);
            }
        }
        // 额外搜杀运行 bot.js 的 node 进程
        const out = execSync('wmic process where "name=\'node.exe\'" get commandline,processid /format:csv 2>nul', { timeout: 5000 }).toString();
        for (let line of out.split('\n')) {
            if (line.includes('bot_direct.js') && !line.includes('watchdog')) {
                let m = line.match(/(\d+)\s*$/);
                if (m && parseInt(m[1]) !== process.pid) {
                    log('搜到残留 bot pid=' + m[1] + ', 杀掉');
                    killPid(parseInt(m[1]));
                }
            }
        }
    } catch (e) {}

    restartCount++;
    log('启动 bot (第' + restartCount + '次) ...');
    
    let botArgs = [BOT_SCRIPT];
    let tcpPort = parseInt(process.argv[2]) || 0;
    if (tcpPort > 0) botArgs.push(String(tcpPort));
    botProcess = spawn('node', botArgs, {
        cwd: __dirname,
        stdio: ['pipe', 'inherit', 'inherit'],
        env: { ...process.env }
    });

    botProcess.on('exit', (code, signal) => {
        log('bot 退出 code=' + code + ' signal=' + signal);
        botProcess = null;
        if (!shuttingDown) setTimeout(startBot, RESTART_DELAY);
    });

    botProcess.on('error', (err) => {
        log('bot spawn 错误:', err.message);
        botProcess = null;
        if (!shuttingDown) setTimeout(startBot, RESTART_DELAY);
    });
}

function checkHeartbeat() {
    if (!botProcess || botProcess.killed) return;
    try {
        if (!fs.existsSync(HB_FILE)) return;
        const hb = JSON.parse(fs.readFileSync(HB_FILE, 'utf8'));
        const age = Date.now() - hb.lastBeat;
        if (age > HEARTBEAT_TIMEOUT) {
            log('心跳超时! ' + (age / 1000).toFixed(0) + 's, 状态: ' + hb.status + ', 任务: ' + JSON.stringify(hb.task));
            log('杀掉僵死 bot, 重启续接...');
            killBot();
        }
    } catch (e) {}
}

function shutdown() {
    log('关闭守护进程...');
    shuttingDown = true;
    if (botProcess) killBot();
    setTimeout(() => process.exit(0), 2000);
}

process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);

log('守护进程启动, 监控', BOT_SCRIPT);
log('心跳超时: ' + (HEARTBEAT_TIMEOUT / 1000) + 's, 检测: ' + (CHECK_INTERVAL / 1000) + 's');

(async () => {
    await setupConfig();
    startBot();
    setInterval(checkHeartbeat, CHECK_INTERVAL);
})();
