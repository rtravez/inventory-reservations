package com.store.inventory.core;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import com.store.inventory.api.StockAlertListener;

/**
 * In-memory implementation of the inventory reservation contract.
 *
 * <p>
 * Design notes:
 * <ul>
 * <li>Expiration is lazy: a reservation is active only while
 * {@code now < expiresAt}.
 * No background scheduler is needed; availability is computed on every
 * operation
 * using the injected {@link Clock} (which also makes TTLs fully testable).</li>
 * <li>Retries are safe: the mobile app resends orders until it gets a response,
 * so
 * {@link #reserve} is idempotent per {@code orderId}. Repeating the same order
 * returns the original reservation instead of double-booking units.</li>
 * <li>Confirmed reservations are kept so that a retry of an already-paid order
 * is
 * rejected instead of selling the same units twice.</li>
 * <li>Low-stock alerts fire once per restocked cycle: after any availability
 * change,
 * if the product is at or below the threshold and no alert was sent since the
 * last restocking, the listener is notified.</li>
 * <li>All public methods are synchronized: correct for a single instance. The
 * move to
 * multiple instances requires database-level concurrency control (see
 * DECISIONS.md).</li>
 * </ul>
 */
public final class InMemoryInventoryService implements InventoryService {

    /**
     * Purchasing wants to be warned at 5 available units or fewer.
     */
    static final int LOW_STOCK_THRESHOLD = 5;

    private final Clock clock;
    private final StockAlertListener alertListener;
    private final Map<String, ProductStock> products = new HashMap<>();
    private final Map<String, StoredReservation> reservationsByOrder = new HashMap<>();

    public InMemoryInventoryService(Clock clock, StockAlertListener alertListener) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.alertListener = Objects.requireNonNull(alertListener, "alertListener");
    }

    @Override
    public synchronized void registerProduct(String sku, ProductCategory category) {
        requireText(sku, "sku");
        Objects.requireNonNull(category, "category");
        if (products.containsKey(sku)) {
            throw new IllegalArgumentException("Product already registered: " + sku);
        }
        products.put(sku, new ProductStock(category));
    }

    @Override
    public synchronized void addStock(String sku, int quantity) {
        ProductStock product = products.get(sku);
        if (product == null) {
            throw new IllegalArgumentException("Product not registered: " + sku);
        }
        requirePositive(quantity);
        product.totalUnits += quantity;
        // Restock starts a new alert cycle.
        product.lowStockAlertSent = false;
        evaluateLowStock(sku, product);
    }

    @Override
    public synchronized Reservation reserve(String orderId, String sku, int quantity) {
        requireText(orderId, "orderId");
        requireText(sku, "sku");
        requirePositive(quantity);
        Instant now = clock.instant();

        StoredReservation existing = reservationsByOrder.get(orderId);
        if (existing != null) {
            if (existing.isActive(now)) {
                // The app retries orders on slow connections: replay the same reservation.
                if (existing.sku.equals(sku) && existing.quantity == quantity) {
                    return existing.toApiReservation();
                }
                throw new IllegalArgumentException(
                        "Order " + orderId + " already has an active reservation for a different product or quantity");
            }
            if (existing.isConfirmed()) {
                throw new IllegalStateException("Order " + orderId + " is already confirmed");
            }
            // Expired and never paid: release it and allow the order to be reserved again.
            reservationsByOrder.remove(orderId);
        }

        ProductStock product = products.get(sku);
        CategoryPolicy policy = product == null ? null : CategoryPolicies.of(product.category);
        if (policy != null && quantity > policy.maxUnitsPerOrder()) {
            throw new OrderLimitExceededException(sku, quantity, policy.maxUnitsPerOrder());
        }

        int available = availableUnits(sku, now);
        if (quantity > available) {
            // Unknown products have 0 available, so they always end up here.
            throw new InsufficientStockException(sku, quantity, available);
        }

        StoredReservation reservation = new StoredReservation(
                orderId, sku, quantity, now.plus(policy.paymentWindow()));
        reservationsByOrder.put(orderId, reservation);
        evaluateLowStock(sku, product);
        return reservation.toApiReservation();
    }

    @Override
    public synchronized void confirm(String orderId) {
        requireText(orderId, "orderId");
        StoredReservation reservation = reservationsByOrder.get(orderId);
        if (reservation == null || !reservation.isActive(clock.instant())) {
            throw new IllegalStateException("Order " + orderId + " has no active reservation");
        }
        reservation.confirm();
        ProductStock product = products.get(reservation.sku);
        product.totalUnits -= reservation.quantity;
        evaluateLowStock(reservation.sku, product);
    }

    @Override
    public synchronized int available(String sku) {
        return availableUnits(sku, clock.instant());
    }

    private int availableUnits(String sku, Instant now) {
        ProductStock product = products.get(sku);
        if (product == null) {
            return 0;
        }
        int reserved = reservationsByOrder.values().stream()
                .filter(r -> r.sku.equals(sku) && r.isActive(now))
                .mapToInt(r -> r.quantity)
                .sum();
        return product.totalUnits - reserved;
    }

    private void evaluateLowStock(String sku, ProductStock product) {
        int available = availableUnits(sku, clock.instant());
        if (available <= LOW_STOCK_THRESHOLD && !product.lowStockAlertSent) {
            product.lowStockAlertSent = true;
            alertListener.onLowStock(sku, available);
        }
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive, got " + quantity);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
    }

    /**
     * Physical units in the warehouse, plus the alert cycle flag.
     */
    private static final class ProductStock {
        private final ProductCategory category;
        private int totalUnits;
        private boolean lowStockAlertSent;

        private ProductStock(ProductCategory category) {
            this.category = category;
        }
    }

    /**
     * Lifecycle of a reservation: PENDING until paid (CONFIRMED) or until expiresAt
     * passes.
     */
    private static final class StoredReservation {
        private final String orderId;
        private final String sku;
        private final int quantity;
        private final Instant expiresAt;
        private boolean confirmed;

        private StoredReservation(String orderId, String sku, int quantity, Instant expiresAt) {
            this.orderId = orderId;
            this.sku = sku;
            this.quantity = quantity;
            this.expiresAt = expiresAt;
        }

        private boolean isActive(Instant now) {
            return !confirmed && now.isBefore(expiresAt);
        }

        private boolean isConfirmed() {
            return confirmed;
        }

        private void confirm() {
            this.confirmed = true;
        }

        private Reservation toApiReservation() {
            return new Reservation(orderId, sku, quantity, expiresAt);
        }
    }
}
