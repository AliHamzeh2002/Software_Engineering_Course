package ir.ramtung.tinyme.domain.entity;

import lombok.Builder;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

@Builder
public final class MatchResult {
    private final MatchingOutcome outcome;
    private final Order remainder;
    private final LinkedList<Trade> trades;
    @Builder.Default
    private int openingPrice = 0;
    @Builder.Default
    private int tradableQuantity = 0;

    public static MatchResult executed(Order remainder, List<Trade> trades) {
        return new MatchResult(MatchingOutcome.EXECUTED, remainder, new LinkedList<>(trades));
    }

    public static MatchResult executed(Order remainder, List<Trade> trades, int openingPrice, int tradableQuantity) {
        return new MatchResult(MatchingOutcome.EXECUTED, remainder, new LinkedList<>(trades), openingPrice, tradableQuantity);
    }

    public static MatchResult stopLimitOrderIsNotAllowed() {
        return new MatchResult(MatchingOutcome.STOP_LIMIT_ORDER_IS_NOT_ALLOWED_IN_AUCTION_STATE, null, new LinkedList<>());
    }

    public static MatchResult minimumExecutionQuantityIsNotAllowed() {
        return new MatchResult(MatchingOutcome.MINIMUM_EXECUTION_QUANTITY_IS_NOT_ALLOWED_IN_AUCTION_STATE, null, new LinkedList<>());
    }

    public static MatchResult notEnoughCredit() {
        return new MatchResult(MatchingOutcome.NOT_ENOUGH_CREDIT, null, new LinkedList<>());
    }
    public static MatchResult notEnoughPositions() {
        return new MatchResult(MatchingOutcome.NOT_ENOUGH_POSITIONS, null, new LinkedList<>());
    }

    public static MatchResult notEnoughMatches(){
        return new MatchResult(MatchingOutcome.NOT_ENOUGH_EXECUTION_QUANTITY, null, new LinkedList<>());
    }

    public static MatchResult isInActive(){
        return new MatchResult(MatchingOutcome.IS_INACTIVE, null, new LinkedList<>());
    }

    private MatchResult(MatchingOutcome outcome, Order remainder, LinkedList<Trade> trades) {
        this.outcome = outcome;
        this.remainder = remainder;
        this.trades = trades;
    }

    private MatchResult(MatchingOutcome outcome, Order remainder, LinkedList<Trade> trades, int openingPrice, int tradableQuantity) {
        this.outcome = outcome;
        this.remainder = remainder;
        this.trades = trades;
        this.openingPrice = openingPrice;
        this.tradableQuantity = tradableQuantity;
    }

    public MatchingOutcome outcome() {
        return outcome;
    }
    public Order remainder() {
        return remainder;
    }

    public int openingPrice(){
        return openingPrice;
    }

    public int tradableQuantity(){
        return tradableQuantity;
    }

    public LinkedList<Trade> trades() {
        return trades;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (MatchResult) obj;
        return Objects.equals(this.remainder, that.remainder) &&
                Objects.equals(this.trades, that.trades);
    }

    @Override
    public int hashCode() {
        return Objects.hash(remainder, trades);
    }

    @Override
    public String toString() {
        return "MatchResult[" +
                "remainder=" + remainder + ", " +
                "trades=" + trades + ']';
    }


}
