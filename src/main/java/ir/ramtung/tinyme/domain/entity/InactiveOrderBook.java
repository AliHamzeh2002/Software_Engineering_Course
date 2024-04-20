package ir.ramtung.tinyme.domain.entity;

import lombok.Getter;

import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

@Getter
public class InactiveOrderBook {
    private final LinkedList<StopLimitOrder> buyQueue;
    private final LinkedList<StopLimitOrder> sellQueue;

    public InactiveOrderBook() {
        buyQueue = new LinkedList<>();
        sellQueue = new LinkedList<>();
    }

    public void enqueue(StopLimitOrder order) {
        List<StopLimitOrder> queue = getQueue(order.getSide());
        ListIterator<StopLimitOrder> it = queue.listIterator();
        while (it.hasNext()) {
            if (order.queuesBeforeInInactiveQueue(it.next())) {
                it.previous();
                break;
            }
        }
        order.markAsInactive();
        it.add(order);
    }

    private LinkedList<StopLimitOrder> getQueue(Side side) {
        return side == Side.BUY ? buyQueue : sellQueue;
    }

    public StopLimitOrder findByOrderId(Side side, long orderId) {
        var queue = getQueue(side);
        for (StopLimitOrder order : queue) {
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

    public boolean isFirstOrderActive(Side side) {
        var queue = getQueue(side);
        return !queue.isEmpty() && queue.getFirst().isActive();
    }

    public StopLimitOrder dequeue(Side side) {
        var queue = getQueue(side);
        return queue.pollFirst();
    }

}
