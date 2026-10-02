package com.achintha.orderservice.payment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReferenceNormalizerTest {

    @Test
    void trimsUppercasesAndRemovesSpaces() {
        assertThat(ReferenceNormalizer.normalize("  ab 12 cd\t34 ")).isEqualTo("AB12CD34");
        assertThat(ReferenceNormalizer.normalize("AB12CD34")).isEqualTo("AB12CD34");
    }
}
