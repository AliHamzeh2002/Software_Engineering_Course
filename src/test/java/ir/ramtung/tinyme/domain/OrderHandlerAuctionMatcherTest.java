package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.ChangeMatchingStateRq;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class OrderHandlerAuctionMatcherTest {
    @Autowired
    OrderHandler orderHandler;
    @Autowired
    EventPublisher eventPublisher;
    @Autowired
    SecurityRepository securityRepository;
    @Autowired
    BrokerRepository brokerRepository;
    @Autowired
    ShareholderRepository shareholderRepository;
    private Security security;
    private Shareholder shareholder;
    private Broker broker1;
    private Broker broker2;
    private Broker broker3;

    private List<Order> orders;

    @BeforeEach
    void setup() {
        securityRepository.clear();
        brokerRepository.clear();
        shareholderRepository.clear();

        security = Security.builder().isin("ABC").build();
        securityRepository.addSecurity(security);

        shareholder = Shareholder.builder().build();
        shareholder.incPosition(security, 100_000);
        shareholderRepository.addShareholder(shareholder);

        broker1 = Broker.builder().brokerId(1).credit(1000_000L).build();
        broker2 = Broker.builder().brokerId(2).credit(1000_000L).build();
        broker3 = Broker.builder().brokerId(3).credit(1000_000L).build();

        orders = Arrays.asList(
                new Order(1, security, BUY, 5, 30, broker1, shareholder, 0),
                new Order(2, security, BUY, 5,20 , broker1, shareholder, 0),
                new Order(3, security, BUY, 5, 10, broker1, shareholder, 0),
                new Order(6, security, Side.SELL, 5, 5, broker2, shareholder, 0),
                new Order(7, security, Side.SELL, 5, 14, broker2, shareholder, 0),
                new Order(8, security, Side.SELL, 5, 25, broker2, shareholder, 0)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        brokerRepository.addBroker(broker1);
        brokerRepository.addBroker(broker2);
        brokerRepository.addBroker(broker3);
    }

    @Test
    void stop_limit_order_can_not_have_minimum_execution_quantity() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 300, 15450, 2, shareholder.getShareholderId(), 0, 200, 10));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 200, List.of(Message.CANNOT_SPECIFY_MINIMUM_EXECUTION_QUANTITY_FOR_A_STOP_LIMIT_ORDER)));
    }

    @Test
    void stop_limit_order_can_not_be_iceberg(){
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 300, 15450, 2, shareholder.getShareholderId(), 100, 0, 10));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 200, List.of(Message.STOP_LIMIT_ORDER_CANNOT_BE_ICEBERG)));
    }

    @Test
    void inactive_sell_stop_limit_order_is_accepted(){
        security.setLastTradePrice(100);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 300, 15450, 2, shareholder.getShareholderId(), 0, 0, 10));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 200));
    }

    @Test
    void inactive_buy_stop_limit_order_is_accepted(){
        security.setLastTradePrice(5);
        broker1.increaseCreditBy(100);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 10, 10, 1, shareholder.getShareholderId(), 0, 0, 10));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 200));
    }

    @Test
    void new_sell_stop_limit_order_is_activated(){
        security.setLastTradePrice(5);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 300, 15450, 2, shareholder.getShareholderId(), 0, 0, 10));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 200));
        verify(eventPublisher).publish(new OrderActivatedEvent(1, 200));
    }

    @Test
    void new_buy_stop_limit_order_is_activated(){
        security.setLastTradePrice(30);
        broker1.increaseCreditBy(100);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 10, 10, 1, shareholder.getShareholderId(), 0, 0, 20));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 200));
        verify(eventPublisher).publish(new OrderActivatedEvent(1, 200));
    }

    @Test
    void security_matcher_changes_state_from_continuous_to_auction(){
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq(1, "ABC", MatchingState.AUCTION));
        assertThat(security.getMatchingState()).isEqualTo(MatchingState.AUCTION);
        verify(eventPublisher).publish(new SecurityStateChangedEvent("ABC", MatchingState.AUCTION));
    }

    @Test
    void security_matcher_changes_state_from_auction_to_continuous(){
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq(1, "ABC", MatchingState.CONTINUOUS));
        assertThat(security.getMatchingState()).isEqualTo(MatchingState.CONTINUOUS);
        verify(eventPublisher).publish(new SecurityStateChangedEvent("ABC", MatchingState.CONTINUOUS));
    }

    @Test
    void security_matcher_changes_state_from_auction_to_auction(){
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq(1, "ABC", MatchingState.AUCTION));
        assertThat(security.getMatchingState()).isEqualTo(MatchingState.AUCTION);
        verify(eventPublisher).publish(new SecurityStateChangedEvent("ABC", MatchingState.AUCTION));
    }

    @Test
    void security_matcher_changes_state_from_continuous_to_continuous(){
        security.setMatchingState(MatchingState.CONTINUOUS);
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq(1, "ABC", MatchingState.CONTINUOUS));
        assertThat(security.getMatchingState()).isEqualTo(MatchingState.CONTINUOUS);
        verify(eventPublisher).publish(new SecurityStateChangedEvent("ABC", MatchingState.CONTINUOUS));
    }

    @Test
    void opening_price_event_publishes_when_new_order_is_entered(){
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 5, LocalDateTime.now(), Side.BUY, 5, 40, 1, shareholder.getShareholderId(), 0, 0));
        verify(eventPublisher).publish(new OpeningPriceEvent("ABC", 14, 10));
    }

    @Test
    void opening_price_event_publishes_when_order_is_updated(){
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 1, LocalDateTime.now(), Side.BUY, 5, 40, 1, shareholder.getShareholderId(), 0, 0));
        verify(eventPublisher).publish(new OpeningPriceEvent("ABC", 14, 10));
    }

    @Test
    void broker_does_not_have_enough_credit_after_order_update_request(){
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 1, LocalDateTime.now(), Side.BUY, 5000, 4000, 1, shareholder.getShareholderId(), 0, 0));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 1, List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
    }

    @Test
    void opening_price_event_publishes_when_order_is_deleted(){
        security.setMatchingState(MatchingState.AUCTION);
        security.setLastTradePrice(40);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, "ABC", Side.BUY, 1));
        verify(eventPublisher).publish(new OpeningPriceEvent("ABC", 20, 5));
    }

    @Test
    void new_stop_limit_order_is_rejected_in_auction_state(){
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 5, LocalDateTime.now(), Side.BUY, 5, 40, 1, shareholder.getShareholderId(), 0, 0, 10));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 5, List.of(Message.STOP_LIMIT_ORDER_IS_NOT_ALLOWED_IN_AUCTION_STATE)));
    }

    @Test
    void inactive_stop_limit_order_can_not_get_updated_in_auction_state(){
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 5, LocalDateTime.now(), Side.BUY, 5, 40, 1, shareholder.getShareholderId(), 0, 0, 10));
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 5, LocalDateTime.now(), Side.BUY, 5, 40, 1, shareholder.getShareholderId(), 0, 0, 5));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 5, List.of(Message.STOP_LIMIT_ORDER_IS_NOT_ALLOWED_IN_AUCTION_STATE)));
    }

    @Test
    void active_stop_limit_order_can_get_updated_in_auction_state() {
        security.setLastTradePrice(20);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 5, LocalDateTime.now(), Side.BUY, 100, 40, 1, shareholder.getShareholderId(), 0, 0, 10));
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 5, LocalDateTime.now(), Side.BUY, 100, 30, 1, shareholder.getShareholderId(), 0, 0, 0));
        verify(eventPublisher).publish(new OrderUpdatedEvent(1, 5));
        verify(eventPublisher).publish(any(OpeningPriceEvent.class));
    }

    @Test
    void inactive_stop_limit_order_can_not_get_deleted_in_auction_state(){
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 5, LocalDateTime.now(), Side.BUY, 5, 40, 1, shareholder.getShareholderId(), 0, 0, 10));
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, "ABC", Side.BUY, 5));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 5, List.of(Message.CANNOT_DELETE_INACTIVE_ORDER_IN_AUCTION)));
    }

    @Test
    void active_stop_limit_order_can_get_deleted_in_auction_state(){
        security.setLastTradePrice(20);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 5, LocalDateTime.now(), Side.BUY, 100, 40, 1, shareholder.getShareholderId(), 0, 0, 10));
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, "ABC", Side.BUY, 5));
        verify(eventPublisher).publish(new OrderDeletedEvent(1, 5));
        verify(eventPublisher).publish(any(OpeningPriceEvent.class));
    }

    @Test
    void order_with_minimum_execution_quantity_is_rejected(){
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 5, LocalDateTime.now(), Side.BUY, 5, 40, 1, shareholder.getShareholderId(), 0, 10, 0));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 5, List.of(Message.MINIMUM_EXECUTION_QUANTITY_IS_NOT_ALLOWED_IN_AUCTION_STATE)));
    }

    @Test
    void trade_events_publish_after_auction_to_continuous_reopening(){
        security.setMatchingState(MatchingState.AUCTION);
        security.setLastTradePrice(18);
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq(1, "ABC", MatchingState.CONTINUOUS));
        List<TradeEvent> tradeEvents = Arrays.asList(
                new TradeEvent(security.getIsin(), 18, 5, 1, 6),
                new TradeEvent(security.getIsin(), 18, 5, 2, 7)
        );
        tradeEvents.forEach(tradeEvent -> verify(eventPublisher).publish(tradeEvent));
    }

    @Test
    void trade_events_publish_after_auction_to_auction_reopening(){
        security.setMatchingState(MatchingState.AUCTION);
        security.setLastTradePrice(7);
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq(1, "ABC", MatchingState.AUCTION));
        List<TradeEvent> tradeEvents = Arrays.asList(
                new TradeEvent(security.getIsin(), 14, 5, 1, 6),
                new TradeEvent(security.getIsin(), 14, 5, 2, 7)
        );
        tradeEvents.forEach(tradeEvent -> verify(eventPublisher).publish(tradeEvent));

    }

    @Test
    void stop_limit_order_activates_after_reopening_from_auction_to_auction(){
        security.setLastTradePrice(5);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 5, LocalDateTime.now(), Side.BUY, 100, 40, 1, shareholder.getShareholderId(), 0, 0, 10));
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq(2, "ABC", MatchingState.AUCTION));
        verify(eventPublisher).publish(new OrderActivatedEvent(2, 5));
        verify(eventPublisher).publish(new SecurityStateChangedEvent("ABC", MatchingState.AUCTION));
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 5)).isNotNull();
    }

    @Test
    void stop_limit_order_activates_after_reopening_from_auction_to_continuous(){
        security.setLastTradePrice(5);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 5, LocalDateTime.now(), Side.BUY, 100, 30, 1, shareholder.getShareholderId(), 0, 0, 10));
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq(2, "ABC", MatchingState.CONTINUOUS));
        verify(eventPublisher).publish(new OrderActivatedEvent(2, 5));
        verify(eventPublisher).publish(new SecurityStateChangedEvent("ABC", MatchingState.CONTINUOUS));
        verify(eventPublisher).publish(new OrderExecutedEvent(2, 5, List.of(new TradeDTO(new Trade(security, 25, 5, security.getOrderBook().findByOrderId(Side.BUY, 5), orders.get(5))))));
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 5)).isNotNull();
    }
}
