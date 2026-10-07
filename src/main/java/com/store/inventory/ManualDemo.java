package com.store.inventory;

import java.time.Clock;

import com.store.inventory.api.InventoryService;
import com.store.inventory.api.ProductCategory;
import com.store.inventory.api.Reservation;

import lombok.extern.slf4j.Slf4j;

/**
 * Manual playground: run with
 * mvn -q compile exec:java -Dexec.mainClass=com.store.inventory.ManualDemo
 * or from the IDE running the main method.
 */
@Slf4j
public final class ManualDemo {

    public static void main(String[] args) {
        InventoryService service = Inventory.create(
                Clock.systemUTC(),
                (sku, available) -> log.warn(">>> ALERTA A COMPRAS: {} tiene {} unidades", sku, available));

        service.registerProduct("SKU-TECLADO", ProductCategory.STANDARD);
        service.registerProduct("SKU-CONSOLA", ProductCategory.FLASH_SALE);

        service.addStock("SKU-TECLADO", 6);
        service.addStock("SKU-CONSOLA", 10);

        Reservation r1 = service.reserve("ORDER-1", "SKU-TECLADO", 2);
        log.info("Reservado: {} | disponibles: {}", r1, service.available("SKU-TECLADO"));

        // Reintento de la app: mismo orderId, no duplica unidades
        service.reserve("ORDER-1", "SKU-TECLADO", 2);
        log.info("Tras reintento, disponibles: {}", service.available("SKU-TECLADO"));

        service.confirm("ORDER-1");
        log.info("Tras confirmar pago, disponibles: {}", service.available("SKU-TECLADO"));

        try {
            service.reserve("ORDER-2", "SKU-CONSOLA", 3); // excede el límite de FLASH_SALE
        } catch (Exception e) {
            log.error("Rechazado: {}", e.getMessage());
        }
    }
}
