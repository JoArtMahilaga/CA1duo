package server;

import common.Order;

import java.math.BigDecimal;

// This class handles matching of buy and sell orders in the order book
// When a new order is placed, the OrderBook checks if there is a matching order on the opposite side
// If a match is found, a MatchResult is created containing the details of the trade
public class MatchResult {
    private final Order incomingOrder;
    private final Order restingOrder;
    private final BigDecimal tradePrice;

    public MatchResult(Order incomingOrder, Order restingOrder, BigDecimal tradePrice) {
        this.incomingOrder = incomingOrder;
        this.restingOrder = restingOrder;
        this.tradePrice = tradePrice;
    }

    public Order getIncomingOrder() {
        return incomingOrder;
    }

    public Order getRestingOrder() {
        return restingOrder;
    }

    public BigDecimal getTradePrice() {
        return tradePrice;
    }
}