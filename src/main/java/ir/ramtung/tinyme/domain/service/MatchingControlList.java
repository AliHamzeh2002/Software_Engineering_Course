package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.MatchResult;
import ir.ramtung.tinyme.domain.entity.MatchingOutcome;
import ir.ramtung.tinyme.domain.entity.Order;
import ir.ramtung.tinyme.domain.entity.Trade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedList;
import java.util.List;

@Component
public class MatchingControlList {
    @Autowired
    private List<MatchingControl> controlList;

    public MatchingOutcome canStartExecuting(Order order) {
        for (MatchingControl control : controlList) {
            MatchingOutcome outcome = control.canStartExecuting(order);
            if (outcome != MatchingOutcome.APPROVED)
                return outcome;
        }
        return MatchingOutcome.APPROVED;
    }
    public void executionStarted(Order order) {
        for (MatchingControl control : controlList) {
            control.executionStarted(order);
        }
    }
    public MatchingOutcome canAcceptMatching(Order order, MatchResult result) {
        for (MatchingControl control : controlList) {
            MatchingOutcome outcome = control.canAcceptMatching(order, result);
            if (outcome != MatchingOutcome.APPROVED) {
                return outcome;
            }
        }
        return MatchingOutcome.APPROVED;
    }
    public void matchingAccepted(Order order, MatchResult result) {
        for (MatchingControl control : controlList) {
            control.matchingAccepted(order, result);
        }
    }

    public MatchingOutcome canTrade(Order newOrder, Trade trade) {
        for (MatchingControl control : controlList) {
            MatchingOutcome outcome = control.canTrade(newOrder, trade);
            if (outcome != MatchingOutcome.APPROVED) {
                return outcome;
            }
        }
        return MatchingOutcome.APPROVED;
    }

    public void tradeAccepted(Order newOrder, Trade trade) {
        for (MatchingControl control : controlList) {
            control.tradeAccepted(newOrder, trade);
        }
    }

    public void tradeAccepted(Trade trade) {
        for (MatchingControl control : controlList) {
            control.tradeAccepted(trade);
        }
    }

    public void rollbackTrades(Order newOrder, LinkedList<Trade> trades) {
        for (MatchingControl control : controlList) {
            control.rollbackTrades(newOrder, trades);
        }

    }

}
