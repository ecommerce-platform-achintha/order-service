package com.achintha.orderservice.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.achintha.orderservice.common.PageResponse;
import com.achintha.orderservice.order.CreateOrderRequest;
import com.achintha.orderservice.order.OrderController;
import com.achintha.orderservice.order.OrderResponse;
import com.achintha.orderservice.order.OrderService;
import com.achintha.orderservice.order.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Writes need the Gateway's X-User-Id header; reads do not. The service is mocked, so no DB or Kafka is needed. */
@WebMvcTest(controllers = OrderController.class, properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class GatewayUserHeaderFilterTest {

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String ORDER_JSON = """
            {"userId":"%s","items":[{"productId":"%s","quantity":1}]}
            """.formatted(USER_ID, UUID.randomUUID());
    private static final OrderResponse ORDER = new OrderResponse(ORDER_ID, USER_ID, OrderStatus.CONFIRMED,
            new BigDecimal("10.00"), List.of(), Instant.now(), Instant.now());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    static Stream<MockHttpServletRequestBuilder> writeRequests() {
        return Stream.of(
                post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(ORDER_JSON),
                patch("/api/orders/{id}/cancel", ORDER_ID));
    }

    static Stream<MockHttpServletRequestBuilder> readRequests() {
        return Stream.of(
                get("/api/orders/{id}", ORDER_ID),
                get("/api/orders").param("userId", USER_ID.toString()));
    }

    @ParameterizedTest
    @MethodSource("writeRequests")
    void writeWithoutUserIdIsRejectedBeforeReachingTheHandler(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"error\":\"Authentication required\"}", true));

        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    void writeWithBlankUserIdIsRejected(String blank) throws Exception {
        for (MockHttpServletRequestBuilder request : writeRequests().toList()) {
            mockMvc.perform(request.header(GatewayUserHeaderFilter.USER_ID_HEADER, blank))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().json("{\"error\":\"Authentication required\"}", true));
        }
        verifyNoInteractions(orderService);
    }

    @Test
    void createWithUserIdPassesThroughAndForwardsTheHeader() throws Exception {
        when(orderService.create(any(CreateOrderRequest.class), eq("gateway-user"))).thenReturn(ORDER);

        mockMvc.perform(post("/api/orders").header(GatewayUserHeaderFilter.USER_ID_HEADER, "gateway-user")
                        .contentType(MediaType.APPLICATION_JSON).content(ORDER_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ORDER_ID.toString()));

        verify(orderService).create(any(CreateOrderRequest.class), eq("gateway-user"));
    }

    @Test
    void cancelWithUserIdPassesThrough() throws Exception {
        when(orderService.cancel(ORDER_ID)).thenReturn(ORDER);

        mockMvc.perform(patch("/api/orders/{id}/cancel", ORDER_ID)
                        .header(GatewayUserHeaderFilter.USER_ID_HEADER, "gateway-user"))
                .andExpect(status().isOk());

        verify(orderService).cancel(ORDER_ID);
    }

    @Test
    void writesWithUserIdStillGetNormalValidation() throws Exception {
        // The guard only decides "through the Gateway or not"; the request is then validated as usual
        mockMvc.perform(post("/api/orders").header(GatewayUserHeaderFilter.USER_ID_HEADER, "gateway-user")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[]}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(orderService);
    }

    @ParameterizedTest
    @MethodSource("readRequests")
    void readsWorkWithoutUserId(MockHttpServletRequestBuilder request) throws Exception {
        stubReads();
        mockMvc.perform(request).andExpect(status().isOk());
    }

    @ParameterizedTest
    @MethodSource("readRequests")
    void readsWorkWithUserId(MockHttpServletRequestBuilder request) throws Exception {
        stubReads();
        mockMvc.perform(request.header(GatewayUserHeaderFilter.USER_ID_HEADER, "gateway-user"))
                .andExpect(status().isOk());
    }

    private void stubReads() {
        when(orderService.get(ORDER_ID)).thenReturn(ORDER);
        when(orderService.listByUser(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(ORDER), 0, 20, 1, 1));
    }
}
