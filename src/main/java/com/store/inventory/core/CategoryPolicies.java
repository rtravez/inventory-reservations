package com.store.inventory.core;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

import com.store.inventory.api.ProductCategory;

import lombok.RequiredArgsConstructor;

/**
 * Maps each {@link ProductCategory} to its business rules.
 *
 * Marketing creates a new category almost every season: adding one only
 * requires
 * a new enum value in the API plus a single entry here. Categories without an
 * explicit entry fall back to {@link #DEFAULT_POLICY} so nothing breaks in the
 * meantime.
 */
@RequiredArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class CategoryPolicies {

    private static final int NO_LIMIT = Integer.MAX_VALUE;

    /**
     * Conservative fallback for categories not configured yet:
     * short payment window and no per-order limit.
     */
    private static final CategoryPolicy DEFAULT_POLICY = new CategoryPolicy(Duration.ofMinutes(15), NO_LIMIT);

    private static final Map<ProductCategory, CategoryPolicy> POLICIES = buildPolicies();

    private static Map<ProductCategory, CategoryPolicy> buildPolicies() {
        Map<ProductCategory, CategoryPolicy> policies = new EnumMap<>(ProductCategory.class);
        policies.put(ProductCategory.STANDARD, new CategoryPolicy(Duration.ofMinutes(15), NO_LIMIT));
        policies.put(ProductCategory.PRE_ORDER, new CategoryPolicy(Duration.ofHours(24), NO_LIMIT));
        policies.put(ProductCategory.FLASH_SALE, new CategoryPolicy(Duration.ofMinutes(5), 2));
        return Map.copyOf(policies);
    }

    public static CategoryPolicy of(ProductCategory category) {
        return POLICIES.getOrDefault(category, DEFAULT_POLICY);
    }
}
