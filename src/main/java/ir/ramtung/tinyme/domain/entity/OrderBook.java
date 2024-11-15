package ir.ramtung.tinyme.domain.entity;

import lombok.Getter;

import java.util.*;

@Getter
public class OrderBook {
    private final LinkedList<Order> buyQueue;
    private final LinkedList<Order> sellQueue;

    public OrderBook() {
        buyQueue = new LinkedList<>();
        sellQueue = new LinkedList<>();
    }

    public void enqueue(Order order) {
        List<Order> queue = getQueue(order.getSide());
        ListIterator<Order> it = queue.listIterator();
        while (it.hasNext()) {
            if (order.queuesBefore(it.next())) {
                it.previous();
                break;
            }
        }
        order.markAsQueue();
        it.add(order);
    }

    protected LinkedList<Order> getQueue(Side side) {
        return side == Side.BUY ? buyQueue : sellQueue;
    }

    public Order findByOrderId(Side side, long orderId) {
        var queue = getQueue(side);
        for (Order order : queue) {
            if (order.getOrderId() == orderId)
                return order;
        }
        return null;
    }

    public boolean removeByOrderId(Side side, long orderId) {
        var queue = getQueue(side);
        var it = queue.listIterator();
        while (it.hasNext()) {
            if (it.next().getOrderId() == orderId) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    public Order matchWithFirst(Order newOrder) {
        var queue = getQueue(newOrder.getSide().opposite());
        if (newOrder.matches(queue.getFirst().getPrice()))
            return queue.getFirst();
        else
            return null;
    }

    public void putBack(Order order) {
        LinkedList<Order> queue = getQueue(order.getSide());
        order.markAsQueue();
        queue.addFirst(order);
    }

    public void restoreOrder(Order order) {
        removeByOrderId(order.getSide(), order.getOrderId());
        putBack(order);
    }

    public boolean hasOrderOfType(Side side) {
        return !getQueue(side).isEmpty();
    }

    public Order removeFirst(Side side) {
        return getQueue(side).pollFirst();
    }

    public Order getFirst(Side side){
        return getQueue(side).getFirst();
    }

    public int totalSellQuantityByShareholder(Shareholder shareholder) {
        return sellQueue.stream()
                .filter(order -> order.getShareholder().equals(shareholder))
                .mapToInt(Order::getTotalQuantity)
                .sum();
    }

    public int calculateTradableQuantity(Side side, int openingPrice){
        int tradableQuantity = 0;
        LinkedList<Order> queue = getQueue(side);
        for (Order order : queue) {
            if (!order.matches(openingPrice))
                break;
            tradableQuantity += order.getTotalQuantity();
        }
        return tradableQuantity;
    }

    public Set<Integer> getUniquePrices(){
        Set<Integer> prices = new HashSet<>();
        for (Order order : buyQueue) {
            prices.add(order.getPrice());
        }
        for (Order order : sellQueue) {
            prices.add(order.getPrice());
        }
        return prices;
    }

}
