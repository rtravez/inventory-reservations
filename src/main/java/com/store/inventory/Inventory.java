package com.store.inventory;

import java.time.Clock;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.StockAlertListener;
import com.store.inventory.core.InMemoryInventoryService;

import lombok.RequiredArgsConstructor;

/**
 * Entry point used by our automated tests. Keep this signature exactly as it
 * is,
 * and build your implementation here.
 */
@RequiredArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class Inventory {

    public static InventoryService create(Clock clock, StockAlertListener alertListener) {
        return new InMemoryInventoryService(clock, alertListener);
    }
}
