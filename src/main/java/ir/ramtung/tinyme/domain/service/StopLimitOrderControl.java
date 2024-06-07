package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import org.springframework.stereotype.Component;

@Component
public class StopLimitOrderControl implements MatchingControl {
    @Override
    public MatchingOutcome canStartExecution(Order order) {
        if (!(order instanceof StopLimitOrder stopLimitOrder))
            return MatchingOutcome.APPROVED;

        if (stopLimitOrder.getSecurity().getMatchingState() == MatchingState.AUCTION) {
            if (stopLimitOrder.getStatus() == OrderStatus.NEW || stopLimitOrder.getStatus() == OrderStatus.INACTIVE)
                return MatchingOutcome.STOP_LIMIT_ORDER_IS_NOT_ALLOWED_IN_AUCTION_STATE;
            return MatchingOutcome.APPROVED;
        }
        if (!stopLimitOrder.isActive()) {
            if (stopLimitOrder.getSide() == Side.BUY) {
                if (!stopLimitOrder.getBroker().hasEnoughCredit(stopLimitOrder.getValue()))
                    return MatchingOutcome.NOT_ENOUGH_CREDIT;
                stopLimitOrder.getBroker().decreaseCreditBy(stopLimitOrder.getValue());
            }
            stopLimitOrder.getSecurity().getInactiveOrderBook().enqueue(stopLimitOrder);
            return MatchingOutcome.IS_INACTIVE;
        }
        return MatchingOutcome.APPROVED;
    }

}