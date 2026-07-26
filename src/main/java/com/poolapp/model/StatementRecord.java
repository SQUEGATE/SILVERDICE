package com.poolapp.model;

import java.math.BigDecimal;

public class StatementRecord {
    private final long id;
    private final String date;
    private final String type;
    private final BigDecimal amount;

    public StatementRecord(long id, String date, String type, BigDecimal amount) {
        this.id = id;
        this.date = date;
        this.type = type;
        this.amount = amount;
    }

    public long getId() {
        return id;
    }

    public String getDate() {
        return date;
    }

    public String getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}
