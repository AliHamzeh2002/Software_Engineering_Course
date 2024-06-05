package ir.ramtung.tinyme.domain.entity;

import lombok.Getter;

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

    @Override
    public StopLimitOrder removeFirst(Side side) {
        return (StopLimitOrder) super.removeFirst(side);
    }

}
