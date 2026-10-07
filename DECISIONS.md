# Decisiones de diseño

## Arquitectura

- El contrato (`com.store.inventory.api`) quedó intacto, tal como piden las reglas.
- La implementación vive en `com.store.inventory.core`:
  - `InMemoryInventoryService`: lógica de reservas, expiración, confirmación y alertas.
  - `CategoryPolicy` / `CategoryPolicies`: las reglas de negocio de cada categoría
    (tiempo para pagar y límite por pedido) aisladas en un solo lugar. Como Marketing
    crea categorías cada temporada, agregar una solo requiere el nuevo valor en el enum
    y una línea en el mapa de políticas. Mientras tanto, una categoría sin configurar
    usa una política por defecto conservadora (15 minutos, sin límite) en vez de fallar.
- La expiración es **perezosa**: no hay scheduler ni hilos de fondo. Una reserva está
  activa solo mientras `now < expiresAt`, y la disponibilidad se calcula en cada
  operación usando el `Clock` inyectado. Esto hace el comportamiento determinista y
  fácil de probar.
- Las alertas a compras salen exclusivamente por `StockAlertListener`. El servicio no
  sabe si el aviso llega por correo u otro canal: para expandirse a más canales solo
  hay que cambiar la implementación del listener, sin tocar el dominio.

## Supuestos

1. **Reintentos de la app (idempotencia).** El README dice que la app reenvía el pedido
   hasta recibir respuesta, así que `reserve` es idempotente por `orderId`:
   - Mismo `orderId`, mismo producto y misma cantidad → devuelve la reserva original
     sin volver a descontar unidades.
   - Mismo `orderId` con producto o cantidad distinta → `IllegalArgumentException`
     (se interpreta como un error del cliente, no como un reintento).
   - Pedido ya confirmado → `IllegalStateException`, para no vender dos veces las
     mismas unidades si el pago se reintenta.
   - Reserva expirada → se libera y el mismo `orderId` puede reservar de nuevo.
2. **Las reservas confirmadas se conservan** en memoria precisamente para detectar
   esos reintentos de pedidos pagados.
3. **Registro duplicado de producto** → `IllegalArgumentException`. Sobrescribir la
   categoría en silencio escondía errores de datos.
4. **Producto desconocido en `reserve`** → `InsufficientStockException` con 0
   disponibles, como indica el contrato ("unknown products have none").
5. **Alertas de stock bajo**: se evalúan tras cada cambio de disponibilidad
   (`addStock`, `reserve`, `confirm`). Se avisa una vez cuando el producto queda en
   5 unidades o menos, y `addStock` rearma el ciclo: si tras reabastecer sigue en 5 o
   menos, se vuelve a avisar. El umbral es una constante visible
   (`LOW_STOCK_THRESHOLD = 5`).
6. **Un pedido reserva un solo producto**, tal como dice el contrato.
7. **Concurrencia**: todos los métodos públicos están sincronizados. Es suficiente
   para una sola instancia en memoria y mantiene el código simple de leer.

## Lo que quedó fuera

- Persistencia (el README lo permite: "por ahora los datos pueden vivir en memoria").
- Scheduler activo para expirar reservas (innecesario con expiración perezosa).
- Limpieza de reservas históricas (expiradas o confirmadas muy antiguas).
- Métricas, logging estructurado y trazabilidad.
- Publicación de alertas a múltiples canales (basta inyectar otro `StockAlertListener`).
- Cancelación explícita de una reserva por parte del cliente (hoy solo expira por tiempo).

## Qué cambiaría antes de producción

- **Base de datos + multi-instancia**: el estado en memoria no sobrevive reinicios ni se
  comparte entre instancias. Migraría el stock y las reservas a tablas con control de
  concurrencia a nivel de fila (`SELECT ... FOR UPDATE` o actualizaciones condicionales
  tipo `UPDATE stock SET ... WHERE available >= ?`) para que dos instancias no reserven
  la misma unidad. La clave de idempotencia (`orderId`) pasaría a ser una restricción
  única en BD.
- **Job de expiración**: aunque la disponibilidad se calcula perezoso, convendría un
  proceso que marque/libere reservas vencidas para reportes y para no acumular filas.
- **Alertas como eventos**: en vez de llamar al listener en el mismo hilo de la compra,
  publicar el evento en una cola para que un fallo del canal (correo, etc.) no afecte
  la venta, con reintentos y deduplicación.
- **Configuración externa de políticas**: umbrales, TTLs y límites por categoría
  cargados desde configuración/BD para que Marketing no requiera un deploy.
- **Observabilidad**: métricas de sobreventa evitada, reservas expiradas vs.
  confirmadas, y logs con `orderId`/`sku` para soporte.
- **Pruebas de concurrencia reales** y pruebas de carga con el volumen de temporada alta.
