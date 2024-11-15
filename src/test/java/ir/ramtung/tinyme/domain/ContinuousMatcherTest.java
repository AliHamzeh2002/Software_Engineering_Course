package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.ContinuousMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class ContinuousMatcherTest {
    private Security security;
    private Broker broker;
    private Shareholder shareholder;
    private OrderBook orderBook;
    private List<Order> orders;
    @Autowired
    private ContinuousMatcher matcher;

    @BeforeEach
    void setupOrderBook() {
        security = Security.builder().build();
        broker = Broker.builder().credit(100_000_000L).build();
        shareholder = Shareholder.builder().build();
        shareholder.incPosition(security, 100_000);
        orderBook = security.getOrderBook();
        orders = Arrays.asList(
                new Order(1, security, BUY, 304, 15700, broker, shareholder, 10),
                new Order(2, security, BUY, 43, 15500, broker, shareholder, 0),
                new Order(3, security, BUY, 445, 15450, broker, shareholder, 0),
                new Order(4, security, BUY, 526, 15450, broker, shareholder, 0),
                new Order(5, security, BUY, 1000, 15400, broker, shareholder, 0),
                new Order(6, security, Side.SELL, 350, 15800, broker, shareholder, 0),
                new Order(7, security, Side.SELL, 285, 15810, broker, shareholder, 0),
                new Order(8, security, Side.SELL, 800, 15810, broker, shareholder, 0),
                new Order(9, security, Side.SELL, 340, 15820, broker, shareholder, 0),
                new Order(10, security, Side.SELL, 65, 15820, broker, shareholder, 0)
        );
        orders.forEach(order -> orderBook.enqueue(order));
    }

    @Test
    void new_sell_order_matches_completely_with_part_of_the_first_buy() {
        Order order = new Order(11, security, Side.SELL, 100, 15600, broker, shareholder, 0);
        Trade trade = new Trade(security, 15700, 100, orders.get(0), order);
        MatchResult result = matcher.match(order);
        assertThat(result.remainder().getQuantity()).isEqualTo(0);
        assertThat(result.trades()).containsExactly(trade);
        assertThat(security.getOrderBook().getBuyQueue().getFirst().getQuantity()).isEqualTo(204);
    }

    @Test
    void new_sell_order_matches_partially_with_the_first_buy() {
        Order order = new Order(11, security, Side.SELL, 500, 15600, broker, shareholder, 0);
        Trade trade = new Trade(security, 15700, 304, orders.get(0), order);
        MatchResult result = matcher.match(order);
        assertThat(result.remainder().getQuantity()).isEqualTo(196);
        assertThat(result.trades()).containsExactly(trade);
        assertThat(security.getOrderBook().getBuyQueue().getFirst().getOrderId()).isEqualTo(2);
    }

    @Test
    void new_sell_order_matches_partially_with_two_buys() {
        Order order = new Order(11, security, Side.SELL, 500, 15500, broker, shareholder, 0);
        Trade trade1 = new Trade(security, 15700, 304, orders.get(0), order);
        Trade trade2 = new Trade(security, 15500, 43, orders.get(1), order.snapshotWithQuantity(196));
        MatchResult result = matcher.match(order);
        assertThat(result.remainder().getQuantity()).isEqualTo(153);
        assertThat(result.trades()).containsExactly(trade1, trade2);
        assertThat(security.getOrderBook().getBuyQueue().getFirst().getOrderId()).isEqualTo(3);
    }

    @Test
    void new_buy_order_matches_partially_with_the_entire_sell_queue() {
        Order order = new Order(11, security, BUY, 2000, 15820, broker, shareholder, 0);
        List<Trade> trades = new ArrayList<>();
        int totalTraded = 0;
        for (Order o : orders.subList(5, 10)) {
            trades.add(new Trade(security, o.getPrice(), o.getQuantity(),
                    order.snapshotWithQuantity(order.getQuantity() - totalTraded), o));
            totalTraded += o.getQuantity();
        }

        MatchResult result = matcher.match(order);
        assertThat(result.remainder().getQuantity()).isEqualTo(160);
        assertThat(result.trades()).isEqualTo(trades);
        assertThat(security.getOrderBook().getSellQueue()).isEmpty();
    }

    @Test
    void new_buy_order_does_not_match() {
        Order order = new Order(11, security, BUY, 2000, 15500, broker, shareholder, 0);
        MatchResult result = matcher.match(order);
        assertThat(result.remainder()).isEqualTo(order);
        assertThat(result.trades()).isEmpty();
    }

    @Test
    void iceberg_order_in_queue_matched_completely_after_three_rounds() {
        security = Security.builder().build();
        broker = Broker.builder().build();
        orderBook = security.getOrderBook();
        orders = Arrays.asList(
                new IcebergOrder(1, security, BUY, 450, 15450, broker, shareholder, 200, 0),
                new Order(2, security, BUY, 70, 15450, broker, shareholder, 0),
                new Order(3, security, BUY, 1000, 15400, broker, shareholder, 0)
        );
        orders.forEach(order -> orderBook.enqueue(order));
        Order order = new Order(4, security, Side.SELL, 600, 15450, broker, shareholder, 0);
        List<Trade> trades = List.of(
                new Trade(security, 15450, 200, orders.get(0).snapshotWithQuantity(200), order.snapshotWithQuantity(600)),
                new Trade(security, 15450, 70, orders.get(1).snapshotWithQuantity(70), order.snapshotWithQuantity(400)),
                new Trade(security, 15450, 200, orders.get(0).snapshotWithQuantity(200), order.snapshotWithQuantity(330)),
                new Trade(security, 15450, 50, orders.get(0).snapshotWithQuantity(50), order.snapshotWithQuantity(130))
        );

        MatchResult result = matcher.match(order);

        assertThat(result.remainder().getQuantity()).isEqualTo(80);
        assertThat(result.trades()).isEqualTo(trades);
    }

    @Test
    void insert_iceberg_and_match_until_quantity_is_less_than_peak_size() {
        security = Security.builder().isin("TEST").build();
        shareholder.incPosition(security, 1_000);
        security.getOrderBook().enqueue(
                new Order(1, security, Side.SELL, 100, 10, broker, shareholder, 0)
        );

        Order order = new IcebergOrder(1, security, BUY, 120 , 10, broker, shareholder, 40, 0);
        MatchResult result = matcher.execute(order);

        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
        assertThat(result.trades()).hasSize(1);
        assertThat(security.getOrderBook().getSellQueue()).hasSize(0);
        assertThat(security.getOrderBook().getBuyQueue()).hasSize(1);
        assertThat(security.getOrderBook().getBuyQueue().get(0).getQuantity()).isEqualTo(20);

    }

    @Test
    void new_sell_order_satisfies_minimum_execution_quantity(){
        Order order = new Order(11, security, Side.SELL, 400, 15480, broker, shareholder, 200);
        MatchResult result = matcher.match(order);
        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
        assertThat(order.getExecutionQuantity()).isEqualTo(347);
    }

    @Test
    void new_sell_order_does_not_satisfy_minimum_execution_quantity(){
        Order order = new Order(11, security, Side.SELL, 600, 15480, broker, shareholder, 500);
        MatchResult result = matcher.execute(order);
        assertThat(result.outcome()).isEqualTo(MatchingOutcome.NOT_ENOUGH_EXECUTION_QUANTITY);
        assertThat(order.getExecutionQuantity()).isEqualTo(347);
    }

    @Test
    void new_buy_order_satisfies_minimum_execution_quantity(){
        Order order = new Order(11, security, Side.BUY, 400, 15805, broker, shareholder, 300);
        MatchResult result = matcher.match(order);
        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
        assertThat(order.getExecutionQuantity()).isEqualTo(350);
    }

    @Test
    void new_buy_order_does_not_satisfy_minimum_execution_quantity(){
        Order order = new Order(11, security, Side.BUY, 350 + 800 + 285 + 10, 15810, broker, shareholder, 8000);
        MatchResult result = matcher.execute(order);
        assertThat(result.outcome()).isEqualTo(MatchingOutcome.NOT_ENOUGH_EXECUTION_QUANTITY);
        assertThat(order.getExecutionQuantity()).isEqualTo(350 + 800 + 285);
    }

    @Test
    void new_iceberg_order_does_not_satisfy_minimum_execution_quantity(){
        IcebergOrder icebergOrder = new IcebergOrder(11, security, Side.BUY, 350 + 800 + 285 + 10, 15810, broker, shareholder, 200, 8000);
        MatchResult result = matcher.execute(icebergOrder);
        assertThat(result.outcome()).isEqualTo(MatchingOutcome.NOT_ENOUGH_EXECUTION_QUANTITY);
        assertThat(icebergOrder.getExecutionQuantity()).isEqualTo(350 + 800 + 285);
    }

    @Test
    void new_iceberg_order_satisfies_minimum_execution_quantity(){
        IcebergOrder icebergOrder = new IcebergOrder(11, security, Side.BUY, 400, 15805, broker, shareholder, 200, 300);
        MatchResult result = matcher.match(icebergOrder);
        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
        assertThat(icebergOrder.getExecutionQuantity()).isEqualTo(350);
    }

    @Test
    void sell_order_rollsback_after_minimum_execution_quantity_not_satisfied(){
        Order order = new Order(11, security, Side.SELL, 600, 15480, broker, shareholder, 500);
        MatchResult result = matcher.execute(order);
        assertThat(security.getOrderBook().getBuyQueue().getFirst().getOrderId()).isEqualTo(1);
        assertThat(security.getOrderBook().getBuyQueue().getFirst().getQuantity()).isEqualTo(304);
        assertThat(security.getOrderBook().getBuyQueue().stream().count()).isEqualTo(5);
        assertThat(security.getOrderBook().getBuyQueue().get(1).getOrderId()).isEqualTo(2);
        assertThat(security.getOrderBook().getBuyQueue().get(1).getQuantity()).isEqualTo(43);
    }

    @Test
    void buy_order_rollsback_after_minimum_execution_quantity_not_satisfied(){
        Order order = new Order(11, security, Side.BUY, 100000, 15810, broker, shareholder, 8000);
        LinkedList<Order> initialOrders = security.getOrderBook().getSellQueue();
        MatchResult result = matcher.execute(order);
        for (int i = 0; i < 3; i++) {
            assertThat(security.getOrderBook().getSellQueue().get(i).getOrderId()).isEqualTo(initialOrders.get(i).getOrderId());
            assertThat(security.getOrderBook().getSellQueue().get(i).getQuantity()).isEqualTo(initialOrders.get(i).getQuantity());
        }
    }

    @Test
    void last_trade_price_changes_after_match(){
        Order order = new Order(11, security, Side.SELL, 600, 15480, broker, shareholder, 0);
        MatchResult result = matcher.execute(order);
        assertThat(security.getLastTradePrice()).isEqualTo(15500);
    }

}
