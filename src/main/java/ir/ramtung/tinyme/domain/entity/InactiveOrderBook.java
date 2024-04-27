package ir.ramtung.tinyme.domain.entity;

import lombok.Getter;

import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

@Getter
public class InactiveOrderBook extends OrderBook{

    public void enqueue(StopLimitOrder order) {
        super.enqueue(order);
        order.markAsInactive();
    }

    public StopLimitOrder findByOrderId(Side side, long orderId) {
        Order result = super.findByOrderId(side, orderId);
        return result == null ? null : (StopLimitOrder) result;
    }

    public boolean isFirstOrderActive(Side side) {
        var queue = getQueue(side);
        return !queue.isEmpty() && ((StopLimitOrder)queue.getFirst()).isActive();
    }

    public StopLimitOrder dequeue(Side side) {
        var queue = getQueue(side);
        return (StopLimitOrder)queue.pollFirst();
    }

}
