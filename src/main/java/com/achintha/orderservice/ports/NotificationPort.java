package com.achintha.orderservice.ports;

import java.util.UUID;

/** Sends SMS / email / in-app notifications (section 10). Implementations must never log message secrets or PII. */
public interface NotificationPort {

    /** Tells a customer something about their order (e.g. a quote is ready, a payment was rejected). */
    void notifyCustomer(UUID customerId, String template, String subject);

    /** Tells the store's team something (e.g. a new order, a payment to verify). */
    void notifyStore(UUID storeId, String template, String subject);

    /** Alerts the admin team (e.g. a flagged payment reference, an order needing resolution). */
    void notifyAdmins(String template, String subject);
}
