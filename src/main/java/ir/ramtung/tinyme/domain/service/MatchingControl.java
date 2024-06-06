package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.MatchResult;
import ir.ramtung.tinyme.domain.entity.MatchingOutcome;
import ir.ramtung.tinyme.domain.entity.Order;
import ir.ramtung.tinyme.domain.entity.Trade;

import java.util.LinkedList;

public interface MatchingControl {
    default MatchingOutcome canStartExecuting(Order order) { return MatchingOutcome.APPROVED; }
    default void executionStarted(Order order) {}
    default MatchingOutcome canAcceptMatching(Order order, MatchResult result) { return MatchingOutcome.APPROVED; }
    default void matchingAccepted(Order order, MatchResult result) {}

    default MatchingOutcome canTrade(Order newOrder, Trade trade) { return MatchingOutcome.APPROVED; }

    default void tradeAccepted(Order newOrder, Trade trade) {}

    default void tradeAccepted(Trade trade){}

    default void rollbackTrades(Order newOrder, LinkedList<Trade> trades) {}
}
