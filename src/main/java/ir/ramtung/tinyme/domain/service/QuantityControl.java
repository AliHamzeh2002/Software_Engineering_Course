package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import org.springframework.stereotype.Component;

import java.util.LinkedList;

@Component
public class QuantityControl implements MatchingControl {

    @Override
    public MatchingOutcome canStartExecution(Order order){
        if (order.getSide() == Side.SELL &&
                !order.getShareholder().hasEnoughPositionsOn(order.getSecurity(),
                        order.getSecurity().getOrderBook().totalSellQuantityByShareholder(order.getShareholder()) + order.getQuantity()))
            return MatchingOutcome.NOT_ENOUGH_POSITIONS;
        return MatchingOutcome.APPROVED;
    }

    private void handleOrderQuantityAfterTrade(Order order, int tradedQuantity, OrderBook orderBook){
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

    @Override
    public void tradeAccepted(Order newOrder, Order matchingOrder, Trade trade){
        OrderBook orderBook = trade.getSecurity().getOrderBook();
        handleOrderQuantityAfterTrade(newOrder, trade.getQuantity(), orderBook);
        handleOrderQuantityAfterTrade(matchingOrder, trade.getQuantity(), orderBook);
    }

    public void matchingAccepted(Order order, MatchResult result) {
        for (Trade trade : result.trades()) {
            trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
            trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
        }
    }

}