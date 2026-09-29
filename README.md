# order-service

Spring Boot microservice for placing orders and managing their lifecycle. It prices and stock-checks each order
against **product-service** and decrements inventory there when an order is confirmed.

- Java 25, Spring Boot 4.1.1, Spring Cloud 2025.1.3, Maven
- PostgreSQL via Spring Data JPA (`ddl-auto=update` for now; Flyway/Liquibase to follow)
- Config from the Config Server (`optional:configserver:http://localhost:8888`)
- Registers with Eureka (`http://localhost:8761/eureka/`) and finds product-service through it (service id
  `product-service`, no hardcoded URL)
- OpenFeign + Resilience4j (circuit breaker, retry, timeout) for every call to product-service
- Port **8083**

## Prerequisites

Start these **before** order-service:

| Dependency | Default location | Notes |
|---|---|---|
| Config Server | `http://localhost:8888` | `../config-server`. Optional: the service starts without it |
| Eureka (service-registry) | `http://localhost:8761` | `../service-registry` |
| **product-service** | registered in Eureka as `product-service` (port 8082) | `../product-service`. order-service calls it directly on every order. Without it, creating an order returns **503** |
| PostgreSQL | `localhost:5434`, db `orderdb`, user/pass `orderservice`/`orderservice` | `docker compose up -d postgres` starts one with these defaults |

The database is on port **5434** with its own name and volume, so it can run next to user-service's (5432) and
product-service's (5433).

You also need JDK 25, Maven 3.9+, and Docker (for docker-compose and the Testcontainers integration test).

## Configuration

Every setting can be overridden with an environment variable:

| Env var | Default | Purpose |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5434` / `orderdb` | JDBC URL parts |
| `DB_USERNAME` / `DB_PASSWORD` | `orderservice` / `orderservice` | DB credentials |
| `CONFIG_SERVER_URL` | `http://localhost:8888` | Config Server |
| `EUREKA_URL` | `http://localhost:8761/eureka/` | Eureka `defaultZone` |

### Resilience settings

All Resilience4j settings are named instances in `application.yml`, so you can tune them there or through the
Config Server without code changes:

| Instance | Setting | Value |
|---|---|---|
| `resilience4j.circuitbreaker.instances.productService` | count-based sliding window / min calls | 10 / 5 |
| | failure-rate threshold | 50 % |
| | slow call (counts as failure) | ≥ 2 s |
| | open-state wait → half-open trial calls | 15 s → 3 |
| `resilience4j.retry.instances.productLookup` | attempts / wait | 2 / 300 ms |
| `resilience4j.retry.instances.inventoryUpdate` | attempts / wait | 2 / 300 ms, only when the request never reached product-service (see below) |
| `resilience4j.timelimiter.instances.productService` | timeout per attempt | 3 s |

Each call runs as `Retry(CircuitBreaker(TimeLimiter(feign call)))`, so each attempt has its own 3 s timeout and
the breaker counts every attempt. The worst case is about 2 × 3 s plus the retry wait. After that, or immediately
while the breaker is open, the fallback throws `ProductServiceUnavailableException` → **503**. It never returns
made-up data.

Some details:

- **404/409 from product-service don't count as failures.** An unknown product or a stock conflict means
  product-service is healthy and said "no". Those errors pass through (404 / 409) without tripping the breaker
  or being retried.
- **The inventory decrement is retried only when the connection was refused** (or the load balancer had no
  instance). A decrement isn't idempotent: after a timeout or a 500, product-service may already have applied it,
  so a retry could take stock twice. The rule is `ConnectFailurePredicate`, wired in via yml.
- Apache HttpClient's own automatic retries are disabled (`FeignConfig`), so Resilience4j is the only retry layer.

## Run locally

```bash
# 1. Config Server, Eureka and product-service (each in its own terminal)
(cd ../config-server    && mvn spring-boot:run)
(cd ../service-registry && mvn spring-boot:run)
(cd ../product-service  && docker compose up -d postgres && mvn spring-boot:run)

# 2. PostgreSQL for orders only
docker compose up -d postgres

# 3. order-service
mvn spring-boot:run
```

Swagger UI: http://localhost:8083/swagger-ui.html
Health check: http://localhost:8083/actuator/health
Circuit breaker state: http://localhost:8083/actuator/circuitbreakers (recent calls: `/actuator/circuitbreakerevents`)

## Run with docker-compose

This starts order-service and its PostgreSQL with one command. Config Server, Eureka and product-service must
still be running. The container reaches Config Server and Eureka through `host.docker.internal`.

```bash
docker compose up --build
```

**Networking caveat:** order-service calls product-service at whatever address product-service registered in
Eureka, so that address must be reachable **from inside the order-service container**. If product-service runs on
the host via `mvn spring-boot:run` (it registers its IP), this works. If product-service runs in its own compose
stack, it registers as `localhost:8082`. Inside the order-service container, `localhost` is the container itself,
so order creation returns 503. In that case run order-service with `mvn spring-boot:run` instead (or put all
services on one Docker network).

Stop with `docker compose down` (add `-v` to also delete the database volume).

## API

No authentication yet. Access control will be enforced by the API Gateway.

| Method | Path | Description |
|---|---|---|
| POST | `/api/orders` | Body `{userId, items: [{productId, quantity}]}`. Returns **201** with the order's final status (`CONFIRMED` or `FAILED`) |
| GET | `/api/orders/{id}` | One order with its items (404 if unknown) |
| GET | `/api/orders?userId=...` | A user's orders, paginated (`page`, `size` ≤ 100, `sort`), newest first by default |
| PATCH | `/api/orders/{id}/cancel` | `PENDING`/`CONFIRMED` → `CANCELLED`; **409** if already `CANCELLED` or `FAILED` |

### How an order is created

1. Repeated `productId`s are merged into one line.
2. For each product, product-service is asked for the current name, price and stock
   (`GET /api/products/{id}`). If the requested quantity exceeds `stock.quantityAvailable`, the request fails with
   **409**. An unknown product gives **404**, and product-service being down gives **503**. In all three cases
   nothing is saved.
3. Name and unit price are **copied** into each `OrderItem`, so later catalog changes don't rewrite old orders.
   `totalAmount = Σ unitPrice × quantity`.
4. The order is saved as `PENDING`.
5. Stock is decremented for each item (`PATCH /api/products/{id}/inventory` with `{"delta": -quantity}`).
   - All succeed → `CONFIRMED`.
   - Any fails (unavailable, or 409 because someone else bought the stock since step 2) → `FAILED`. Decrements
     that already succeeded for this order are put back (best effort, logged if that also fails).

Status lifecycle: `PENDING → CONFIRMED | FAILED | CANCELLED`, `CONFIRMED → CANCELLED`. `CANCELLED` and `FAILED` are
final.

### Errors

Every error uses the same JSON shape (`fieldErrors` only for validation):

```json
{
  "timestamp": "2026-09-29T10:15:30Z",
  "status": 409,
  "error": "Conflict",
  "message": "Insufficient stock for product 5f0c...: requested 5, available 1",
  "path": "/api/orders"
}
```

| Status | When |
|---|---|
| 400 | Validation failure (missing `userId`, empty `items`, `quantity` < 1), malformed JSON, non-UUID id |
| 404 | Unknown order, or an order line references a product that doesn't exist |
| 409 | Not enough stock; cancelling a `CANCELLED`/`FAILED` order; concurrent modification of the same order |
| 503 | product-service unavailable, timed out, erroring, or its circuit breaker is open |

## Example: full create-order flow

```bash
# 1. Create a category and product in product-service, and give it stock
CATEGORY_ID=$(curl -s -X POST localhost:8082/api/categories -H 'Content-Type: application/json' \
  -d '{"name": "Electronics"}' | jq -r .id)
PRODUCT_ID=$(curl -s -X POST localhost:8082/api/products -H 'Content-Type: application/json' \
  -d "{\"name\": \"Gaming Laptop\", \"price\": 999.99, \"sku\": \"LAP-001\", \"categoryId\": \"$CATEGORY_ID\"}" | jq -r .id)
curl -s -X PATCH localhost:8082/api/products/$PRODUCT_ID/inventory -H 'Content-Type: application/json' \
  -d '{"delta": 10}'

# 2. Place an order -> 201, status CONFIRMED, totalAmount 1999.98
USER_ID=$(uuidgen)
ORDER_ID=$(curl -s -X POST localhost:8083/api/orders -H 'Content-Type: application/json' \
  -d "{\"userId\": \"$USER_ID\", \"items\": [{\"productId\": \"$PRODUCT_ID\", \"quantity\": 2}]}" \
  | tee /dev/stderr | jq -r .id)

# Stock in product-service went from 10 to 8
curl -s localhost:8082/api/products/$PRODUCT_ID | jq .stock

# 3. Fetch it, and list the user's orders
curl -s localhost:8083/api/orders/$ORDER_ID | jq
curl -s "localhost:8083/api/orders?userId=$USER_ID&page=0&size=10" | jq

# 4. Ask for more than is in stock -> 409, nothing saved
curl -s -X POST localhost:8083/api/orders -H 'Content-Type: application/json' \
  -d "{\"userId\": \"$USER_ID\", \"items\": [{\"productId\": \"$PRODUCT_ID\", \"quantity\": 999}]}" | jq

# 5. Cancel -> CANCELLED; cancelling again -> 409
curl -s -X PATCH localhost:8083/api/orders/$ORDER_ID/cancel | jq .status
curl -s -X PATCH localhost:8083/api/orders/$ORDER_ID/cancel | jq
```

## Observing the circuit breaker

1. With everything running, check the breaker: `curl -s localhost:8083/actuator/circuitbreakers | jq` →
   `"state": "CLOSED"`.
2. **Stop product-service** (Ctrl+C its `mvn spring-boot:run`).
3. Create an order (step 2 above). Expect a **503** within a few seconds, not a hang:
   ```json
   {"status": 503, "error": "Service Unavailable",
    "message": "Product service is unavailable (request failed): could not look up product ... Try again later."}
   ```
   While Eureka still lists the stopped instance, each attempt gets a refused connection. Once Eureka drops it,
   the load balancer reports no instance. Either way the result is 503.
4. Repeat a few times. After 5 failed calls at ≥ 50 % failure rate the breaker **opens**
   (`/actuator/circuitbreakers` shows `OPEN`). Requests now fail **immediately** without calling product-service,
   with `"... (circuit breaker is open) ..."`. `/actuator/circuitbreakerevents` lists each recorded call and state
   change.
5. Start product-service again. After 15 s the breaker goes half-open, lets 3 trial calls through, and closes again
   if they succeed.

To see the **timeout** instead of a refused connection, keep product-service running but make it slow (e.g. pause
it in a debugger). Each attempt is cut off after 3 s and the response says `(request timed out)`.

## Tests

```bash
mvn clean package   # unit + integration tests (Docker required)
```

- `OrderTest`: unit tests for the total calculation (`Σ unitPrice × quantity`, BigDecimal precision) and the status
  rules (cancel allowed from `PENDING`/`CONFIRMED`, rejected from `CANCELLED`/`FAILED`; only `PENDING` can be
  confirmed or failed).
- `OrderFlowIntegrationTest`: real PostgreSQL through Testcontainers, with product-service stubbed by WireMock. The
  Feign client still goes through Spring Cloud LoadBalancer, which is pointed at WireMock. Covers:
  - successful creation (price snapshots, exact inventory deltas, get/list, cancel, double cancel → 409)
  - insufficient stock → 409, nothing saved, no decrement
  - product-service unreachable → 503 after exactly 2 attempts
  - breaker opening and then failing fast without calling product-service
  - slow product-service → 503 on timeout, not a hang
  - inventory decrement failure → order `FAILED`, not retried
  - unknown product → 404 without affecting the breaker; validation → 400

## Known limitations / next steps

- **Cancelling a `CONFIRMED` order doesn't restock product-service.** The cancel endpoint only changes the order's
  status. Restocking needs a decision on what to do when the restock call itself fails (e.g. an outbox or a
  retry queue).
- **A decrement that times out is ambiguous.** product-service may have applied it after order-service gave up.
  The order is then `FAILED` while stock was taken. Fixing that properly needs an idempotency key on the inventory
  endpoint, or a reservation/saga flow.
- Between the stock check and the decrement, another order can take the stock. product-service's 409 on the
  decrement catches this, and the order ends `FAILED` rather than overselling.
- The schema is managed by `ddl-auto=update` until migrations are introduced.
