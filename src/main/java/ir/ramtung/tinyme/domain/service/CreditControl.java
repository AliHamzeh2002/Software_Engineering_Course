package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import org.springframework.stereotype.Component;

import java.util.LinkedList;

@Component
public class CreditControl implements MatchingControl {
    @Override
    public MatchingOutcome canTrade(Order newOrder, Trade trade) {
        if ((newOrder.getSide() == Side.SELL) || (newOrder.getSide() == Side.BUY && trade.buyerHasEnoughCredit())) {
            return MatchingOutcome.APPROVED;
        } else return MatchingOutcome.NOT_ENOUGH_CREDIT;
    }

    @Override
    public MatchingOutcome canStartExecuting(Order order){
        if (order.getSecurity().getMatchingState() == MatchingState.CONTINUOUS)
            return MatchingOutcome.APPROVED;
        if (order.getSide() == Side.SELL)
            return MatchingOutcome.APPROVED;
        if (order.getBroker().hasEnoughCredit(order.getValue()))
            return MatchingOutcome.APPROVED;
        return MatchingOutcome.NOT_ENOUGH_CREDIT;
    }

    @Override
    public void executionStarted(Order order) {
        if (order.getSide() == Side.BUY && order.getSecurity().getMatchingState() == MatchingState.AUCTION)
            order.getBroker().decreaseCreditBy(order.getValue());
    }

    @Override
    public void tradeAccepted(Trade trade) {
        trade.increaseSellersCredit();
        Order buyOrder = trade.getBuy();
        buyOrder.getBroker().increaseCreditBy((long) (buyOrder.getPrice() - trade.getPrice()) * trade.getQuantity());
    }

    @Override
    public MatchingOutcome canAcceptMatching(Order order, MatchResult result) {
        if (result.remainder().getQuantity() > 0) {
            if (order.getSide() == Side.BUY) {
                if (!order.getBroker().hasEnoughCredit(order.getValue()))
                    return MatchingOutcome.NOT_ENOUGH_CREDIT;
            }
        }
        return MatchingOutcome.APPROVED;
    }

    @Override
    public void matchingAccepted(Order order, MatchResult result) {
        if (order.getSide() == Side.BUY) {
            order.getBroker().decreaseCreditBy(order.getValue());
        }
    }

    @Override
    public void rollbackTrades(Order newOrder, LinkedList<Trade> trades) {
        if (newOrder.getSide() == Side.BUY) {
            newOrder.getBroker().increaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
            trades.forEach(trade -> trade.getSell().getBroker().decreaseCreditBy(trade.getTradedValue()));
        } else {
            newOrder.getBroker().decreaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
            trades.forEach(trade -> trade.getSell().getBroker().increaseCreditBy(trade.getTradedValue()));
        }
    }
}
