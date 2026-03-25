package client;

import common.Protocol;

import java.io.BufferedReader;
import java.io.IOException;

public class ServerListener implements Runnable {
    private final BufferedReader reader;

    public ServerListener(BufferedReader reader) {
        this.reader = reader;
    }

    @Override
    public void run() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String display = formatResponse(line);
                System.out.println();
                System.out.println(display);
                System.out.print("> ");
            }
        } catch (IOException e) {
            System.out.println();
            System.out.println("Connection closed.");
        }
    }

    private String formatResponse(String line) {
        if (line.startsWith(Protocol.MATCH_PREFIX)) {
            return formatMatch(line.substring(Protocol.MATCH_PREFIX.length()));
        }
        if (line.startsWith("ERROR:")) {
            return "[ERROR] " + friendlyError(line.substring(6));
        }
        if (line.equals(Protocol.CONNECTED)) {
            return "Logged in successfully!";
        }
        if (line.equals(Protocol.CANCELLED)) {
            return "Order cancelled.";
        }
        if (line.equals(Protocol.NOT_FOUND)) {
            return "No matching order found to cancel.";
        }
        if (line.equals(Protocol.ENDED)) {
            return "Session ended. Goodbye!";
        }
        if (line.startsWith("ORDER BOOK")) {
            return formatOrderBook(line);
        }
        return line;
    }

    private String formatMatch(String payload) {
        String[] parts = payload.split(",", 4);
        if (parts.length == 4) {
            String side = parts[0].equals("B") ? "BUY" : "SELL";
            return "*** TRADE MATCHED ***\n"
                    + "  " + side + "  " + parts[1] + "  @ " + parts[2] + "\n"
                    + "  Counterparty: " + parts[3];
        }
        return "[MATCH] " + payload;
    }

    private String formatOrderBook(String raw) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== ORDER BOOK ==========");
        String[] lines = raw.split("\u001E");
        for (String l : lines) {
            if (l.equals("ORDER BOOK")) continue;
            if (l.equals("BUY ORDERS")) {
                sb.append("\n--- Buy Orders ---");
                continue;
            }
            if (l.equals("SELL ORDERS")) {
                sb.append("\n--- Sell Orders ---");
                continue;
            }
            if (l.equals("EMPTY")) {
                sb.append("\n  (none)");
                continue;
            }
            String[] parts = l.split(",", 3);
            if (parts.length == 3) {
                String side = parts[0].equals("B") ? "BUY " : "SELL";
                sb.append("\n  ").append(side).append("  ").append(parts[1]).append("  @ ").append(parts[2]);
            } else {
                sb.append("\n  ").append(l);
            }
        }
        sb.append("\n================================");
        return sb.toString();
    }

    private String friendlyError(String code) {
        return switch (code) {
            case "USERNAME_REQUIRED" -> "You must provide a username.";
            case "CONNECT_FIRST" -> "Send your username first before placing orders.";
            case "ORDER_FORMAT" -> "Bad order format. Use: buy <title>,<price>";
            case "CANCEL_FORMAT" -> "Bad cancel format. Use: cancel buy <title>,<price>";
            case "UNKNOWN_COMMAND" -> "Unknown command. Type 'help' for a list of commands.";
            default -> code;
        };
    }
}