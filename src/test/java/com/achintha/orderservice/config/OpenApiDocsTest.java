package com.achintha.orderservice.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.achintha.orderservice.support.IntegrationTest;
import org.junit.jupiter.api.Test;

/** The public API docs are served without a token and list no internal endpoint and no removed one. */
class OpenApiDocsTest extends IntegrationTest {

    @Test
    void docsArePublicAndCoverTheNewApi() throws Exception {
        String docs = expect(getAs(null, "/v3/api-docs"), 200).getContentAsString();
        assertThat(docs).contains("/api/customer/checkout", "/api/merchant/orders/{publicId}/quote",
                "/api/admin/orders/{publicId}/resolve", "/api/admin/flagged-references");
        assertThat(docs).doesNotContain("/internal/").doesNotContain("\"/api/orders\"").doesNotContain("userId");
    }
}
