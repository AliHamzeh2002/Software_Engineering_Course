package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
//@DirtiesContext
public class BrokerCreditTest {
    public static final long BROKER1_INIT_CREDIT = 1_000_000L;
    public static final long BROKER2_INIT_CREDIT = 2_000_000L;
    private Security security;
    private Broker broker1, broker2;
    private Shareholder shareholder;
    @Autowired
    Matcher matcher;

    @BeforeEach
    void setupOrderBook() {
        security = Security.builder().build();
        broker1 = Broker.builder().brokerId(1).credit(BROKER1_INIT_CREDIT).build();
        broker2 = Broker.builder().brokerId(2).credit(BROKER2_INIT_CREDIT).build();
        shareholder = Shareholder.builder().shareholderId(0).build();
        shareholder.incPosition(security, 100_000);
        List<Order> orders = Arrays.asList(
                new Order(1, security, Side.BUY, 200, 2000, broker1, shareholder, 0),
                new Order(2, security, Side.BUY, 500, 1500, broker1, shareholder,0),
                new Order(3, security, Side.SELL, 100, 3000, broker2, shareholder,0),
                new Order(4, security, Side.SELL, 50, 3500, broker2, shareholder,0),
                new Order(5, security, Side.SELL, 20, 4000, broker2, shareholder,0)
        );
        orders.forEach(order -> security.getOrderBook().enqueue(order));
    }

    @Test
    void new_sell_order_matches_no_orders() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 9, LocalDateTime.now(), Side.SELL, 1000, 2100, 2, 0, 0,0);
        security.newOrder(enterOrderRq, broker1, shareholder, matcher);
        assertThat(broker2.getCredit()).isEqualTo(BROKER2_INIT_CREDIT);
    }

    @Test
    void new_sell_order_matches_completely() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 9, LocalDateTime.now(), Side.SELL, 300, 1000, 2, 0, 0,0);
        security.newOrder(enterOrderRq, broker2, shareholder, matcher);
        assertThat(broker2.getCredit()).isEqualTo(BROKER2_INIT_CREDIT + 200 * 2000 + 100 * 1500);
    }

    @Test
    void new_sell_order_matches_partially() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 9, LocalDateTime.now(), Side.SELL, 300, 1900, 2, 0, 0,0);
        security.newOrder(enterOrderRq, broker2, shareholder, matcher);
        assertThat(broker2.getCredit()).isEqualTo(BROKER2_INIT_CREDIT + 200 * 2000);
    }

    @Test
    void new_buy_order_matches_no_orders() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 9, LocalDateTime.now(), Side.BUY, 300, 1000, 1, 0, 0,0);
        security.newOrder(enterOrderRq, broker1, shareholder, matcher);
        assertThat(broker1.getCredit()).isEqualTo(BROKER1_INIT_CREDIT - 300 * 1000);
    }


    @Test
    void new_buy_order_matches_completely() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 9, LocalDateTime.now(), Side.BUY, 120, 4000, 1, 0, 0,0);
        security.newOrder(enterOrderRq, broker1, shareholder, matcher);
        assertThat(broker1.getCredit()).isEqualTo(BROKER1_INIT_CREDIT - 100 * 3000 - 20 * 3500);
        assertThat(broker2.getCredit()).isEqualTo(BROKER2_INIT_CREDIT + 100 * 3000 + 20 * 3500);
    }

    @Test
    void new_buy_order_matches_partially() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 9, LocalDateTime.now(), Side.BUY, 120, 3000, 1, 0, 0,0);
        security.newOrder(enterOrderRq, broker1, shareholder, matcher);
        assertThat(broker1.getCredit()).isEqualTo(BROKER1_INIT_CREDIT - 120 * 3000);
        assertThat(broker2.getCredit()).isEqualTo(BROKER2_INIT_CREDIT + 100 * 3000);
    }

    @Test
    void new_buy_order_is_rolled_back_during_matching() {
        Broker broker3 = Broker.builder().brokerId(3).credit(100 * 3000 + 50 * 3500).build();
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 9, LocalDateTime.now(), Side.BUY, 170, 4000, 3, 0, 0,0);
        security.newOrder(enterOrderRq, broker3, shareholder, matcher);
        assertThat(broker3.getCredit()).isEqualTo(100 * 3000 + 50 * 3500);
        assertThat(broker2.getCredit()).isEqualTo(BROKER2_INIT_CREDIT);
        assertThat(broker1.getCredit()).isEqualTo(BROKER1_INIT_CREDIT);
    }

    @Test
    void new_sell_order_is_rolled_back_during_matching() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 9, LocalDateTime.now(), Side.SELL, 300, 1700, 2, 0, 0,250);
        security.newOrder(enterOrderRq, broker2, shareholder, matcher);
        assertThat(broker2.getCredit()).isEqualTo(BROKER2_INIT_CREDIT);
        assertThat(broker1.getCredit()).isEqualTo(BROKER1_INIT_CREDIT);
    }

    @Test
    void new_buy_order_is_rolled_back_after_matching() {
        Broker broker3 = Broker.builder().brokerId(3).credit(100 * 3000 + 50 * 3500 + 20 * 4000).build();
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 9, LocalDateTime.now(), Side.BUY, 180, 4000, 3, 0, 0,0);
        security.newOrder(enterOrderRq, broker3, shareholder, matcher);
        assertThat(broker3.getCredit()).isEqualTo(100 * 3000 + 50 * 3500 + 20 * 4000);
        assertThat(broker2.getCredit()).isEqualTo(BROKER2_INIT_CREDIT);
        assertThat(broker1.getCredit()).isEqualTo(BROKER1_INIT_CREDIT);
    }

    @Test
    void buy_order_is_deleted() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 9, LocalDateTime.now(), Side.BUY, 180, 3000, 1, 0, 0,0);
        security.newOrder(enterOrderRq, broker1, shareholder, matcher);
        DeleteOrderRq deleteOrderRq = new DeleteOrderRq(1, security.getIsin(), Side.BUY, 9);
        assertThatNoException().isThrownBy(() -> security.deleteOrder(deleteOrderRq));
        assertThat(broker1.getCredit()).isEqualTo(BROKER1_INIT_CREDIT - 100 * 3000);
    }

    @Test
    void sell_order_is_deleted(){
        DeleteOrderRq deleteOrderRq = new DeleteOrderRq(1, security.getIsin(), Side.SELL, 3);
        assertThatNoException().isThrownBy(() -> security.deleteOrder(deleteOrderRq));
        assertThat(broker2.getCredit()).isEqualTo(BROKER2_INIT_CREDIT);
    }

    @Test
    void updated_buy_order_matches_partially() {
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 9, LocalDateTime.now(), Side.BUY, 180, 3200, 1, 0, 0,0);
        security.newOrder(enterOrderRq, broker1, shareholder, matcher);
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 9, LocalDateTime.now(), Side.BUY, 100, 3600, 1, 0, 0,0);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        assertThat(broker1.getCredit()).isEqualTo(BROKER1_INIT_CREDIT - 100 * 3000 - 50 * 3500 - 50 * 3600);
        assertThat(broker2.getCredit()).isEqualTo(BROKER2_INIT_CREDIT + 100 * 3000 + 50 * 3500);
    }

    @Test
    void updated_sell_order_matches_completely() {
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 5, LocalDateTime.now(), Side.SELL, 100, 1000, 2, 0, 0,0);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        assertThat(broker2.getCredit()).isEqualTo(BROKER2_INIT_CREDIT + 100 * 2000);
    }

    @Test
    void updated_buy_order_does_not_lose_priority() {
        security.getOrderBook().enqueue(new Order(6, security, Side.SELL, 100, 1000, broker2, shareholder,0));
        EnterOrderRq updateOrderRq = EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 1, LocalDateTime.now(), Side.BUY, 10, 2000, 1, 0, 0,0);
        assertThatNoException().isThrownBy(() -> security.updateOrder(updateOrderRq, matcher));
        assertThat(broker1.getCredit()).isEqualTo(BROKER1_INIT_CREDIT + 190 * 2000);
    }

    @Test
    void sell_iceberg_order_in_queue_matches_partially() {
        Broker broker3 = Broker.builder().brokerId(3).credit(0).build();
        IcebergOrder icebergOrder = new IcebergOrder(6, security, Side.SELL, 1000, 3000, broker3, shareholder, 100,0);
        security.getOrderBook().enqueue(icebergOrder);
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 9, LocalDateTime.now(), Side.BUY, 300, 3000, 1, 0, 0,0);
        security.newOrder(enterOrderRq, broker1, shareholder, matcher);
        assertThat(broker1.getCredit()).isEqualTo(BROKER1_INIT_CREDIT - 300 * 3000);
        assertThat(broker2.getCredit()).isEqualTo(BROKER2_INIT_CREDIT + 100 * 3000);
        assertThat(broker3.getCredit()).isEqualTo(200 * 3000);
    }

    @Test
    void new_buy_iceberg_order_matches_completely(){
        security.getOrderBook().enqueue(new Order(6, security, Side.SELL, 100, 3000, broker1, shareholder,0));
        Broker broker3 = Broker.builder().brokerId(3).credit(1000_000L).build();
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 9, LocalDateTime.now(), Side.BUY, 80, 3000, 3, 0, 40,0);
        security.newOrder(enterOrderRq, broker3, shareholder, matcher);
        assertThat(broker1.getCredit()).isEqualTo(BROKER1_INIT_CREDIT);
        assertThat(broker2.getCredit()).isEqualTo(BROKER2_INIT_CREDIT + 80 * 3000);
        assertThat(broker3.getCredit()).isEqualTo(1000_000 - 80 * 3000);
    }

    @Test
    void new_inactive_buy_stop_limit_order_does_not_have_enough_credit(){
        security.setLastTradePrice(100);
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 9, LocalDateTime.now(), Side.BUY, 80000, 10000, 1, 0, 0,0, 200);
        MatchResult result = security.newOrder(enterOrderRq, broker1, shareholder, matcher);
        assertThat(result.outcome()).isEqualTo(MatchingOutcome.NOT_ENOUGH_CREDIT);
        assertThat(broker1.getCredit()).isEqualTo(BROKER1_INIT_CREDIT);
    }

    @Test
    void new_inactive_buy_stop_limit_order_changes_credit(){
        security.setLastTradePrice(100);
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 9, LocalDateTime.now(), Side.BUY, 800, 100, 1, 0, 0,0, 200);
        MatchResult result = security.newOrder(enterOrderRq, broker1, shareholder, matcher);
        assertThat(result.outcome()).isEqualTo(MatchingOutcome.IS_INACTIVE);
        assertThat(broker1.getCredit()).isEqualTo(BROKER1_INIT_CREDIT - 800 * 100);
    }

    @Test
    void new_inactive_sell_order_does_not_change_credit(){
        security.setLastTradePrice(300);
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 9, LocalDateTime.now(), Side.SELL, 80000, 10000, 1, 0, 0,0, 200);
        MatchResult result = security.newOrder(enterOrderRq, broker1, shareholder, matcher);
        assertThat(result.outcome()).isEqualTo(MatchingOutcome.IS_INACTIVE);
        assertThat(broker1.getCredit()).isEqualTo(BROKER1_INIT_CREDIT);
    }

    @Test
    void new_active_order_has_enough_credit(){
        security.setLastTradePrice(400);
        EnterOrderRq enterOrderRq = EnterOrderRq.createNewOrderRq(1, security.getIsin(), 9, LocalDateTime.now(), Side.BUY, 80, 2000, 1, 0, 0,0, 200);
        MatchResult result = security.newOrder(enterOrderRq, broker1, shareholder, matcher);
        assertThat(result.outcome()).isEqualTo(MatchingOutcome.EXECUTED);
        assertThat(broker1.getCredit()).isEqualTo(BROKER1_INIT_CREDIT - 2000 * 80);
    }



}
