package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import org.springframework.stereotype.Component;

import java.util.LinkedList;

@Component
public class CreditControl implements MatchingControl {
    @Override
    public MatchingOutcome canTrade(Order newOrder, Trade trade) {
        if ((newOrder.getSide() == Side.SELL) || (trade.buyerHasEnoughCredit())) {
            return MatchingOutcome.APPROVED;
        } else return MatchingOutcome.NOT_ENOUGH_CREDIT;
    }

    @Override
    public MatchingOutcome canStartExecution(Order order){
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
    public void tradeAccepted(Order newOrder, Order matchingOrder, Trade trade) {
        if (trade.getSecurity().getMatchingState() == MatchingState.AUCTION) {
            trade.increaseSellersCredit();
            Order buyOrder = trade.getBuy();
            buyOrder.getBroker().increaseCreditBy((long) (buyOrder.getPrice() - trade.getPrice()) * trade.getQuantity());
            return;
        }
        if (newOrder.getSide() == Side.BUY)
            trade.decreaseBuyersCredit();
        trade.increaseSellersCredit();
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

}
