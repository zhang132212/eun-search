package com.eunsearch.bot;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.lang.reflect.Field;
import java.net.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class BotTCPServerTest {
    private BotTCPServer server;

    @AfterEach void stopServer() { if (server != null) server.stop(); }

    private Object field(String name) throws Exception {
        Field field = BotTCPServer.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(server);
    }

    private Socket connect() throws Exception {
        if (server == null) {
            String previous = System.getProperty("eunsearch.tcpToken");
            try {
                System.setProperty("eunsearch.tcpToken", "test-token");
                server = new BotTCPServer(0, "warehouse");
            } finally {
                if (previous == null) System.clearProperty("eunsearch.tcpToken");
                else System.setProperty("eunsearch.tcpToken", previous);
            }
            server.start();
        }
        long deadline = System.nanoTime() + 3_000_000_000L;
        ServerSocket listener;
        while ((listener = (ServerSocket) field("serverSocket")) == null) {
            if (System.nanoTime() > deadline) fail("TCP listener did not start");
            Thread.sleep(10);
        }
        Socket socket = new Socket("127.0.0.1", listener.getLocalPort());
        socket.setSoTimeout(3000);
        return socket;
    }

    private void send(Socket socket, String message) throws IOException {
        socket.getOutputStream().write((message + "\n").getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    private BufferedReader reader(Socket socket) throws IOException {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }

    @Test void authenticatedClientCanPingAndHasNoIdleReadTimeout() throws Exception {
        try (Socket socket = connect()) {
            send(socket, "{\"type\":\"register\",\"name\":\"worker\",\"token\":\"test-token\"}");
            send(socket, "{\"type\":\"ping\"}");
            assertEquals("pong", JsonParser.parseString(reader(socket).readLine()).getAsJsonObject().get("type").getAsString());
            assertEquals("worker", server.getBotName());
            assertEquals(0, ((Socket) field("activeClient")).getSoTimeout());
        }
    }

    @Test void wrongTokenIsRejectedAndConnectionIsClosed() throws Exception {
        try (Socket socket = connect()) {
            BufferedReader reader = reader(socket);
            send(socket, "{\"type\":\"register\",\"name\":\"attacker\",\"token\":\"wrong\"}");
            assertEquals("error", JsonParser.parseString(reader.readLine()).getAsJsonObject().get("type").getAsString());
            assertNull(reader.readLine());
            assertNull(server.getBotName());
        }
    }

    @Test void requestBeforeRegistrationIsRejected() throws Exception {
        try (Socket socket = connect()) {
            BufferedReader reader = reader(socket);
            send(socket, "{\"type\":\"ping\"}");
            assertEquals("error", JsonParser.parseString(reader.readLine()).getAsJsonObject().get("type").getAsString());
            assertNull(reader.readLine());
        }
    }

    @Test void stopClosesAuthenticatedClientAndDisablesCommands() throws Exception {
        try (Socket socket = connect()) {
            send(socket, "{\"type\":\"register\",\"name\":\"worker\",\"token\":\"test-token\"}");
            send(socket, "{\"type\":\"ping\"}");
            BufferedReader reader = reader(socket);
            assertNotNull(reader.readLine());
            server.stop();
            assertNull(reader.readLine());
            assertFalse(server.sendBotCommand("fetchAll", "user", "warehouse", "stone", 1));
            assertNull(server.getBotName());
        }
    }

    @Test void idleUnregisteredClientTimesOutAndReleasesTheSlot() throws Exception {
        try (Socket idle = connect()) {
            idle.setSoTimeout(15_000);
            assertNull(reader(idle).readLine());
        }
        try (Socket replacement = connect()) {
            send(replacement, "{\"type\":\"register\",\"name\":\"worker\",\"token\":\"test-token\"}");
            send(replacement, "{\"type\":\"ping\"}");
            assertEquals("pong", JsonParser.parseString(reader(replacement).readLine()).getAsJsonObject().get("type").getAsString());
        }
    }
}
