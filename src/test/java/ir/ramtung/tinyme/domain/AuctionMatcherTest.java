package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.AuctionMatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.util.Arrays;
import java.util.List;

import static ir.ramtung.tinyme.domain.entity.Side.BUY;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class AuctionMatcherTest {
    private Security security;
    private Broker broker1;
    private Broker broker2;
    private Shareholder shareholder;
    private OrderBook orderBook;
    private List<Order> orders;
    @Autowired
    private AuctionMatcher matcher;

    @BeforeEach
    void setupOrderBook() {
        security = Security.builder().build();
        broker1 = Broker.builder().credit(100_000_000L).build();
        broker2 = Broker.builder().credit(100_000_000L).build();
        shareholder = Shareholder.builder().build();
        shareholder.incPosition(security, 100_000);
        orderBook = security.getOrderBook();
        orders = Arrays.asList(
                new Order(1, security, BUY, 5, 30, broker1, shareholder, 0),
                new Order(2, security, BUY, 5,20 , broker1, shareholder, 0),
                new Order(3, security, BUY, 5, 10, broker1, shareholder, 0),
                new Order(6, security, Side.SELL, 5, 5, broker2, shareholder, 0),
                new Order(7, security, Side.SELL, 5, 14, broker2, shareholder, 0),
                new Order(8, security, Side.SELL, 5, 25, broker2, shareholder, 0)
        );
        orders.forEach(order -> orderBook.enqueue(order));
    }

    @Test
    void calculate_tradable_quantity_works_correctly() {
        int tradableQuantity = matcher.calculateTradableQuantity(5, orderBook);
        assertThat(tradableQuantity).isEqualTo(5);
    }

    @Test
    void calculate_tradable_quantity_works_correctly_with_icebreg_order(){
        Order icebergOrder = new IcebergOrder(10, security, BUY, 10, 30, broker1, shareholder, 2, 0);
        orderBook.enqueue(icebergOrder);
        int tradableQuantity = matcher.calculateTradableQuantity(25, orderBook);
        assertThat(tradableQuantity).isEqualTo(15);
    }

    @Test
    void calculate_opening_price_with_multiple_max_tradable_quantity_works_correctly(){
        int openingPrice = matcher.calculateOpeningPrice(orderBook, 40);
        assertThat(openingPrice).isEqualTo(20);
    }

    @Test
    void calculate_opening_price_when_last_trade_price_is_opening_price(){
        int openingPrice = matcher.calculateOpeningPrice(orderBook, 17);
        assertThat(openingPrice).isEqualTo(17);
    }

    @Test
    void calculate_opening_price_when_order_book_is_empty(){
        orderBook.getBuyQueue().clear();
        orderBook.getSellQueue().clear();
        int openingPrice = matcher.calculateOpeningPrice(orderBook, 40);
        assertThat(openingPrice).isEqualTo(AuctionMatcher.INVALID_OPENING_PRICE);
    }

    @Test
    void calculate_opening_price_when_there_is_no_match(){
        orderBook.getBuyQueue().clear();
        orderBook.getSellQueue().clear();
        orders = Arrays.asList(
                new Order(1, security, BUY, 5, 30, broker1, shareholder, 0),
                new Order(2, security, BUY, 5, 20, broker1, shareholder, 0),
                new Order(3, security, BUY, 5, 10, broker1, shareholder, 0),
                new Order(6, security, Side.SELL, 5, 35, broker2, shareholder, 0),
                new Order(7, security, Side.SELL, 5, 45, broker2, shareholder, 0),
                new Order(8, security, Side.SELL, 5, 55, broker2, shareholder, 0)
        );
        orders.forEach(order -> orderBook.enqueue(order));
        int openingPrice = matcher.calculateOpeningPrice(orderBook, 40);
        assertThat(openingPrice).isEqualTo(AuctionMatcher.INVALID_OPENING_PRICE);
    }

    @Test
    void executing_new_stop_limit_order_is_rejected(){
        Order order = new StopLimitOrder(10, security, BUY, 5, 30, broker1, shareholder, 25);
        MatchResult result = matcher.execute(order);
        assertThat(result.outcome()).isEqualTo(MatchingOutcome.STOP_LIMIT_ORDER_IS_NOT_ALLOWED_IN_AUCTION_STATE);
    }

    @Test
    void executing_updated_inactive_stop_limit_order_is_rejected(){
        StopLimitOrder stopLimitOrder = new StopLimitOrder(10, security, BUY, 5, 30, broker1, shareholder, 25);
        stopLimitOrder.markAsInactive();
        MatchResult result = matcher.execute(stopLimitOrder);
        assertThat(result.outcome()).isEqualTo(MatchingOutcome.STOP_LIMIT_ORDER_IS_NOT_ALLOWED_IN_AUCTION_STATE);
    }

    @Test
    void order_with_minimum_execution_quantity_is_rejected(){
        Order order = new Order(10, security, BUY, 5, 30, broker1, shareholder, 10);
        MatchResult result = matcher.execute(order);
        assertThat(result.outcome()).isEqualTo(MatchingOutcome.MINIMUM_EXECUTION_QUANTITY_IS_NOT_ALLOWED_IN_AUCTION_STATE);
    }

    @Test
    void buyer_with_not_enough_credit_is_rejected(){
        Order order = new Order(10, security, BUY, 5, 200_000_000, broker1, shareholder, 0);
        MatchResult result = matcher.execute(order);
        assertThat(result.outcome()).isEqualTo(MatchingOutcome.NOT_ENOUGH_CREDIT);
    }

    @Test
    void iceberg_order_is_accepted(){
        Order order = new IcebergOrder(10, security, BUY, 5, 30, broker1, shareholder, 5, 0);
        MatchResult result = matcher.execute(order);
        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
    }

    @Test
    void active_stop_limit_can_be_updated(){
        StopLimitOrder stopLimitOrder = new StopLimitOrder(10, security, BUY, 5, 30, broker1, shareholder, 25);
        orderBook.enqueue(stopLimitOrder);
        MatchResult matchResult = matcher.execute(stopLimitOrder);
        assertThat(matchResult.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
    }

    @Test
    void accepted_new_buy_order_decreases_broker_credit() {
        Order order = new Order(10, security, BUY, 5, 30, broker1, shareholder, 0);
        MatchResult matchResult = matcher.execute(order);
        assertThat(broker1.getCredit()).isEqualTo(100_000_000L - 150);
        assertThat(matchResult.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
    }

    @Test
    void new_sell_order_is_accepted(){
        Order order = new Order(10, security, Side.SELL, 5, 30, broker1, shareholder, 0);
        MatchResult matchResult = matcher.execute(order);
        assertThat(broker2.getCredit()).isEqualTo(100_000_000L);
        assertThat(matchResult.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
    }

    @Test
    void orders_match_in_the_reopening(){
        MatchResult matchResult = matcher.reopen(orderBook, 30);
        List<Trade> trades = List.of(
                new Trade(security, 20, 5, orders.get(0).snapshotWithQuantity(5), orders.get(3).snapshotWithQuantity(5)),
                new Trade(security, 20, 5, orders.get(1).snapshotWithQuantity(5), orders.get(4).snapshotWithQuantity(5))
        );
        assertThat(matchResult.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
        assertThat(matchResult.trades()).isEqualTo(trades);
        assertThat(orderBook.getBuyQueue()).containsExactly(orders.get(2));
        assertThat(orderBook.getSellQueue()).containsExactly(orders.get(5));
        assertThat(security.getLastTradePrice()).isEqualTo(20);
    }

    @Test
    void no_trades_are_made_in_the_reopening() {
        orderBook.getBuyQueue().clear();
        orderBook.getSellQueue().clear();
        orders = Arrays.asList(
                new Order(1, security, BUY, 5, 30, broker1, shareholder, 0),
                new Order(2, security, BUY, 5, 20, broker1, shareholder, 0),
                new Order(3, security, BUY, 5, 10, broker1, shareholder, 0),
                new Order(6, security, Side.SELL, 5, 35, broker2, shareholder, 0),
                new Order(7, security, Side.SELL, 5, 45, broker2, shareholder, 0),
                new Order(8, security, Side.SELL, 5, 55, broker2, shareholder, 0)
        );
        orders.forEach(order -> orderBook.enqueue(order));
        MatchResult matchResult = matcher.reopen(orderBook, 30);
        assertThat(matchResult.trades()).isEmpty();
    }

    @Test
    void credits_are_updated_in_the_reopening(){
        matcher.reopen(orderBook, 30);
        assertThat(broker1.getCredit()).isEqualTo(100_000_000L + 50);
        assertThat(broker2.getCredit()).isEqualTo(100_000_000L + 200);
    }

    @Test
    void buy_iceberg_order_is_replenished_in_the_reopening() {
        orders = Arrays.asList(
                new Order(1, security, BUY, 5, 30, broker1, shareholder, 0),
                new IcebergOrder(5, security, BUY, 5, 20, broker1, shareholder, 2, 0),
                new Order(2, security, BUY, 5, 20, broker1, shareholder, 0),
                new Order(3, security, BUY, 5, 10, broker1, shareholder, 0),
                new Order(6, security, Side.SELL, 5, 5, broker2, shareholder, 0),
                new Order(7, security, Side.SELL, 5, 14, broker2, shareholder, 0),
                new Order(8, security, Side.SELL, 5, 25, broker2, shareholder, 0)
        );
        security.getOrderBook().getBuyQueue().clear();
        security.getOrderBook().getSellQueue().clear();
        orders.forEach(order -> orderBook.enqueue(order));
        MatchResult matchResult = matcher.reopen(orderBook, 30);
        List<Trade> trades = List.of(
                new Trade(security, 20, 5, orders.get(0).snapshotWithQuantity(5), orders.get(4).snapshotWithQuantity(5)),
                new Trade(security, 20, 2, orders.get(1).snapshotWithQuantity(5), orders.get(5).snapshotWithQuantity(5)),
                new Trade(security, 20, 3, orders.get(2).snapshotWithQuantity(5), orders.get(5).snapshotWithQuantity(3))
        );
        assertThat(matchResult.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
        assertThat(matchResult.trades()).isEqualTo(trades);
    }

    @Test
    void sell_iceberg_order_is_replenished_in_the_reopening() {
        orders = Arrays.asList(
                new Order(1, security, BUY, 5, 30, broker1, shareholder, 0),
                new Order(2, security, BUY, 5, 20, broker1, shareholder, 0),
                new Order(3, security, BUY, 5, 10, broker1, shareholder, 0),
                new Order(6, security, Side.SELL, 5, 5, broker2, shareholder, 0),
                new IcebergOrder(5, security, Side.SELL, 5, 14, broker2, shareholder, 2, 0),
                new Order(7, security, Side.SELL, 5, 14, broker2, shareholder, 0),
                new Order(8, security, Side.SELL, 5, 25, broker2, shareholder, 0)
        );
        security.getOrderBook().getBuyQueue().clear();
        security.getOrderBook().getSellQueue().clear();
        orders.forEach(order -> orderBook.enqueue(order));
        MatchResult matchResult = matcher.reopen(orderBook, 30);
        List<Trade> trades = List.of(
                new Trade(security, 20, 5, orders.get(0).snapshotWithQuantity(5), orders.get(3).snapshotWithQuantity(5)),
                new Trade(security, 20, 2, orders.get(1).snapshotWithQuantity(5), orders.get(4).snapshotWithQuantity(5)),
                new Trade(security, 20, 3, orders.get(1).snapshotWithQuantity(3), orders.get(5).snapshotWithQuantity(5))
        );
        assertThat(matchResult.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
        assertThat(matchResult.trades()).isEqualTo(trades);
    }

}