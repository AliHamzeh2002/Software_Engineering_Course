package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.MatchResult;
import ir.ramtung.tinyme.domain.entity.MatchingOutcome;
import ir.ramtung.tinyme.domain.entity.Order;
import ir.ramtung.tinyme.domain.entity.OrderStatus;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import org.springframework.stereotype.Component;

@Component
public class minimumExecutionQuantityControl implements MatchingControl {

    @Override
    public MatchingOutcome canStartExecution(Order order){
        if (order.getSecurity().getMatchingState() == MatchingState.CONTINUOUS)
            return MatchingOutcome.APPROVED;
        if (order.getStatus() != OrderStatus.NEW || order.getMinimumExecutionQuantity() == 0)
            return MatchingOutcome.APPROVED;
        return MatchingOutcome.MINIMUM_EXECUTION_QUANTITY_IS_NOT_ALLOWED_IN_AUCTION_STATE;
    }

    @Override
    public MatchingOutcome canAcceptMatching(Order order, MatchResult result) {
        if (order.getStatus() == OrderStatus.NEW && !order.hasEnoughExecutions()){
            return MatchingOutcome.NOT_ENOUGH_EXECUTION_QUANTITY;
        }
        return MatchingOutcome.APPROVED;

    }


}