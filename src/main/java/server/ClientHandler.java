package server;

import common.Order;
import common.Protocol;
import common.Side;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.net.Socket;
import java.util.logging.Logger;


//CLIENT HANDLER
//------------------------------
// This class handles the communication with a single client connected to the GameExchangeServer

// It processes incoming messages from the client, interacts with the OrderBook to place and cancel orders
// and sends responses back to the client

// It also manages the client's session and ensures proper cleanup when the client disconnects

// In case of any errors during communication, it will log the error and close the connection gracefully

public class ClientHandler implements Runnable {
    private static final Logger LOG = Logger.getLogger(ClientHandler.class.getName());

    private final Socket socket;
    private final String sessionId;
    private final GameExchangeServer server;
    private final OrderBook orderBook;

    private BufferedReader reader;
    private PrintWriter writer;
    private String userName;
    private boolean connected;

    public ClientHandler(Socket socket, String sessionId, GameExchangeServer server, OrderBook orderBook) {
        this.socket = socket;
        this.sessionId = sessionId;
        this.server = server;
        this.orderBook = orderBook;
    }

    @Override
    public void run() {
        try {
            socket.setSoTimeout(300000); // 5-minute read timeout for idle clients
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new PrintWriter(socket.getOutputStream(), true);

            String line;
            boolean running = true;

            while (running && (line = reader.readLine()) != null) {
                running = handleMessage(line.trim());
            }

        } catch (IOException e) {
            LOG.warning("[" + Thread.currentThread().getName() + "] Client connection ended: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    // Handles incoming messages from the client, processes commands, and interacts with the OrderBook
    private boolean handleMessage(String message) {
        if (message.isEmpty()) {
            return true;
        }

        try {
            if (message.startsWith(Protocol.USER_PREFIX)) {
                String proposedName = message.substring(Protocol.USER_PREFIX.length()).trim();

                if (proposedName.isEmpty()) {
                    sendMessage("ERROR:USERNAME_REQUIRED");
                    return true;
                }

                this.userName = proposedName;
                this.connected = true;
                server.registerClient(sessionId, this);
                LOG.info("[" + Thread.currentThread().getName() + "] User '" + userName + "' connected (session " + sessionId.substring(0, 8) + ")");
                sendMessage(Protocol.CONNECTED);
                return true;
            }

            if (!connected) {
                sendMessage("ERROR:CONNECT_FIRST");
                return true;
            }

            if (message.startsWith(Protocol.ORDER_PREFIX)) {
                return handleOrder(message.substring(Protocol.ORDER_PREFIX.length()));
            }

            if (message.startsWith(Protocol.CANCEL_PREFIX)) {
                return handleCancel(message.substring(Protocol.CANCEL_PREFIX.length()));
            }

            if (message.equalsIgnoreCase(Protocol.VIEW)) {
                sendMessage(orderBook.viewBook());
                return true;
            }

            if (message.equalsIgnoreCase(Protocol.END)) {
                sendMessage(Protocol.ENDED);
                return false;
            }

            sendMessage("ERROR:UNKNOWN_COMMAND");
            return true;

        } catch (IllegalArgumentException e) {
            sendMessage("ERROR:" + e.getMessage());
            return true;
        }
    }

    // Handles incoming order messages from the client, parses the order details, and interacts with the OrderBook
    private boolean handleOrder(String payload) {
        String[] parts = payload.split(",", 3);

        if (parts.length != 3) {
            throw new IllegalArgumentException("ORDER_FORMAT");
        }

        Side side = Side.fromCode(parts[0]);
        String title = parts[1].trim();
        BigDecimal price = new BigDecimal(parts[2].trim());

        Order incomingOrder = new Order(sessionId, userName, side, title, price);
        LOG.info("[" + Thread.currentThread().getName() + "] " + userName + " placed ORDER " + side + " " + title + " @ " + price);
        MatchResult matchResult = orderBook.placeOrder(incomingOrder);

        if (matchResult == null) {
            sendMessage(orderBook.viewBook());
            return true;
        }

        LOG.info("[" + Thread.currentThread().getName() + "] MATCH: " + title + " @ " + matchResult.getTradePrice() + " between " + userName + " and " + matchResult.getRestingOrder().getUserName());

        sendMessage(Protocol.buildMatch(
                incomingOrder.getSide(),
                incomingOrder.getTitle(),
                matchResult.getTradePrice(),
                matchResult.getRestingOrder().getUserName()
        ));

        ClientHandler counterpartyHandler = server.getClient(matchResult.getRestingOrder().getSessionId());

        if (counterpartyHandler != null && counterpartyHandler != this) {
            counterpartyHandler.sendMessage(Protocol.buildMatch(
                    matchResult.getRestingOrder().getSide(),
                    matchResult.getRestingOrder().getTitle(),
                    matchResult.getTradePrice(),
                    incomingOrder.getUserName()
            ));
        }

        return true;
    }

    private boolean handleCancel(String payload) {
        String[] parts = payload.split(",", 3);

        if (parts.length != 3) {
            throw new IllegalArgumentException("CANCEL_FORMAT");
        }

        Side side = Side.fromCode(parts[0]);
        String title = parts[1].trim();
        BigDecimal price = new BigDecimal(parts[2].trim());

        boolean removed = orderBook.cancelOrder(sessionId, side, title, price);
        LOG.info("[" + Thread.currentThread().getName() + "] " + userName + " CANCEL " + side + " " + title + " @ " + price + " -> " + (removed ? "CANCELLED" : "NOT_FOUND"));
        sendMessage(removed ? Protocol.CANCELLED : Protocol.NOT_FOUND);
        return true;
    }

    public synchronized void sendMessage(String message) {
        if (writer != null) {
            writer.println(message);
            writer.flush();
        }
    }

    // After the client disconnects or an error occurs, this method ensures that all resources are cleaned up properly

    // It removes any remaining orders of the client from the OrderBook preventing ghost orders and
    // only allows active clients to transact games

    // and closes the socket and streams to free up resources

    private void cleanup() {
        LOG.info("[" + Thread.currentThread().getName() + "] Cleaning up session " + sessionId.substring(0, 8) + (userName != null ? " (" + userName + ")" : ""));
        orderBook.removeAllForSession(sessionId);
        server.unregisterClient(sessionId);

        try {
            if (reader != null) {
                reader.close();
            }
        } catch (IOException ignored) {
        }

        if (writer != null) {
            writer.close();
        }

        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}