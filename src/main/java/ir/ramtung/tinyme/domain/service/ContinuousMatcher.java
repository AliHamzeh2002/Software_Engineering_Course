package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.ListIterator;

@Service
public class ContinuousMatcher extends Matcher{
    @Autowired
    private MatchingControlList controls;

    public MatchResult match(Order newOrder) {
        OrderBook orderBook = newOrder.getSecurity().getOrderBook();
        LinkedList<Trade> trades = new LinkedList<>();

        while (orderBook.hasOrderOfType(newOrder.getSide().opposite()) && newOrder.getQuantity() > 0) {
            Order matchingOrder = orderBook.matchWithFirst(newOrder);
            if (matchingOrder == null)
                break;

            Trade trade = new Trade(newOrder.getSecurity(), matchingOrder.getPrice(), Math.min(newOrder.getQuantity(), matchingOrder.getQuantity()), newOrder, matchingOrder);

            MatchingOutcome outcome = controls.canTrade(newOrder, trade);
            if (outcome != MatchingOutcome.APPROVED) {
                rollbackTrades(newOrder, trades);
                return new MatchResult(outcome, newOrder);
            }
            trades.add(trade);
            controls.tradeAccepted(newOrder, matchingOrder, trade);
        }
        if (newOrder.getStatus() == OrderStatus.NEW && !newOrder.hasEnoughExecutions()) {
            rollbackTrades(newOrder, trades);
            return MatchResult.notEnoughMatches();
        }
        return MatchResult.executed(newOrder, trades);
    }

    private void rollbackTrades(Order newOrder, LinkedList<Trade> trades) {
        trades.forEach(trade -> trade.rollback(newOrder.getSide()));
        ListIterator<Trade> it = trades.listIterator(trades.size());
        while (it.hasPrevious()) {
            Order currentMatchingOrder = it.previous().getOrder(newOrder.getSide().opposite());
            newOrder.getSecurity().getOrderBook().restoreOrder(currentMatchingOrder);
        }
    }

    public MatchResult execute(Order order) {
        MatchingOutcome outcome = controls.canStartExecution(order);
        if (outcome != MatchingOutcome.APPROVED)
            return new MatchResult(outcome, order);

        if (order instanceof StopLimitOrder stopLimitOrder && !stopLimitOrder.isActive()) {
            if (stopLimitOrder.getSide() == Side.BUY) {
                if (!stopLimitOrder.getBroker().hasEnoughCredit(stopLimitOrder.getValue()))
                    return MatchResult.notEnoughCredit();
                stopLimitOrder.getBroker().decreaseCreditBy(stopLimitOrder.getValue());
            }
            stopLimitOrder.getSecurity().getInactiveOrderBook().enqueue(stopLimitOrder);
            return MatchResult.isInActive();
        }

        MatchResult result = match(order);

        if (result.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT || result.outcome() == MatchingOutcome.NOT_ENOUGH_EXECUTION_QUANTITY)
            return result;

        outcome = controls.canAcceptMatching(order, result);
        if (outcome != MatchingOutcome.APPROVED) {
            rollbackTrades(order, result.trades());
            return new MatchResult(outcome, order);
        }

        if (result.remainder().getQuantity() > 0) {
            order.getSecurity().getOrderBook().enqueue(result.remainder());
        }
        controls.matchingAccepted(order, result);

        if (!result.trades().isEmpty()) {
            order.getSecurity().setLastTradePrice(result.trades().getLast().getPrice());
        }
        return result;
    }

}
