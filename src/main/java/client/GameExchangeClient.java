package client;

import common.Protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

public class GameExchangeClient {
    public static void main(String[] args) {
        String host = "localhost";
        int port = 5000;

        if (args.length >= 1) {
            host = args[0];
        }

        if (args.length >= 2) {
            port = Integer.parseInt(args[1]);
        }

        try (
                Socket socket = new Socket(host, port);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);
                Scanner scanner = new Scanner(System.in)
        ) {
            System.out.println("Connected to " + host + ":" + port);

            Thread listenerThread = new Thread(new ServerListener(reader));
            listenerThread.setDaemon(true);
            listenerThread.start();

            String username = promptForUsername(scanner);
            writer.println(Protocol.USER_PREFIX + username);

            // Give the listener thread a moment to print CONNECTED
            Thread.sleep(200);

            printHelp();

            while (true) {
                System.out.print("> ");
                String userInput = scanner.nextLine();

                if (ClientCommandParser.isHelpCommand(userInput)) {
                    printHelp();
                    continue;
                }

                try {
                    String protocolMessage = ClientCommandParser.toProtocol(userInput);

                    if (protocolMessage == null) {
                        continue;
                    }

                    writer.println(protocolMessage);

                    if (Protocol.END.equalsIgnoreCase(protocolMessage)) {
                        break;
                    }

                } catch (IllegalArgumentException e) {
                    System.out.println(e.getMessage());
                }
            }

        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String promptForUsername(Scanner scanner) {
        while (true) {
            System.out.print("Enter username: ");
            String username = scanner.nextLine().trim();

            if (!username.isEmpty()) {
                return username;
            }

            System.out.println("Username cannot be empty.");
        }
    }

    private static void printHelp() {
        System.out.println();
        System.out.println("=== Game Exchange - Commands ===");
        System.out.println("  buy <title>,<price>          Place a buy order");
        System.out.println("  sell <title>,<price>         Place a sell order");
        System.out.println("  cancel buy <title>,<price>   Cancel a buy order");
        System.out.println("  cancel sell <title>,<price>  Cancel a sell order");
        System.out.println("  view                         Show the order book");
        System.out.println("  end                          Disconnect");
        System.out.println("  help                         Show this help");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  buy Minecraft,29.99");
        System.out.println("  sell FIFA 25,15");
        System.out.println("  cancel buy Minecraft,29.99");
        System.out.println("================================");
    }
}