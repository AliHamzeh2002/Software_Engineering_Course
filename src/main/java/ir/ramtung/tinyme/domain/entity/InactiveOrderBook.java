package ir.ramtung.tinyme.domain.entity;

import lombok.Getter;

import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

@Getter
public class InactiveOrderBook {
    private final LinkedList<StopLimitOrder> queue;

    public InactiveOrderBook() {
        queue = new LinkedList<>();
    }

    public void enqueue(StopLimitOrder order) {

        queue.addLast(order);
        order.setAsInactive();
    }

    public boolean removeByOrderId(long orderId) {

        var it = queue.listIterator();
        while (it.hasNext()) {
            if (it.next().getOrderId() == orderId) {
                it.remove();
                return true;
            }
        }
        return false;
    }

}
