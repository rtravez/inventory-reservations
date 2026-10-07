package com.store.inventory.core;

import java.time.Duration;

/**
 * Business rules that apply to a product category: how long a reservation
 * stays active while the customer pays, and the maximum units allowed per
 * order.
 *
 * @param paymentWindow    time the customer has to pay before the reservation
 *                         expires
 * @param maxUnitsPerOrder maximum units a single order may reserve;
 *                         {@link Integer#MAX_VALUE} means no limit
 */
public record CategoryPolicy(Duration paymentWindow, int maxUnitsPerOrder) {

    public CategoryPolicy {
        if (paymentWindow == null || paymentWindow.isNegative() || paymentWindow.isZero()) {
            throw new IllegalArgumentException("paymentWindow must be a positive duration");
        }
        if (maxUnitsPerOrder <= 0) {
            throw new IllegalArgumentException("maxUnitsPerOrder must be positive");
        }
    }

    public boolean hasOrderLimit() {
        return maxUnitsPerOrder != Integer.MAX_VALUE;
    }
}
