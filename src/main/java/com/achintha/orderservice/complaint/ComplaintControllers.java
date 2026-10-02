package com.achintha.orderservice.complaint;

/** Holder for the complaint controllers' shared constants. */
final class ComplaintControllers {

    static final String SORT_DEFAULT = "createdAt,desc";
    static final java.util.Map<String, String> SORTABLE = java.util.Map.of("createdAt", "createdAt",
            "updatedAt", "updatedAt", "status", "status");

    private ComplaintControllers() {
    }
}
