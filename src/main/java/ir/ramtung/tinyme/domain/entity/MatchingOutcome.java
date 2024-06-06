package ir.ramtung.tinyme.domain.entity;

public enum MatchingOutcome {
    APPROVED,
    EXECUTED,
    NOT_ENOUGH_CREDIT,
    NOT_ENOUGH_POSITIONS,
    NOT_ENOUGH_EXECUTION_QUANTITY,
    IS_INACTIVE,
    STOP_LIMIT_ORDER_IS_NOT_ALLOWED_IN_AUCTION_STATE,
    MINIMUM_EXECUTION_QUANTITY_IS_NOT_ALLOWED_IN_AUCTION_STATE
}
