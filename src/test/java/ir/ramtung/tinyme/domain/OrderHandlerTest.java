package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.AuctionMatcher;
import ir.ramtung.tinyme.domain.service.ContinuousMatcher;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class OrderHandlerTest {
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
        orders = new ArrayList<Order>();

        shareholder = Shareholder.builder().build();
        shareholder.incPosition(security, 100_000);
        shareholderRepository.addShareholder(shareholder);

        broker1 = Broker.builder().brokerId(1).build();
        broker2 = Broker.builder().brokerId(2).build();
        broker3 = Broker.builder().brokerId(3).build();
        brokerRepository.addBroker(broker1);
        brokerRepository.addBroker(broker2);
        brokerRepository.addBroker(broker3);
    }

    @Test
    void new_order_matched_completely_with_one_trade() {
        Order matchingBuyOrder = new Order(100, security, Side.BUY, 1000, 15500, broker1, shareholder, 0);
        Order incomingSellOrder = new Order(200, security, Side.SELL, 300, 15450, broker2, shareholder, 0);
        security.getOrderBook().enqueue(matchingBuyOrder);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 300, 15450, 2, shareholder.getShareholderId(), 0, 0));

        Trade trade = new Trade(security, matchingBuyOrder.getPrice(), incomingSellOrder.getQuantity(),
                matchingBuyOrder, incomingSellOrder);
        verify(eventPublisher).publish((new OrderAcceptedEvent(1, 200)));
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 200, List.of(new TradeDTO(trade))));
    }

    @Test
    void new_order_queued_with_no_trade() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 300, 15450, 2, shareholder.getShareholderId(), 0, 0));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 200));
    }

    @Test
    void new_order_matched_partially_with_two_trades() {
        Order matchingBuyOrder1 = new Order(100, security, Side.BUY, 300, 15500, broker1, shareholder, 0);
        Order matchingBuyOrder2 = new Order(110, security, Side.BUY, 300, 15500, broker1, shareholder, 0);
        Order incomingSellOrder = new Order(200, security, Side.SELL, 1000, 15450, broker2, shareholder, 0);
        security.getOrderBook().enqueue(matchingBuyOrder1);
        security.getOrderBook().enqueue(matchingBuyOrder2);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1,
                incomingSellOrder.getSecurity().getIsin(),
                incomingSellOrder.getOrderId(),
                incomingSellOrder.getEntryTime(),
                incomingSellOrder.getSide(),
                incomingSellOrder.getTotalQuantity(),
                incomingSellOrder.getPrice(),
                incomingSellOrder.getBroker().getBrokerId(),
                incomingSellOrder.getShareholder().getShareholderId(), 0, 0));

        Trade trade1 = new Trade(security, matchingBuyOrder1.getPrice(), matchingBuyOrder1.getQuantity(),
                matchingBuyOrder1, incomingSellOrder);
        Trade trade2 = new Trade(security, matchingBuyOrder2.getPrice(), matchingBuyOrder2.getQuantity(),
                matchingBuyOrder2, incomingSellOrder.snapshotWithQuantity(700));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 200));
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 200, List.of(new TradeDTO(trade1), new TradeDTO(trade2))));
    }

    @Test
    void iceberg_order_behaves_normally_before_being_queued() {
        Order matchingBuyOrder = new Order(100, security, Side.BUY, 1000, 15500, broker1, shareholder, 0);
        Order incomingSellOrder = new IcebergOrder(200, security, Side.SELL, 300, 15450, broker2, shareholder, 100, 0);
        security.getOrderBook().enqueue(matchingBuyOrder);
        Trade trade = new Trade(security, matchingBuyOrder.getPrice(), incomingSellOrder.getQuantity(),
                matchingBuyOrder, incomingSellOrder);

        EventPublisher mockEventPublisher = mock(EventPublisher.class, withSettings().verboseLogging());
        OrderHandler myOrderHandler = new OrderHandler(securityRepository, brokerRepository, shareholderRepository, mockEventPublisher, new ContinuousMatcher(), new AuctionMatcher());
        myOrderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1,
                incomingSellOrder.getSecurity().getIsin(),
                incomingSellOrder.getOrderId(),
                incomingSellOrder.getEntryTime(),
                incomingSellOrder.getSide(),
                incomingSellOrder.getTotalQuantity(),
                incomingSellOrder.getPrice(),
                incomingSellOrder.getBroker().getBrokerId(),
                incomingSellOrder.getShareholder().getShareholderId(), 100, 0));

        verify(mockEventPublisher).publish(new OrderAcceptedEvent(1, 200));
        verify(mockEventPublisher).publish(new OrderExecutedEvent(1, 200, List.of(new TradeDTO(trade))));
    }

    @Test
    void invalid_new_order_with_multiple_errors() {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "XXX", -1, LocalDateTime.now(), Side.SELL, 0, 0, -1, -1, 0, 0));
        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getOrderId()).isEqualTo(-1);
        assertThat(outputEvent.getErrors()).containsOnly(
                Message.UNKNOWN_SECURITY_ISIN,
                Message.INVALID_ORDER_ID,
                Message.ORDER_PRICE_NOT_POSITIVE,
                Message.ORDER_QUANTITY_NOT_POSITIVE,
                Message.INVALID_PEAK_SIZE,
                Message.UNKNOWN_BROKER_ID,
                Message.UNKNOWN_SHAREHOLDER_ID
        );
    }

    @Test
    void invalid_new_order_with_tick_and_lot_size_errors() {
        Security aSecurity = Security.builder().isin("XXX").lotSize(10).tickSize(10).build();
        securityRepository.addSecurity(aSecurity);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "XXX", 1, LocalDateTime.now(), Side.SELL, 12, 1001, 1, shareholder.getShareholderId(), 0, 0));
        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getOrderId()).isEqualTo(1);
        assertThat(outputEvent.getErrors()).containsOnly(
                Message.QUANTITY_NOT_MULTIPLE_OF_LOT_SIZE,
                Message.PRICE_NOT_MULTIPLE_OF_TICK_SIZE
        );
    }

    @Test
    void update_order_causing_no_trades() {
        Order queuedOrder = new Order(200, security, Side.SELL, 500, 15450, broker1, shareholder, 0);
        security.getOrderBook().enqueue(queuedOrder);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 1000, 15450, 1, shareholder.getShareholderId(), 0, 0));
        verify(eventPublisher).publish(new OrderUpdatedEvent(1, 200));
    }

    @Test
    void handle_valid_update_with_trades() {
        Order matchingOrder = new Order(1, security, Side.BUY, 500, 15450, broker1, shareholder, 0);
        Order beforeUpdate = new Order(200, security, Side.SELL, 1000, 15455, broker2, shareholder, 0);
        Order afterUpdate = new Order(200, security, Side.SELL, 500, 15450, broker2, shareholder, 0);
        security.getOrderBook().enqueue(matchingOrder);
        security.getOrderBook().enqueue(beforeUpdate);

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 1000, 15450, broker2.getBrokerId(), shareholder.getShareholderId(), 0, 0));

        Trade trade = new Trade(security, 15450, 500, matchingOrder, afterUpdate);
        verify(eventPublisher).publish(new OrderUpdatedEvent(1, 200));
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 200, List.of(new TradeDTO(trade))));
    }

    @Test
    void invalid_update_with_order_id_not_found() {
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 1000, 15450, 1, shareholder.getShareholderId(), 0, 0));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 200, any()));
    }

    @Test
    void invalid_update_with_multiple_errors() {
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "XXX", -1, LocalDateTime.now(), Side.SELL, 0, 0, -1, shareholder.getShareholderId(), 0, 0));
        ArgumentCaptor<OrderRejectedEvent> orderRejectedCaptor = ArgumentCaptor.forClass(OrderRejectedEvent.class);
        verify(eventPublisher).publish(orderRejectedCaptor.capture());
        OrderRejectedEvent outputEvent = orderRejectedCaptor.getValue();
        assertThat(outputEvent.getOrderId()).isEqualTo(-1);
        assertThat(outputEvent.getErrors()).containsOnly(
                Message.UNKNOWN_SECURITY_ISIN,
                Message.UNKNOWN_BROKER_ID,
                Message.INVALID_ORDER_ID,
                Message.ORDER_PRICE_NOT_POSITIVE,
                Message.ORDER_QUANTITY_NOT_POSITIVE,
                Message.INVALID_PEAK_SIZE
        );
    }

    @Test
    void delete_buy_order_deletes_successfully_and_increases_credit() {
        Broker buyBroker = Broker.builder().credit(1_000_000).build();
        brokerRepository.addBroker(buyBroker);
        Order someOrder = new Order(100, security, Side.BUY, 300, 15500, buyBroker, shareholder, 0);
        Order queuedOrder = new Order(200, security, Side.BUY, 1000, 15500, buyBroker, shareholder, 0);
        security.getOrderBook().enqueue(someOrder);
        security.getOrderBook().enqueue(queuedOrder);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, security.getIsin(), Side.BUY, 200));
        verify(eventPublisher).publish(new OrderDeletedEvent(1, 200));
        assertThat(buyBroker.getCredit()).isEqualTo(1_000_000 + 1000 * 15500);
    }

    @Test
    void delete_sell_order_deletes_successfully_and_does_not_change_credit() {
        Broker sellBroker = Broker.builder().credit(1_000_000).build();
        brokerRepository.addBroker(sellBroker);
        Order someOrder = new Order(100, security, Side.SELL, 300, 15500, sellBroker, shareholder, 0);
        Order queuedOrder = new Order(200, security, Side.SELL, 1000, 15500, sellBroker, shareholder, 0);
        security.getOrderBook().enqueue(someOrder);
        security.getOrderBook().enqueue(queuedOrder);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, security.getIsin(), Side.SELL, 200));
        verify(eventPublisher).publish(new OrderDeletedEvent(1, 200));
        assertThat(sellBroker.getCredit()).isEqualTo(1_000_000);
    }


    @Test
    void invalid_delete_with_order_id_not_found() {
        Broker buyBroker = Broker.builder().credit(1_000_000).build();
        brokerRepository.addBroker(buyBroker);
        Order queuedOrder = new Order(200, security, Side.BUY, 1000, 15500, buyBroker, shareholder, 0);
        security.getOrderBook().enqueue(queuedOrder);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, "ABC", Side.SELL, 100));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 100, List.of(Message.ORDER_ID_NOT_FOUND)));
        assertThat(buyBroker.getCredit()).isEqualTo(1_000_000);
    }

    @Test
    void invalid_delete_order_with_non_existing_security() {
        Order queuedOrder = new Order(200, security, Side.BUY, 1000, 15500, broker1, shareholder, 0);
        security.getOrderBook().enqueue(queuedOrder);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, "XXX", Side.SELL, 200));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 200, List.of(Message.UNKNOWN_SECURITY_ISIN)));
    }

    @Test
    void buyers_credit_decreases_on_new_order_without_trades() {
        Broker broker = Broker.builder().brokerId(10).credit(10_000).build();
        brokerRepository.addBroker(broker);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 30, 100, 10, shareholder.getShareholderId(), 0, 0));
        assertThat(broker.getCredit()).isEqualTo(10_000 - 30 * 100);
    }

    @Test
    void buyers_credit_decreases_on_new_iceberg_order_without_trades() {
        Broker broker = Broker.builder().brokerId(10).credit(10_000).build();
        brokerRepository.addBroker(broker);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 30, 100, 10, shareholder.getShareholderId(), 10, 0));
        assertThat(broker.getCredit()).isEqualTo(10_000 - 30 * 100);
    }

    @Test
    void credit_does_not_change_on_invalid_new_order() {
        Broker broker = Broker.builder().brokerId(10).credit(10_000).build();
        brokerRepository.addBroker(broker);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", -1, LocalDateTime.now(), Side.BUY, 30, 100, broker.getBrokerId(), shareholder.getShareholderId(), 0, 0));
        assertThat(broker.getCredit()).isEqualTo(10_000);
    }

    @Test
    void credit_updated_on_new_order_matched_partially_with_two_orders() {
        Broker broker1 = Broker.builder().brokerId(10).credit(100_000).build();
        Broker broker2 = Broker.builder().brokerId(20).credit(100_000).build();
        Broker broker3 = Broker.builder().brokerId(30).credit(100_000).build();
        List.of(broker1, broker2, broker3).forEach(b -> brokerRepository.addBroker(b));

        Order matchingSellOrder1 = new Order(100, security, Side.SELL, 30, 500, broker1, shareholder, 0);
        Order matchingSellOrder2 = new Order(110, security, Side.SELL, 20, 500, broker2, shareholder, 0);
        security.getOrderBook().enqueue(matchingSellOrder1);
        security.getOrderBook().enqueue(matchingSellOrder2);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 100, 550, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 0));

        assertThat(broker1.getCredit()).isEqualTo(100_000 + 30 * 500);
        assertThat(broker2.getCredit()).isEqualTo(100_000 + 20 * 500);
        assertThat(broker3.getCredit()).isEqualTo(100_000 - 50 * 500 - 50 * 550);
    }

    @Test
    void new_order_from_buyer_with_not_enough_credit_no_trades() {
        Broker broker = Broker.builder().brokerId(10).credit(1000).build();
        brokerRepository.addBroker(broker);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 30, 100, 10, shareholder.getShareholderId(), 0, 0));
        assertThat(broker.getCredit()).isEqualTo(1000);
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 200, List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
    }

    @Test
    void new_order_from_buyer_with_enough_credit_based_on_trades() {
        Broker broker1 = Broker.builder().brokerId(10).credit(100_000).build();
        Broker broker2 = Broker.builder().brokerId(20).credit(100_000).build();
        Broker broker3 = Broker.builder().brokerId(30).credit(52_500).build();
        List.of(broker1, broker2, broker3).forEach(b -> brokerRepository.addBroker(b));
        Order matchingSellOrder1 = new Order(100, security, Side.SELL, 30, 500, broker1, shareholder, 0);
        Order matchingSellOrder2 = new Order(110, security, Side.SELL, 20, 500, broker2, shareholder, 0);
        Order incomingBuyOrder = new Order(200, security, Side.BUY, 100, 550, broker3, shareholder, 0);
        security.getOrderBook().enqueue(matchingSellOrder1);
        security.getOrderBook().enqueue(matchingSellOrder2);
        Trade trade1 = new Trade(security, matchingSellOrder1.getPrice(), matchingSellOrder1.getQuantity(),
                incomingBuyOrder, matchingSellOrder1);
        Trade trade2 = new Trade(security, matchingSellOrder2.getPrice(), matchingSellOrder2.getQuantity(),
                incomingBuyOrder.snapshotWithQuantity(700), matchingSellOrder2);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 100, 550, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 0));

        assertThat(broker1.getCredit()).isEqualTo(100_000 + 30 * 500);
        assertThat(broker2.getCredit()).isEqualTo(100_000 + 20 * 500);
        assertThat(broker3.getCredit()).isEqualTo(0);

        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 200));
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 200, List.of(new TradeDTO(trade1), new TradeDTO(trade2))));
    }

    @Test
    void new_order_from_buyer_with_not_enough_credit_based_on_trades() {
        Broker broker1 = Broker.builder().brokerId(1).credit(100_000).build();
        Broker broker2 = Broker.builder().brokerId(2).credit(100_000).build();
        Broker broker3 = Broker.builder().brokerId(3).credit(50_000).build();
        List.of(broker1, broker2, broker3).forEach(b -> brokerRepository.addBroker(b));
        Order matchingSellOrder1 = new Order(100, security, Side.SELL, 30, 500, broker1, shareholder, 0);
        Order matchingSellOrder2 = new Order(110, security, Side.SELL, 20, 500, broker2, shareholder, 0);
        security.getOrderBook().enqueue(matchingSellOrder1);
        security.getOrderBook().enqueue(matchingSellOrder2);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 100, 550, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 0));

        assertThat(broker1.getCredit()).isEqualTo(100_000);
        assertThat(broker2.getCredit()).isEqualTo(100_000);
        assertThat(broker3.getCredit()).isEqualTo(50_000);

        verify(eventPublisher).publish(new OrderRejectedEvent(1, 200, List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
    }

    @Test
    void update_buy_order_changing_price_with_no_trades_changes_buyers_credit() {
        Broker broker1 = Broker.builder().brokerId(1).credit(100_000).build();
        brokerRepository.addBroker(broker1);
        Order order = new Order(100, security, Side.BUY, 30, 500, broker1, shareholder, 0);
        security.getOrderBook().enqueue(order);

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.BUY, 30, 550, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0));

        assertThat(broker1.getCredit()).isEqualTo(100_000 - 1_500);
    }

    @Test
    void update_sell_order_changing_price_with_no_trades_does_not_changes_sellers_credit() {
        Broker broker1 = Broker.builder().brokerId(1).credit(100_000).build();
        brokerRepository.addBroker(broker1);
        Order order = new Order(100, security, Side.SELL, 30, 500, broker1, shareholder, 0);
        security.getOrderBook().enqueue(order);

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.SELL, 30, 550, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0));

        assertThat(broker1.getCredit()).isEqualTo(100_000);
    }

    @Test
    void update_order_changing_price_with_trades_changes_buyers_and_sellers_credit() {
        Broker broker1 = Broker.builder().brokerId(10).credit(100_000).build();
        Broker broker2 = Broker.builder().brokerId(20).credit(100_000).build();
        Broker broker3 = Broker.builder().brokerId(30).credit(100_000).build();
        List.of(broker1, broker2, broker3).forEach(b -> brokerRepository.addBroker(b));
        List<Order> orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 570, broker3, shareholder, 0),
                new Order(2, security, Side.BUY, 430, 550, broker3, shareholder, 0),
                new Order(3, security, Side.BUY, 445, 545, broker3, shareholder, 0),
                new Order(6, security, Side.SELL, 350, 580, broker1, shareholder, 0),
                new Order(7, security, Side.SELL, 100, 581, broker2, shareholder, 0)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 2, LocalDateTime.now(), Side.BUY, 500, 590, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 0));

        assertThat(broker1.getCredit()).isEqualTo(100_000 + 350 * 580);
        assertThat(broker2.getCredit()).isEqualTo(100_000 + 100 * 581);
        assertThat(broker3.getCredit()).isEqualTo(100_000 + 430 * 550 - 350 * 580 - 100 * 581 - 50 * 590);
    }

    @Test
    void update_order_changing_price_with_trades_for_buyer_with_insufficient_quantity_rolls_back() {
        Broker broker1 = Broker.builder().brokerId(10).credit(100_000).build();
        Broker broker2 = Broker.builder().brokerId(20).credit(100_000).build();
        Broker broker3 = Broker.builder().brokerId(30).credit(54_000).build();
        List.of(broker1, broker2, broker3).forEach(b -> brokerRepository.addBroker(b));
        List<Order> orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 570, broker3, shareholder, 0),
                new Order(2, security, Side.BUY, 430, 550, broker3, shareholder, 0),
                new Order(3, security, Side.BUY, 445, 545, broker3, shareholder, 0),
                new Order(6, security, Side.SELL, 350, 580, broker1, shareholder, 0),
                new Order(7, security, Side.SELL, 100, 581, broker2, shareholder, 0)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        Order originalOrder = orders.get(1).snapshot();
        originalOrder.queue();

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 2, LocalDateTime.now(), Side.BUY, 500, 590, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 0));

        assertThat(broker1.getCredit()).isEqualTo(100_000);
        assertThat(broker2.getCredit()).isEqualTo(100_000);
        assertThat(broker3.getCredit()).isEqualTo(54_000);
        assertThat(originalOrder).isEqualTo(security.getOrderBook().findByOrderId(Side.BUY, 2));
    }

    @Test
    void update_order_without_trade_decreasing_quantity_changes_buyers_credit() {
        Broker broker1 = Broker.builder().brokerId(10).credit(100_000).build();
        Broker broker2 = Broker.builder().brokerId(20).credit(100_000).build();
        Broker broker3 = Broker.builder().brokerId(30).credit(100_000).build();
        List.of(broker1, broker2, broker3).forEach(b -> brokerRepository.addBroker(b));
        List<Order> orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 570, broker3, shareholder, 0),
                new Order(2, security, Side.BUY, 430, 550, broker3, shareholder, 0),
                new Order(3, security, Side.BUY, 445, 545, broker3, shareholder, 0),
                new Order(6, security, Side.SELL, 350, 580, broker1, shareholder, 0),
                new Order(7, security, Side.SELL, 100, 581, broker2, shareholder, 0)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 2, LocalDateTime.now(), Side.BUY, 400, 550, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 0));

        assertThat(broker1.getCredit()).isEqualTo(100_000);
        assertThat(broker2.getCredit()).isEqualTo(100_000);
        assertThat(broker3.getCredit()).isEqualTo(100_000 + 30 * 550);
    }

    @Test
    void new_sell_order_without_enough_positions_is_rejected() {
        List<Order> orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 570, broker3, shareholder, 0),
                new Order(2, security, Side.BUY, 430, 550, broker3, shareholder, 0),
                new Order(3, security, Side.BUY, 445, 545, broker3, shareholder, 0),
                new Order(6, security, Side.SELL, 350, 580, broker1, shareholder, 0),
                new Order(7, security, Side.SELL, 100, 581, broker2, shareholder, 0)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        shareholder.decPosition(security, 99_500);
        broker3.increaseCreditBy(100_000_000);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 400, 590, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0));

        verify(eventPublisher).publish(new OrderRejectedEvent(1, 200, List.of(Message.SELLER_HAS_NOT_ENOUGH_POSITIONS)));
    }

    @Test
    void update_sell_order_without_enough_positions_is_rejected() {
        List<Order> orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 570, broker3, shareholder, 0),
                new Order(2, security, Side.BUY, 430, 550, broker3, shareholder, 0),
                new Order(3, security, Side.BUY, 445, 545, broker3, shareholder, 0),
                new Order(6, security, Side.SELL, 350, 580, broker1, shareholder, 0),
                new Order(7, security, Side.SELL, 100, 581, broker2, shareholder, 0)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        shareholder.decPosition(security, 99_500);
        broker3.increaseCreditBy(100_000_000);

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 6, LocalDateTime.now(), Side.SELL, 450, 580, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0));

        verify(eventPublisher).publish(new OrderRejectedEvent(1, 6, List.of(Message.SELLER_HAS_NOT_ENOUGH_POSITIONS)));
    }

    @Test
    void update_sell_order_with_enough_positions_is_executed() {
        Shareholder shareholder1 = Shareholder.builder().build();
        shareholder1.incPosition(security, 100_000);
        shareholderRepository.addShareholder(shareholder1);
        List<Order> orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 570, broker3, shareholder1, 0),
                new Order(2, security, Side.BUY, 430, 550, broker3, shareholder1, 0),
                new Order(3, security, Side.BUY, 445, 545, broker3, shareholder1, 0),
                new Order(6, security, Side.SELL, 350, 580, broker1, shareholder, 0),
                new Order(7, security, Side.SELL, 100, 581, broker2, shareholder, 0)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        shareholder.decPosition(security, 99_500);
        broker3.increaseCreditBy(100_000_000);

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 6, LocalDateTime.now(), Side.SELL, 250, 570, broker1.getBrokerId(), shareholder.getShareholderId(), 0, 0));

        verify(eventPublisher).publish(any(OrderExecutedEvent.class));
        assertThat(shareholder1.hasEnoughPositionsOn(security, 100_000 + 250)).isTrue();
        assertThat(shareholder.hasEnoughPositionsOn(security, 99_500 - 251)).isFalse();
    }

    @Test
    void new_buy_order_does_not_check_for_position() {
        Shareholder shareholder1 = Shareholder.builder().build();
        shareholder1.incPosition(security, 100_000);
        shareholderRepository.addShareholder(shareholder1);
        List<Order> orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 570, broker3, shareholder1, 0),
                new Order(2, security, Side.BUY, 430, 550, broker3, shareholder1, 0),
                new Order(3, security, Side.BUY, 445, 545, broker3, shareholder1, 0),
                new Order(6, security, Side.SELL, 350, 580, broker1, shareholder, 0),
                new Order(7, security, Side.SELL, 100, 581, broker2, shareholder, 0)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        shareholder.decPosition(security, 99_500);
        broker3.increaseCreditBy(100_000_000);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.BUY, 500, 570, broker3.getBrokerId(), shareholder.getShareholderId(), 0, 0));

        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
        assertThat(shareholder1.hasEnoughPositionsOn(security, 100_000)).isTrue();
        assertThat(shareholder.hasEnoughPositionsOn(security, 500)).isTrue();
    }

    @Test
    void update_buy_order_does_not_check_for_position() {
        Shareholder shareholder1 = Shareholder.builder().build();
        shareholder1.incPosition(security, 100_000);
        shareholderRepository.addShareholder(shareholder1);
        List<Order> orders = Arrays.asList(
                new Order(1, security, Side.BUY, 304, 570, broker3, shareholder1, 0),
                new Order(2, security, Side.BUY, 430, 550, broker3, shareholder1, 0),
                new Order(3, security, Side.BUY, 445, 545, broker3, shareholder1, 0),
                new Order(6, security, Side.SELL, 350, 580, broker1, shareholder, 0),
                new Order(7, security, Side.SELL, 100, 581, broker2, shareholder, 0)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        shareholder.decPosition(security, 99_500);
        broker3.increaseCreditBy(100_000_000);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 3, LocalDateTime.now(), Side.BUY, 500, 545, broker3.getBrokerId(), shareholder1.getShareholderId(), 0, 0));

        verify(eventPublisher).publish(any(OrderAcceptedEvent.class));
        assertThat(shareholder1.hasEnoughPositionsOn(security, 100_000)).isTrue();
        assertThat(shareholder.hasEnoughPositionsOn(security, 500)).isTrue();
    }

    @Test
    void new_order_does_not_satisfy_minimum_execution_quantity() {
        Order matchingBuyOrder = new Order(100, security, Side.BUY, 100, 15500, broker1, shareholder, 0);
        security.getOrderBook().enqueue(matchingBuyOrder);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 200, LocalDateTime.now(), Side.SELL, 300, 15450, 2, shareholder.getShareholderId(), 0, 200));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 200, List.of(Message.HAS_NOT_ENOUGH_EXECUTION_QUANTITY)));
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

    void initialize_orders_for_stop_limit_tests(){
        orders = Arrays.asList(
                new Order(1, security, Side.BUY, 200, 2000, broker1, shareholder, 0),
                new Order(2, security, Side.BUY, 500, 1500, broker2, shareholder,0),
                new Order(3, security, Side.SELL, 100, 3000, broker1, shareholder,0),
                new Order(4, security, Side.SELL, 50, 3500, broker2, shareholder,0)
        );
        broker1.increaseCreditBy(1000_000L);
        broker2.increaseCreditBy(1000_000L);
        broker3.increaseCreditBy(1000_000L);
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        brokerRepository.clear();
        brokerRepository.addBroker(broker1);
        brokerRepository.addBroker(broker2);
        brokerRepository.addBroker(broker3);
    }
    @Test
    void buy_stop_limit_order_activates(){
        initialize_orders_for_stop_limit_tests();
        security.setLastTradePrice(5);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 5, LocalDateTime.now(), Side.BUY, 100, 4000, 1, shareholder.getShareholderId(), 0, 0, 10));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(6, "ABC", 6, LocalDateTime.now(), Side.BUY, 100, 3000, 2, shareholder.getShareholderId(), 0, 0, 0));
        verify(eventPublisher).publish(new OrderActivatedEvent(6, 5));
    }

    @Test
    void sell_stop_limit_order_activates(){
        initialize_orders_for_stop_limit_tests();
        security.setLastTradePrice(5000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 5, LocalDateTime.now(), Side.SELL, 100, 4000, 1, shareholder.getShareholderId(), 0, 0, 4500));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(6, "ABC", 6, LocalDateTime.now(), Side.BUY, 100, 3000, 2, shareholder.getShareholderId(), 0, 0, 0));
        verify(eventPublisher).publish(new OrderActivatedEvent(6, 5));
    }

    @Test
    void activated_buy_order_executes(){
        initialize_orders_for_stop_limit_tests();
        security.setLastTradePrice(5);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 5, LocalDateTime.now(), Side.BUY, 100, 4000, 1, shareholder.getShareholderId(), 0, 0, 10));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(6, "ABC", 6, LocalDateTime.now(), Side.BUY, 100, 3000, 2, shareholder.getShareholderId(), 0, 0, 0));

        List<Trade> trades = List.of(
                new Trade(security, 3500, 50, orders.get(3), security.getOrderBook().findByOrderId(Side.BUY, 5))
        );
        verify(eventPublisher).publish(new OrderExecutedEvent(6, 5, List.of(new TradeDTO(trades.get(0)))));
    }

    @Test
    void activated_sell_order_executes(){
        initialize_orders_for_stop_limit_tests();
        security.setLastTradePrice(5000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 5, LocalDateTime.now(), Side.SELL, 300, 2000, 1, shareholder.getShareholderId(), 0, 0, 4000));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(6, "ABC", 6, LocalDateTime.now(), Side.BUY, 100, 4000, 2, shareholder.getShareholderId(), 0, 0, 0));

        List<Trade> trades = List.of(
                new Trade(security, 2000, 200, orders.get(0), security.getOrderBook().findByOrderId(Side.SELL, 5))
        );
        verify(eventPublisher).publish(new OrderExecutedEvent(6, 5, List.of(new TradeDTO(trades.get(0)))));
    }

    @Test
    void activated_order_activates_another_order(){
        initialize_orders_for_stop_limit_tests();
        security.setLastTradePrice(5000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 5, LocalDateTime.now(), Side.SELL, 300, 2000, 1, shareholder.getShareholderId(), 0, 0, 3100));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(6, "ABC", 6, LocalDateTime.now(), Side.SELL, 300, 2000, 1, shareholder.getShareholderId(), 0, 0, 2000));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(7, "ABC", 7, LocalDateTime.now(), Side.BUY, 100, 4000, 2, shareholder.getShareholderId(), 0, 0, 0));
        verify(eventPublisher).publish(new OrderActivatedEvent(7, 5));
        verify(eventPublisher).publish(new OrderActivatedEvent(7, 6));

    }

    @Test
    void inactive_stop_limit_order_updates_stop_price(){
        initialize_orders_for_stop_limit_tests();
        security.setLastTradePrice(5000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 5, LocalDateTime.now(), Side.SELL, 300, 2000, 1, shareholder.getShareholderId(), 0, 0, 3100));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(6, "ABC", 5, LocalDateTime.now(), Side.SELL, 300, 2000, 1, shareholder.getShareholderId(), 0, 0, 3200));
        verify(eventPublisher).publish(new OrderUpdatedEvent(6,5));
        assert(security.getInactiveOrderBook().findByOrderId(Side.SELL, 5).getStopPrice() == 3200);
    }

    @Test
    void active_stop_limit_order_can_not_update_stop_price(){
        initialize_orders_for_stop_limit_tests();
        security.setLastTradePrice(5000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 5, LocalDateTime.now(), Side.SELL, 300, 6000, 1, shareholder.getShareholderId(), 0, 0, 3100));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(6, "ABC", 6, LocalDateTime.now(), Side.BUY, 100, 4000, 2, shareholder.getShareholderId(), 0, 0, 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(7, "ABC", 5, LocalDateTime.now(), Side.SELL, 300, 2000, 1, shareholder.getShareholderId(), 0, 0, 3200));
        verify(eventPublisher).publish(new OrderRejectedEvent(7, 5, List.of(Message.CANNOT_SPECIFY_STOP_PRICE_FOR_ACTIVATED_ORDER)));

    }

    @Test
    void order_can_not_update_stop_price(){
        initialize_orders_for_stop_limit_tests();
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(5, "ABC", 1, LocalDateTime.now(), Side.BUY, 200, 2000, 1, shareholder.getShareholderId(), 0, 0, 3200));
        verify(eventPublisher).publish(new OrderRejectedEvent(5, 1, List.of(Message.CANNOT_SPECIFY_STOP_PRICE_FOR_A_NON_STOP_LIMIT_ORDER)));
    }

    @Test
    void remove_inactive_stop_order(){
        security.setLastTradePrice(5000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 5, LocalDateTime.now(), Side.SELL, 300, 6000, 1, shareholder.getShareholderId(), 0, 0, 3100));
        orderHandler.handleDeleteOrder(new DeleteOrderRq(5, "ABC", Side.SELL, 5));
    }

    @Test
    void inactive_buy_order_activated_and_restore_its_credit(){
        initialize_orders_for_stop_limit_tests();
        security.setLastTradePrice(5);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 5, LocalDateTime.now(), Side.BUY, 100, 4000, 3, shareholder.getShareholderId(), 0, 0, 10));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(6, "ABC", 6, LocalDateTime.now(), Side.BUY, 100, 3000, 2, shareholder.getShareholderId(), 0, 0, 0));

        assertThat(broker3.getCredit()).isEqualTo(1000_000 - (50*3500)-(50*4000));
    }

    @Test
    void inactive_sell_orders_activates_in_correct_order(){
        initialize_orders_for_stop_limit_tests();
        security.setLastTradePrice(5);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 5, LocalDateTime.now(), Side.BUY, 50, 4000, 1, shareholder.getShareholderId(), 0, 0, 15));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(6, "ABC", 6, LocalDateTime.now(), Side.BUY, 30, 4000, 1, shareholder.getShareholderId(), 0, 0, 10));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(7, "ABC", 7, LocalDateTime.now(), Side.BUY, 50, 4000, 1, shareholder.getShareholderId(), 0, 0, 10));
        StopLimitOrder firstStopLimitOrder = security.getInactiveOrderBook().findByOrderId(Side.BUY, 5);
        StopLimitOrder secondStopLimitOrder = security.getInactiveOrderBook().findByOrderId(Side.BUY, 6);
        StopLimitOrder thirdStopLimitOrder = security.getInactiveOrderBook().findByOrderId(Side.BUY, 7);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(8, "ABC", 8, LocalDateTime.now(), Side.BUY, 50, 4000, 1, shareholder.getShareholderId(), 0, 0, 0));

        List<Trade> firstActivatedOrderTrades = List.of(
                new Trade(security, 3000, 30, orders.get(2), secondStopLimitOrder)

        );
        List<Trade> secondActivatedOrderTrades = List.of(
                new Trade(security, 3000, 20, orders.get(2), thirdStopLimitOrder),
                new Trade(security, 3500, 30, orders.get(3), thirdStopLimitOrder)
        );
        List<Trade> thirdActiavtedOrderTrades = List.of(
                new Trade(security, 3500, 20, orders.get(3), firstStopLimitOrder)
        );
        verify(eventPublisher).publish(new OrderExecutedEvent(8, 5, List.of(new TradeDTO(thirdActiavtedOrderTrades.get(0)))));
        verify(eventPublisher).publish(new OrderExecutedEvent(8, 6, List.of(new TradeDTO(firstActivatedOrderTrades.get(0)))));
        verify(eventPublisher).publish(new OrderExecutedEvent(8, 7, List.of(new TradeDTO(secondActivatedOrderTrades.get(0)), new TradeDTO(secondActivatedOrderTrades.get(1)))));

    }

    @Test
    void inactive_buy_orders_activates_in_correct_order(){
        initialize_orders_for_stop_limit_tests();
        security.setLastTradePrice(5000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 5, LocalDateTime.now(), Side.SELL, 100, 1000, 1, shareholder.getShareholderId(), 0, 0, 3000));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(6, "ABC", 6, LocalDateTime.now(), Side.SELL, 80, 1200, 1, shareholder.getShareholderId(), 0, 0, 3200));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(7, "ABC", 7, LocalDateTime.now(), Side.SELL, 70, 1300, 1, shareholder.getShareholderId(), 0, 0, 3200));
        StopLimitOrder firstStopLimitOrder = security.getInactiveOrderBook().findByOrderId(Side.SELL, 5);
        StopLimitOrder secondStopLimitOrder = security.getInactiveOrderBook().findByOrderId(Side.SELL, 6);
        StopLimitOrder thirdStopLimitOrder = security.getInactiveOrderBook().findByOrderId(Side.SELL, 7);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(8, "ABC", 8, LocalDateTime.now(), Side.SELL, 100, 2000, 2, shareholder.getShareholderId(), 0, 0, 0));

        List<Trade> firstActivatedOrderTrades = List.of(
                new Trade(security, 2000, 80, orders.get(0), secondStopLimitOrder)

        );
        List<Trade> secondActivatedOrderTrades = List.of(
                new Trade(security, 2000, 20, orders.get(0), thirdStopLimitOrder),
                new Trade(security, 1500, 50, orders.get(1), thirdStopLimitOrder)
        );
        List<Trade> thirdActivatedOrderTrades = List.of(
                new Trade(security, 1500, 100, orders.get(1), firstStopLimitOrder)
        );
        verify(eventPublisher).publish(new OrderExecutedEvent(8, 5, List.of(new TradeDTO(thirdActivatedOrderTrades.get(0)))));
        verify(eventPublisher).publish(new OrderExecutedEvent(8, 6, List.of(new TradeDTO(firstActivatedOrderTrades.get(0)))));
        verify(eventPublisher).publish(new OrderExecutedEvent(8, 7, List.of(new TradeDTO(secondActivatedOrderTrades.get(0)), new TradeDTO(secondActivatedOrderTrades.get(1)))));
    }

    @Test
    void security_matcher_changes_state_from_continuous_to_auction(){
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq(1, "ABC", MatchingState.AUCTION));
        assertThat(security.getMatchingState()).isEqualTo(MatchingState.AUCTION);
        verify(eventPublisher).publish(any(SecurityStateChangedEvent.class));
        ArgumentCaptor<SecurityStateChangedEvent> argumentCaptor = ArgumentCaptor.forClass(SecurityStateChangedEvent.class);
        verify(eventPublisher).publish(argumentCaptor.capture());
        SecurityStateChangedEvent capturedEvent = argumentCaptor.getValue();
        assertThat(capturedEvent.getState()).isEqualTo(MatchingState.AUCTION);
    }

    @Test
    void security_matcher_changes_state_from_auction_to_continuous(){
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq(1, "ABC", MatchingState.CONTINUOUS));
        assertThat(security.getMatchingState()).isEqualTo(MatchingState.CONTINUOUS);
        verify(eventPublisher).publish(any(SecurityStateChangedEvent.class));
        ArgumentCaptor<SecurityStateChangedEvent> argumentCaptor = ArgumentCaptor.forClass(SecurityStateChangedEvent.class);
        verify(eventPublisher).publish(argumentCaptor.capture());
        SecurityStateChangedEvent capturedEvent = argumentCaptor.getValue();
        assertThat(capturedEvent.getState()).isEqualTo(MatchingState.CONTINUOUS);
    }

    @Test
    void security_matcher_changes_state_from_auction_to_auction(){
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq(1, "ABC", MatchingState.AUCTION));
        assertThat(security.getMatchingState()).isEqualTo(MatchingState.AUCTION);
        verify(eventPublisher).publish(any(SecurityStateChangedEvent.class));
        ArgumentCaptor<SecurityStateChangedEvent> argumentCaptor = ArgumentCaptor.forClass(SecurityStateChangedEvent.class);
        verify(eventPublisher).publish(argumentCaptor.capture());
        SecurityStateChangedEvent capturedEvent = argumentCaptor.getValue();
        assertThat(capturedEvent.getState()).isEqualTo(MatchingState.AUCTION);
    }

    @Test
    void security_matcher_changes_state_from_continuous_to_continuous(){
        security.setMatchingState(MatchingState.CONTINUOUS);
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq(1, "ABC", MatchingState.CONTINUOUS));
        assertThat(security.getMatchingState()).isEqualTo(MatchingState.CONTINUOUS);
        verify(eventPublisher).publish(any(SecurityStateChangedEvent.class));
        ArgumentCaptor<SecurityStateChangedEvent> argumentCaptor = ArgumentCaptor.forClass(SecurityStateChangedEvent.class);
        verify(eventPublisher).publish(argumentCaptor.capture());
        SecurityStateChangedEvent capturedEvent = argumentCaptor.getValue();
        assertThat(capturedEvent.getState()).isEqualTo(MatchingState.CONTINUOUS);
    }

    //TODO: reoopn change location, check opening price in change state

    void initialize_orders_for_auction_matcher(){
        orders = Arrays.asList(
                new Order(1, security, BUY, 5, 30, broker1, shareholder, 0),
                new Order(2, security, BUY, 5,20 , broker1, shareholder, 0),
                new Order(3, security, BUY, 5, 10, broker1, shareholder, 0),
                new Order(6, security, Side.SELL, 5, 5, broker2, shareholder, 0),
                new Order(7, security, Side.SELL, 5, 14, broker2, shareholder, 0),
                new Order(8, security, Side.SELL, 5, 25, broker2, shareholder, 0)
        );
        broker1.increaseCreditBy(1000_000L);
        broker2.increaseCreditBy(1000_000L);
        broker3.increaseCreditBy(1000_000L);
        orders.forEach(order -> security.getOrderBook().enqueue(order));
        brokerRepository.clear();
        brokerRepository.addBroker(broker1);
        brokerRepository.addBroker(broker2);
        brokerRepository.addBroker(broker3);
    }

    @Test
    void opening_price_event_publishes_when_new_order_is_entered(){
        initialize_orders_for_auction_matcher();
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 5, LocalDateTime.now(), Side.BUY, 5, 40, 1, shareholder.getShareholderId(), 0, 0));
        verify(eventPublisher).publish(new OpeningPriceEvent("ABC", 14, 10));
    }

    @Test
    void opening_price_event_publishes_when_order_is_updated(){
        initialize_orders_for_auction_matcher();
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 1, LocalDateTime.now(), Side.BUY, 5, 40, 1, shareholder.getShareholderId(), 0, 0));
        verify(eventPublisher).publish(new OpeningPriceEvent("ABC", 14, 10));
    }

    @Test
    void broker_does_not_have_enough_credit_after_order_update_request(){
        initialize_orders_for_auction_matcher();
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 1, LocalDateTime.now(), Side.BUY, 5000, 4000, 1, shareholder.getShareholderId(), 0, 0));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 1, List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
    }

    @Test
    void opening_price_event_publishes_when_order_is_deleted(){
        initialize_orders_for_auction_matcher();
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, "ABC", Side.BUY, 1));
        verify(eventPublisher).publish(new OpeningPriceEvent("ABC", 14, 10));
    }

    @Test
    void new_stop_limit_order_is_rejected_in_auction_state(){
        initialize_orders_for_auction_matcher();
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 5, LocalDateTime.now(), Side.BUY, 5, 40, 1, shareholder.getShareholderId(), 0, 0, 10));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 5, List.of(Message.STOP_LIMIT_ORDER_IS_NOT_ALLOWED_IN_AUCTION_STATE)));
    }

    @Test
    void inactive_stop_limit_order_can_not_get_updated_in_auction_state(){
        initialize_orders_for_auction_matcher();
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 5, LocalDateTime.now(), Side.BUY, 5, 40, 1, shareholder.getShareholderId(), 0, 0, 10));
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 5, LocalDateTime.now(), Side.BUY, 5, 40, 1, shareholder.getShareholderId(), 0, 0, 5));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 5, List.of(Message.STOP_LIMIT_ORDER_IS_NOT_ALLOWED_IN_AUCTION_STATE)));
    }

    @Test
    void active_stop_limit_order_can_get_updated_in_auction_state() {
        initialize_orders_for_auction_matcher();
        security.setLastTradePrice(20);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 5, LocalDateTime.now(), Side.BUY, 100, 40, 1, shareholder.getShareholderId(), 0, 0, 10));
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 5, LocalDateTime.now(), Side.BUY, 100, 30, 1, shareholder.getShareholderId(), 0, 0, 0));
        verify(eventPublisher).publish(new OrderUpdatedEvent(1, 5));
        verify(eventPublisher).publish(any(OpeningPriceEvent.class));
    }

    @Test
    void inactive_stop_limit_order_can_not_get_deleted_in_auction_state(){
        initialize_orders_for_auction_matcher();
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 5, LocalDateTime.now(), Side.BUY, 5, 40, 1, shareholder.getShareholderId(), 0, 0, 10));
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleDeleteOrder(new DeleteOrderRq(1, "ABC", Side.BUY, 5));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 5, List.of(Message.CANNOT_DELETE_INACTIVE_ORDER_IN_AUCTION)));
    }

    @Test
    void active_stop_limit_order_can_get_deleted_in_auction_state(){
        initialize_orders_for_auction_matcher();
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
        initialize_orders_for_auction_matcher();
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq(1, "ABC", MatchingState.CONTINUOUS));
        verify(eventPublisher, times(2)).publish(any(TradeEvent.class));
    }

    @Test
    void trade_events_publish_after_auction_to_auction_reopening(){
        initialize_orders_for_auction_matcher();
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq(1, "ABC", MatchingState.AUCTION));
        verify(eventPublisher, times(2)).publish(any(TradeEvent.class));
    }

    @Test
    void stop_limit_order_activates_after_reopening_from_auction_to_auction(){
        initialize_orders_for_auction_matcher();
        security.setLastTradePrice(5);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 5, LocalDateTime.now(), Side.BUY, 100, 40, 1, shareholder.getShareholderId(), 0, 0, 10));
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq(1, "ABC", MatchingState.AUCTION));
        verify(eventPublisher).publish(new OrderActivatedEvent(1, 5));
        verify(eventPublisher).publish(new SecurityStateChangedEvent("ABC", MatchingState.AUCTION));
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 5)).isNotNull();
    }

    @Test
    void stop_limit_order_activates_after_reopening_from_auction_to_continuous(){
        initialize_orders_for_auction_matcher();
        security.setLastTradePrice(5);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 5, LocalDateTime.now(), Side.BUY, 100, 30, 1, shareholder.getShareholderId(), 0, 0, 10));
        security.setMatchingState(MatchingState.AUCTION);
        orderHandler.handleChangeMatchingState(new ChangeMatchingStateRq(1, "ABC", MatchingState.CONTINUOUS));
        verify(eventPublisher).publish(new OrderActivatedEvent(1, 5));
        verify(eventPublisher).publish(new SecurityStateChangedEvent("ABC", MatchingState.CONTINUOUS));
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 5, List.of(new TradeDTO(new Trade(security, 25, 5, security.getOrderBook().findByOrderId(Side.BUY, 5), orders.get(5))))));
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 5)).isNotNull();
    }

}
