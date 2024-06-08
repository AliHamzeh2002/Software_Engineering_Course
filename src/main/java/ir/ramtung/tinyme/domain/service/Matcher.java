package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;

public interface Matcher {
    public MatchResult execute(Order order);
}

