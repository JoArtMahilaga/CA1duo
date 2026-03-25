package server;

import common.Order;
import common.Protocol;
import common.Side;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

// Handles buy and sell game orders and aids in matching orders of games if they exist in the order book
// Also handles order cancellations and viewing the list of games being bought and sold

public class OrderBook {
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final List<Order> buyOrders = new ArrayList<>();
    private final List<Order> sellOrders = new ArrayList<>();

    //Comparators for finding best matches and displaying the games in the correct order

    private final Comparator<Order> bestBuyComparator =
            Comparator.comparing(Order::getPrice, Comparator.reverseOrder())
                    .thenComparingLong(Order::getSequence);

    private final Comparator<Order> bestSellComparator =
            Comparator.comparing(Order::getPrice)
                    .thenComparingLong(Order::getSequence);

    private final Comparator<Order> buyDisplayComparator =
            Comparator.comparing((Order o) -> o.getTitle().toLowerCase())
                    .thenComparing(Order::getPrice, Comparator.reverseOrder())
                    .thenComparingLong(Order::getSequence);

    private final Comparator<Order> sellDisplayComparator =
            Comparator.comparing((Order o) -> o.getTitle().toLowerCase())
                    .thenComparing(Order::getPrice)
                    .thenComparingLong(Order::getSequence);


    // Places a game order in the book, returns a MatchResult if a match is found, otherwise null (Hello there, Gerneral order (-W-)/ )
    public MatchResult placeOrder(Order incomingOrder) {
        lock.writeLock().lock();
        try {
            if (incomingOrder.getSide() == Side.BUY) {
                Order bestSell = findBestSellFor(incomingOrder);

                if (bestSell != null) {
                    sellOrders.remove(bestSell);
                    return new MatchResult(incomingOrder, bestSell, bestSell.getPrice());
                }

                buyOrders.add(incomingOrder);
                return null;
            }

            Order bestBuy = findBestBuyFor(incomingOrder);

            if (bestBuy != null) {
                buyOrders.remove(bestBuy);
                return new MatchResult(incomingOrder, bestBuy, bestBuy.getPrice());
            }

            sellOrders.add(incomingOrder);
            return null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Cancels an order matching the given details, returns true if an order was found and removed (Sayonara order you wil be missed :'( )
    public boolean cancelOrder(String sessionId, Side side, String title, BigDecimal price) {
        lock.writeLock().lock();
        try {
            List<Order> targetList = (side == Side.BUY) ? buyOrders : sellOrders;

            for (int i = 0; i < targetList.size(); i++) {
                Order current = targetList.get(i);
                if (current.matchesCancellation(sessionId, side, title, price)) {
                    targetList.remove(i);
                    return true;
                }
            }

            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // Removes all game orders of a client
    // This can be used when a client disconnects to ensure their orders are not left hanging in the book (Cleaning the mess after the party :D )
    public void removeAllForSession(String sessionId) {
        lock.writeLock().lock();
        try {
            buyOrders.removeIf(order -> order.belongsToSession(sessionId));
            sellOrders.removeIf(order -> order.belongsToSession(sessionId));
        } finally {
            lock.writeLock().unlock();
        }
    }


    // Returns a string representation of the current order book, sorted and formatted for display
    public String viewBook() {
        lock.readLock().lock();
        try {
            List<String> buyLines = buyOrders.stream()
                    .sorted(buyDisplayComparator)
                    .map(Order::toDisplayLine)
                    .collect(Collectors.toList());

            List<String> sellLines = sellOrders.stream()
                    .sorted(sellDisplayComparator)
                    .map(Order::toDisplayLine)
                    .collect(Collectors.toList());

            return Protocol.buildOrderBook(buyLines, sellLines);
        } finally {
            lock.readLock().unlock();
        }
    }

    // Returns a list of orders as JSON 
    public List<Map<String, String>> getOrdersAsJson(Side side) {
        lock.readLock().lock();
        try {
            List<Order> source = (side == Side.BUY) ? buyOrders : sellOrders;
            Comparator<Order> comp = (side == Side.BUY) ? buyDisplayComparator : sellDisplayComparator;
            List<Map<String, String>> result = new ArrayList<>();
            for (Order o : source.stream().sorted(comp).collect(Collectors.toList())) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("side", o.getSide().getCode());
                m.put("title", o.getTitle());
                m.put("price", Protocol.formatPrice(o.getPrice()));
                m.put("sessionId", o.getSessionId());
                m.put("user", o.getUserName());
                result.add(m);
            }
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    // Finds the best possible sell order that matches the given buy order, returns null if no match found
    private Order findBestSellFor(Order buyOrder) {
        return sellOrders.stream()
                .filter(order -> order.sameTitle(buyOrder.getTitle()))
                .filter(order -> order.getPrice().compareTo(buyOrder.getPrice()) <= 0)
                .filter(order -> !order.getSessionId().equals(buyOrder.getSessionId()))
                .sorted(bestSellComparator)
                .findFirst()
                .orElse(null);
    }

    // Finds the best possible buy order that matches the given sell order, returns null if no match found
    private Order findBestBuyFor(Order sellOrder) {
        return buyOrders.stream()
                .filter(order -> order.sameTitle(sellOrder.getTitle()))
                .filter(order -> order.getPrice().compareTo(sellOrder.getPrice()) >= 0)
                .filter(order -> !order.getSessionId().equals(sellOrder.getSessionId()))
                .sorted(bestBuyComparator)
                .findFirst()
                .orElse(null);
    }
}