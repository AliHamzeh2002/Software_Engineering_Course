package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
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
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class OrderHandlerStopLimitOrderTest {
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
        brokerRepository.addBroker(broker1);
        brokerRepository.addBroker(broker2);
        brokerRepository.addBroker(broker3);

        orders = Arrays.asList(
                new Order(1, security, Side.BUY, 200, 2000, broker1, shareholder, 0),
                new Order(2, security, Side.BUY, 500, 1500, broker2, shareholder,0),
                new Order(3, security, Side.SELL, 100, 3000, broker1, shareholder,0),
                new Order(4, security, Side.SELL, 50, 3500, broker2, shareholder,0)
        );

        orders.forEach(order -> security.getOrderBook().enqueue(order));

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
    void buy_stop_limit_order_activates(){
        security.setLastTradePrice(5);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 5, LocalDateTime.now(), Side.BUY, 100, 4000, 1, shareholder.getShareholderId(), 0, 0, 10));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(6, "ABC", 6, LocalDateTime.now(), Side.BUY, 100, 3000, 2, shareholder.getShareholderId(), 0, 0, 0));
        verify(eventPublisher).publish(new OrderActivatedEvent(6, 5));
    }

    @Test
    void sell_stop_limit_order_activates(){
        security.setLastTradePrice(5000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 5, LocalDateTime.now(), Side.SELL, 100, 4000, 1, shareholder.getShareholderId(), 0, 0, 4500));

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(6, "ABC", 6, LocalDateTime.now(), Side.BUY, 100, 3000, 2, shareholder.getShareholderId(), 0, 0, 0));
        verify(eventPublisher).publish(new OrderActivatedEvent(6, 5));
    }

    @Test
    void activated_buy_order_executes(){
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
        security.setLastTradePrice(5000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 5, LocalDateTime.now(), Side.SELL, 300, 2000, 1, shareholder.getShareholderId(), 0, 0, 3100));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(6, "ABC", 6, LocalDateTime.now(), Side.SELL, 300, 2000, 1, shareholder.getShareholderId(), 0, 0, 2000));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(7, "ABC", 7, LocalDateTime.now(), Side.BUY, 100, 4000, 2, shareholder.getShareholderId(), 0, 0, 0));
        verify(eventPublisher).publish(new OrderActivatedEvent(7, 5));
        verify(eventPublisher).publish(new OrderActivatedEvent(7, 6));

    }

    @Test
    void inactive_stop_limit_order_updates_stop_price(){
        security.setLastTradePrice(5000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 5, LocalDateTime.now(), Side.SELL, 300, 2000, 1, shareholder.getShareholderId(), 0, 0, 3100));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(6, "ABC", 5, LocalDateTime.now(), Side.SELL, 300, 2000, 1, shareholder.getShareholderId(), 0, 0, 3200));
        verify(eventPublisher).publish(new OrderUpdatedEvent(6,5));
        assert(security.getInactiveOrderBook().findByOrderId(Side.SELL, 5).getStopPrice() == 3200);
    }

    @Test
    void inactive_stop_limit_order_activates_after_updating(){
        security.setLastTradePrice(5000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 5, LocalDateTime.now(), Side.SELL, 300, 2000, 1, shareholder.getShareholderId(), 0, 0, 3100));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(6, "ABC", 5, LocalDateTime.now(), Side.SELL, 300, 2000, 1, shareholder.getShareholderId(), 0, 0, 6200));
        verify(eventPublisher).publish(new OrderUpdatedEvent(6,5));
        verify(eventPublisher).publish(new OrderActivatedEvent(6,5));
    }

    @Test
    void active_stop_limit_order_updates_successfully() {
        security.setLastTradePrice(2000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 5, LocalDateTime.now(), Side.SELL, 300, 3000, 1, shareholder.getShareholderId(), 0, 0, 3100));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(7, "ABC", 5, LocalDateTime.now(), Side.SELL, 400, 2000, 1, shareholder.getShareholderId(), 0, 0, 0));
        verify(eventPublisher).publish(new OrderUpdatedEvent(7, 5));
        verify(eventPublisher).publish(any(OrderExecutedEvent.class));
    }

    @Test
    void active_stop_limit_order_can_not_update_stop_price(){
        security.setLastTradePrice(5000);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 5, LocalDateTime.now(), Side.SELL, 300, 6000, 1, shareholder.getShareholderId(), 0, 0, 3100));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(6, "ABC", 6, LocalDateTime.now(), Side.BUY, 100, 4000, 2, shareholder.getShareholderId(), 0, 0, 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(7, "ABC", 5, LocalDateTime.now(), Side.SELL, 300, 2000, 1, shareholder.getShareholderId(), 0, 0, 3200));
        verify(eventPublisher).publish(new OrderRejectedEvent(7, 5, List.of(Message.CANNOT_SPECIFY_STOP_PRICE_FOR_ACTIVATED_ORDER)));

    }

    @Test
    void order_can_not_update_stop_price(){
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
        security.setLastTradePrice(5);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(5, "ABC", 5, LocalDateTime.now(), Side.BUY, 100, 4000, 3, shareholder.getShareholderId(), 0, 0, 10));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(6, "ABC", 6, LocalDateTime.now(), Side.BUY, 100, 3000, 2, shareholder.getShareholderId(), 0, 0, 0));

        assertThat(broker3.getCredit()).isEqualTo(1000_000 - (50*3500)-(50*4000));
    }

    @Test
    void inactive_sell_orders_activates_in_correct_order(){
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
}
