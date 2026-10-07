package com.store.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.store.inventory.api.InsufficientStockException;
import com.store.inventory.api.InventoryService;
import com.store.inventory.api.OrderLimitExceededException;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class InMemoryInventoryServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private MutableClock clock;
    private List<String> alerts;
    private InventoryService service;

    @BeforeEach
    void setUp() {
        clock = MutableClock.startingAt(T0);
        alerts = new ArrayList<>();
        service = Inventory.create(clock, (sku, available) -> alerts.add(sku + ":" + available));
    }

    // ------------------------------------------------------------------
    // Reservation lifecycle
    // ------------------------------------------------------------------

    @Nested
    class ReservationLifecycle {

        @Test
        void standardReservationExpiresAfter15MinutesAndUnitsAreReleased() {
            service.registerProduct("SKU-1", ProductCategory.STANDARD);
            service.addStock("SKU-1", 4);
            service.reserve("ORDER-1", "SKU-1", 3);
            assertEquals(1, service.available("SKU-1"));

            clock.advance(Duration.ofMinutes(15));

            assertEquals(4, service.available("SKU-1"));
        }

        @Test
        void reservationIsStillActiveOneInstantBeforeExpiry() {
            service.registerProduct("SKU-1", ProductCategory.STANDARD);
            service.addStock("SKU-1", 4);
            service.reserve("ORDER-1", "SKU-1", 3);

            clock.advance(Duration.ofMinutes(15).minusMillis(1));

            assertEquals(1, service.available("SKU-1"));
        }

        @Test
        void expiredReservationCanBeReservedAgainByAnotherOrder() {
            service.registerProduct("SKU-1", ProductCategory.STANDARD);
            service.addStock("SKU-1", 2);
            service.reserve("ORDER-1", "SKU-1", 2);
            clock.advance(Duration.ofMinutes(15));

            service.reserve("ORDER-2", "SKU-1", 2);

            assertEquals(0, service.available("SKU-1"));
        }

        @Test
        void sameOrderCanReserveAgainAfterItsReservationExpired() {
            service.registerProduct("SKU-1", ProductCategory.STANDARD);
            service.addStock("SKU-1", 2);
            service.reserve("ORDER-1", "SKU-1", 2);
            clock.advance(Duration.ofMinutes(15));

            Reservation retry = service.reserve("ORDER-1", "SKU-1", 2);

            assertEquals(T0.plus(Duration.ofMinutes(30)), retry.expiresAt());
            assertEquals(0, service.available("SKU-1"));
        }

        @Test
        void confirmedUnitsNeverReturnToStockEvenAfterTimePasses() {
            service.registerProduct("SKU-1", ProductCategory.STANDARD);
            service.addStock("SKU-1", 5);
            service.reserve("ORDER-1", "SKU-1", 2);
            service.confirm("ORDER-1");

            clock.advance(Duration.ofDays(30));

            assertEquals(3, service.available("SKU-1"));
        }
    }

    // ------------------------------------------------------------------
    // Category rules
    // ------------------------------------------------------------------

    @Nested
    class CategoryRules {

        @Test
        void preOrderReservationsLast24Hours() {
            service.registerProduct("SKU-PO", ProductCategory.PRE_ORDER);
            service.addStock("SKU-PO", 10);

            Reservation reservation = service.reserve("ORDER-1", "SKU-PO", 5);

            assertEquals(T0.plus(Duration.ofHours(24)), reservation.expiresAt());
            clock.advance(Duration.ofHours(24));
            assertEquals(10, service.available("SKU-PO"));
        }

        @Test
        void flashSaleReservationsLast5Minutes() {
            service.registerProduct("SKU-FLASH", ProductCategory.FLASH_SALE);
            service.addStock("SKU-FLASH", 10);

            Reservation reservation = service.reserve("ORDER-1", "SKU-FLASH", 2);

            assertEquals(T0.plus(Duration.ofMinutes(5)), reservation.expiresAt());
            clock.advance(Duration.ofMinutes(5));
            assertEquals(10, service.available("SKU-FLASH"));
        }

        @Test
        void flashSaleAllowsAtMost2UnitsPerOrder() {
            service.registerProduct("SKU-FLASH", ProductCategory.FLASH_SALE);
            service.addStock("SKU-FLASH", 10);

            assertThrows(OrderLimitExceededException.class,
                    () -> service.reserve("ORDER-1", "SKU-FLASH", 3));
            assertEquals(10, service.available("SKU-FLASH"));
        }

        @Test
        void flashSaleLimitAppliesPerOrderNotPerCustomer() {
            service.registerProduct("SKU-FLASH", ProductCategory.FLASH_SALE);
            service.addStock("SKU-FLASH", 10);

            service.reserve("ORDER-1", "SKU-FLASH", 2);
            service.reserve("ORDER-2", "SKU-FLASH", 2);

            assertEquals(6, service.available("SKU-FLASH"));
        }

        @Test
        void standardAndPreOrderHaveNoPerOrderLimit() {
            service.registerProduct("SKU-1", ProductCategory.STANDARD);
            service.registerProduct("SKU-PO", ProductCategory.PRE_ORDER);
            service.addStock("SKU-1", 100);
            service.addStock("SKU-PO", 100);

            service.reserve("ORDER-1", "SKU-1", 100);
            service.reserve("ORDER-2", "SKU-PO", 100);

            assertEquals(0, service.available("SKU-1"));
            assertEquals(0, service.available("SKU-PO"));
        }
    }

    // ------------------------------------------------------------------
    // Idempotency: the app resends orders until it gets a response
    // ------------------------------------------------------------------

    @Nested
    class Retries {

        @Test
        void retryingTheSameOrderReturnsTheOriginalReservationWithoutDoubleBooking() {
            service.registerProduct("SKU-1", ProductCategory.STANDARD);
            service.addStock("SKU-1", 10);

            Reservation first = service.reserve("ORDER-1", "SKU-1", 3);
            Reservation retry = service.reserve("ORDER-1", "SKU-1", 3);

            assertEquals(first, retry);
            assertEquals(7, service.available("SKU-1"));
        }

        @Test
        void retryingWithDifferentPayloadIsRejected() {
            service.registerProduct("SKU-1", ProductCategory.STANDARD);
            service.registerProduct("SKU-2", ProductCategory.STANDARD);
            service.addStock("SKU-1", 10);
            service.addStock("SKU-2", 10);
            service.reserve("ORDER-1", "SKU-1", 3);

            assertThrows(IllegalArgumentException.class,
                    () -> service.reserve("ORDER-1", "SKU-1", 5));
            assertThrows(IllegalArgumentException.class,
                    () -> service.reserve("ORDER-1", "SKU-2", 3));
        }

        @Test
        void retryingAnAlreadyConfirmedOrderIsRejectedSoUnitsAreNotSoldTwice() {
            service.registerProduct("SKU-1", ProductCategory.STANDARD);
            service.addStock("SKU-1", 10);
            service.reserve("ORDER-1", "SKU-1", 3);
            service.confirm("ORDER-1");

            assertThrows(IllegalStateException.class,
                    () -> service.reserve("ORDER-1", "SKU-1", 3));
            assertEquals(7, service.available("SKU-1"));
        }
    }

    // ------------------------------------------------------------------
    // Confirmation
    // ------------------------------------------------------------------

    @Nested
    class Confirmation {

        @Test
        void confirmingAnUnknownOrderFails() {
            assertThrows(IllegalStateException.class, () -> service.confirm("ORDER-404"));
        }

        @Test
        void confirmingTwiceFails() {
            service.registerProduct("SKU-1", ProductCategory.STANDARD);
            service.addStock("SKU-1", 5);
            service.reserve("ORDER-1", "SKU-1", 2);
            service.confirm("ORDER-1");

            assertThrows(IllegalStateException.class, () -> service.confirm("ORDER-1"));
        }

        @Test
        void confirmingAnExpiredReservationFails() {
            service.registerProduct("SKU-1", ProductCategory.STANDARD);
            service.addStock("SKU-1", 5);
            service.reserve("ORDER-1", "SKU-1", 2);
            clock.advance(Duration.ofMinutes(15));

            assertThrows(IllegalStateException.class, () -> service.confirm("ORDER-1"));
            assertEquals(5, service.available("SKU-1"));
        }

        @Test
        void confirmingRightBeforeExpiryStillWorks() {
            service.registerProduct("SKU-1", ProductCategory.STANDARD);
            service.addStock("SKU-1", 5);
            service.reserve("ORDER-1", "SKU-1", 2);
            clock.advance(Duration.ofMinutes(15).minusMillis(1));

            service.confirm("ORDER-1");

            assertEquals(3, service.available("SKU-1"));
        }
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    @Nested
    class Validation {

        @Test
        void availableIsZeroForUnknownProducts() {
            assertEquals(0, service.available("SKU-404"));
        }

        @Test
        void reservingAnUnknownProductFailsWithInsufficientStock() {
            assertThrows(InsufficientStockException.class,
                    () -> service.reserve("ORDER-1", "SKU-404", 1));
        }

        @Test
        void cannotReserveZeroOrNegativeUnits() {
            service.registerProduct("SKU-1", ProductCategory.STANDARD);
            service.addStock("SKU-1", 10);

            assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-1", 0));
            assertThrows(IllegalArgumentException.class, () -> service.reserve("ORDER-1", "SKU-1", -2));
        }

        @Test
        void cannotAddZeroOrNegativeStock() {
            service.registerProduct("SKU-1", ProductCategory.STANDARD);

            assertThrows(IllegalArgumentException.class, () -> service.addStock("SKU-1", 0));
            assertThrows(IllegalArgumentException.class, () -> service.addStock("SKU-1", -1));
        }

        @Test
        void cannotAddStockToAnUnregisteredProduct() {
            assertThrows(IllegalArgumentException.class, () -> service.addStock("SKU-404", 10));
        }

        @Test
        void cannotRegisterTheSameProductTwice() {
            service.registerProduct("SKU-1", ProductCategory.STANDARD);

            assertThrows(IllegalArgumentException.class,
                    () -> service.registerProduct("SKU-1", ProductCategory.PRE_ORDER));
        }
    }

    // ------------------------------------------------------------------
    // Low stock alerts
    // ------------------------------------------------------------------

    @Nested
    class LowStockAlerts {

        @Test
        void alertFiresWhenAvailabilityDropsTo5OrBelow() {
            service.registerProduct("SKU-1", ProductCategory.STANDARD);
            service.addStock("SKU-1", 6);

            service.reserve("ORDER-1", "SKU-1", 1);

            assertEquals(List.of("SKU-1:5"), alerts);
        }

        @Test
        void noAlertWhileAboveTheThreshold() {
            service.registerProduct("SKU-1", ProductCategory.STANDARD);
            service.addStock("SKU-1", 10);

            service.reserve("ORDER-1", "SKU-1", 2);

            assertTrue(alerts.isEmpty());
        }

        @Test
        void alertIsNotRepeatedUntilTheProductIsRestocked() {
            service.registerProduct("SKU-1", ProductCategory.STANDARD);
            service.addStock("SKU-1", 6);

            service.reserve("ORDER-1", "SKU-1", 1); // 5 left -> alert
            service.reserve("ORDER-2", "SKU-1", 1); // 4 left -> no repeat
            service.confirm("ORDER-2");             // 4 left -> no repeat

            assertEquals(List.of("SKU-1:5"), alerts);
        }

        @Test
        void restockingRearmsTheAlert() {
            service.registerProduct("SKU-1", ProductCategory.STANDARD);
            service.addStock("SKU-1", 6);
            service.reserve("ORDER-1", "SKU-1", 1); // 5 left -> alert

            service.addStock("SKU-1", 10);          // restock: 15 left
            service.reserve("ORDER-2", "SKU-1", 10); // 5 left -> alert again

            assertEquals(List.of("SKU-1:5", "SKU-1:5"), alerts);
        }

        @Test
        void alertDoesNotFireOnRestockIfStillLowButFiresOnTheNextDrop() {
            service.registerProduct("SKU-1", ProductCategory.STANDARD);
            service.addStock("SKU-1", 6);
            service.reserve("ORDER-1", "SKU-1", 1); // 5 left -> alert

            service.addStock("SKU-1", 1);           // 6 left, rearmed
            service.reserve("ORDER-2", "SKU-1", 1); // 5 left -> alert again

            assertEquals(List.of("SKU-1:5", "SKU-1:5"), alerts);
        }
    }
}
