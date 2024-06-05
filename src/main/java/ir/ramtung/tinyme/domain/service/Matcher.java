package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;

public abstract class Matcher {
    public abstract MatchResult execute(Order order);
    protected void handleOrderQuantityAfterTrade(Order order, int tradedQuantity, OrderBook orderBook){
        order.decreaseQuantity(tradedQuantity);
        if (order.getStatus() == OrderStatus.NEW || order.getStatus() == OrderStatus.UPDATING || order.getStatus() == OrderStatus.ACTIVE)
            return;
        if (order.getQuantity() != 0)
            return;
        orderBook.removeFirst(order.getSide());
        if (order instanceof IcebergOrder icebergOrder) {
            icebergOrder.replenish();
            if (icebergOrder.getQuantity() > 0)
                orderBook.enqueue(icebergOrder);
        }
    }
}

