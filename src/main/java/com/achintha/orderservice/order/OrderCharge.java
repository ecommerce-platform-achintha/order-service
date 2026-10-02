package com.achintha.orderservice.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A labelled extra charge of a quote (e.g. "Gift wrapping"). */
@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class OrderCharge {

    @Column(nullable = false, length = 80)
    private String label;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;
}
