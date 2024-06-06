package ir.ramtung.tinyme.domain.entity;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Builder
public class Broker {
    @EqualsAndHashCode.Include
    private long brokerId;
    private String name;
    private long credit;

    public void increaseCreditBy(long amount) {
        assert amount >= 0;
        credit += amount;
    }

    public void decreaseCreditBy(long amount) {
        assert amount >= 0;
        credit -= amount;
    }

    public boolean hasEnoughCredit(long amount) {
        return credit >= amount;
    }
}
