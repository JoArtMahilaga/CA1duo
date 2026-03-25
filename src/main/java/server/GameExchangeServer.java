package server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

//MAIN GAME EXCHANGE SERVER
//------------------------------
// Main server class that listens for client connections and manages the overall state of the game exchange
// It uses a thread pool to handle multiple clients concurrently and maintains a shared OrderBook for processing game orders
// It also has baisic logging and client management functionality.

// In case the server fails to start, it will log the error and shut down gracefully. The server also starts a web server for viewing the order book and user management.

// It also initialises a web server allowing users to manage the games buy and sell order using a web interface
// the web interface shares the same OrderBook and UserStore as the main server to ensure that the stuff between the commandline and web interfaces'
// remain the same 

// In case if the web server fails to start, it will log the error but still continue to start the main server

public class GameExchangeServer {
    private static final Logger LOG = Logger.getLogger(GameExchangeServer.class.getName());

    private final int port;
    private final ExecutorService threadPool;
    private final Map<String, ClientHandler> connectedClients = new ConcurrentHashMap<>();
    private final OrderBook orderBook = new OrderBook();

    public GameExchangeServer(int port, int poolSize) {
        this.port = port;
        this.threadPool = Executors.newFixedThreadPool(poolSize);
    }

    // Starts the server, listens for incoming client connections, and assigns a ClientHandler to each new connection
    public void start() {
        LOG.info("[" + Thread.currentThread().getName() + "] Server listening on port " + port);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            while (true) {
                Socket socket = serverSocket.accept();
                String sessionId = UUID.randomUUID().toString();
                ClientHandler handler = new ClientHandler(socket, sessionId, this, orderBook);
                threadPool.submit(handler);
            }
        } catch (IOException e) {
            LOG.severe("[" + Thread.currentThread().getName() + "] Server error: " + e.getMessage());
        } finally {
            threadPool.shutdown();
        }
    }

    public void registerClient(String sessionId, ClientHandler handler) {
        connectedClients.put(sessionId, handler);
    }

    public void unregisterClient(String sessionId) {
        connectedClients.remove(sessionId);
    }

    public ClientHandler getClient(String sessionId) {
        return connectedClients.get(sessionId);
    }

    public OrderBook getOrderBook() {
        return orderBook;
    }

    public static void main(String[] args) {
        int port = 5000;
        int poolSize = 10;
        int httpPort = 8080;

        if (args.length >= 1) {
            port = Integer.parseInt(args[0]);
        }

        if (args.length >= 2) {
            poolSize = Integer.parseInt(args[1]);
        }

        if (args.length >= 3) {
            httpPort = Integer.parseInt(args[2]);
        }

        GameExchangeServer server = new GameExchangeServer(port, poolSize);
        UserStore userStore = new UserStore("users.dat");

//        try {
//            new WebServer(httpPort, server.orderBook, userStore).start(); // Start the web server
//        } catch (Exception e) {
//            LOG.severe("[" + Thread.currentThread().getName() + "] Failed to start web server: " + e.getMessage());
//
//            server.start();
//        }
    }
}