package common;

import java.math.BigDecimal;
import java.util.List;

public final class Protocol {
    public static final String USER_PREFIX = "USER:";
    public static final String ORDER_PREFIX = "ORDER:";
    public static final String CANCEL_PREFIX = "CANCEL:";
    public static final String MATCH_PREFIX = "MATCH:";

    public static final String VIEW = "VIEW";
    public static final String END = "END";

    public static final String CONNECTED = "CONNECTED";
    public static final String CANCELLED = "CANCELLED";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String ENDED = "ENDED";

    // Record separator used as line delimiter in protocol messages (keeps order book as a single line on the wire)
    public static final String LINE_SEP = "\u001E";

    private Protocol() {
    }

    public static String formatPrice(BigDecimal price) {
        return price.stripTrailingZeros().toPlainString();
    }

    public static String buildMatch(Side side, String title, BigDecimal price, String counterparty) {
        return MATCH_PREFIX
                + side.getCode() + ","
                + title + ","
                + formatPrice(price) + ","
                + counterparty;
    }

    public static String buildOrderBook(List<String> buyLines, List<String> sellLines) {
        StringBuilder sb = new StringBuilder();

        sb.append("ORDER BOOK").append(LINE_SEP);
        sb.append("BUY ORDERS").append(LINE_SEP);

        if (buyLines.isEmpty()) {
            sb.append("EMPTY").append(LINE_SEP);
        } else {
            for (String line : buyLines) {
                sb.append(line).append(LINE_SEP);
            }
        }

        sb.append("SELL ORDERS").append(LINE_SEP);

        if (sellLines.isEmpty()) {
            sb.append("EMPTY");
        } else {
            for (int i = 0; i < sellLines.size(); i++) {
                sb.append(sellLines.get(i));
                if (i < sellLines.size() - 1) {
                    sb.append(LINE_SEP);
                }
            }
        }

        return sb.toString();
    }

    public static String emptyBook() {
        return buildOrderBook(List.of(), List.of());
    }
}