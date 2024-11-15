package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.Set;


@Service
public class AuctionMatcher implements Matcher{

    public final static int INVALID_OPENING_PRICE = 0;
    @Autowired
    private MatchingControlList controls;

    public int calculateTradableQuantity(int openingPrice, OrderBook orderBook){
        int tradableQuantityBuy = orderBook.calculateTradableQuantity(Side.BUY, openingPrice);
        int tradableQuantitySell = orderBook.calculateTradableQuantity(Side.SELL, openingPrice);
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
        Set<Integer> prices = orderBook.getUniquePrices();
        for (int price : prices){
            int tradableQuantity = calculateTradableQuantity(price, orderBook);
            if (isBetterOpeningPrice(openingPrice, maxTradableQuantity, price, tradableQuantity, lastTradePrice)){
                maxTradableQuantity = tradableQuantity;
                openingPrice = price;
            }
        }
        if (maxTradableQuantity == 0)
            return INVALID_OPENING_PRICE;
        return openingPrice;
    }

    @Override
    public MatchResult execute(Order order) {
        MatchingOutcome outcome = controls.canStartExecution(order);
        if (outcome != MatchingOutcome.APPROVED)
            return new MatchResult(outcome, order);
        controls.executionStarted(order);
        order.getSecurity().getOrderBook().enqueue(order);
        int openingPrice = calculateOpeningPrice(order.getSecurity().getOrderBook(), order.getSecurity().getLastTradePrice());
        int tradableQuantity = calculateTradableQuantity(openingPrice, order.getSecurity().getOrderBook());
        return MatchResult.executed(order, new LinkedList<>(), openingPrice, tradableQuantity);
    }

    public MatchResult reopen(OrderBook orderBook,int lastTradePrice) {
        int openingPrice = calculateOpeningPrice(orderBook, lastTradePrice);
        LinkedList<Trade> trades = new LinkedList<>();
        while (orderBook.hasOrderOfType(Side.BUY) && orderBook.hasOrderOfType(Side.SELL)) {
            Order buyOrder = orderBook.getFirst(Side.BUY);
            Order sellOrder = orderBook.getFirst(Side.SELL);
            int tradedQuantity = Math.min(buyOrder.getQuantity(), sellOrder.getQuantity());
            if (!buyOrder.matches(openingPrice) || !sellOrder.matches(openingPrice)) {
                break;
            }
            Trade trade = new Trade(buyOrder.getSecurity(), openingPrice, tradedQuantity, buyOrder, sellOrder);
            trades.add(trade);
            controls.tradeAccepted(buyOrder, sellOrder, trade);
        }
        if (!trades.isEmpty())
            trades.get(0).getBuy().getSecurity().setLastTradePrice(openingPrice);

        return MatchResult.executed(null, trades);
    }
}