package ir.ramtung.tinyme.domain.entity;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.LocalDateTime;

@Getter
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)

public class StopLimitOrder extends Order {
    int stopPrice;

    public StopLimitOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, int stopPrice, OrderStatus status) {
        super(orderId, security, side, quantity, price, broker, shareholder, entryTime, status,0);
        this.stopPrice = stopPrice;
    }

    public StopLimitOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, int stopPrice, OrderStatus status) {
        super(orderId,security,side,quantity,price,broker,shareholder,0);
        this.stopPrice = stopPrice;
    }

    public StopLimitOrder(long orderId, Security security, Side side, int quantity, int price, Broker broker, Shareholder shareholder, LocalDateTime entryTime, int stopPrice) {
        super(orderId, security, side, quantity, price, broker, shareholder, entryTime, OrderStatus.NEW,0);
        this.stopPrice = stopPrice;
    }


    public boolean isActive(int lastTradePrice){
        if (this.getSide() == Side.BUY) {
            return stopPrice <= lastTradePrice;
        } else {
            return stopPrice >= lastTradePrice;
        }
    }

    public boolean isInactive(int lastTradePrice){
        if (this.getSide() == Side.BUY) {
            return stopPrice >= lastTradePrice;
        } else {
            return stopPrice <= lastTradePrice;
        }
    }

    public void markAsInactive(){
        this.status = OrderStatus.INACTIVE;
    }

    public boolean queuesBeforeInInactiveQueue(StopLimitOrder order) {
        if (order.getSide() == Side.BUY) {
            return stopPrice < order.getStopPrice();
        } else {
            return stopPrice > order.getStopPrice();
        }
    }

}
