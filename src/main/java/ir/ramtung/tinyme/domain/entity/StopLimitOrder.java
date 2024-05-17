package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)

public class StopLimitOrder extends Order {
    int stopPrice;

    public StopLimitOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime,OrderStatus status, int stopPrice) {
        super(orderId, security, side, quantity, price, broker, shareholder, entryTime, status,0);
        this.stopPrice = stopPrice;
    }

    public StopLimitOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, int stopPrice) {
        super(orderId,security,side,quantity,price,broker,shareholder,0);
        this.stopPrice = stopPrice;
    }

    public StopLimitOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, int stopPrice) {
        super(orderId, security, side, quantity, price, broker, shareholder, entryTime, OrderStatus.NEW,0);
        this.stopPrice = stopPrice;
    }

    @Override
    public StopLimitOrder snapshot() {
        return new StopLimitOrder(orderId, security, side, quantity, price, broker, shareholder, entryTime, OrderStatus.SNAPSHOT, stopPrice);
    }


    public boolean isActive(){
        int lastTradePrice = security.getLastTradePrice();
        if (this.getSide() == Side.BUY) {
            return stopPrice <= lastTradePrice;
        } else {
            return stopPrice >= lastTradePrice;
        }
    }

    public void markAsInactive(){
        this.status = OrderStatus.INACTIVE;
    }

    public void markAsActive(){this.status = OrderStatus.ACTIVE;}

    public boolean queuesBeforeInInactiveQueue(StopLimitOrder order) {
        if (order.getSide() == Side.BUY) {
            return stopPrice < order.getStopPrice();
        } else {
            return stopPrice > order.getStopPrice();
        }
    }

    @Override
    public void updateFromRequest(EnterOrderRq updateOrderRq) {
        super.updateFromRequest(updateOrderRq);
        if (status == OrderStatus.INACTIVE)
            this.stopPrice = updateOrderRq.getStopPrice();
    }

    @Override
    public boolean queuesBefore(Order order) {
        if (order.getStatus() == OrderStatus.INACTIVE) {
            return queuesBeforeInInactiveQueue((StopLimitOrder) order);
        } else {
            return super.queuesBefore(order);
        }
    }

}
