# VELORA — Project Rules

Premium e-commerce for the Egyptian market: watches, wallets, perfumes.
Spring Boot 4.1 · Java 21 · SQL Server 2025 · Angular front end (separate repo)
Base package: `com.velora.api`

---

## Non-negotiable rules

Break any of these and the fix later is a migration, not an edit.

### Money and tax
- Prices are **TAX-INCLUSIVE**. The displayed price is what the customer pays.
- Tax is **extracted**, never added: `net = gross / (1 + rate)`, `tax = gross - net`.
- Compute tax **per order line, then sum**. Never on the order total — the invoice
  would disagree with its own lines by a piastre.
- All money is `BigDecimal` in Java and `DECIMAL(19,4)` in SQL. **Never `float` or
  `double`.**
- Use `MoneyUtils` for every calculation. Do not hand-roll rounding.

### Catalog
- **Sell the variant, not the product.** Cart lines, order lines, stock movements,
  reservations and price rules all reference `product_variant` — never `product`.
- A product with exactly one variant is normal, not a special case.
- Products and variants are **archived**, never hard-deleted: order lines reference
  them forever.
- SKUs are never reused.

### Orders
- Order items **SNAPSHOT** name, SKU, unit price, tax rate and image at purchase
  time. Never read those from the variant when displaying a historical order.
- The delivery address is **copied onto the order**, not linked to `customer_address`.
- Cart-level discounts are **allocated to lines at order creation**
  (`order_item.allocated_cart_discount`) via `MoneyUtils.allocate()`. Without it a
  partial return has no correct refund amount, and it cannot be reconstructed later.
- Fulfilment status and payment status are **two separate state machines**. Never
  collapse them. `PENDING` payment on a delivered COD order is correct, not a bug.
- Invoices are immutable once issued. Corrections are credit notes.

### Inventory
- Stock is three numbers: `qty_on_hand`, `qty_reserved`, and derived
  `qty_available = on_hand - reserved`. Never store availability.
- Reserve with a **guarded atomic UPDATE**; if the row count is 0, fail the checkout
  cleanly. Never read-then-write.
- Never overwrite a quantity without writing a `stock_movement` row.
- Returned goods are restocked **only after inspection**, never on receipt.

### Arabic and localization
- Arabic columns are `NVARCHAR` with `N'...'` literals. Never `VARCHAR`.
- `ArabicNormalizer.normalize()` must be applied when **writing** `search_text` and
  when **building the query**. Same function, both sides.
- `PhoneNormalizer.toE164()` must be applied before every insert **and** every
  lookup. Otherwise one person gets two accounts.

### Security
- Authorize on the server for every request. Hiding a button in Angular is UX.
- **Object-level checks in the service layer**: verify the order/address/cart
  belongs to the caller. Changing an id in the URL must not expose another
  customer's data.
- Passwords: BCrypt strength 12. Never MD5, SHA-1 or plain SHA-256.
- Never log tokens, passwords, OTP codes or full phone numbers. Use
  `PhoneNormalizer.mask()`.
- Never return the same error shape for "not found" vs "not yours" — return 404 for
  both.

### Architecture
- **Package by feature**, not by layer: `catalog/`, `order/`, `inventory/` — each
  with `domain/ repository/ service/ dto/ mapper/ web/`.
- Controllers **never** return JPA entities. DTOs only.
- Controllers never call repositories. Controller → Service → Repository, one way.
- `@Transactional` belongs on the service, not the controller.
- `ddl-auto` stays `validate`. The SQL scripts own the schema. **Never `update`, never
  `create`.** (Hibernate-built tables are `VARCHAR`, which stores Arabic as `?`, and carry
  none of the unique constraints below — that is how `royal` once lost its Arabic text.)
- **Tests run only against `royal_test`**, as a login that cannot open any other database.
  A test that passes while writing to the real database is worse than one that fails.
- Every list endpoint is paginated and returns `PageResponse<T>`.
- Business failures throw `BusinessException(ErrorCode.X)` — never a bare
  `RuntimeException` and never a raw string message.

---

## Reference files

| Path | What it is |
|---|---|
| `src/main/resources/db/baseline/baseline_through_V14.sql` | The schema — 57 tables, the source of truth. Read `src/main/resources/db/README.md` first |
| `docs/VELORA_API_Contract.md` | Endpoints + which Angular screen uses each |
| `docs/VELORA_Backend_Blueprint.md` | Architecture, patterns, package layout |

Schema changes are a **new numbered script** in `src/main/resources/db/`: `V15__...sql` onwards.
Never edit an existing `V` script or the baseline, and never run V2–V14 on a database built from
the baseline (they are already in it). Scripts contain no `USE`; pick the database with
`sqlcmd -d`. Create databases `COLLATE Arabic_CI_AS`.

---

## Design patterns and where they belong

| Pattern | Use it for |
|---|---|
| Specification | Product filtering — 8+ optional filters compose into one query |
| Strategy | `PaymentProcessor` (COD now, gateway later), `ShippingRateCalculator` |
| Facade | `CheckoutService` — one transactional boundary over cart/stock/order/invoice |
| Domain events | `OrderPlacedEvent` → notifications, via `@TransactionalEventListener(AFTER_COMMIT)` |
| Optimistic locking | `@Version` on `Inventory` |

---

## Commands

```powershell
.\mvnw clean test            # run before every commit (against royal_test)
.\mvnw spring-boot:run       # http://localhost:8081/swagger-ui.html

# (Re)build the isolated test database and point the tests at it
$env:SQLCMDPASSWORD = '<sa password>'
.\scripts\db\create-test-db.ps1 -WriteTestConfig
```

Environment variables required: `DB_PASSWORD`, `JWT_SECRET` (64+ chars).

---

## Working style

- Write the test first for anything involving money, stock or status transitions.
- Prefer editing an existing file over creating a parallel one.
- Don't add dependencies without asking — the pom is deliberately small.
- Don't reformat files you aren't otherwise changing.
- If a rule above blocks what's being asked, say so instead of working around it.
