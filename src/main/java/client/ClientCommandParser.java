package client;

import java.math.BigDecimal;

import common.Protocol;
import common.Side;

// Paarses User input to match the protocol format used by the server
public final class ClientCommandParser {
    private ClientCommandParser() {
    }

    // Checks if the input is a help command
    public static boolean isHelpCommand(String input) {
        return input != null && input.trim().equalsIgnoreCase("help");
    }

    // Converts user input into the protocol format, throws IllegalArgumentException for invalid commands
    public static String toProtocol(String input) {
        if (input == null || input.trim().isEmpty()) {
            return null;
        }

        String trimmed = input.trim();
        String lower = trimmed.toLowerCase();

        //VIEW COMMAND
        if (trimmed.equalsIgnoreCase(Protocol.VIEW)) {
            return Protocol.VIEW;
        }

        //END COMMAND
        if (trimmed.equalsIgnoreCase(Protocol.END)) {
            return Protocol.END;
        }

        //ORDER, CANCEL, USER COMMANDS
        if (startsWithIgnoreCase(trimmed, Protocol.ORDER_PREFIX)
                || startsWithIgnoreCase(trimmed, Protocol.CANCEL_PREFIX)
                || startsWithIgnoreCase(trimmed, Protocol.USER_PREFIX)) {
            return trimmed;
        }

        //TRANSACTION COMMANDS
        if (lower.startsWith("buy ")) {
            return buildOrder(Side.BUY, trimmed.substring(4));
        }

        if (lower.startsWith("sell ")) {
            return buildOrder(Side.SELL, trimmed.substring(5));
        }

        if (lower.startsWith("cancel buy ")) {
            return buildCancel(Side.BUY, trimmed.substring(11));
        }

        if (lower.startsWith("cancel sell ")) {
            return buildCancel(Side.SELL, trimmed.substring(12));
        }

        throw new IllegalArgumentException(
                "Invalid command.\n"
                        + "Use one of these:\n"
                        + "  buy <title>,<price>\n"
                        + "  sell <title>,<price>\n"
                        + "  cancel buy <title>,<price>\n"
                        + "  cancel sell <title>,<price>\n"
                        + "  view\n"
                        + "  end\n"
                        + "  help"
        );
    }

    // Method that builds the protocol string for buy/sell orders, validates the input format and price
    private static String buildOrder(Side side, String remainder) {
        int commaIndex = remainder.lastIndexOf(',');

        if (commaIndex <= 0 || commaIndex == remainder.length() - 1) {
            throw new IllegalArgumentException("Use format: buy <title>,<price> or sell <title>,<price>");
        }

        String title = remainder.substring(0, commaIndex).trim();
        String price = remainder.substring(commaIndex + 1).trim();

        if (title.isEmpty()) {
            throw new IllegalArgumentException("Title cannot be empty");
        }

        new BigDecimal(price);

        return Protocol.ORDER_PREFIX + side.getCode() + "," + title + "," + price;
    }

    // Method that builds the protocol string for canceling buy/sell orders, validates title and price format
    private static String buildCancel(Side side, String remainder) {
        int commaIndex = remainder.lastIndexOf(',');

        if (commaIndex <= 0 || commaIndex == remainder.length() - 1) {
            throw new IllegalArgumentException("Use format: cancel buy <title>,<price> or cancel sell <title>,<price>");
        }

        String title = remainder.substring(0, commaIndex).trim();
        String price = remainder.substring(commaIndex + 1).trim();

        if (title.isEmpty()) {
            throw new IllegalArgumentException("Title cannot be empty");
        }

        new BigDecimal(price);

        return Protocol.CANCEL_PREFIX + side.getCode() + "," + title + "," + price;
    }

    // Checks if the input starts with the given prefix, ignoring case
    private static boolean startsWithIgnoreCase(String input, String prefix) {
        return input.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}