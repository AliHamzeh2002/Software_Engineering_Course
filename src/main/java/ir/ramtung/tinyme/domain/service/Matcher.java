package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.MatchResult;
import ir.ramtung.tinyme.domain.entity.Order;

public interface Matcher {
    //public MatchResult match(Order newOrder);
    public MatchResult execute(Order order);
}

