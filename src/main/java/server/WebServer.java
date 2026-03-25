package server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import common.Order;
import common.Protocol;
import common.Side;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;

//Http Server For Displaying stuff on a webpage and handling user accounts. Uses Java's built in HttpServer which is very basic but good enough for this demo
public class WebServer {
    private static final Logger LOG = Logger.getLogger(WebServer.class.getName());

    private final int httpPort;
    private final OrderBook orderBook;
    private final UserStore userStore;
    private final Map<String, WebSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService broadcastPool = Executors.newCachedThreadPool();
    private final ScheduledExecutorService heartbeatScheduler = Executors.newScheduledThreadPool(2);

    // Constructor takes the port to listen too, the order book and user store to interact with 
    public WebServer(int httpPort, OrderBook orderBook, UserStore userStore) {
        this.httpPort = httpPort;
        this.orderBook = orderBook;
        this.userStore = userStore;
    }

    //Starts the server adn sets up all endpoints
    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(httpPort), 0);
        server.createContext("/", this::serveIndex);
        server.createContext("/api/signup", this::handleSignup);
        server.createContext("/api/login", this::handleLogin);
        server.createContext("/api/logout", this::handleLogout);
        server.createContext("/api/delete-account", this::handleDeleteAccount);
        server.createContext("/api/order", this::handleOrder);
        server.createContext("/api/cancel", this::handleCancel);
        server.createContext("/api/book", this::handleBook);
        server.createContext("/api/view", this::handleView);
        server.createContext("/api/events", this::handleSSE);
        server.createContext("/api/activity", this::handleActivity);
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();
        LOG.info("[" + Thread.currentThread().getName() + "] Web UI available at http://localhost:" + httpPort);
    }

    // Serves the index file which shows the main webpage
    private void serveIndex(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            sendText(ex, 404, "Not Found");
            return;
        }
        InputStream is = getClass().getResourceAsStream("/index.html");
        if (is == null) {
            sendText(ex, 500, "index.html not found on classpath");
            return;
        }
        byte[] html = is.readAllBytes();
        is.close();
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, html.length);
        ex.getResponseBody().write(html);
        ex.getResponseBody().close();
    }

    // ---- Auth endpoints ----

    private void handleSignup(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendText(ex, 405, "POST only"); return; }
        Map<String, String> body = parseForm(ex);
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "").trim();

        if (username.isEmpty() || password.isEmpty()) {
            sendJson(ex, 400, "{\"error\":\"Username and password required\"}"); return;
        }
        if (username.length() < 3) {
            sendJson(ex, 400, "{\"error\":\"Username must be at least 3 characters\"}"); return;
        }
        if (password.length() < 4) {
            sendJson(ex, 400, "{\"error\":\"Password must be at least 4 characters\"}"); return;
        }

        boolean ok = userStore.register(username, password);
        if (!ok) {
            sendJson(ex, 409, "{\"error\":\"Username already taken\"}"); return;
        }

        sendJson(ex, 200, "{\"status\":\"registered\",\"username\":\"" + escapeJson(username) + "\"}");
    }

    // Handles login requests, creates a new web session and returns the session ID to the client
    private void handleLogin(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendText(ex, 405, "POST only"); return; }
        Map<String, String> body = parseForm(ex);
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "").trim();

        if (username.isEmpty() || password.isEmpty()) {
            sendJson(ex, 400, "{\"error\":\"Username and password required\"}"); return;
        }

        if (!userStore.authenticate(username, password)) {
            sendJson(ex, 401, "{\"error\":\"Invalid username or password\"}"); return;
        }

        String sessionId = UUID.randomUUID().toString();
        WebSession session = new WebSession(sessionId, username);
        session.addActivity("Logged in");
        sessions.put(sessionId, session);

        LOG.info("[" + Thread.currentThread().getName() + "] User '" + username + "' logged in (web session " + sessionId.substring(0, 8) + ")");
        sendJson(ex, 200, "{\"sessionId\":\"" + sessionId + "\",\"username\":\"" + escapeJson(username) + "\"}");
    }

    // Handles logout requests, removes the web session and updates the order book
    private void handleLogout(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendText(ex, 405, "POST only"); return; }
        Map<String, String> body = parseForm(ex);
        WebSession session = getSession(ex, body);
        if (session == null) return;

        session.addActivity("Logged out");
        orderBook.removeAllForSession(session.sessionId);
        session.disconnected = true;
        sessions.remove(session.sessionId);
        broadcastBookUpdate();

        LOG.info("[" + Thread.currentThread().getName() + "] User '" + session.username + "' logged out");
        sendJson(ex, 200, "{\"status\":\"logged_out\"}");
    }

    // Handles account deletion, removes the user from the user store, removes all their orders and web session
    private void handleDeleteAccount(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendText(ex, 405, "POST only"); return; }
        Map<String, String> body = parseForm(ex);
        WebSession session = getSession(ex, body);
        if (session == null) return;

        String password = body.getOrDefault("password", "").trim();
        if (password.isEmpty()) {
            sendJson(ex, 400, "{\"error\":\"Password required\"}"); return;
        }

        boolean deleted = userStore.deleteUser(session.username, password);
        if (!deleted) {
            sendJson(ex, 401, "{\"error\":\"Incorrect password\"}"); return;
        }

        orderBook.removeAllForSession(session.sessionId);
        session.disconnected = true;
        sessions.remove(session.sessionId);
        broadcastBookUpdate();

        LOG.info("[" + Thread.currentThread().getName() + "] User '" + session.username + "' deleted account");
        sendJson(ex, 200, "{\"status\":\"deleted\"}");
    }

    // ---- Trading endpoints ----

    // Handles order placement, matches orders and updates the order book
    private void handleOrder(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendText(ex, 405, "POST only"); return; }
        Map<String, String> body = parseForm(ex);
        WebSession session = getSession(ex, body);
        if (session == null) return;

        String side = body.getOrDefault("side", "").trim();
        String title = body.getOrDefault("title", "").trim();
        String priceStr = body.getOrDefault("price", "").trim();

        if (side.isEmpty() || title.isEmpty() || priceStr.isEmpty()) {
            sendJson(ex, 400, "{\"error\":\"Missing fields\"}"); return;
        }

        Side s;
        try { s = Side.fromCode(side); } catch (IllegalArgumentException e) {
            sendJson(ex, 400, "{\"error\":\"Invalid side\"}"); return;
        }

        BigDecimal price;
        try { price = new BigDecimal(priceStr); } catch (NumberFormatException e) {
            sendJson(ex, 400, "{\"error\":\"Invalid price\"}"); return;
        }

        String sideLabel = s == Side.BUY ? "Buy" : "Sell";
        Order incoming = new Order(session.sessionId, session.username, s, title, price);
        MatchResult result = orderBook.placeOrder(incoming);

        if (result == null) {
            session.addActivity("Placed " + sideLabel + " order: " + title + " @ \u20ac" + Protocol.formatPrice(price));
            sendJson(ex, 200, "{\"status\":\"placed\",\"book\":" + bookJson() + "}");
            broadcastBookUpdate();
        } else {
            String tradePrice = Protocol.formatPrice(result.getTradePrice());
            String counterpartyName = result.getRestingOrder().getUserName();
            session.addActivity("MATCHED " + sideLabel + ": " + title + " @ \u20ac" + tradePrice + " with " + counterpartyName);

            String matchJson = "{\"status\":\"matched\",\"side\":\"" + escapeJson(s.getCode())
                    + "\",\"title\":\"" + escapeJson(title)
                    + "\",\"price\":\"" + tradePrice
                    + "\",\"counterparty\":\"" + escapeJson(counterpartyName)
                    + "\",\"book\":" + bookJson() + "}";
            sendJson(ex, 200, matchJson);

            // Notify counterparty via SSE
            WebSession cpSession = sessions.get(result.getRestingOrder().getSessionId());
            if (cpSession != null) {
                String cpSideLabel = result.getRestingOrder().getSide() == Side.BUY ? "Buy" : "Sell";
                cpSession.addActivity("MATCHED " + cpSideLabel + ": " + result.getRestingOrder().getTitle()
                        + " @ \u20ac" + tradePrice + " with " + incoming.getUserName());

                String evt = "{\"side\":\"" + escapeJson(result.getRestingOrder().getSide().getCode())
                        + "\",\"title\":\"" + escapeJson(result.getRestingOrder().getTitle())
                        + "\",\"price\":\"" + tradePrice
                        + "\",\"counterparty\":\"" + escapeJson(incoming.getUserName()) + "\"}";
                cpSession.pushEvent("match", evt);
            }
            broadcastBookUpdate();
        }
    }

    // Handles order cancellation, removes the order from the order book and updates clients
    private void handleCancel(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendText(ex, 405, "POST only"); return; }
        Map<String, String> body = parseForm(ex);
        WebSession session = getSession(ex, body);
        if (session == null) return;

        String side = body.getOrDefault("side", "").trim();
        String title = body.getOrDefault("title", "").trim();
        String priceStr = body.getOrDefault("price", "").trim();

        Side s;
        try { s = Side.fromCode(side); } catch (IllegalArgumentException e) {
            sendJson(ex, 400, "{\"error\":\"Invalid side\"}"); return;
        }

        BigDecimal price;
        try { price = new BigDecimal(priceStr); } catch (NumberFormatException e) {
            sendJson(ex, 400, "{\"error\":\"Invalid price\"}"); return;
        }

        boolean removed = orderBook.cancelOrder(session.sessionId, s, title, price);
        String sideLabel = s == Side.BUY ? "Buy" : "Sell";
        if (removed) {
            session.addActivity("Cancelled " + sideLabel + " order: " + title + " @ \u20ac" + Protocol.formatPrice(price));
        }
        sendJson(ex, 200, "{\"cancelled\":" + removed + ",\"book\":" + bookJson() + "}");
        if (removed) {
            broadcastBookUpdate();
        }
    }

    // Handles book retrieval, returns the current order book as JSON
    private void handleBook(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendText(ex, 405, "GET only"); return; }
        sendJson(ex, 200, bookJson());
    }

    // Shows that the user viewed the order book
    private void handleView(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendText(ex, 405, "POST only"); return; }
        Map<String, String> body = parseForm(ex);
        WebSession session = getSession(ex, body);
        if (session == null) return;
        session.addActivity("Viewed order book");
        sendJson(ex, 200, "{\"status\":\"ok\"}");
    }

    private void handleActivity(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendText(ex, 405, "POST only"); return; }
        Map<String, String> body = parseForm(ex);
        WebSession session = getSession(ex, body);
        if (session == null) return;

        StringBuilder sb = new StringBuilder("[");
        List<String> items = session.getActivity();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(items.get(i))).append("\"");
        }
        sb.append("]");
        sendJson(ex, 200, "{\"activity\":" + sb + "}");
    }

    // Handles SSE connections which manage real-time updates to the clients
    private void handleSSE(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getQuery();
        String sessionId = null;
        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && "sessionId".equals(kv[0])) sessionId = kv[1];
            }
        }
        WebSession session = sessionId != null ? sessions.get(sessionId) : null;
        if (session == null) { sendText(ex, 400, "Invalid session"); return; }

        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);

        OutputStream os = ex.getResponseBody();
        session.sseOutput = os;

        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                if (!session.disconnected && session.sseOutput != null) {
                    os.write(":heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            } catch (IOException ignored) {}
        }, 15, 15, TimeUnit.SECONDS);

        try {
            while (!session.disconnected) {
                Thread.sleep(1000);
            }
        } catch (Exception ignored) {
        } finally {
            heartbeat.cancel(false);
            try { os.close(); } catch (IOException ignored) {}
        }
    }

    // --- Broadcast ----

    private void broadcastBookUpdate() {
        String json = bookJson();
        for (WebSession s : sessions.values()) {
            broadcastPool.submit(() -> s.pushEvent("bookUpdate", json));
        }
    }

    // --- Helpers ----

    // Converts the order book to a JSON string manually
    private String bookJson() {
        List<Map<String, String>> buys = orderBook.getOrdersAsJson(Side.BUY);
        List<Map<String, String>> sells = orderBook.getOrdersAsJson(Side.SELL);
        return "{\"buys\":" + listOfMapsToJson(buys) + ",\"sells\":" + listOfMapsToJson(sells) + "}";
    }

    private String listOfMapsToJson(List<Map<String, String>> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("{");
            int j = 0;
            for (Map.Entry<String, String> e : list.get(i).entrySet()) {
                if (j > 0) sb.append(",");
                sb.append("\"").append(escapeJson(e.getKey())).append("\":\"").append(escapeJson(e.getValue())).append("\"");
                j++;
            }
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private Map<String, String> parseForm(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> map = new HashMap<>();
        if (body.isEmpty()) return map;
        for (String pair : body.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                map.put(java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
            }
        }
        return map;
    }

    // Retrieves the web session based on the session ID provided in the request body, returns null and sends 401 not connected in case of connection issues
    private WebSession getSession(HttpExchange ex, Map<String, String> body) throws IOException {
        String sessionId = body.getOrDefault("sessionId", "").trim();
        WebSession s = sessions.get(sessionId);
        if (s == null) {
            sendJson(ex, 401, "{\"error\":\"Not connected\"}");
            return null;
        }
        return s;
    }

    private void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    private void sendText(HttpExchange ex, int code, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    // --- Inner class for web sessions ----

    // handles a user's session on the web server, holds their username, session ID, SSE output stream and activity log
    static class WebSession {
        private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

        final String sessionId;
        final String username;
        volatile OutputStream sseOutput;
        volatile boolean disconnected;

        private final List<String> activity = Collections.synchronizedList(new ArrayList<>());

        WebSession(String sessionId, String username) {
            this.sessionId = sessionId;
            this.username = username;
        }

        void addActivity(String message) {
            activity.add("[" + LocalDateTime.now().format(FMT) + "] " + message);
        }

        List<String> getActivity() {
            return new ArrayList<>(activity);
        }

        void pushEvent(String event, String data) {
            if (sseOutput != null) {
                try {
                    String msg = "event: " + event + "\ndata: " + data + "\n\n";
                    sseOutput.write(msg.getBytes(StandardCharsets.UTF_8));
                    sseOutput.flush();
                } catch (IOException ignored) {}
            }
        }
    }
}
