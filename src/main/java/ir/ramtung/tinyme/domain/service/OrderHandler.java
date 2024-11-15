package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.*;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OrderHandler {
    SecurityRepository securityRepository;
    BrokerRepository brokerRepository;
    ShareholderRepository shareholderRepository;
    EventPublisher eventPublisher;
    @Autowired
    ContinuousMatcher continuousMatcher;
    @Autowired
    AuctionMatcher auctionMatcher;

    HashMap<Long, Long> orderIdToRequestId;

    Map<MatchingOutcome, String> errorMessages;

    public OrderHandler(SecurityRepository securityRepository, BrokerRepository brokerRepository, ShareholderRepository shareholderRepository, EventPublisher eventPublisher, ContinuousMatcher continuousMatcher, AuctionMatcher auctionMatcher) {
        this.securityRepository = securityRepository;
        this.brokerRepository = brokerRepository;
        this.shareholderRepository = shareholderRepository;
        this.eventPublisher = eventPublisher;
        this.continuousMatcher = continuousMatcher;
        this.auctionMatcher = auctionMatcher;
        this.orderIdToRequestId = new HashMap<>();
        this.errorMessages = Map.ofEntries(
                Map.entry(MatchingOutcome.NOT_ENOUGH_CREDIT, Message.BUYER_HAS_NOT_ENOUGH_CREDIT),
                Map.entry(MatchingOutcome.NOT_ENOUGH_POSITIONS, Message.SELLER_HAS_NOT_ENOUGH_POSITIONS),
                Map.entry(MatchingOutcome.NOT_ENOUGH_EXECUTION_QUANTITY, Message.HAS_NOT_ENOUGH_EXECUTION_QUANTITY),
                Map.entry(MatchingOutcome.STOP_LIMIT_ORDER_IS_NOT_ALLOWED_IN_AUCTION_STATE, Message.STOP_LIMIT_ORDER_IS_NOT_ALLOWED_IN_AUCTION_STATE),
                Map.entry(MatchingOutcome.MINIMUM_EXECUTION_QUANTITY_IS_NOT_ALLOWED_IN_AUCTION_STATE, Message.MINIMUM_EXECUTION_QUANTITY_IS_NOT_ALLOWED_IN_AUCTION_STATE)
        );
    }

    public void handleEnterOrder(EnterOrderRq enterOrderRq) {
        try {
            validateEnterOrderRq(enterOrderRq);

            Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
            Broker broker = brokerRepository.findBrokerById(enterOrderRq.getBrokerId());
            Shareholder shareholder = shareholderRepository.findShareholderById(enterOrderRq.getShareholderId());

            MatchResult matchResult;
            if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER)
                matchResult = security.newOrder(enterOrderRq, broker, shareholder, getSecurityMatcher(security));
            else
                matchResult = security.updateOrder(enterOrderRq, getSecurityMatcher(security));

            if (errorMessages.containsKey(matchResult.outcome())){
                eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(errorMessages.get(matchResult.outcome()))));
                return;
            }

            if (matchResult.outcome() == MatchingOutcome.EXECUTED && enterOrderRq.getStopPrice()!=0)
                eventPublisher.publish(new OrderActivatedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));

            if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER)
                eventPublisher.publish(new OrderAcceptedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
            else
                eventPublisher.publish(new OrderUpdatedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
            orderIdToRequestId.put(enterOrderRq.getOrderId(), enterOrderRq.getRequestId());

            if (!matchResult.trades().isEmpty())
                eventPublisher.publish(new OrderExecutedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), matchResult.trades().stream().map(TradeDTO::new).collect(Collectors.toList())));
            if (security.getMatchingState() == MatchingState.AUCTION)
                eventPublisher.publish(new OpeningPriceEvent(security.getIsin(), matchResult.openingPrice(), matchResult.tradableQuantity()));
            if (security.getLastTradePrice() != Security.EMPTY_TRADE_PRICE)
                handleActivations(security);

        } catch (InvalidRequestException ex) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), ex.getReasons()));
        }
    }

    private Matcher getSecurityMatcher(Security security){
        if (security.getMatchingState() == MatchingState.AUCTION)
            return auctionMatcher;
        return continuousMatcher;
    }

    private void handleActivations(Security security){
        StopLimitOrder activatedOrder;
        while ((activatedOrder = security.getFirstActivatedOrder()) != null){
            long requestId = orderIdToRequestId.get(activatedOrder.getOrderId());
            MatchResult result = security.activateOrder(activatedOrder, getSecurityMatcher(security));
            eventPublisher.publish(new OrderActivatedEvent(requestId, activatedOrder.getOrderId()));
            if (!result.trades().isEmpty()) {
                eventPublisher.publish(new OrderExecutedEvent(requestId, activatedOrder.getOrderId(), result.trades().stream().map(TradeDTO::new).collect(Collectors.toList())));
            }
        }
    }

    public void handleDeleteOrder(DeleteOrderRq deleteOrderRq) {
        try {
            validateDeleteOrderRq(deleteOrderRq);
            Security security = securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin());
            MatchResult matchResult = security.deleteOrder(deleteOrderRq, getSecurityMatcher(security));
            eventPublisher.publish(new OrderDeletedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId()));
            if (security.getMatchingState() == MatchingState.AUCTION){
                eventPublisher.publish(new OpeningPriceEvent(security.getIsin(), matchResult.openingPrice(), matchResult.tradableQuantity()));
            }
        } catch (InvalidRequestException ex) {
            eventPublisher.publish(new OrderRejectedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId(), ex.getReasons()));
        }
    }

    public void handleChangeMatchingState(ChangeMatchingStateRq changeMatchingStateRq){
        Security security = securityRepository.findSecurityByIsin(changeMatchingStateRq.getSecurityIsin());
        MatchResult matchResult = security.changeMatchingState(changeMatchingStateRq.getTargetState(), auctionMatcher);
        if (!matchResult.trades().isEmpty()){
            matchResult.trades().forEach(trade -> eventPublisher.publish(new TradeEvent(changeMatchingStateRq.getSecurityIsin(), trade.getPrice(), trade.getQuantity(), trade.getBuy().getOrderId(), trade.getSell().getOrderId())));
            handleActivations(security);
        }
        eventPublisher.publish(new SecurityStateChangedEvent(changeMatchingStateRq.getSecurityIsin(), changeMatchingStateRq.getTargetState()));
    }

    private void validateEnterOrderRq(EnterOrderRq enterOrderRq) throws InvalidRequestException {
        List<String> errors = new LinkedList<>();
        if (enterOrderRq.getOrderId() <= 0)
            errors.add(Message.INVALID_ORDER_ID);
        if (enterOrderRq.getQuantity() <= 0)
            errors.add(Message.ORDER_QUANTITY_NOT_POSITIVE);
        if (enterOrderRq.getPrice() <= 0)
            errors.add(Message.ORDER_PRICE_NOT_POSITIVE);
        Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
        if (security == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        else {
            if (enterOrderRq.getQuantity() % security.getLotSize() != 0)
                errors.add(Message.QUANTITY_NOT_MULTIPLE_OF_LOT_SIZE);
            if (enterOrderRq.getPrice() % security.getTickSize() != 0)
                errors.add(Message.PRICE_NOT_MULTIPLE_OF_TICK_SIZE);
        }
        if (brokerRepository.findBrokerById(enterOrderRq.getBrokerId()) == null)
            errors.add(Message.UNKNOWN_BROKER_ID);
        if (shareholderRepository.findShareholderById(enterOrderRq.getShareholderId()) == null)
            errors.add(Message.UNKNOWN_SHAREHOLDER_ID);
        if (enterOrderRq.getPeakSize() < 0 || enterOrderRq.getPeakSize() >= enterOrderRq.getQuantity())
            errors.add(Message.INVALID_PEAK_SIZE);
        if (enterOrderRq.getStopPrice() > 0 && enterOrderRq.getPeakSize() > 0)
            errors.add(Message.STOP_LIMIT_ORDER_CANNOT_BE_ICEBERG);
        if (enterOrderRq.getStopPrice() > 0 && enterOrderRq.getMinimumExecutionQuantity() > 0)
            errors.add(Message.CANNOT_SPECIFY_MINIMUM_EXECUTION_QUANTITY_FOR_A_STOP_LIMIT_ORDER);
        if (!errors.isEmpty())
            throw new InvalidRequestException(errors);
    }

    private void validateDeleteOrderRq(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        List<String> errors = new LinkedList<>();
        if (deleteOrderRq.getOrderId() <= 0)
            errors.add(Message.INVALID_ORDER_ID);
        if (securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin()) == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        if (!errors.isEmpty())
            throw new InvalidRequestException(errors);
    }
}
