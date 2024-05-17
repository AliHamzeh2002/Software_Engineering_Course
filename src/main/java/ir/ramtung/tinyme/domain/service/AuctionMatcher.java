package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import org.springframework.stereotype.Service;

import java.util.LinkedList;


@Service
public class AuctionMatcher implements Matcher{

    public final static int INVALID_OPENING_PRICE = 0;

    public int calculateTradableQuantity(int openingPrice, OrderBook orderBook){
        int tradableQuantityBuy = 0;
        int tradableQuantitySell = 0;
        LinkedList<Order> buyQueue = orderBook.getBuyQueue();
        LinkedList<Order> sellQueue = orderBook.getSellQueue();
        for (Order buyOrder : buyQueue) {
            if (!buyOrder.matches(openingPrice))
                break;
            tradableQuantityBuy += buyOrder.getTotalQuantity();
        }
        for (Order sellOrder : sellQueue) {
            if (!sellOrder.matches(openingPrice))
                break;
            tradableQuantitySell += sellOrder.getTotalQuantity();
        }
        return Math.min(tradableQuantityBuy, tradableQuantitySell);
    }

    private boolean isBetterOpeningPrice(int currentOpeningPrice, int currentMaxTradableQuantity, int price, int tradableQuantity, int lastTradePrice){
        if (tradableQuantity > currentMaxTradableQuantity)
            return true;
        if (tradableQuantity == currentMaxTradableQuantity && Math.abs(price - lastTradePrice) < Math.abs(currentOpeningPrice - lastTradePrice))
            return true;
        if (tradableQuantity == currentMaxTradableQuantity && Math.abs(price - lastTradePrice) == Math.abs(currentOpeningPrice - lastTradePrice) && price < currentOpeningPrice)
            return true;
        return false;
    }

    public int calculateOpeningPrice(OrderBook orderBook, int lastTradePrice){
        int maxTradableQuantity = calculateTradableQuantity(lastTradePrice, orderBook);
        int openingPrice = lastTradePrice;
        LinkedList<Order> buyOrdersCopy = new LinkedList<>(orderBook.getBuyQueue());
        LinkedList<Order> sellOrdersCopy = new LinkedList<>(orderBook.getSellQueue());
        LinkedList<Order> allOrders = new LinkedList<>(buyOrdersCopy);
        allOrders.addAll(sellOrdersCopy);
        for (Order order : allOrders){
            int tradableQuantity = calculateTradableQuantity(order.getPrice(), orderBook);
            if (isBetterOpeningPrice(openingPrice, maxTradableQuantity, order.getPrice(), tradableQuantity, lastTradePrice)){
                maxTradableQuantity = tradableQuantity;
                openingPrice = order.getPrice();
            }
        }
        if (maxTradableQuantity == 0)
            return INVALID_OPENING_PRICE;
        return openingPrice;
    }


    @Override
    public MatchResult execute(Order order) {
        if (order instanceof StopLimitOrder && ((order.getStatus() == OrderStatus.NEW || order.getStatus() == OrderStatus.INACTIVE))){
            return MatchResult.stopLimitOrderIsNotAllowed();
        }
        if (order.getStatus() == OrderStatus.NEW && order.getMinimumExecutionQuantity() != 0){
            return MatchResult.minimumExecutionQuantityIsNotAllowed();
        }
        if (order.getSide() == Side.BUY) {
            if (!order.getBroker().hasEnoughCredit(order.getValue()))
                return MatchResult.notEnoughCredit();
            order.getBroker().decreaseCreditBy(order.getValue());
        }
        order.getSecurity().getOrderBook().enqueue(order);
        int openingPrice = calculateOpeningPrice(order.getSecurity().getOrderBook(), order.getSecurity().getLastTradePrice());
        int tradableQuantity = calculateTradableQuantity(openingPrice, order.getSecurity().getOrderBook());
        return MatchResult.executed(order, new LinkedList<>(), openingPrice, tradableQuantity);
    }

    private void handleOrderQuantityAfterTrade(Order order, int tradedQuantity, OrderBook orderBook){
        order.decreaseQuantity(tradedQuantity);
        if (order.getQuantity() != 0)
            return;
        orderBook.removeFirst(order.getSide());
        if (order instanceof IcebergOrder icebergOrder) {
            icebergOrder.replenish();
            if (icebergOrder.getQuantity() > 0)
                orderBook.enqueue(icebergOrder);
        }

    }

    public MatchResult reopen(OrderBook orderBook,int lastTradePrice) {
        int openingPrice = calculateOpeningPrice(orderBook, lastTradePrice);
        LinkedList<Order> buyQueue = orderBook.getBuyQueue();
        LinkedList<Order> sellQueue = orderBook.getSellQueue();
        LinkedList<Trade> trades = new LinkedList<>();
        while (orderBook.hasOrderOfType(Side.BUY) && orderBook.hasOrderOfType(Side.SELL)) {
            Order buyOrder = buyQueue.getFirst();
            Order sellOrder = sellQueue.getFirst();
            int tradedQuantity = Math.min(buyOrder.getQuantity(), sellOrder.getQuantity());
            if (!buyOrder.matches(openingPrice) || !sellOrder.matches(openingPrice)) {
                break;
            }
            Trade trade = new Trade(buyOrder.getSecurity(), openingPrice, tradedQuantity, buyOrder, sellOrder);
            trade.increaseSellersCredit();
            trades.add(trade);
            buyOrder.getBroker().increaseCreditBy((long) (buyOrder.getPrice() - openingPrice) * tradedQuantity);
            handleOrderQuantityAfterTrade(buyOrder, tradedQuantity, orderBook);
            handleOrderQuantityAfterTrade(sellOrder, tradedQuantity, orderBook);
        }
        if (!trades.isEmpty())
            trades.get(0).getBuy().getSecurity().setLastTradePrice(openingPrice);

        return MatchResult.executed(null, trades);
    }
}