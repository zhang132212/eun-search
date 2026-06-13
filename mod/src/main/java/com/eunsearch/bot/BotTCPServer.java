package com.eunsearch.bot;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.eunsearch.EunSearchMod;
import com.eunsearch.api.EunSearchAPI;

import java.io.*;
import java.net.ServerSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BotTCPServer {
    private static final Gson GSON = new Gson();
    private final int port;
    private final String defaultTag;
    private String botName;
    private ServerSocket serverSocket;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "EunSearch-BotTCP-Accept");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running;
    private PrintWriter botOut;

    public BotTCPServer(int port, String defaultTag) {
        this.port = port;
        this.defaultTag = defaultTag;
    }

    public void start() {
        if (running) return;
        running = true;
        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"));
                EunSearchMod.LOGGER.info("[BotTCP] TCP 服务端已启动: 127.0.0.1:{}", port);
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        EunSearchMod.LOGGER.info("[BotTCP] Mineflayer bot 已连接");
                        handleClient(client);
                    } catch (IOException e) {
                        if (running) EunSearchMod.LOGGER.error("[BotTCP] 接受连接失败", e);
                    }
                }
            } catch (IOException e) {
                EunSearchMod.LOGGER.error("[BotTCP] 启动失败", e);
            }
        });
    }

    private void handleClient(Socket client) {
        Thread t = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), "UTF-8"));
                 PrintWriter out = new PrintWriter(new OutputStreamWriter(client.getOutputStream(), "UTF-8"), true)) {
                botOut = out;
                EunSearchMod.LOGGER.info("[BotTCP] client handler started");
                String line;
                while (running && (line = in.readLine()) != null) {
                    processMessage(line);
                }
            } catch (IOException e) {
                EunSearchMod.LOGGER.error("[BotTCP] 连接异常", e);
            } finally {
                botOut = null;
                try { client.close(); } catch (IOException ignored) {}
                EunSearchMod.LOGGER.info("[BotTCP] Mineflayer bot 已断开");
            }
        }, "EunSearch-BotTCP-Client");
        t.setDaemon(true);
        t.start();
    }

    private void processMessage(String line) {
        try {
            JsonObject msg = JsonParser.parseString(line).getAsJsonObject();
            String type = msg.has("type") ? msg.get("type").getAsString() : "";

            switch (type) {
                case "fetchAll", "fetchChest" -> {
                    String tag = msg.has("tag") ? msg.get("tag").getAsString() : defaultTag;
                    String itemId = msg.get("itemId").getAsString();
                    int count = msg.has("count") ? msg.get("count").getAsInt() : 64;
                    boolean onlyChest = "fetchChest".equals(type);

                    var entry = EunSearchMod.getInstance().getConfig().findScanByTag(tag);
                    if (entry == null) { error("未找到扫描标签: " + tag); return; }

                    var result = EunSearchAPI.searchItem(EunSearchMod.getInstance().getServer(), tag, itemId);
                    JsonObject resp = new JsonObject();
                    resp.addProperty("type", "scan_result");
                    resp.addProperty("requestTag", tag);
                    resp.addProperty("itemId", itemId);
                    resp.addProperty("count", count);
                    resp.addProperty("dimension", entry.dimension);

                    var arr = new com.google.gson.JsonArray();
                    int foundCount = 0;
                    for (var c : result) {
                        if (onlyChest && !"chest".equals(c.containerType) && !"trapped_chest".equals(c.containerType))
                            continue;
                        foundCount++;
                        var o = new JsonObject();
                        o.addProperty("x", c.x); o.addProperty("y", c.y); o.addProperty("z", c.z);
                        o.addProperty("count", c.count);
                        o.addProperty("directCount", c.directCount); o.addProperty("type", c.containerType);
                        var sl = new com.google.gson.JsonArray();
                        for (int[] ss : c.shulkerSlots) {
                            var so = new JsonObject();
                            so.addProperty("slot", ss[0]);
                            so.addProperty("count", ss[2]);
                            sl.add(so);
                        }
                        o.add("shulkerSlots", sl);
                        o.addProperty("isDoubleChest", c.isDoubleChest);
                        if (c.isDoubleChest) { o.addProperty("partnerX", c.partnerX); o.addProperty("partnerZ", c.partnerZ); }
                        arr.add(o);
                    }
                    resp.addProperty("found", foundCount);
                    resp.add("containers", arr);
                    var rangesArr = new com.google.gson.JsonArray();
                    for (int[] r : entry.ranges) {
                        var ro = new JsonObject();
                        ro.addProperty("x1", r[0]); ro.addProperty("y1", r[1]); ro.addProperty("z1", r[2]);
                        ro.addProperty("x2", r[3]); ro.addProperty("y2", r[4]); ro.addProperty("z2", r[5]);
                        rangesArr.add(ro);
                    }
                    resp.add("ranges", rangesArr);
                    send(resp);
                }
                case "list" -> {
                    JsonObject resp = new JsonObject();
                    resp.addProperty("type", "scan_list");
                    var arr = new com.google.gson.JsonArray();
                    for (var s : EunSearchMod.getInstance().getConfig().getScans()) {
                        var o = new JsonObject();
                        o.addProperty("tag", s.tag);
                        o.addProperty("dimension", s.dimension);
                        o.addProperty("allItems", s.allItems);
                        o.addProperty("x1", s.minX()); o.addProperty("y1", s.minY()); o.addProperty("z1", s.minZ());
                        o.addProperty("x2", s.maxX()); o.addProperty("y2", s.maxY()); o.addProperty("z2", s.maxZ());
                        arr.add(o);
                    }
                    resp.add("scans", arr);
                    send(resp);
                }
                case "ping" -> {
                    var resp = new JsonObject();
                    resp.addProperty("type", "pong");
                    send(resp);
                }
                case "register" -> {
                    botName = msg.has("name") ? msg.get("name").getAsString() : null;
                    EunSearchMod.LOGGER.info("[BotTCP] Bot {} 已注册到端口 {}", botName, port);
                }
                default -> error("未知消息类型: " + type);
            }
        } catch (Exception e) {
            EunSearchMod.LOGGER.error("[BotTCP] 消息处理失败", e);
            error(e.getMessage());
        }
    }

    public boolean sendBotCommand(String action, String player, String tag, String itemId, int count) {
        if (botOut == null) return false;
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "bot_command");
        obj.addProperty("action", action);
        obj.addProperty("player", player);
        obj.addProperty("tag", tag);
        obj.addProperty("itemId", itemId);
        obj.addProperty("count", count);
        send(obj);
        return true;
    }

    private void send(JsonObject obj) {
        if (botOut != null) botOut.println(GSON.toJson(obj));
    }

    private void error(String msg) {
        JsonObject resp = new JsonObject();
        resp.addProperty("type", "error");
        resp.addProperty("message", msg);
        send(resp);
    }

    public void stop() {
        running = false;
        executor.shutdownNow();
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) {}
        }
    }

    public int getPort() { return port; }
    public String getDefaultTag() { return defaultTag; }
    public String getBotName() { return botName; }
}
