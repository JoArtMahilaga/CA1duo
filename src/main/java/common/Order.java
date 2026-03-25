package common;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

public class Order {
    private static final AtomicLong SEQUENCE_GENERATOR = new AtomicLong(0);

    private final String sessionId;
    private final String userName;
    private final Side side;
    private final String title;
    private final BigDecimal price;
    private final long sequence;

    public Order(String sessionId, String userName, Side side, String title, BigDecimal price) {
        this.sessionId = sessionId;
        this.userName = userName;
        this.side = side;
        this.title = title;
        this.price = price;
        this.sequence = SEQUENCE_GENERATOR.incrementAndGet();
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserName() {
        return userName;
    }

    public Side getSide() {
        return side;
    }

    public String getTitle() {
        return title;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public long getSequence() {
        return sequence;
    }

    public boolean sameTitle(String otherTitle) {
        return title.equalsIgnoreCase(otherTitle);
    }

    public boolean belongsToSession(String otherSessionId) {
        return sessionId.equals(otherSessionId);
    }

    public boolean matchesCancellation(String sessionId, Side side, String title, BigDecimal price) {
        return this.sessionId.equals(sessionId)
                && this.side == side
                && this.title.equalsIgnoreCase(title)
                && this.price.compareTo(price) == 0;
    }

    public String toDisplayLine() {
        return side.getCode() + "," + title + "," + Protocol.formatPrice(price);
    }
}
