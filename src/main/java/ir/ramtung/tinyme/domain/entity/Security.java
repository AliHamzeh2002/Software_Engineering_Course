package ir.ramtung.tinyme.domain.entity;

import ir.ramtung.tinyme.domain.service.AuctionMatcher;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

import static ir.ramtung.tinyme.messaging.Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER;

@Getter
@Builder
public class Security {

    public final static int EMPTY_TRADE_PRICE = 0;
    private String isin;
    @Setter
    @Builder.Default
    private int lastTradePrice = EMPTY_TRADE_PRICE;
    @Builder.Default
    private int tickSize = 1;
    @Builder.Default
    private int lotSize = 1;
    @Builder.Default
    private OrderBook orderBook = new OrderBook();
    @Builder.Default
    private InactiveOrderBook inactiveOrderBook = new InactiveOrderBook();
    @Setter
    @Builder.Default
    private MatchingState matchingState = MatchingState.CONTINUOUS;

    public MatchResult newOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, Matcher matcher) {
        Order order;
        if (enterOrderRq.getPeakSize() != 0 && enterOrderRq.getStopPrice() == 0) {
            order = new IcebergOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime(), enterOrderRq.getPeakSize(), enterOrderRq.getMinimumExecutionQuantity());
        }
        else if (enterOrderRq.getPeakSize() == 0 && enterOrderRq.getStopPrice() != 0){
            order = new StopLimitOrder(enterOrderRq.getOrderId(),this,enterOrderRq.getSide(),enterOrderRq.getQuantity(),
                    enterOrderRq.getPrice(),broker,shareholder, enterOrderRq.getEntryTime(),
                    enterOrderRq.getStopPrice());
        }
        else
            order = new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder, enterOrderRq.getEntryTime(), enterOrderRq.getMinimumExecutionQuantity());
        return matcher.execute(order);
    }

    public MatchResult changeMatchingState(MatchingState newState, AuctionMatcher auctionMatcher){
        MatchResult matchResult = MatchResult.executed();
        if (matchingState == MatchingState.AUCTION){
            matchResult = auctionMatcher.reopen(orderBook, lastTradePrice);
        }
        matchingState = newState;
        return matchResult;
    }

    public StopLimitOrder getFirstActivatedOrder(){
        if (inactiveOrderBook.isFirstOrderActive(Side.SELL))
            return inactiveOrderBook.removeFirst(Side.SELL);
        if (inactiveOrderBook.isFirstOrderActive(Side.BUY))
            return inactiveOrderBook.removeFirst(Side.BUY);
        return null;
    }

    public MatchResult activateOrder(StopLimitOrder stoplimitOrder, Matcher matcher){
        stoplimitOrder.markAsActive();
        if (stoplimitOrder.getSide() == Side.BUY)
            stoplimitOrder.getBroker().increaseCreditBy(stoplimitOrder.getValue());
        return matcher.execute(stoplimitOrder);
    }

    private Order findByOrderId(Side side, long orderId){
        Order order = orderBook.findByOrderId(side, orderId);
        if (order == null)
            return inactiveOrderBook.findByOrderId(side, orderId);
        return order;
    }

    private void removeByOrderId(Side side, long orderId) {
        if (orderBook.removeByOrderId(side, orderId))
            return;
        inactiveOrderBook.removeByOrderId(side, orderId);
    }

    public MatchResult deleteOrder(DeleteOrderRq deleteOrderRq, Matcher matcher) throws InvalidRequestException {
        Order order = findByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        try{
            validateDeleteOrder(order);
        }
        catch (InvalidRequestException e){
            throw e;
        }
        if (order.getSide() == Side.BUY)
            order.getBroker().increaseCreditBy(order.getValue());
        removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
        if (matchingState == MatchingState.AUCTION){
            int openingPrice = ((AuctionMatcher) matcher).calculateOpeningPrice(orderBook, lastTradePrice);
            int tradableQuantity = ((AuctionMatcher) matcher).calculateTradableQuantity(openingPrice, orderBook);
            return MatchResult.executed(order, List.of(), openingPrice, tradableQuantity);
        }
        return MatchResult.executed(order, List.of());
    }

    public MatchResult updateOrder(EnterOrderRq updateOrderRq, Matcher matcher) throws InvalidRequestException {
        Order order = findByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        try {
            validateUpdateOrder(updateOrderRq, order);
        } catch (InvalidRequestException e){
            throw e;
        }

        if (updateOrderRq.getSide() == Side.BUY)
            order.getBroker().increaseCreditBy(order.getValue());

        Order originalOrder = order.snapshot();
        order.updateFromRequest(updateOrderRq);
        if (!originalOrder.isPriorityLostAfterUpdate(updateOrderRq)) {
            if (updateOrderRq.getSide() == Side.BUY) {
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            return MatchResult.executed();
        }

        if (order.getStatus() == OrderStatus.INACTIVE)
            order.markAsNew();
        else
            order.markAsUpdating();

        removeByOrderId(order.getSide(), order.getOrderId());
        MatchResult matchResult = matcher.execute(order);
        if (matchResult.outcome() != MatchingOutcome.EXECUTED) {
            orderBook.enqueue(originalOrder);
            if (updateOrderRq.getSide() == Side.BUY) {
                originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
            }
        }
        return matchResult;
    }

    private void validateUpdateOrder(EnterOrderRq updateOrderRq, Order order) throws InvalidRequestException {
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if ((order instanceof IcebergOrder) && updateOrderRq.getPeakSize() == 0)
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);
        if (!(order instanceof IcebergOrder) && updateOrderRq.getPeakSize() != 0)
            throw new InvalidRequestException(CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);
        if (order.minimumExecutionQuantity != updateOrderRq.getMinimumExecutionQuantity())
            throw new InvalidRequestException(Message.CANNOT_CHANGE_MINIMUM_EXECUTION_QUANTITY);
        if (!(order instanceof StopLimitOrder) && updateOrderRq.getStopPrice() > 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_STOP_PRICE_FOR_A_NON_STOP_LIMIT_ORDER);
        if ((order instanceof StopLimitOrder stopLimitOrder) && (stopLimitOrder.getStatus() != OrderStatus.INACTIVE) && (updateOrderRq.getStopPrice() != 0))
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_STOP_PRICE_FOR_ACTIVATED_ORDER);
    }

    private void validateDeleteOrder(Order order) throws InvalidRequestException {
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if (matchingState == MatchingState.AUCTION && order.getStatus() == OrderStatus.INACTIVE) {
            throw new InvalidRequestException(Message.CANNOT_DELETE_INACTIVE_ORDER_IN_AUCTION);
        }
    }
}
