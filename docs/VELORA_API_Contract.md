# VELORA API Contract

Complete reference for every REST endpoint exposed by the VELORA backend (Spring Boot 4.1 / Java 21). Generated from the controllers, DTOs, service layer and `ErrorCode` catalog as of 2026-10-03.

## Conventions

**Base URL:** all paths below are relative to the API root, e.g. `http://localhost:8080` in local dev. Every path already includes the `/api/v1` prefix.

**Authentication:** JWT Bearer tokens, stateless (`Authorization: Bearer <token>`). Three tiers:
- **Public** — no token needed.
- **Authenticated** — any signed-in user (customer or admin).
- **Admin only** — a token whose user has `ROLE_ADMIN`; enforced globally on every `/api/v1/admin/**` path.

Object-level checks (does this order/address/invoice belong to the caller?) happen in the service layer, not the URL. Accessing another customer's resource by id always returns `404 RESOURCE_NOT_FOUND` / `404 ORDER_NOT_FOUND` — never a distinguishable "not yours" response.

**Error shape** — every failure is an RFC 7807 `ProblemDetail`:
```json
{
  "type": "https://velora.com/errors/stock-unavailable",
  "title": "Conflict",
  "status": 409,
  "code": "STOCK_UNAVAILABLE",
  "detail": "Not enough stock available",
  "instance": "/api/v1/orders",
  "timestamp": "2026-08-12T10:04:11Z"
}
```
`code` is the stable contract the Angular client branches on — never parse `detail`. A `400` with `code: VALIDATION_FAILED` additionally carries:
```json
"errors": [ { "field": "email", "message": "must be a well-formed email address" } ]
```

**Applies to every endpoint in this document, not repeated per entry:**
| Status | Code | When |
|---|---|---|
| 400 | `INVALID_REQUEST_BODY` | Malformed JSON, or a field has a type the parser can't accept |
| 400 | `INVALID_PARAMETER` | A query/path parameter has the wrong type or format |
| 401 | `UNAUTHORIZED` | Missing, malformed, or expired Bearer token on a protected endpoint |
| 405 | `METHOD_NOT_ALLOWED` | The path exists but not for this HTTP verb (e.g. `GET /auth/me`, which is a `POST`). `detail` lists the supported methods |
| 403 | `FORBIDDEN` | Authenticated, but lacking the required role |
| 500 | `INTERNAL_ERROR` | Any unhandled exception |

**Codes defined but not returned today.** These exist in the `ErrorCode` catalog, but no code path raises them yet, so the client should not branch on them. What it gets instead:
| Code | What you actually get |
|---|---|
| `UNSUPPORTED_FILE_TYPE` | Image uploads (product, category, portfolio, custom-request attachments) refuse a wrong or fake type with `400 VALIDATION_FAILED`; the `detail` says why |
| `RESERVATION_EXPIRED` | Checkout never expires a held reservation: a line that can't be reserved is `409 STOCK_UNAVAILABLE` |
| `VARIANT_COMBINATION_EXISTS` | The variant generator never errors on a duplicate: `POST /admin/products/{id}/variants` silently skips an existing combination, and `/variants/preview` marks it `alreadyExists: true` with a warning |

**Pagination envelope** — every list endpoint returns `PageResponse<T>`, never a raw array or Spring's native `Page`:
```json
{
  "content": [ /* array of items */ ],
  "page": 0,
  "size": 20,
  "totalElements": 137,
  "totalPages": 7,
  "first": true,
  "last": false,
  "empty": false
}
```

**Rate limits and the client IP:** a few public endpoints are limited per client IP — login, OTP sending, `POST /custom-requests` (10/hour) and `POST /custom-requests/{id}/attachments` (30/hour). Beyond the limit they return `429 RATE_LIMITED`. The client IP is the TCP peer, **except** when that peer is a reverse proxy we run (loopback or a private/link-local address — nginx, or the ngrok agent on a developer machine): then it is taken from `X-Forwarded-For`, read from the right, so each visitor has their own limit instead of the whole site sharing one. A caller on a public address cannot choose its own IP by sending that header. The counters live in memory, per server instance, and reset on restart.

**Money & tax:** every amount is `DECIMAL(19,4)` server-side, serialized as a decimal (shown here as strings for precision, though numeric JSON is also valid — check the actual DTO). Prices are **tax-inclusive**; `taxTotal`/`netTotal` on an order are computed per line then summed, never on the total.

---

## Table of contents

1. [Identity & Authentication](#identity--authentication) — `/api/v1/auth`
2. [Catalog — Storefront](#catalog--storefront) — `/api/v1/products`, `/categories`, `/brands`, `/variants`
3. [Catalog — Admin](#catalog--admin) — `/api/v1/admin/products`, `/variants`, `/categories`, `/brands`, `/attributes`
4. [Cart & Checkout](#cart--checkout) — `/api/v1/cart`, `/api/v1/orders`
5. [Orders](#orders) — `/api/v1/me/orders`, `/api/v1/admin/orders`
6. [Customers & Addresses](#customers--addresses) — `/api/v1/me/addresses`, `/api/v1/admin/customers`
7. [Inventory — Admin](#inventory--admin) — `/api/v1/admin/inventory`
8. [Invoices](#invoices) — `/api/v1/admin/invoices`, `/api/v1/me/invoices`
9. [Shipping & Geography](#shipping--geography) — `/api/v1/shipping`, `/api/v1/geo`, `/api/v1/admin/shipping`
10. [Custom Requests](#custom-requests) — `/api/v1/custom-requests`, `/api/v1/admin/custom-requests`
11. [Portfolio](#portfolio) — `/api/v1/portfolio`, `/api/v1/admin/portfolio`
12. [Dashboard](#dashboard) — `/api/v1/admin/dashboard`
13. [Audit Log](#audit-log) — `/api/v1/admin/audit`
14. [Export](#export) — `/api/v1/admin/exports`
15. [Store Profile](#store-profile) — `/api/v1/admin/settings/store-profile`
16. [Remittance (COD Settlement)](#remittance-cod-settlement) — `/api/v1/admin/remittances`
17. [Health Check](#health-check) — `/api/v1/ping`

---

## Identity & Authentication
Base path: `/api/v1/auth`

Every endpoint below can additionally return `500 INTERNAL_ERROR` or `400 INVALID_REQUEST_BODY` per the global conventions above.

### `POST /api/v1/auth/register`
**Auth:** Public
**Summary:** Create a new customer account with a phone, an email, or both; returns issued tokens.

**Request body:**
```json
{
  "firstName": "string — required, max 100 chars",
  "lastName": "string — optional, max 100 chars",
  "phone": "string — optional, Egyptian mobile, normalized to E.164",
  "email": "string — optional, valid email format, max 255 chars",
  "password": "string — required, 8-72 chars",
  "locale": "string — optional, \"ar\" or \"en\", defaults to \"ar\""
}
```
At least one of `phone` or `email` is required — enforced in the service layer, not by Bean Validation.

Example:
```json
{
  "firstName": "محمد",
  "lastName": "أحمد",
  "phone": "01012345678",
  "email": "mohammed316@gmail.com",
  "password": "SecurePass123",
  "locale": "ar"
}
```

**Success response `201`:**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "8f14e45fceea167a5a36dedd4bea2543a938c1f",
  "tokenType": "Bearer",
  "expiresIn": 1800,
  "user": {
    "id": 102,
    "firstName": "محمد",
    "lastName": "أحمد",
    "fullName": "محمد أحمد",
    "email": "mohammed316@gmail.com",
    "phone": "+201012345678",
    "emailVerified": false,
    "phoneVerified": false,
    "locale": "ar",
    "roles": ["CUSTOMER"]
  }
}
```

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | `firstName`/`password` missing, `password` outside 8-72 chars, `email` malformed, or neither `phone` nor `email` supplied |
| 400 | INVALID_PHONE_FORMAT | `phone` supplied but is not a valid Egyptian mobile number |
| 409 | PHONE_ALREADY_EXISTS | Normalized phone already belongs to an account |
| 409 | EMAIL_ALREADY_EXISTS | Email (case-insensitive) already belongs to an account |

---

### `POST /api/v1/auth/login`
**Auth:** Public
**Summary:** Sign in with a mobile number or email in one `identifier` field.

**Request body:**
```json
{
  "identifier": "string — required, mobile number or email",
  "password": "string — required",
  "deviceInfo": "string — optional, shown in active sessions"
}
```

**Success response `200`:** same `AuthResponse` shape as `register`.

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | `identifier` or `password` missing/blank |
| 401 | INVALID_CREDENTIALS | No account matches the identifier, or the password does not match (same code either way, so the response cannot be used to enumerate accounts) |
| 403 | ACCOUNT_SUSPENDED | Credentials are correct but the account is inactive |

---

### `POST /api/v1/auth/refresh`
**Auth:** Public
**Summary:** Exchange a refresh token for a new access token; the presented refresh token is revoked and replaced (rotation).

**Request body:**
```json
{ "refreshToken": "string — required" }
```

**Success response `200`:** `AuthResponse` with a freshly issued access + refresh token pair.

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | `refreshToken` missing/blank |
| 401 | TOKEN_INVALID | No stored token matches the hash (unknown, malformed, or already-rotated token) |
| 401 | TOKEN_EXPIRED | Token found but expired, revoked, or already used |
| 403 | ACCOUNT_SUSPENDED | Token is valid but the owning account has since been suspended |

---

### `POST /api/v1/auth/logout`
**Auth:** Public
**Summary:** Sign out on this device by revoking the presented refresh token. Idempotent — an unknown token is silently ignored.

**Request body:**
```json
{ "refreshToken": "string — required" }
```

**Success response `200`:**
```json
{ "message": "Signed out" }
```

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | `refreshToken` missing/blank |

---

### `POST /api/v1/auth/logout-all`
**Auth:** Authenticated
**Summary:** Revoke every refresh token belonging to the caller, signing out all devices.

**Success response `200`:**
```json
{ "message": "Signed out on all devices" }
```

**Error responses:** only `401 UNAUTHORIZED` (global conventions).

---

### `POST /api/v1/auth/me`
**Auth:** Authenticated
**Summary:** Return the caller's identity as encoded in the current access token. Note: this is a `POST`, not a `GET`, and `firstName`/`lastName`/`fullName` are always `null` here since it's built from JWT claims, not a DB lookup.

**Success response `200`:**
```json
{
  "id": 102,
  "firstName": null,
  "lastName": null,
  "fullName": null,
  "email": "mohammed316@gmail.com",
  "phone": "+201012345678",
  "emailVerified": false,
  "phoneVerified": false,
  "locale": "ar",
  "roles": ["CUSTOMER"]
}
```

**Error responses:** only `401 UNAUTHORIZED` (global conventions).

---

### `POST /api/v1/auth/otp/send`
**Auth:** Public
**Summary:** Issue a one-time verification code by SMS or email for the given purpose. The code itself is never returned in the response.

**Request body:**
```json
{
  "destination": "string — required, mobile number or email",
  "purpose": "string — required, one of REGISTER | LOGIN | RESET | CHANGE_PHONE"
}
```

**Success response `200`:**
```json
{ "message": "A verification code has been sent" }
```

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | `destination` missing/blank, or `purpose` missing/invalid enum value |
| 400 | INVALID_PHONE_FORMAT | `destination` does not contain "@" and is not a valid Egyptian mobile number |
| 429 | OTP_TOO_MANY_ATTEMPTS | 5 or more codes already requested for this destination in the last hour |

---

### `POST /api/v1/auth/otp/verify`
**Auth:** Public
**Summary:** Verify a one-time code for the given destination and purpose. On success, marks the phone or email as verified on the matching user account, if one exists.

**Request body:**
```json
{
  "destination": "string — required, mobile number or email",
  "code": "string — required, exactly 6 digits",
  "purpose": "string — required, one of REGISTER | LOGIN | RESET | CHANGE_PHONE"
}
```

**Success response `200`:**
```json
{ "message": "Verified" }
```

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | `destination`/`code`/`purpose` missing, or `code` is not 6 digits |
| 400 | INVALID_PHONE_FORMAT | `destination` does not contain "@" and is not a valid Egyptian mobile number |
| 400 | OTP_INVALID | No outstanding unconsumed code for this destination/purpose, or the submitted code does not match |
| 400 | OTP_EXPIRED | The matching code exists but its 10-minute expiry has passed |
| 429 | OTP_TOO_MANY_ATTEMPTS | The matching code has exhausted its allowed verification attempts |

---

### `POST /api/v1/auth/password/forgot`
**Auth:** Public
**Summary:** Start a password reset. Always reports success, even for an unknown identifier, so the endpoint cannot be used to discover which accounts exist.

**Request body:**
```json
{ "identifier": "string — required, mobile number or email" }
```

**Success response `200`:**
```json
{ "message": "If the account exists, reset instructions have been sent" }
```

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | `identifier` missing/blank |

---

### `POST /api/v1/auth/password/reset`
**Auth:** Public
**Summary:** Complete a password reset using the token issued via `/password/forgot`. Revokes every existing session on success.

**Request body:**
```json
{
  "token": "string — required, raw reset token from the reset link",
  "newPassword": "string — required, 8-72 chars"
}
```

**Success response `200`:**
```json
{ "message": "Password updated. Please sign in again" }
```

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | `token` missing/blank, or `newPassword` missing/outside 8-72 chars |
| 401 | TOKEN_INVALID | No stored reset token matches the hash |
| 401 | TOKEN_EXPIRED | Token found but expired, already used, or superseded by a newer reset request |

---

## Catalog — Storefront
Base path: `/api/v1/products`, `/api/v1/categories`, `/api/v1/brands`, `/api/v1/variants`

All endpoints in this section are public (no auth). Response locale is resolved from the `Accept-Language` header (`ar` or `en`, default `ar`) — there is no locale query parameter.

### ProductController

#### `GET /api/v1/products`
**Summary:** Search and filter products. Every filter is optional and they compose (Specification pattern). Search text is Arabic-normalized (`ساعه ذهبى` matches `ساعة ذهبي`).

**Query params:**
| Name | Type | Meaning |
|---|---|---|
| `q` | string | Arabic-normalized full-text search |
| `categoryId` | long | Restrict to one category |
| `brandIds` | long[] | Include products of any of these brands |
| `minPrice` / `maxPrice` | decimal | Tax-inclusive price range |
| `attributeValueIds` | long[] | Match any of these attribute values (e.g. colour=gold OR silver) |
| `inStockOnly` | boolean | Only products with available stock |
| `featured` | boolean | Only featured products |
| `newArrival` | boolean | Only new-arrival products |
| `fulfillmentType` | enum | `READY_MADE` \| `MADE_TO_ORDER` \| `CUSTOM_WORK` — how the product is sold. Omit for all types. An unknown value returns `400 VALIDATION_FAILED` |
| `sort` | string | `newest` (default), `price_asc`, `price_desc`, `name` |
| `page` / `size` | int | Pagination; `size` default 24, capped at 60 |

**Success response `200`:**
```json
{
  "content": [
    {
      "id": 101,
      "slug": "classic-gold-watch",
      "name": "الساعة الكلاسيكية الذهبية",
      "shortDescription": "ساعة سويسرية الصنع بسوار ذهبي",
      "brandName": "Velora Timepieces",
      "categorySlug": "watches",
      "imageUrl": "https://cdn.velora.com/products/101/main.jpg",
      "imageAlt": "Classic Gold Watch",
      "hoverImageUrl": "https://cdn.velora.com/products/101/angle.jpg",
      "minPrice": "4599.0000",
      "maxPrice": "5299.0000",
      "compareAtPrice": "5999.0000",
      "discountPercent": 12,
      "inStock": true,
      "availableQty": 7,
      "featured": true,
      "newArrival": false,
      "fulfillmentType": "READY_MADE",
      "shippingSizeClass": "MEDIUM",
      "requiresAssembly": true,
      "assemblyFee": 300.0000
    }
  ],
  "page": 0, "size": 24, "totalElements": 137, "totalPages": 6,
  "first": true, "last": false, "empty": false
}
```

**Error responses:** `400 VALIDATION_FAILED` when a filter param cannot be bound — an unknown `fulfillmentType` (e.g. `BANANA`) or a non-numeric `categoryId`. `ProductFilterRequest` is bound as a whole object, so Spring reports a bad field as a binding failure, which the global handler maps to `VALIDATION_FAILED` with an `errors` array naming the field. It is not `INVALID_PARAMETER` and never a `500`.

---

#### `GET /api/v1/products/{slug}`
**Summary:** Product detail by slug. Returns the full variant matrix (all colours/sizes, prices, stock, images) in one call.

**Success response `200`:**
```json
{
  "id": 101,
  "slug": "classic-gold-watch",
  "name": "الساعة الكلاسيكية الذهبية",
  "shortDescription": "ساعة سويسرية الصنع بسوار ذهبي",
  "description": "حركة سويسرية أوتوماتيكية، مقاومة للماء حتى 50 متر.",
  "brand": { "id": 5, "slug": "velora-timepieces", "name": "Velora Timepieces", "logoUrl": "https://cdn.velora.com/brands/5/logo.png" },
  "categoryPath": [
    { "id": 1, "slug": "watches", "name": "ساعات" },
    { "id": 4, "slug": "mens-watches", "name": "ساعات رجالية" }
  ],
  "priceRange": { "min": "4599.0000", "max": "5299.0000" },
  "variantOptions": [
    {
      "attributeId": 10, "code": "color", "name": "اللون",
      "values": [
        { "id": 201, "code": "gold", "name": "ذهبي", "hexColor": "#C9A227", "productCount": null },
        { "id": 202, "code": "silver", "name": "فضي", "hexColor": "#C0C0C0", "productCount": null }
      ]
    }
  ],
  "variants": [
    {
      "id": 5001, "sku": "WCH-CGW-GLD-42", "summary": "ذهبي / 42 مم",
      "price": "4599.0000", "compareAtPrice": "5199.0000", "discountPercent": 12,
      "attributeValueIds": [201, 301], "availableQty": 7, "inStock": true,
      "images": [{ "id": 9001, "url": "https://cdn.velora.com/variants/5001/1.jpg", "thumbUrl": "https://cdn.velora.com/variants/5001/1_thumb.jpg", "alt": "Classic Gold Watch - gold", "main": true }]
    }
  ],
  "specifications": [{ "code": "movement", "name": "الحركة", "value": "أوتوماتيكي سويسري" }],
  "images": [{ "id": 9000, "url": "https://cdn.velora.com/products/101/main.jpg", "thumbUrl": "https://cdn.velora.com/products/101/main_thumb.jpg", "alt": "Classic Gold Watch", "main": true }],
  "inStock": true, "featured": true, "newArrival": false,
  "fulfillmentType": "READY_MADE", "shippingSizeClass": "MEDIUM",
  "requiresAssembly": true, "assemblyFee": 300.0000,
  "seo": { "metaTitle": "الساعة الكلاسيكية الذهبية | Velora", "metaDescription": "تسوق الساعة الكلاسيكية الذهبية.", "canonicalPath": "/products/classic-gold-watch" }
}
```

**Error responses:**
| Status | Code | When |
|---|---|---|
| 404 | PRODUCT_NOT_FOUND | No product with this slug, archived, or not `ACTIVE` — a draft looks identical to nonexistent |

---

#### `GET /api/v1/products/{id}/related`
**Summary:** Related products — same category, excludes the product itself. Fixed internal limit of 8, not paginated.

**Success response `200`:** array of `ProductSummaryResponse` (same shape as the search results' `content` items).

**Error responses:** `404 PRODUCT_NOT_FOUND` — no non-archived product with this id (does not require `ACTIVE` status, unlike `GET /{slug}`).

---

#### `GET /api/v1/products/featured`
**Summary:** Featured products for homepage merchandising (`featured=true`, sorted `newest`).

**Query params:** `page`, `size` (default 12, capped 60).

**Success response `200`:** same envelope as `GET /api/v1/products`.

---

#### `GET /api/v1/products/new-arrivals`
**Summary:** New arrivals (`newArrival=true`, sorted `newest`).

**Query params:** `page`, `size` (default 12, capped 60).

**Success response `200`:** same envelope as `GET /api/v1/products`.

---

### VariantController

#### `GET /api/v1/variants/{id}/availability`
**Summary:** Live availability for a single variant — call when the customer changes colour/size, and again right before add-to-cart, since the product page's figures can be a few seconds stale.

**Path params:** `id` — variant id (never the product id).

**Success response `200`:**
```json
{
  "variantId": 5001,
  "sku": "WCH-CGW-GLD-42",
  "price": "4599.0000",
  "availableQty": 7,
  "inStock": true,
  "sellable": true
}
```
`availableQty = qty_on_hand - qty_reserved`; `inStock = availableQty > 0`; `sellable` = variant is `ACTIVE`, not archived, and in stock — gate add-to-cart on `sellable`, not `inStock` alone.

**Error responses:** `404 VARIANT_NOT_FOUND`.

---

### BrandController

#### `GET /api/v1/brands`
**Summary:** List active brands, Arabic name ascending. Not paginated.

**Success response `200`:**
```json
[
  { "id": 5, "slug": "velora-timepieces", "name": "Velora Timepieces", "logoUrl": "https://cdn.velora.com/brands/5/logo.png" },
  { "id": 8, "slug": "nour-perfumes", "name": "نور للعطور", "logoUrl": "https://cdn.velora.com/brands/8/logo.png" }
]
```

---

### CategoryController

#### `GET /api/v1/categories/tree`
**Summary:** Full category tree for the header mega-menu, built recursively from one flat query (max depth guard: 5 levels).

`imageUrl`/`bannerUrl` are built at read time from the uploaded image's storage key (see `POST /admin/categories/{id}/images` below) — `null` when no image of that type has been uploaded yet.

**Success response `200`:**
```json
[
  {
    "id": 1, "slug": "watches", "name": "ساعات",
    "imageUrl": "http://localhost:8080/uploads/categories/2026/09/3f2a1b9c8e7d4a5b.jpg", "bannerUrl": "http://localhost:8080/uploads/categories/2026/09/9e1d4a5b3f2a1b9c.jpg",
    "displayOrder": 1,
    "children": [
      { "id": 4, "slug": "mens-watches", "name": "ساعات رجالية", "imageUrl": "http://localhost:8080/uploads/categories/2026/09/a5b3f2a1b9c8e7d4.jpg", "bannerUrl": null, "displayOrder": 1, "children": [] }
    ]
  }
]
```

---

#### `GET /api/v1/categories/{slug}`
**Summary:** Category landing page: name/description, banner, children, breadcrumb.

**Success response `200`:**
```json
{
  "id": 1, "slug": "watches", "name": "ساعات",
  "description": "تشكيلة واسعة من الساعات الفاخرة.",
  "imageUrl": "http://localhost:8080/uploads/categories/2026/09/3f2a1b9c8e7d4a5b.jpg", "bannerUrl": "http://localhost:8080/uploads/categories/2026/09/9e1d4a5b3f2a1b9c.jpg",
  "children": [ { "id": 4, "slug": "mens-watches", "name": "ساعات رجالية", "imageUrl": "http://localhost:8080/uploads/categories/2026/09/a5b3f2a1b9c8e7d4.jpg", "bannerUrl": null, "displayOrder": 1, "children": [] } ],
  "breadcrumb": [ { "id": 1, "slug": "watches", "name": "ساعات" } ],
  "metaTitle": "ساعات فاخرة | Velora",
  "metaDescription": "تسوق أحدث تشكيلة من الساعات الفاخرة."
}
```

**Error responses:** `404 RESOURCE_NOT_FOUND` — note the service uses the generic code here, not the more specific `CATEGORY_NOT_FOUND` that exists in the catalog.

---

#### `GET /api/v1/categories/filters`
**Summary:** Filter facets (brands + filterable attribute values) for the sidebar, optionally scoped by `categoryId`. Only attribute facets are actually scoped — the brand list is always global. `minPrice`/`maxPrice` in the response are currently always `null` (not yet implemented server-side).

**Query params:** `categoryId` (optional).

**Success response `200`:**
```json
{
  "brands": [ { "id": 5, "slug": "velora-timepieces", "name": "Velora Timepieces", "logoUrl": "https://cdn.velora.com/brands/5/logo.png" } ],
  "attributes": [
    {
      "attributeId": 10, "code": "color", "name": "اللون",
      "values": [
        { "id": 201, "code": "gold", "name": "ذهبي", "hexColor": "#C9A227", "productCount": 34 },
        { "id": 202, "code": "silver", "name": "فضي", "hexColor": "#C0C0C0", "productCount": 21 }
      ]
    }
  ],
  "minPrice": null, "maxPrice": null
}
```

---

## Catalog — Admin
Base path: `/api/v1/admin/products`, `/api/v1/admin/variants`, `/api/v1/admin/categories`, `/api/v1/admin/brands`, `/api/v1/admin/attributes`

**Auth:** Admin only, on every endpoint below. A unique-constraint race caught at the DB level (two admins saving the same slug/SKU/code at once) surfaces as `409 SLUG_ALREADY_EXISTS` / `409 SKU_ALREADY_EXISTS` / `409 BARCODE_ALREADY_EXISTS` / `409 ATTRIBUTE_CODE_EXISTS` / `409 ATTRIBUTE_VALUE_CODE_EXISTS`, or a generic `409 DUPLICATE_VALUE` / `409 REFERENCED_BY_OTHER_RECORDS` — not repeated per endpoint.

### AdminProductController — `/api/v1/admin/products`

#### `GET /api/v1/admin/products`
**Summary:** List all products, including drafts and archived, paginated.

**Success response `200`:**
```json
{
  "content": [
    {
      "id": 501, "slug": "classic-gold-watch", "status": "ACTIVE",
      "nameAr": "ساعة كلاسيك ذهبية", "nameEn": "Classic Gold Watch",
      "categoryId": 12, "categoryName": "ساعات رجالية", "brandId": 3, "brandName": "فيلورا",
      "featured": false, "newArrival": true,
      "fulfillmentType": "READY_MADE", "shippingSizeClass": "MEDIUM",
      "requiresAssembly": true, "assemblyFee": 300.0000,
      "variantCount": 2, "imageCount": 4,
      "minPrice": 2200.00, "maxPrice": 2600.00, "availableQty": 56,
      "publishedAt": "2026-07-01T09:00:00Z", "archivedAt": null,
      "createdAt": "2026-06-28T14:22:10Z", "updatedAt": "2026-08-10T11:05:44Z",
      "warnings": []
    }
  ],
  "page": 0, "size": 20, "totalElements": 137, "totalPages": 7, "first": true, "last": false, "empty": false
}
```

---

#### `GET /api/v1/admin/products/{id}`
**Summary:** Get one product (any status).

**Success response `200`:** single `ProductAdminResponse` object (shape above).

**Error responses:** `404 PRODUCT_NOT_FOUND`.

---

#### `POST /api/v1/admin/products`
**Summary:** Create a product as `DRAFT`. Slug and Arabic `search_text` are server-generated; variants are added separately.

**Request body:**
```json
{
  "categoryId": 12,
  "brandId": 3,
  "slug": null,
  "translations": [
    { "locale": "ar", "name": "ساعة كلاسيك ذهبية", "shortDescription": "ساعة أنيقة", "description": null, "metaTitle": null, "metaDescription": null },
    { "locale": "en", "name": "Classic Gold Watch", "shortDescription": "An elegant timepiece", "description": null, "metaTitle": null, "metaDescription": null }
  ],
  "featured": false,
  "newArrival": true,
  "specifications": [ { "attributeId": 9, "attributeValueId": null, "valueText": "Automatic" } ],
  "fulfillmentType": "READY_MADE",
  "shippingSizeClass": "MEDIUM",
  "requiresAssembly": true,
  "assemblyFee": 300
}
```
`categoryId` required. `translations` required, non-empty, each `locale` one of `ar`|`en`, `name` required (max 255).

`fulfillmentType` — `READY_MADE` \| `MADE_TO_ORDER` \| `CUSTOM_WORK`; how the product is sold, **not** what it is (watch / wallet / perfume is the category). Optional on create, defaults to `READY_MADE`. Only `READY_MADE` products can be added to a cart.
`shippingSizeClass` — `SMALL` \| `MEDIUM` \| `LARGE`. **Required when `fulfillmentType` is `READY_MADE`** (including when it is omitted and defaulted); optional for the other two types. Missing → `400 VALIDATION_FAILED`.
`requiresAssembly` — boolean, optional, defaults to `false`: the piece has to be assembled on delivery.
`assemblyFee` — decimal `>= 0`, optional, defaults to `0`: the fee **per piece**, **tax-inclusive** like every price. It is only charged while `requiresAssembly` is `true`; a stored fee on a product with `requiresAssembly: false` is ignored at the cart, quote and checkout. A negative value fails `400 VALIDATION_FAILED`. Fees are `0` for now — nothing is charged until a fee is set.

**Success response `201`:** `ProductAdminResponse`, `status: "DRAFT"`, `variantCount: 0`, `warnings` including `"No variants — this product cannot be published or bought"` and `"No images"`.

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | Missing/invalid fields, or no `slug` derivable (non-Latin names only, no `slug` override) |
| 404 | CATEGORY_NOT_FOUND | `categoryId` does not exist |
| 404 | BRAND_NOT_FOUND | `brandId` given but does not exist |

---

#### `PUT /api/v1/admin/products/{id}`
**Summary:** Update category, brand, slug, translations and specifications. `translations`/`specifications`, if present, fully replace the existing set.

**Request body:** same shape as create (minus `slug` required — only changes if different).

`requiresAssembly` and `assemblyFee` follow the same rule — **omit or send `null` to leave unchanged**; to switch assembly off send `requiresAssembly: false` (and `assemblyFee: 0` to clear the fee).

`fulfillmentType` and `shippingSizeClass`: **omit or send `null` to leave the stored value unchanged** (there is no way to clear a value with `null`). After applying them, a `READY_MADE` product must have a `shippingSizeClass` or the request fails with `400 VALIDATION_FAILED`. Products that existed before this field was added are `READY_MADE` with `shippingSizeClass: null`, so the first edit of each must supply a size.

**Success response `200`:** `ProductAdminResponse`.

**Error responses:** `404 PRODUCT_NOT_FOUND`, `404 CATEGORY_NOT_FOUND`, `404 BRAND_NOT_FOUND`, `400 VALIDATION_FAILED`.

---

#### `PATCH /api/v1/admin/products/{id}/publish`
**Summary:** Move a product to `ACTIVE`. Always requires an Arabic name. A `READY_MADE` product also needs at least one variant; `MADE_TO_ORDER` and `CUSTOM_WORK` products publish without variants.

**Success response `200`:** `ProductAdminResponse`, `status: "ACTIVE"`, `publishedAt` set.

**Error responses:**
| Status | Code | When |
|---|---|---|
| 404 | PRODUCT_NOT_FOUND | No product with this id |
| 409 | PRODUCT_HAS_NO_VARIANTS | `READY_MADE` product has zero variants (never raised for the other fulfillment types) |
| 409 | PRODUCT_MISSING_ARABIC_NAME | No `ar` translation on the product |

---

#### `PATCH /api/v1/admin/products/{id}/unpublish`
**Summary:** Move a product back to `DRAFT`.

**Success response `200`:** `ProductAdminResponse`, `status: "DRAFT"`.

**Error responses:** `404 PRODUCT_NOT_FOUND`.

---

#### `PATCH /api/v1/admin/products/{id}/archive`
**Summary:** Soft delete. Order lines keep referencing this product; the row is never removed.

**Success response `200`:** `ProductAdminResponse`, `status: "ARCHIVED"`, `archivedAt` set.

**Error responses:** `404 PRODUCT_NOT_FOUND`.

---

#### `POST /api/v1/admin/products/{id}/duplicate`
**Summary:** Copy a product's content (translations, category, brand, `fulfillmentType`, `shippingSizeClass`) as a new `DRAFT`. SKUs, stock and images are not copied.

**Success response `201`:** `ProductAdminResponse` for the new product (name suffixed `" (copy)"`, fresh unique slug).

**Error responses:** `404 PRODUCT_NOT_FOUND` (source).

---

#### `GET /api/v1/admin/products/{id}/images`
**Summary:** List a product's images (max 20, not paginated).

**Success response `200`:**
```json
[
  {
    "id": 901, "key": "products/2026/08/3f2a1b9c8e7d4a5b.jpg",
    "url": "http://localhost:8080/uploads/products/2026/08/3f2a1b9c8e7d4a5b.jpg", "thumbUrl": null,
    "altTextAr": "ساعة كلاسيك ذهبية - منظر أمامي", "altTextEn": "Classic Gold Watch - front view",
    "main": true, "displayOrder": 0, "variantId": null
  }
]
```

**Error responses:** `404 PRODUCT_NOT_FOUND`.

---

#### `POST /api/v1/admin/products/{id}/images`
**Summary:** Upload a product image. `multipart/form-data`: part `file` (JPEG/PNG/WebP/AVIF, up to 5 MB) and optional query param `variantId`. The first image uploaded becomes `main` automatically. **The file's real bytes must be an image of an allowed type** — a file that only claims to be one (HTML renamed `.png`, a GIF, a PDF) is refused with `400 VALIDATION_FAILED`; the stored extension comes from the detected type, never from the filename.

**Path/Query params:** `id` (path), `file` (multipart part, required), `variantId` (query, optional).

**Success response `201`:** `ImageResponse` (shape above).

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | File missing/empty, over 5 MB (app-level check), unsupported content type, or product already has 20 images |
| 404 | PRODUCT_NOT_FOUND | No product with this id |
| 404 | VARIANT_NOT_FOUND | `variantId` given but does not exist |
| 413 | FILE_TOO_LARGE | Multipart request exceeds the server's configured upload limit |

---

#### `PUT /api/v1/admin/products/{id}/images/{imageId}`
**Summary:** Update image metadata — alt text, display order, main flag, or reattach to a different variant. Setting `main: true` unsets it on every other image of the product.

**Request body:**
```json
{ "variantId": 777, "altTextAr": "منظر جانبي", "altTextEn": "Side view", "main": false, "displayOrder": 1 }
```
All fields optional/nullable — only supplied fields are applied.

**Success response `200`:** updated `ImageResponse`.

**Error responses:** `404 PRODUCT_NOT_FOUND`, `404 RESOURCE_NOT_FOUND` (image doesn't belong to this product), `404 VARIANT_NOT_FOUND`.

---

#### `DELETE /api/v1/admin/products/{id}/images/{imageId}`
**Summary:** Delete an image. If it was the main image, the next remaining image is promoted to main.

**Success response `204`:** no body.

**Error responses:** `404 PRODUCT_NOT_FOUND`, `404 RESOURCE_NOT_FOUND`.

---

### AdminVariantController — `/api/v1/admin`

#### `GET /api/v1/admin/products/{productId}/variants`
**Summary:** List a product's non-archived variants, ordered by position. Plain array, not paginated.

**Success response `200`:**
```json
[
  {
    "id": 2001, "productId": 501, "sku": "VLR-CLASSIC-GLD-42", "barcode": "6221234567890",
    "summary": "ذهبي / 42 مم", "price": 2400.00, "compareAtPrice": 2800.00, "costPrice": 1500.00,
    "taxRate": 0.1400, "weightGrams": 120, "status": "ACTIVE", "attributeValueIds": [15, 22],
    "qtyOnHand": 30, "qtyReserved": 4, "qtyAvailable": 26, "minStockLevel": 3, "lowStock": false
  }
]
```
An unknown `productId` returns an empty array rather than a 404.

---

#### `POST /api/v1/admin/products/{productId}/variants/preview`
**Summary:** Generate every combination of the selected attribute values, without saving. Review and prune, then `POST` the survivors to `/variants`.

**Request body:**
```json
{ "selections": [ { "attributeId": 8, "valueIds": [15, 16] }, { "attributeId": 9, "valueIds": [22, 23, 24] } ] }
```

**Success response `200`:**
```json
{
  "totalCombinations": 6, "existingCount": 1,
  "combinations": [ { "suggestedSku": "VLR-CLASSIC-GLD-42", "summary": "ذهبي / 42 مم", "attributeValueIds": [15, 22], "valueNames": ["Gold", "42mm"], "alreadyExists": false } ],
  "warnings": ["1 combination(s) already exist and will be skipped on save."]
}
```
Two independent warnings can appear: `"The selection produces %d combinations. Showing the first 200 — select fewer values, or add the rest individually."` when the full cartesian product exceeds 200, and `"%d combination(s) already exist and will be skipped on save."` when any generated combination matches an existing variant.

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | A selection's `valueIds` don't belong to the referenced attribute |
| 404 | PRODUCT_NOT_FOUND | No product with this id |
| 404 | ATTRIBUTE_NOT_FOUND | `attributeId` does not exist |
| 400 | ATTRIBUTE_NOT_VARIANT_DEFINING | Attribute is informational (`variantDefining: false`) and cannot generate variants |

---

#### `POST /api/v1/admin/products/{productId}/variants`
**Summary:** Create and/or update variants in one call. `id: null` creates, `id` set updates. Combinations already present among create-items are silently skipped. Every created variant gets an inventory row (opening stock writes a `PURCHASE_RECEIVED` stock movement); a price change on update is audit-logged.

**Request body:**
```json
{
  "variants": [
    {
      "id": null, "sku": "VLR-CLASSIC-GLD-42", "barcode": "6221234567890",
      "price": 2400.00, "compareAtPrice": 2800.00, "costPrice": 1500.00, "taxRate": 0.1400,
      "weightGrams": 120, "attributeValueIds": [15, 22], "initialStock": 30, "minStockLevel": 3
    }
  ]
}
```
`price` required, `>= 0`. `weightGrams`/`initialStock`/`minStockLevel`: `>= 0` if present. `taxRate` defaults to `0.1400` on create. `sku` required on create; unique (case-insensitive, upper-cased on save).

**Success response `200`:** array of `VariantAdminResponse` — only variants actually created/updated (skipped duplicates omitted).

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | `sku` missing/blank on a create item |
| 404 | PRODUCT_NOT_FOUND | No product with this id |
| 404 | VARIANT_NOT_FOUND | `id` set on an item but does not exist |
| 404 | ATTRIBUTE_VALUE_NOT_FOUND | An id in `attributeValueIds` does not exist |
| 409 | SKU_ALREADY_EXISTS | `sku` already used by another variant |
| 409 | BARCODE_ALREADY_EXISTS | `barcode` already used by another variant |

---

#### `DELETE /api/v1/admin/variants/{variantId}`
**Summary:** Archive a variant (soft delete — SKU never reused).

**Success response `204`:** no body.

**Error responses:** `404 VARIANT_NOT_FOUND`.

---

### AdminTaxonomyController — `/api/v1/admin` (categories, brands, attributes)

#### `GET /api/v1/admin/categories`
**Summary:** Full category tree, including inactive categories — same shape as the storefront `GET /api/v1/categories/tree`, but staff need to see what was turned off in order to turn it back on. Response language follows `Accept-Language` (`ar` default), same as the storefront tree.

**Success response `200`:**
```json
[
  {
    "id": 1, "slug": "watches",
    "translations": [ { "locale": "ar", "name": "ساعات", "description": null, "metaTitle": null, "metaDescription": null } ],
    "imageUrl": "http://localhost:8080/uploads/categories/2026/09/3f2a1b9c8e7d4a5b.jpg", "bannerUrl": "http://localhost:8080/uploads/categories/2026/09/9e1d4a5b3f2a1b9c.jpg",
    "displayOrder": 1, "active": true, "productCount": 42,
    "children": [
      { "id": 4, "slug": "discontinued-line", "translations": [ { "locale": "ar", "name": "خط موقوف", "description": null, "metaTitle": null, "metaDescription": null } ], "imageUrl": null, "bannerUrl": null, "displayOrder": 1, "active": false, "productCount": 0, "children": [] }
    ]
  }
]
```
Unlike the storefront tree, every node carries `active` and the full `translations[]`, since `PUT /api/v1/admin/categories/{id}` replaces them wholesale.

**Error responses:** none beyond the global conventions.

---

#### `POST /api/v1/admin/categories`
**Summary:** Create a category. Slug is auto-generated (de-duplicated with a numeric suffix) from `slug` or the first translation's name. Images are not set here — upload them afterwards with `POST /api/v1/admin/categories/{id}/images`.

**Request body:**
```json
{
  "parentId": null, "slug": "mens-watches",
  "translations": [
    { "locale": "ar", "name": "ساعات رجالية", "shortDescription": null, "description": null, "metaTitle": null, "metaDescription": null },
    { "locale": "en", "name": "Men's Watches", "shortDescription": null, "description": null, "metaTitle": null, "metaDescription": null }
  ],
  "displayOrder": 1, "active": true
}
```

**Success response `201`:**
```json
{ "id": 12 }
```

**Error responses:** `404 CATEGORY_NOT_FOUND` (`parentId` doesn't exist), `409 SLUG_ALREADY_EXISTS` (rare dedup race).

---

#### `PUT /api/v1/admin/categories/{id}`
**Summary:** Update a category — re-parent, rename, re-slug, toggle active.

**Request body:** same shape as create.

**Success response `200`:**
```json
{ "id": 12 }
```

**Error responses:** `404 CATEGORY_NOT_FOUND`, `400 CATEGORY_CYCLE` (`parentId` equals own id), `409 SLUG_ALREADY_EXISTS`.

> `409 CATEGORY_NOT_EMPTY` exists in the error catalog for blocking removal of a non-empty category, but no delete endpoint is exposed here — categories are only created/updated.

---

#### `POST /api/v1/admin/categories/{id}/images`
**Summary:** Upload (or replace) the `CARD` or `BANNER` image for a category. Each type holds exactly one image — uploading again with the same `imageType` deletes the old file and replaces it, it does not add a second image. Multipart, JPEG/PNG/WebP/AVIF up to 5 MB, same limits as product images. The same real-bytes check applies.

**Request:** `multipart/form-data`
- `file` — the image
- `imageType` (query param) — `CARD` or `BANNER`

```
POST /api/v1/admin/categories/12/images?imageType=CARD
Content-Type: multipart/form-data; boundary=...
```

**Success response `200`:** the full updated category (same shape as one node of `GET /api/v1/admin/categories`), so the admin UI can show the new image immediately without a second call.
```json
{
  "id": 12, "slug": "mens-watches",
  "translations": [ "..." ],
  "imageUrl": "http://localhost:8080/uploads/categories/2026/09/a5b3f2a1b9c8e7d4.jpg", "bannerUrl": null,
  "displayOrder": 1, "active": true, "productCount": 0, "children": []
}
```

**Error responses:** `404 CATEGORY_NOT_FOUND`, `400 VALIDATION_FAILED` (file too large, empty, or not an accepted image type), `400 INVALID_PARAMETER` (`imageType` is neither `CARD` nor `BANNER`).

---

#### `DELETE /api/v1/admin/categories/{id}/images/{imageType}`
**Summary:** Removes the `CARD` or `BANNER` image — the column reverts to `null` (so the storefront hides the element instead of showing a broken image) and the stored file is deleted.

**Success response:** `204 No Content`.

**Error responses:** `404 CATEGORY_NOT_FOUND`, `400 INVALID_PARAMETER` (`imageType` is neither `CARD` nor `BANNER`).

> Out of scope by design: the storefront's "New Arrivals" promo image is a marketing banner, not a category attribute — it changes with campaigns and stays in the front end's `assets` folder until a dedicated promotional-banner system exists.

---

#### `GET /api/v1/admin/brands`
**Summary:** All brands, including inactive ones — same shape as the storefront `GET /api/v1/brands`. Response language follows `Accept-Language` (`ar` default).

**Success response `200`:**
```json
[
  { "id": 5, "slug": "velora-timepieces", "name": "Velora Timepieces", "logoUrl": "https://cdn.velora.com/brands/5/logo.png" },
  { "id": 9, "slug": "retired-brand", "name": "علامة موقوفة", "logoUrl": null }
]
```

**Error responses:** none beyond the global conventions.

---

#### `POST /api/v1/admin/brands`
**Summary:** Create a brand.

**Request body:**
```json
{ "slug": "velora", "nameAr": "فيلورا", "nameEn": "Velora", "logoUrl": "https://cdn.velora.com/brands/velora.png", "active": true }
```
`nameAr`/`nameEn` required (max 150 each).

**Success response `201`:**
```json
{ "id": 3 }
```

**Error responses:** `400 VALIDATION_FAILED` (no slug derivable), `409 BRAND_SLUG_EXISTS`.

---

#### `PUT /api/v1/admin/brands/{id}`
**Summary:** Update a brand.

**Request body:** same shape as create.

**Success response `200`:**
```json
{ "id": 3 }
```

**Error responses:** `404 BRAND_NOT_FOUND`, `400 VALIDATION_FAILED`, `409 BRAND_SLUG_EXISTS`.

---

#### `GET /api/v1/admin/attributes`
**Summary:** Every attribute, with its values and both locale names. Not paginated — the catalog only has a handful of attributes. Unlike the storefront filter facets (which only return `filterable: true` attributes), this includes specification-only attributes too (movement, water resistance) — staff need to see and edit them even though they never appear as a public filter. Each attribute's `id` and each value's `id` are meant to be used as-is as `attributeId` / `valueIds` in `POST /admin/products/{productId}/variants/preview` — no lookup step in between.

**Query params:**
| Name | Type | Required | Notes |
|---|---|---|---|
| `variantDefining` | boolean | No | Omit for all attributes; `true` for SKU-generating attributes only, `false` for specification-only attributes only |

**Success response `200`:**
```json
[
  {
    "id": 8, "code": "COLOR", "dataType": "LIST", "variantDefining": true, "filterable": true, "displayOrder": 1,
    "nameAr": "اللون", "nameEn": "Color",
    "values": [
      { "id": 201, "code": "GOLD", "hexColor": "#C9A227", "displayOrder": 1, "nameAr": "ذهبي", "nameEn": "Gold" },
      { "id": 202, "code": "SILVER", "hexColor": "#C0C0C0", "displayOrder": 2, "nameAr": "فضي", "nameEn": "Silver" }
    ]
  },
  {
    "id": 15, "code": "MOVEMENT", "dataType": "TEXT", "variantDefining": false, "filterable": false, "displayOrder": 2,
    "nameAr": "الحركة", "nameEn": "Movement", "values": []
  }
]
```

**Error responses:** none beyond the global conventions.

---

#### `POST /api/v1/admin/attributes`
**Summary:** Create an attribute, optionally with its allowed values in the same call. `variantDefining: true` generates SKUs (colour, size); `false` is specification-only (movement, water resistance) — this flag can never be changed on a later update, even if no variant actually uses the attribute yet; create a new attribute instead.

**Request body:**
```json
{
  "code": "COLOR", "dataType": "LIST", "variantDefining": true, "filterable": true, "displayOrder": 1,
  "translations": [ { "locale": "ar", "name": "اللون" }, { "locale": "en", "name": "Color" } ],
  "values": [
    {
      "id": null, "code": "GOLD", "hexColor": "#C9A227", "displayOrder": 1,
      "translations": [ { "locale": "ar", "name": "ذهبي" }, { "locale": "en", "name": "Gold" } ]
    }
  ]
}
```
`code` required, pattern `[A-Z0-9_]+`. `dataType` one of `LIST`/`TEXT`/`NUMBER`/`BOOLEAN` (defaults `LIST`). `translations` required non-empty; each `values[]` entry needs `code` (`[A-Z0-9_]+`) and non-empty `translations`.

**Success response `201`:**
```json
{ "id": 8 }
```

**Error responses:** `409 ATTRIBUTE_CODE_EXISTS`, `409 ATTRIBUTE_VALUE_CODE_EXISTS` (duplicate `code` within `values[]`).

---

#### `PUT /api/v1/admin/attributes/{id}`
**Summary:** Update an attribute and/or add/edit its values. A `values[]` entry with `id` set updates that value; `id: null` creates a new one. Values are merged, not replaced wholesale.

**Request body:** same shape as create.

**Success response `200`:**
```json
{ "id": 8 }
```

**Error responses:**
| Status | Code | When |
|---|---|---|
| 404 | ATTRIBUTE_NOT_FOUND | No attribute with this id |
| 409 | ATTRIBUTE_CODE_EXISTS | Changing `code` to one already used by another attribute |
| 409 | ATTRIBUTE_IN_USE | Sending a `variantDefining` value that differs from the attribute's stored one on an update. Unconditional — the check does not actually verify any variant references the attribute; the only way to change the flag is to create a new attribute |
| 409 | ATTRIBUTE_VALUE_CODE_EXISTS | Duplicate `code` within `values[]` |

> `404 ATTRIBUTE_VALUE_NOT_FOUND` exists in the catalog but this service doesn't look values up by id here — an unrecognized `id` in `values[]` is treated as a new value.

---

## Cart & Checkout
Base path: `/api/v1/cart`, `/api/v1/orders`

### CartController

All cart endpoints are public — usable signed in (Bearer token) or as a guest via `X-Guest-Token`. When a Bearer token is present it takes priority over the header. The cart is recalculated from current prices/stock on every call; `warnings` reports what changed since items were added, and `checkoutReady` is `false` when a blocking warning is present (out of stock / unavailable / quantity reduced), as opposed to the non-blocking `PRICE_CHANGED`.

**`X-Guest-Token` is now issued and signed by the server** — call `POST /api/v1/cart/guest-token` once and store the result (a cookie, same as before); do not generate the UUID client-side anymore. Every endpoint below that accepts the header verifies its signature before touching the cart, so a stolen or guessed UUID alone is no longer enough to read or edit someone else's cart. Old, unsigned tokens (from before this changed) are still accepted for now — see `401 TOKEN_INVALID` in each endpoint's error table.

#### `POST /api/v1/cart/guest-token`
**Summary:** Issue a fresh, signed guest token. Call once, before the first cart action, if the browser does not already have one.

**Success response `200`:**
```json
{ "guestToken": "3fa1c8d0-e5b6-479a-8c2d-1e0f9b8a7c6d.kY3o2b1J9z8xW6vU5tS4rQ3pO2nM1lK0jI9hG8fE7d" }
```
Send this back as `X-Guest-Token` on every subsequent cart, shipping-quote and checkout call.

**Error responses:** none beyond the global conventions.

---

#### `GET /api/v1/cart`
**Summary:** Get the current cart, recalculated server-side.

**Headers:** `X-Guest-Token` (required only if not signed in).

**Success response `200`:**
```json
{
  "cartId": 501,
  "items": [
    {
      "itemId": 1201, "variantId": 88, "productId": 40, "slug": "orient-bambino-classic",
      "name": "Orient Bambino Classic", "variantSummary": "ذهبي / 42 مم", "sku": "ORB-42-GLD",
      "imageUrl": "https://cdn.velora.com/products/40/main.jpg",
      "unitPrice": 8500.0000, "priceAtAdd": 8200.0000, "priceChanged": true,
      "quantity": 1, "qtyAvailable": 6, "inStock": true, "lineTotal": 8500.0000
    }
  ],
  "itemCount": 1, "totalQuantity": 1, "subtotal": 8500.0000, "discountTotal": 0.0000,
  "assemblyTotal": 0.0000, "estimatedTotal": 8500.0000, "taxIncluded": 1108.7000, "couponCode": null,
  "warnings": [ { "code": "PRICE_CHANGED", "itemId": 1201, "sku": "ORB-42-GLD", "detail": "The price went up since you added this" } ],
  "checkoutReady": true
}
```

**Error responses:** `400 VALIDATION_FAILED` — not signed in and no `X-Guest-Token` sent. `401 TOKEN_INVALID` — the guest token's signature is wrong, or it's an unsigned legacy token and the transition flag is off.

---

#### `POST /api/v1/cart/items`
**Summary:** Add a variant to the cart (or increase quantity if the line already exists).

**Headers:** `X-Guest-Token` (required only if not signed in).

**Request body:**
```json
{ "variantId": "Long — required, the VARIANT id, never the product id", "quantity": "Integer — required, min 1, max 99" }
```

**Success response `200`:** `CartResponse` (shape above).

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | No guest token / not signed in, or cart already at 50 distinct lines |
| 401 | TOKEN_INVALID | Guest token signature is wrong, or unsigned and the transition flag is off |
| 404 | VARIANT_NOT_FOUND | Variant id does not exist |
| 409 | PRODUCT_NOT_PURCHASABLE | The variant's product is `MADE_TO_ORDER` or `CUSTOM_WORK` — only `READY_MADE` products go in the cart. Checked before `PRODUCT_NOT_ACTIVE`, so it is returned even for a draft |
| 409 | PRODUCT_NOT_ACTIVE | Variant or its product is archived / not active |
| 409 | STOCK_UNAVAILABLE | Requested quantity (plus any already in cart) exceeds available stock |

---

#### `PATCH /api/v1/cart/items/{itemId}`
**Summary:** Change a line's quantity.

**Headers:** `X-Guest-Token` (required only if not signed in).

**Request body:**
```json
{ "quantity": "Integer — required, min 1 (use DELETE to remove), max 99" }
```

**Success response `200`:** `CartResponse`.

**Error responses:** `400 VALIDATION_FAILED`, `401 TOKEN_INVALID` (guest token signature wrong or unsigned with the transition flag off), `404 CART_ITEM_NOT_FOUND`, `409 STOCK_UNAVAILABLE`.

---

#### `DELETE /api/v1/cart/items/{itemId}`
**Summary:** Remove a line from the cart.

**Headers:** `X-Guest-Token` (required only if not signed in).

**Success response `200`:** `CartResponse` (without the removed line).

**Error responses:** `400 VALIDATION_FAILED`, `401 TOKEN_INVALID` (guest token signature wrong or unsigned with the transition flag off), `404 CART_ITEM_NOT_FOUND`.

---

#### `DELETE /api/v1/cart`
**Summary:** Empty the cart (also clears any applied coupon).

**Headers:** `X-Guest-Token` (required only if not signed in).

**Success response `200`:**
```json
{
  "cartId": 501, "items": [], "itemCount": 0, "totalQuantity": 0,
  "subtotal": 0.0000, "discountTotal": 0.0000, "assemblyTotal": 0.0000, "estimatedTotal": 0.0000, "taxIncluded": 0.0000,
  "couponCode": null, "warnings": [], "checkoutReady": false
}
```

**Error responses:** `400 VALIDATION_FAILED`, `401 TOKEN_INVALID` (guest token signature wrong or unsigned with the transition flag off).

---

#### `POST /api/v1/cart/merge`
**Auth:** Intended to be Authenticated, but `/api/v1/cart/**` is covered by the blanket `permitAll()` rule for the cart module in `SecurityConfig`, so nothing at the gateway actually requires a Bearer token on this specific path.
**Summary:** Fold a guest cart into the signed-in account's cart, right after login. Quantities are added together and capped at available stock. If the guest token doesn't resolve to an active cart, the account cart is returned unchanged (not an error).

**Request body:**
```json
{ "guestToken": "String — required, the X-Guest-Token used before signing in" }
```

**Success response `200`:** `CartResponse` for the merged account cart.

**Error responses:** `401 TOKEN_INVALID` (`guestToken` signature is wrong, or unsigned and the transition flag is off). Note: an anonymous call does NOT get a clean `401 UNAUTHORIZED` — `CartController.merge()` reads `principal.id()` directly with no null check (unlike every other method on this controller), so a missing/invalid Bearer token causes a `NullPointerException` that the global handler reports as a generic `500 INTERNAL_ERROR`. Always send a valid Bearer token when calling this endpoint.

---

### CheckoutController

Turns a cart into an order in one transaction: reserves stock (guarded atomic update — fails cleanly if a line ran out), copies prices/address/contact info onto the order (snapshot, never a live reference), allocates any cart-level discount to lines via `MoneyUtils.allocate()`, computes tax per line then sums, retires the cart. Any failure after reservation rolls back the whole transaction including the stock holds. Cash on delivery is the only payment method in V1; a fresh order starts `fulfillmentStatus: PENDING`, `paymentStatus: PENDING`.

#### `POST /api/v1/orders`
**Auth:** Public (guest or signed-in)
**Summary:** Place an order from the current cart.

**Headers:**
- `X-Guest-Token` — required only if not signed in. Must be a token issued by `POST /api/v1/cart/guest-token` (or a pre-existing unsigned legacy token, while the transition flag is on) — its signature is verified before the cart is loaded.
- `Idempotency-Key` — optional, one UUID per checkout attempt. A repeat with the same key + same body returns the **same** order. Same key + different body is rejected. Same key while the first attempt is in flight is rejected as "already being processed." Same key after the first attempt failed is rejected — retry with a fresh key.

**Request body:**
```json
{
  "addressId": "Long — a saved address id, signed-in only (mutually exclusive with 'address')",
  "address": {
    "recipientName": "String — required, max 150",
    "phone": "String — required, e.g. 01012345678, normalized to E.164 server-side",
    "altPhone": "String — optional",
    "email": "String — optional, guest order confirmation only",
    "governorateId": "Long — required",
    "area": "String — optional, max 150",
    "streetAddress": "String — required, max 255",
    "building": "String — optional, max 50",
    "floor": "String — optional, max 20",
    "apartment": "String — optional, max 20",
    "landmark": "String — optional, max 255"
  },
  "paymentMethod": "String — optional, only 'COD' allowed, defaults to COD",
  "customerNote": "String — optional, max 500",
  "preferredDeliveryDate": "String — optional, ISO date, e.g. 2026-10-20",
  "preferredDeliverySlot": "String — optional: MORNING | AFTERNOON | EVENING. Needs preferredDeliveryDate"
}
```
Supply either `addressId` (signed-in) or `address` inline (required for guest checkout) — never both.

**Delivery preference.** `preferredDeliveryDate` and `preferredDeliverySlot` are what the customer would *like*: a request, not a booking. Staff agree the real appointment with them afterwards (`scheduledDeliveryAt`, see `PATCH /admin/orders/{id}/schedule`), and that is what the customer is told. The date must be today or later (**Cairo date**) and no more than 90 days ahead; a slot without a date is refused — all `400 VALIDATION_FAILED`, before any stock is reserved. It is deliberately not checked against the zone's `deliveryDaysMin`/`deliveryDaysMax`: those are parcel-courier estimates, and when furniture is delivered is agreed with the customer, not derived from them. Both fields are optional, and an order without them is unchanged.

**Shipping on the order** is calculated exactly as in `POST /api/v1/shipping/quote` (see that endpoint for the formula) and **frozen on the order**: `shippingCost`, `shippingCapApplied`, `shippingUncappedCost` and `shippingBreakdown` are a snapshot, so later price or cap changes never alter an existing order. Orders placed before shipping was priced per size class have `shippingBreakdown: null`, `shippingUncappedCost: null` and `shippingCapApplied: false` — render only `shippingCost` for those. `null` breakdown means "not recorded", not "no charge".

**Success response `201`:**
```json
{
  "id": 9042, "orderNumber": "VLR-260812-9042",
  "fulfillmentStatus": "PENDING", "paymentStatus": "PENDING", "paymentMethod": "COD", "currency": "EGP",
  "subtotal": 8500.0000, "discountTotal": 0.0000, "shippingCost": 0.0000,
  "shippingCapApplied": false, "shippingUncappedCost": 0.0000,
  "shippingBreakdown": [ { "sizeClass": "SMALL", "quantity": 1, "unitCost": 0.0000, "lineCost": 0.0000 } ],
  "codFee": 25.0000,
  "assemblyTotal": 0.0000,
  "grandTotal": 8525.0000, "taxTotal": 1108.7000, "netTotal": 7416.3000,
  "contactName": "Mohammed Hassan", "contactPhone": "+201012345678", "contactAltPhone": null, "contactEmail": "mohammed316@gmail.com",
  "shippingAddress": {
    "governorateName": "القاهرة", "cityName": "القاهرة الجديدة", "area": "القاهرة الجديدة",
    "streetAddress": "12 شارع التسعين", "building": "4", "floor": "3", "apartment": "12",
    "landmark": "بجوار مسجد النور", "formatted": "12 شارع التسعين، مبنى 4، الدور 3، شقة 12، القاهرة الجديدة، القاهرة"
  },
  "shippingZoneName": "Greater Cairo", "deliveryDaysMin": 1, "deliveryDaysMax": 3,
  "preferredDeliveryDate": "2026-10-20", "preferredDeliverySlot": "MORNING", "scheduledDeliveryAt": null,
  "customerNote": "اتصل قبل التوصيل",
  "items": [
    {
      "id": 15021, "variantId": 88, "productId": 40, "productSlug": "orient-bambino-classic",
      "name": "Orient Bambino Classic", "sku": "ORB-42-GLD", "variantSummary": "ذهبي / 42 مم",
      "imageUrl": "https://cdn.velora.com/products/40/main.jpg",
      "unitPrice": 8500.0000, "quantity": 1, "lineDiscount": 0.0000, "allocatedCartDiscount": 0.0000,
      "lineTotal": 8500.0000, "taxAmount": 1108.7000, "assemblyFee": 0.0000, "assemblyTotal": 0.0000, "quantityReturned": 0, "returnableQuantity": 1
    }
  ],
  "totalQuantity": 1,
  "timeline": [ { "kind": "FULFILLMENT", "from": null, "to": "PENDING", "note": "Order placed", "at": "2026-08-12T10:04:11Z" } ],
  "cancellable": true, "placedAt": "2026-08-12T10:04:11Z",
  "confirmedAt": null, "shippedAt": null, "deliveredAt": null, "cancelledAt": null, "cancelReason": null
}
```

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | CART_EMPTY | The cart has no items |
| 400 | INVALID_ADDRESS | Neither `addressId` nor `address` supplied |
| 400 | INVALID_PHONE_FORMAT | Inline address phone is not a valid Egyptian mobile number |
| 400 | VALIDATION_FAILED | Field validation failed, or a repeat `Idempotency-Key` with a different body, or the original attempt under that key failed |
| 401 | UNAUTHORIZED | `addressId` supplied while not signed in |
| 401 | TOKEN_INVALID | `X-Guest-Token` signature is wrong, or unsigned and the transition flag is off |
| 404 | RESOURCE_NOT_FOUND | `addressId` / `governorateId` doesn't exist, or a saved address doesn't belong to the caller (identical 404 either way) |
| 409 | STOCK_UNAVAILABLE | A cart line blocks checkout (out of stock, quantity reduced, reservation could not be granted) |
| 409 | GOVERNORATE_NOT_SERVED | VELORA does not deliver to the given governorate — it is closed (in no shipping zone, or in a zone with no rates). Checked for inline addresses and for a saved `addressId` alike, so an address saved before its governorate closed is refused too. Raised before stock is reserved |
| 409 | SHIPPING_SIZE_MISSING | A cart line's product has no `shippingSizeClass`; the `detail` names the SKU. Raised before stock is reserved, so nothing is held |
| 409 | SHIPPING_RATE_NOT_CONFIGURED | The resolved zone has no rate for a size class in the cart |
| 409 | PAYMENT_METHOD_UNAVAILABLE | `paymentMethod` is set to anything other than `COD` |
| 409 | DUPLICATE_ORDER | Same `Idempotency-Key` reused while the original request is still processing |

---

## Orders
Base path: `/api/v1/me/orders` (customer), `/api/v1/admin/orders` (admin)

`fulfillmentStatus` and `paymentStatus` are **two separate state machines** on every order. `fulfillmentStatus: DELIVERED` + `paymentStatus: PENDING` is the normal, correct state for a COD parcel awaiting courier cash remittance, not a bug.

`fulfillmentStatus` values: `PENDING, CONFIRMED, AWAITING_SCHEDULE, PROCESSING, SHIPPED, OUT_FOR_DELIVERY, DELIVERED, DELIVERY_FAILED, REFUSED_ON_DELIVERY, RETURNED_TO_SELLER, CANCELLED, RETURNED, PARTIALLY_RETURNED`.
`paymentStatus` values: `PENDING, AUTHORIZED, PAID, PARTIALLY_REFUNDED, REFUNDED, FAILED, EXPIRED`.

> **Scope note:** the domain model and error catalog (`RETURN_WINDOW_CLOSED`, `RETURN_QUANTITY_EXCEEDED`, `REFUND_EXCEEDS_ORDER_TOTAL`) anticipate customer-initiated returns and partial refunds, but no REST endpoint currently exercises them — there is no return/refund endpoint yet. Only pre-dispatch **cancellation** is exposed today.

### My Orders (`OrderController`)

#### `GET /api/v1/me/orders`
**Auth:** Authenticated (own orders only)
**Summary:** Paginated list of the caller's own orders, newest first.

**Query params:** `page` (default 0), `size` (default 10), `sort`.

**Success response `200`:**
```json
{
  "content": [
    {
      "id": 4821, "orderNumber": "VLR-260809-4821", "fulfillmentStatus": "OUT_FOR_DELIVERY", "paymentStatus": "PENDING",
      "paymentMethod": "COD", "grandTotal": 18450.0000, "itemCount": 2, "totalQuantity": 3,
      "contactName": "Mohammed Hassan", "contactPhone": "01012345678", "governorateName": "Cairo",
      "thumbnailUrl": "https://cdn.velora.com/products/watch-42mm-gold.jpg", "placedAt": "2026-08-09T14:22:05Z"
    }
  ],
  "page": 0, "size": 10, "totalElements": 7, "totalPages": 1, "first": true, "last": true, "empty": false
}
```

**Error responses:** `401 UNAUTHORIZED`.

---

#### `GET /api/v1/me/orders/{orderNumber}`
**Auth:** Authenticated (own orders only)
**Summary:** Full order detail including item lines and status timeline. Lookup scoped by `(orderNumber, customerId)` — another customer's order behaves identically to a nonexistent one.

**Success response `200`:**
```json
{
  "id": 4821, "orderNumber": "VLR-260809-4821", "fulfillmentStatus": "OUT_FOR_DELIVERY", "paymentStatus": "PENDING",
  "paymentMethod": "COD", "currency": "EGP",
  "subtotal": 19500.0000, "discountTotal": 1500.0000, "shippingCost": 150.0000,
  "shippingCapApplied": false, "shippingUncappedCost": null, "shippingBreakdown": null,
  "codFee": 50.0000,
  "assemblyTotal": 0.0000,
  "grandTotal": 18450.0000, "taxTotal": 2405.2174, "netTotal": 16044.7826,
  "contactName": "Mohammed Hassan", "contactPhone": "01012345678", "contactAltPhone": null, "contactEmail": "mohammed316@gmail.com",
  "shippingAddress": {
    "governorateName": "Cairo", "cityName": "Nasr City", "area": "Zone 7", "streetAddress": "12 Abbas El Akkad St",
    "building": "12", "floor": "4", "apartment": "9", "landmark": "Near City Stars",
    "formatted": "12 Abbas El Akkad St, building 12, floor 4, apt 9, Zone 7, Nasr City, Cairo (Near City Stars)"
  },
  "shippingZoneName": "Greater Cairo", "deliveryDaysMin": 1, "deliveryDaysMax": 3,
  "preferredDeliveryDate": null, "preferredDeliverySlot": null, "scheduledDeliveryAt": "2026-08-12T10:00:00+03:00",
  "customerNote": "الرجاء الاتصال قبل الوصول",
  "items": [
    {
      "id": 9931, "variantId": 512, "productId": 88, "productSlug": "velora-classic-42mm",
      "name": "ساعة فيلورا كلاسيك 42 مم", "sku": "VLR-WATCH-CLS-42-GLD", "variantSummary": "ذهبي / 42 مم",
      "imageUrl": "https://cdn.velora.com/products/watch-42mm-gold.jpg",
      "unitPrice": 9750.0000, "quantity": 2, "lineDiscount": 0.0000, "allocatedCartDiscount": 1500.0000,
      "lineTotal": 19500.0000, "taxAmount": 2405.2174, "assemblyFee": 0.0000, "assemblyTotal": 0.0000, "quantityReturned": 0, "returnableQuantity": 2
    }
  ],
  "totalQuantity": 2,
  "timeline": [
    { "kind": "FULFILLMENT", "from": "PENDING", "to": "CONFIRMED", "note": "Confirmed by phone", "at": "2026-08-09T15:00:00Z" },
    { "kind": "FULFILLMENT", "from": "PROCESSING", "to": "SHIPPED", "note": null, "at": "2026-08-10T09:30:00Z" }
  ],
  "cancellable": false, "placedAt": "2026-08-09T14:22:05Z", "confirmedAt": "2026-08-09T15:00:00Z",
  "shippedAt": "2026-08-10T09:30:00Z", "deliveredAt": null, "cancelledAt": null, "cancelReason": null
}
```

**Error responses:** `401 UNAUTHORIZED`, `404 ORDER_NOT_FOUND` (no such order, or belongs to another customer).

---

#### `POST /api/v1/me/orders/{orderNumber}/cancel`
**Auth:** Authenticated (own orders only)
**Summary:** Customer self-cancellation, allowed only until dispatch (`fulfillmentStatus` not yet `SHIPPED`+). Releases the stock reservation.

**Request body:**
```json
{ "reason": "غيرت رأيي" }
```
`reason`: optional, max 255 chars.

**Success response `200`:** order detail with `fulfillmentStatus: "CANCELLED"`, `cancellable: false`, `cancelledAt` set, `cancelReason` populated, new timeline entry.

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | `reason` exceeds 255 chars |
| 404 | ORDER_NOT_FOUND | No such order, or belongs to another customer |
| 409 | ORDER_CANNOT_BE_CANCELLED | Order already dispatched (`SHIPPED`+) or already `CANCELLED` |
| 409 | INVALID_STATUS_TRANSITION | Defensive fallback if the transition is otherwise illegal |

---

### Admin — Orders (`AdminOrderController`)

#### `GET /api/v1/admin/orders`
**Summary:** List orders, optionally filtered by fulfilment status or customer phone (normalized to E.164 before matching). `phone` takes precedence over `status` if both given.

**Query params:** `status`, `phone`, `page` (default 0), `size` (default 20), `sort`.

**Success response `200`:** `PageResponse<OrderSummaryResponse>` (same row shape as the customer list).

**Error responses:** `400 INVALID_PARAMETER` — `status` not a recognized value.

---

#### `GET /api/v1/admin/orders/{orderId}`
**Summary:** Full order detail by numeric id. No ownership scoping — any admin can view any order.

**Success response `200`:** same `OrderResponse` shape as the customer detail endpoint.

**Error responses:** `404 ORDER_NOT_FOUND`.

---

#### `POST /api/v1/admin/orders/{orderId}/confirm`
**Summary:** Confirms the order (`PENDING` → `CONFIRMED`), typically after the COD phone call. Records who confirmed and when.

**Query params:** `note` (optional, defaults to `"Confirmed by phone"`).

**Success response `200`:** `OrderResponse` with `fulfillmentStatus: "CONFIRMED"`, `confirmedAt` set, new timeline entry.

**Error responses:** `404 ORDER_NOT_FOUND`, `409 INVALID_STATUS_TRANSITION` (not currently `PENDING`).

---

#### `PATCH /api/v1/admin/orders/{orderId}/fulfillment-status`
**Summary:** Move to a new `fulfillmentStatus`. Transitions enforced server-side by `OrderStatusMachine`. `SHIPPED` commits the stock reservation for good. `CANCELLED`/`RETURNED_TO_SELLER` release any still-held reservation. Reaching `DELIVERED` issues the invoice in the same transaction. `note` is **required** for `DELIVERY_FAILED`, `REFUSED_ON_DELIVERY`, `RETURNED_TO_SELLER`, `CANCELLED`.

Legal transitions:
| From | To (one of) |
|---|---|
| `PENDING` | `CONFIRMED`, `CANCELLED` |
| `CONFIRMED` | `AWAITING_SCHEDULE`, `PROCESSING`, `CANCELLED` |
| `AWAITING_SCHEDULE` | `PROCESSING`, `CANCELLED` |
| `PROCESSING` | `SHIPPED`, `CANCELLED` |
| `SHIPPED` | `OUT_FOR_DELIVERY`, `DELIVERY_FAILED`, `RETURNED_TO_SELLER` |
| `OUT_FOR_DELIVERY` | `DELIVERED`, `DELIVERY_FAILED`, `REFUSED_ON_DELIVERY` |
| `DELIVERY_FAILED` | `OUT_FOR_DELIVERY`, `REFUSED_ON_DELIVERY`, `RETURNED_TO_SELLER` |
| `REFUSED_ON_DELIVERY` | `RETURNED_TO_SELLER` |
| `DELIVERED` | `RETURNED`, `PARTIALLY_RETURNED` |
| `PARTIALLY_RETURNED` | `RETURNED` |
| `CANCELLED` / `RETURNED` / `RETURNED_TO_SELLER` | *(terminal)* |

**Request body:**
```json
{ "status": "SHIPPED", "note": "العميل لم يرد على الهاتف" }
```
`status`: required, one of the fulfilment enum values. `note`: required (non-blank, max 500) for `DELIVERY_FAILED`/`REFUSED_ON_DELIVERY`/`RETURNED_TO_SELLER`/`CANCELLED`, optional otherwise.

**Success response `200`:** `OrderResponse` with updated status, relevant timestamp set, new timeline entry.

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | `status` blank, `note` required but missing, or `note` too long |
| 400 | INVALID_PARAMETER | `status` not a recognized `FulfillmentStatus` |
| 404 | ORDER_NOT_FOUND | No order with this id |
| 409 | INVALID_STATUS_TRANSITION | Transition not allowed from the current status |
| 409 | DELIVERY_NOT_SCHEDULED | `AWAITING_SCHEDULE` → `PROCESSING` while the order has no `scheduledDeliveryAt`. The move **is** allowed — a date is missing. Show the `detail` to the user and send them to `PATCH /schedule`; do not present it as a broken order |

**`AWAITING_SCHEDULE`** sits between `CONFIRMED` and `PROCESSING`: confirmed, waiting for a delivery date to be agreed with the customer. For furniture the date is agreed *before* the goods leave the warehouse, because it decides when they ship. It is **optional** — `CONFIRMED` may go straight to `PROCESSING` with no date. Nothing has left the warehouse in this status, so the order can still be cancelled (by staff or by the customer) and its stock stays reserved for it; cancelling releases the reservation. It is the only status that needs a date to move on, and only towards `PROCESSING`. Everything after it is unchanged: `SHIPPED` still takes the stock for good, and `DELIVERED` still issues the invoice.

---

#### `PATCH /api/v1/admin/orders/{orderId}/schedule`
**Summary:** Set or move the delivery appointment agreed with the customer. Audited (`ORDER_DELIVERY_SCHEDULED`, old and new, with who and why).

**Request body:**
```json
{ "scheduledDeliveryAt": "2026-10-12T10:00:00+03:00", "note": "العميل طلب الخميس بعد الظهر" }
```
`scheduledDeliveryAt` required, an ISO-8601 date-time **with its UTC offset**, and **in the future**. `note` optional, max 500; it goes to the audit log (e.g. why the appointment moved).

**It does not change the order's status.** An order in `AWAITING_SCHEDULE` stays there until staff move it on with `PATCH /fulfillment-status`, which now succeeds because the date is set. Setting the same date again changes nothing and records nothing.

**Allowed in:** `CONFIRMED`, `AWAITING_SCHEDULE`, `PROCESSING`, `SHIPPED`, `OUT_FOR_DELIVERY`, `DELIVERY_FAILED`. The last one matters most: after a failed attempt, moving the appointment is the routine next step. **Refused in:** `PENDING` (confirm the order first) and every final status.

**Success response `200`:** the full `OrderResponse`, with `scheduledDeliveryAt` set.

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | `scheduledDeliveryAt` missing, or not in the future |
| 404 | ORDER_NOT_FOUND | No order with this id |
| 409 | ORDER_NOT_SCHEDULABLE | The order is `PENDING` ("Confirm the order before setting its delivery date") or in a final status. The status is checked before the date, so a past date is only reported for an order that could be scheduled |

---

#### `PATCH /api/v1/admin/orders/{orderId}/payment-status`
**Summary:** Manually set the payment status — normally driven by courier COD remittance batches, kept available for out-of-band settlements. Independent of `fulfillmentStatus`; does not touch stock or invoices.

Legal transitions:
| From | To (one of) |
|---|---|
| `PENDING` | `AUTHORIZED`, `PAID`, `FAILED`, `EXPIRED` |
| `AUTHORIZED` | `PAID`, `FAILED`, `EXPIRED` |
| `PAID` | `PARTIALLY_REFUNDED`, `REFUNDED` |
| `PARTIALLY_REFUNDED` | `REFUNDED` |
| `FAILED` / `EXPIRED` | `PENDING` |
| `REFUNDED` | *(terminal)* |

**Query params:** `status` (required, one of the payment enum values), `note` (optional).

**Success response `200`:** `OrderResponse` with updated `paymentStatus`, new timeline entry.

**Error responses:** `400 INVALID_PARAMETER` for an unknown `status` value (the `detail` lists the valid values), `404 ORDER_NOT_FOUND`, `409 INVALID_STATUS_TRANSITION`.

---

#### `POST /api/v1/admin/orders/{orderId}/cancel`
**Summary:** Staff-initiated cancellation. Not gated by the customer-side `isCancellable()` check — goes straight to `OrderStatusMachine`, so it's rejected once `SHIPPED`+ same as the transition table above.

**Request body:**
```json
{ "reason": "Out of stock — supplier could not fulfil" }
```
`reason`: optional, max 255 chars.

**Success response `200`:** `OrderResponse` with `fulfillmentStatus: "CANCELLED"`, `cancelledAt` set, `cancelReason` populated, new timeline entry.

**Error responses:** `400 VALIDATION_FAILED`, `404 ORDER_NOT_FOUND`, `409 INVALID_STATUS_TRANSITION`.

### Assembly fee (money rules)

- `assemblyFee` lives on the **product** (`requiresAssembly` + a per-piece, tax-inclusive fee). A line's assembly is `assemblyFee × quantity`; the order's `assemblyTotal` is the sum over its lines.
- It is **frozen at purchase**: `order_item.assemblyFee` (per piece) and `order.assemblyTotal`. Editing the product afterwards never changes an existing order.
- It is part of `grandTotal` (`subtotal − discount + shippingCost + codFee + assemblyTotal`). It is **outside the cart discount**: the discount applies to goods only and none of it is allocated to assembly. `subtotal` stays goods-only.
- Tax is **extracted**, not added: per line, at that line's own `tax_rate`, then summed into `taxTotal`. `netTotal = grandTotal − taxTotal`. **Pending accountant confirmation** that assembly is taxable at the goods rate (marked `TODO(accountant)` in `CheckoutService`); shipping and the COD fee are still treated as untaxed.
- Assembly is not refunded by a partial return automatically (a return refunds goods value only) — decide that with the accountant before fees are non-zero.
- Fees are `0` for now, so every figure is unchanged until a fee is set.

---

## Customers & Addresses
Base path: `/api/v1/me/addresses` (customer), `/api/v1/admin/customers` (admin)

Address phone fields are normalized to E.164 on write but rendered back in local Egyptian format (e.g. `01012345678`) in every response. Admin customer responses also render full, unmasked local-format phone numbers — these endpoints are admin-only and staff need the real number to call the customer.

### AddressController — `/api/v1/me/addresses`
**Auth:** Authenticated. Every lookup is scoped to the caller — an address id belonging to another customer returns `404 RESOURCE_NOT_FOUND`, identical to a nonexistent id.

#### `GET /api/v1/me/addresses`
**Summary:** List the caller's saved addresses, default address first.

**Success response `200`:**
```json
[
  {
    "id": 41, "label": "HOME", "recipientName": "محمد أحمد", "phone": "01012345678", "altPhone": null,
    "governorateId": 3, "governorateName": "القاهرة", "area": "المنيا الجديدة", "streetAddress": "شارع الجمهورية",
    "building": "12", "floor": "3", "apartment": "5", "landmark": "بجوار مسجد النور", "isDefault": true,
    "formatted": "شارع الجمهورية, building 12, floor 3, apt 5, المنيا الجديدة (بجوار مسجد النور)"
  }
]
```

**Error responses:** `401 UNAUTHORIZED`.

---

#### `POST /api/v1/me/addresses`
**Summary:** Add an address to the caller's address book. The first address ever saved becomes the default automatically, regardless of `makeDefault`.

**Request body:**
```json
{
  "label": "string, max 30 — HOME | WORK | OTHER",
  "recipientName": "string, required, max 150",
  "phone": "string, required — Egyptian mobile",
  "altPhone": "string, optional — Egyptian mobile",
  "governorateId": "number, required",
  "area": "string, optional, max 150",
  "streetAddress": "string, required, max 255",
  "building": "string, optional, max 50",
  "floor": "string, optional, max 20",
  "apartment": "string, optional, max 20",
  "landmark": "string, optional, max 255",
  "makeDefault": "boolean, optional"
}
```

**Success response `201`:** the created address (same shape as the list item above).

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | Bean validation fails, or the customer already has 10 saved addresses |
| 400 | INVALID_PHONE_FORMAT | `phone`/`altPhone` doesn't normalize to a valid Egyptian mobile number |
| 404 | RESOURCE_NOT_FOUND | `governorateId` doesn't exist |
| 409 | GOVERNORATE_NOT_SERVED | The governorate is closed for delivery (in no shipping zone). Applies to `PUT` as well |

---

#### `PUT /api/v1/me/addresses/{addressId}`
**Summary:** Replace the fields of one of the caller's saved addresses.

**Request body:** same shape as `POST`.

**Success response `200`:** the updated address.

**Error responses:** same as `POST`, plus `404 RESOURCE_NOT_FOUND` if `addressId` doesn't exist/belong to the caller.

---

#### `DELETE /api/v1/me/addresses/{addressId}`
**Summary:** Soft-delete a saved address. If it was the default, the next most-recent remaining address is promoted automatically. Past orders keep their own copied snapshot, unaffected.

**Success response `204`:** empty body.

**Error responses:** `404 RESOURCE_NOT_FOUND`.

---

#### `PUT /api/v1/me/addresses/{addressId}/default`
**Summary:** Make this address the caller's default (clears the flag on any other saved address).

**Success response `200`:** the updated address, `isDefault: true`.

**Error responses:** `404 RESOURCE_NOT_FOUND`.

---

### AdminCustomerController — `/api/v1/admin/customers`

#### `GET /api/v1/admin/customers`
**Summary:** Paginated customer list with order counts and lifetime value. Cancelled orders excluded from both `orderCount` and `totalSpent`.

**Query params:** `search` (name/phone/email; a phone-shaped input is normalized to E.164 before matching), `sort` (`spent_desc`, `orders_desc`, `recent_order`, `name`), `page`, `size` (default 25), Pageable `sort`.

**Success response `200`:**
```json
{
  "content": [
    {
      "id": 101, "name": "محمد أحمد", "phone": "01012345678", "email": "mohamed@example.com",
      "phoneVerified": true, "orderCount": 6, "totalSpent": 24500.0000,
      "lastOrderAt": "2026-08-01T14:22:00Z", "registeredAt": "2025-03-11T09:10:00Z", "status": "ACTIVE"
    }
  ],
  "page": 0, "size": 25, "totalElements": 137, "totalPages": 6, "first": true, "last": false, "empty": false
}
```

**Error responses:** none beyond the global auth cases.

---

#### `GET /api/v1/admin/customers/{customerId}`
**Summary:** One customer in full — profile, purchase totals, saved addresses, 10 most recent orders. `purchases.failedOrders` counts refusals/failed COD deliveries.

**Success response `200`:**
```json
{
  "id": 101, "firstName": "محمد", "lastName": "أحمد", "phone": "01012345678", "email": "mohamed@example.com",
  "phoneVerified": true, "emailVerified": true, "status": "ACTIVE", "locale": "ar",
  "registeredAt": "2025-03-11T09:10:00Z", "lastLoginAt": "2026-08-10T18:45:00Z", "roles": ["CUSTOMER"],
  "purchases": {
    "totalOrders": 6, "deliveredOrders": 4, "failedOrders": 1, "cancelledOrders": 1,
    "totalSpent": 24500.0000, "averageOrderValue": 4083.33,
    "firstOrderAt": "2025-04-02T10:00:00Z", "lastOrderAt": "2026-08-01T14:22:00Z"
  },
  "addresses": [
    { "id": 41, "label": "HOME", "recipientName": "محمد أحمد", "phone": "01012345678", "governorate": "القاهرة", "formatted": "شارع الجمهورية, building 12, floor 3, apt 5, المنيا الجديدة (بجوار مسجد النور)", "isDefault": true }
  ],
  "recentOrders": [
    { "id": 5231, "orderNumber": "VLR-260801-5231", "fulfillmentStatus": "DELIVERED", "paymentStatus": "PAID", "grandTotal": 4500.0000, "itemCount": 2, "placedAt": "2026-08-01T14:22:00Z" }
  ]
}
```

**Error responses:** `404 RESOURCE_NOT_FOUND` — no user exists with this id.

---

## Inventory — Admin
Base path: `/api/v1/admin/inventory`

Stock is always three numbers: `qtyOnHand`, `qtyReserved`, and derived `qtyAvailable = qtyOnHand - qtyReserved` (never stored). Every write endpoint appends a `stock_movement` row. Writes are guarded by `@Version` optimistic locking; a lost race surfaces as `409 CONCURRENT_STOCK_CHANGE` — the client should retry.

There is no "receive return" / "restock after inspection" endpoint pair here — only supplier receipt (`/receive`) and manual adjustment (`/adjust`). The movement types `RETURN_SELLABLE`/`RETURN_DAMAGED` exist in the ledger but are written exclusively by the order/returns flow, not by admins; passing either (or `SALE`/`CANCELLATION_RESTOCK`) as `movementType` on `/adjust` is rejected with `MOVEMENT_TYPE_NOT_MANUAL`.

### `GET /api/v1/admin/inventory/{variantId}`
**Summary:** Stock position for one variant.

**Success response `200`:**
```json
{
  "variantId": 42, "sku": "WCH-ROL-SUB-41-BLK", "productName": "روليكس سبمارينر", "variantSummary": "أسود / 41 مم",
  "qtyOnHand": 12, "qtyReserved": 3, "qtyAvailable": 9, "minStockLevel": 3,
  "lowStock": false, "outOfStock": false, "updatedAt": "2026-08-12T09:15:00Z"
}
```

**Error responses:** `404 VARIANT_NOT_FOUND`, `409 INVENTORY_RECORD_MISSING`.

---

### `GET /api/v1/admin/inventory/low-stock`
**Summary:** Variants at or below their `minStockLevel`, ordered by scarcity. Plain array, not paginated.

**Success response `200`:** array of the stock-position shape above, each with `lowStock: true`.

---

### `POST /api/v1/admin/inventory/{variantId}/receive`
**Summary:** Record goods received from a supplier. Always increases `qtyOnHand`; writes a `PURCHASE_RECEIVED` movement.

**Request body:**
```json
{ "quantity": 20, "reference": "PO-2026-0042", "note": "Restock ahead of Ramadan launch" }
```
`quantity`: required, positive. `reference`: optional, max 60. `note`: optional, max 500, defaults to `"Goods received"`.

**Success response `200`:** updated stock-position shape.

**Error responses:** `400 VALIDATION_FAILED`, `404 VARIANT_NOT_FOUND`, `409 INVENTORY_RECORD_MISSING`, `409 CONCURRENT_STOCK_CHANGE`.

---

### `POST /api/v1/admin/inventory/{variantId}/adjust`
**Summary:** Manual correction to on-hand stock (stocktake, breakage, a found box). A reason is mandatory. Refused if the result would drop `qtyOnHand` below `qtyReserved` or below zero.

**Request body:**
```json
{ "quantityDelta": -2, "reason": "Physical count correction after stocktake", "movementType": "MANUAL_ADJUSTMENT" }
```
`quantityDelta`: required, signed, non-zero. `reason`: required, non-blank, max 500. `movementType`: optional, one of `MANUAL_ADJUSTMENT` (default), `DAMAGE_WRITEOFF`, `PURCHASE_RECEIVED` — any order-flow-only value (`SALE`, `RETURN_SELLABLE`, `RETURN_DAMAGED`, `CANCELLATION_RESTOCK`) is rejected.

**Success response `200`:** updated stock-position shape.

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | `reason` blank/too long, `quantityDelta` missing/zero, or invalid `movementType` |
| 400 | NEGATIVE_STOCK | Resulting `qtyOnHand` would go below zero |
| 400 | MOVEMENT_TYPE_NOT_MANUAL | `movementType` is order-flow-only |
| 404 | VARIANT_NOT_FOUND | No variant with this id |
| 409 | STOCK_BELOW_RESERVED | Resulting `qtyOnHand` would drop below `qtyReserved` |
| 409 | INVENTORY_RECORD_MISSING | Variant has no inventory row |
| 409 | CONCURRENT_STOCK_CHANGE | Optimistic-lock conflict — retry |

---

### `GET /api/v1/admin/inventory/movements`
**Summary:** Append-only stock movement ledger, newest first. Omit `variantId` for the full ledger.

**Query params:** `variantId` (optional), `page` (default 0), `size` (default 50), `sort`.

**Success response `200`:**
```json
{
  "content": [
    {
      "id": 981, "variantId": 42, "sku": "WCH-ROL-SUB-41-BLK", "movementType": "MANUAL_ADJUSTMENT",
      "quantityDelta": -2, "qtyAfter": 10, "referenceType": "ADJUSTMENT", "referenceId": null,
      "reason": "Physical count correction after stocktake", "actorId": 7, "createdAt": "2026-08-12T10:04:11Z"
    }
  ],
  "page": 0, "size": 50, "totalElements": 2, "totalPages": 1, "first": true, "last": true, "empty": false
}
```
An unrecognized `variantId` simply yields an empty page.

---

## Invoices
Base path: `/api/v1/admin/invoices` (admin), `/api/v1/me/invoices` (customer)

Invoices are **immutable once issued** — there is no update endpoint. Numbering (`VLR-INV-{year}-{sequence}`) is gapless and issued automatically when an order reaches `DELIVERED`; the admin `issue` endpoint exists only to catch orders that slipped through, and is idempotent. The only correction mechanism exposed is **cancellation** (a status flag — the row and number are never removed); there is no separate credit-note endpoint here. No dedicated `INVOICE_NOT_FOUND` code exists — a missing/inaccessible invoice uses the generic `RESOURCE_NOT_FOUND`.

**Assembly and COD fee on the invoice and PDF.** `assemblyTotal` and `codFee` are frozen onto the invoice from the order when it is issued (columns `invoice.assembly_total`, `invoice.cod_fee`; invoices issued before they existed were backfilled from their order). The PDF shows a **التركيب** row and a **رسوم الدفع عند الاستلام** row after the shipping row, each **only when above zero**. Both are inside `grandTotal`; the assembly's tax is inside `taxTotal`.

### AdminInvoiceController

#### `GET /api/v1/admin/invoices`
**Summary:** List all invoices, newest first.

**Query params:** `page` (default 0), `size` (default 20), `sort`.

**Success response `200`:**
```json
{
  "content": [
    {
      "id": 551, "invoiceNumber": "VLR-INV-2026-000001", "status": "ISSUED", "orderId": 2044, "orderNumber": "VLR-260810-2044",
      "buyerName": "Mostafa Yehia", "buyerPhone": "01012345678",
      "grandTotal": "4250.0000", "taxTotal": "553.6200", "netTotal": "3696.3800", "currency": "EGP",
      "pdfUrl": "https://storage.velora.com/invoices/VLR-INV-2026-000001.pdf",
      "issuedAt": "2026-08-10T09:15:22Z", "cancelReason": null
    }
  ],
  "page": 0, "size": 20, "totalElements": 137, "totalPages": 7, "first": true, "last": false, "empty": false
}
```

---

#### `GET /api/v1/admin/invoices/{invoiceId}`
**Summary:** Fetch one invoice by internal id.

**Success response `200`:** single invoice object (shape above).

**Error responses:** `404 RESOURCE_NOT_FOUND`.

---

#### `GET /api/v1/admin/invoices/{invoiceId}/pdf`
**Summary:** Download the invoice PDF, rendered fresh from the stored snapshot every time (byte-identical even if the store profile or prices changed since issuance).

**Success response `200`:** binary PDF. `Content-Type: application/pdf`; `Content-Disposition: inline; filename="VLR-INV-2026-000001.pdf"`.

**Error responses:** `404 RESOURCE_NOT_FOUND`, `404 ORDER_NOT_FOUND` (underlying order couldn't be reloaded — data integrity edge case).

---

#### `POST /api/v1/admin/invoices/issue/{orderId}`
**Summary:** Manually issue an invoice for a delivered order that has none. Idempotent — if the order already has an invoice, that one is returned rather than consuming a new number.

**Success response `200`:** the issued invoice.

**Error responses:** `404 ORDER_NOT_FOUND`, `409 INVALID_STATUS_TRANSITION` (order not `DELIVERED`).

---

#### `POST /api/v1/admin/invoices/{invoiceId}/cancel`
**Summary:** Void an issued invoice. The number stays consumed; status becomes `CANCELLED`.

**Request body:**
```json
{ "reason": "خطأ في بيانات العميل" }
```
`reason`: required, non-blank, max 255.

**Success response `200`:** the invoice with `status: "CANCELLED"`, `cancelReason` populated.

**Error responses:** `400 VALIDATION_FAILED`, `404 RESOURCE_NOT_FOUND`, `409 INVALID_STATUS_TRANSITION` (already cancelled).

---

#### `GET /api/v1/admin/invoices/reconciliation/uninvoiced`
**Summary:** Reconciliation check — delivered orders with no invoice. Should always be empty; a non-empty result means a delivered order slipped through, fixed by calling `issue/{orderId}`.

**Success response `200`:**
```json
{ "count": 0, "orderIds": [] }
```

---

### CustomerInvoiceController

#### `GET /api/v1/me/invoices/{invoiceNumber}/pdf`
**Auth:** Authenticated (own invoices only)
**Summary:** Download the caller's own invoice PDF by invoice number, scoped server-side. A nonexistent number and one belonging to another customer both produce identical `404 RESOURCE_NOT_FOUND`.

**Success response `200`:** binary PDF, same headers as the admin PDF endpoint.

**Error responses:** `401 UNAUTHORIZED`, `404 RESOURCE_NOT_FOUND`.

---

## Shipping & Geography
Base paths: `/api/v1/shipping`, `/api/v1/geo`, `/api/v1/admin/shipping`

Response language follows `Accept-Language` (`ar` default, or `en`) — not a query parameter.

### ShippingController

#### `POST /api/v1/shipping/quote`
**Auth:** Public — no token required even for signed-in customers, used during guest checkout.
**Summary:** Quote delivery cost and estimate for the current cart to a governorate. Cash-on-delivery assumed. Cheap enough to call on every governorate change.

**How the cost is calculated** (the same calculation places an order):
1. Every cart line is classified by its **product's** `shippingSizeClass` (`SMALL` \| `MEDIUM` \| `LARGE`).
2. Each size class costs `unitCost(destination zone, size) × quantity`, where quantity is the total units of that size in the cart.
3. `uncappedCost` is the sum of those lines.
4. If the zone has a `maxShippingCost` and `uncappedCost` is **above** it, `shippingCost` is the cap and `shippingCapApplied` is `true`. Otherwise `shippingCost = uncappedCost`. A total exactly equal to the cap is not "capped".
5. `codFee` is added on top of `shippingCost` in `estimatedTotal` and is never capped.
6. `assemblyTotal` — the per-piece assembly fee × quantity of every cart line whose product requires assembly — is added to `estimatedTotal` as well (`subtotal + assemblyTotal + shippingCost + codFee`). It is `0` when nothing in the cart needs assembly. It is not shipping and is never capped.

`breakdown` always shows the real, uncapped lines (largest size first) so the screen can explain the number. Weight and the old free-shipping threshold are **no longer used**: `totalWeightGrams` is informational, `freeShippingThreshold` and `amountToFreeShipping` are always `null`, and `freeShippingApplied` is simply "`shippingCost` is zero" (Greater Cairo — free for every cart, no minimum).

Initial prices, EGP per unit, cap 1500 in every zone:

| Zone | SMALL | MEDIUM | LARGE |
|---|---|---|---|
| `GREATER_CAIRO` (Cairo, Giza, Qalyubia) | 0 | 0 | 0 |
| `DELTA` | 100 | 200 | 400 |
| `ALEXANDRIA` | 120 | 250 | 450 |
| `CANAL` (Port Said, Ismailia, Suez) | 120 | 250 | 450 |
| `UPPER_EGYPT` (Beni Suef, Faiyum, Minya, Asyut, Sohag, Qena) | 150 | 300 | 550 |
| `REMOTE` (North Sinai, Red Sea, New Valley, Matrouh) | 200 | 400 | 700 |

`REMOTE` is an estimate pending a courier contract. Admin can change any of these — see `PUT /api/v1/admin/shipping/rates`.

**Headers:** `Authorization` (optional — resolves the caller's own cart), `X-Guest-Token` (optional — resolves a guest cart; signature verified the same as on cart endpoints), `Accept-Language` (optional).

**Request body:**
```json
{ "governorateId": "Long — required", "cartId": "Long — optional, defaults to the caller's active cart" }
```

**Success response `200`:**
```json
{
  "governorateId": 8, "governorateName": "أسيوط", "zoneName": "صعيد مصر",
  "shippingCost": "1500.00", "shippingCapApplied": true, "uncappedCost": "2950.00",
  "breakdown": [
    { "sizeClass": "LARGE", "quantity": 4, "unitCost": "550.00", "lineCost": "2200.00" },
    { "sizeClass": "SMALL", "quantity": 5, "unitCost": "150.00", "lineCost": "750.00" }
  ],
  "codFee": "0.00", "assemblyTotal": "0.00",
  "freeShippingApplied": false, "freeShippingThreshold": null, "amountToFreeShipping": null,
  "deliveryDaysMin": 2, "deliveryDaysMax": 5,
  "orderSubtotal": "42000.00", "totalWeightGrams": 3400, "estimatedTotal": "43500.00"
}
```
`baseCost` was removed from this response — `uncappedCost` replaces it.

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | `governorateId` missing/null |
| 400 | CART_EMPTY | No cart could be resolved, or the resolved/explicit cart isn't `ACTIVE` |
| 401 | TOKEN_INVALID | `X-Guest-Token` signature is wrong, or unsigned and the transition flag is off |
| 404 | RESOURCE_NOT_FOUND | `governorateId` doesn't match any governorate |
| 409 | GOVERNORATE_NOT_SERVED | The governorate is closed for delivery (in no shipping zone, or in a zone with no rates) |
| 409 | SHIPPING_SIZE_MISSING | A cart line's product has no `shippingSizeClass`; the `detail` names the SKU. Never guessed — the product needs a size set in the admin |
| 409 | SHIPPING_RATE_NOT_CONFIGURED | The destination zone has no rate for a size class present in the cart |

---

### GeoController

#### `GET /api/v1/geo/governorates`
**Auth:** Public
**Summary:** All 27 Egyptian governorates with their shipping terms, for the address form and shipping estimator. Unserved governorates are still returned, with `served: false`, rather than hidden.

**Success response `200`:**
```json
[
  {
    "id": 19, "code": "CAI", "name": "القاهرة", "zoneName": "القاهرة الكبرى",
    "shippingRates": [
      { "sizeClass": "SMALL", "unitCost": "0.00" },
      { "sizeClass": "MEDIUM", "unitCost": "0.00" },
      { "sizeClass": "LARGE", "unitCost": "0.00" }
    ],
    "maxShippingCost": "1500.00", "deliveryDaysMin": 1, "deliveryDaysMax": 3, "served": true
  },
  { "id": 16, "code": "SSI", "name": "جنوب سيناء", "zoneName": null, "shippingRates": [], "maxShippingCost": null, "deliveryDaysMin": null, "deliveryDaysMax": null, "served": false }
]
```
**Currently closed** (`served: false`): **South Sinai (`SSI`), Aswan (`ASW`) and Luxor (`LUX`)**. South Sinai is closed as a whole — the schema has no city level, so Sharm El-Sheikh cannot be closed on its own, and Dahab, Nuweiba, Taba, Ras Sudr and Saint Catherine are closed with it. Closed governorates stay in this list so the address form can show them and say "we don't deliver there"; the client should read `served`, never hard-code the three. `POST /api/v1/shipping/quote`, `POST /api/v1/orders` and `POST`/`PUT /api/v1/me/addresses` all refuse them with `409 GOVERNORATE_NOT_SERVED`. Staff can reopen or close governorates from the admin panel — see `PUT`/`DELETE /api/v1/admin/shipping/governorates/{governorateId}/zone`.
`shippingRates` is the cost of **one unit** of each size class (replaces the single `shippingCost` field, which was removed). The final price for a cart comes from `POST /api/v1/shipping/quote`.

---

### AdminShippingController — `/api/v1/admin/shipping`

#### `GET /api/v1/admin/shipping/zones`
**Summary:** Active shipping zones with their per-size rates, cap, and covered governorates (Arabic names). Plain array, not paginated.

**Success response `200`:**
```json
[
  {
    "zoneId": 1, "code": "GREATER_CAIRO", "nameAr": "القاهرة الكبرى", "nameEn": "Greater Cairo",
    "rates": [
      { "sizeClass": "SMALL", "unitCost": "0.00" },
      { "sizeClass": "MEDIUM", "unitCost": "0.00" },
      { "sizeClass": "LARGE", "unitCost": "0.00" }
    ],
    "maxShippingCost": "1500.00", "codFee": "0.00",
    "deliveryDaysMin": 1, "deliveryDaysMax": 3, "active": true,
    "governorates": ["القاهرة", "الجيزة", "القليوبية"]
  },
  {
    "zoneId": 5, "code": "UPPER_EGYPT", "nameAr": "صعيد مصر", "nameEn": "Upper Egypt",
    "rates": [], "maxShippingCost": null, "codFee": null,
    "deliveryDaysMin": 0, "deliveryDaysMax": 0, "active": true, "governorates": []
  }
]
```
`rates` holds the cost of **one unit** per size class and is empty (and `codFee` `null`, delivery days `0`) when the zone has no active rate yet. `baseCost` and `freeShippingOver` were removed from this response.

---

#### `GET /api/v1/admin/shipping/governorates`
**Summary:** Every governorate, served or closed, with the zone it is in. Unlike `GET /zones` — which only lists governorates that are inside a zone — this includes the closed ones, so the panel can offer to reopen them. Plain array, 27 items, in display order.

**Success response `200`:**
```json
[
  { "id": 1, "code": "CAI", "nameAr": "القاهرة", "nameEn": "Cairo", "zoneId": 1, "zoneCode": "GREATER_CAIRO", "served": true },
  { "id": 24, "code": "ASW", "nameAr": "أسوان", "nameEn": "Aswan", "zoneId": null, "zoneCode": null, "served": false }
]
```
`served` is `true` when the governorate is in an active zone that has rates. A governorate that is closed has `zoneId` and `zoneCode` `null`.

---

#### `PUT /api/v1/admin/shipping/governorates/{governorateId}/zone`
**Summary:** Open a governorate for delivery by putting it in a zone — or move it to a different zone. A governorate belongs to exactly one zone, so the URL is the governorate's, not the zone's, and there is no way to put it in two. Idempotent: assigning it to the zone it is already in changes nothing.

**Request body:**
```json
{ "zoneId": 5 }
```
`zoneId` required. The zone must be active and must have an active rate for **every** size class (`SMALL`, `MEDIUM`, `LARGE`) — otherwise the governorate would look open here and still fail at the customer's checkout.

**Success response `200`:** empty body. Takes effect immediately for quotes, checkout and new addresses.

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | `zoneId` missing |
| 404 | RESOURCE_NOT_FOUND | `governorateId` or `zoneId` doesn't exist (an inactive zone counts as not found) |
| 409 | SHIPPING_RATE_NOT_CONFIGURED | The zone has no active rate for one or more size classes; the governorate is left as it was |

---

#### `DELETE /api/v1/admin/shipping/governorates/{governorateId}/zone`
**Summary:** Close a governorate for delivery by removing it from its zone. The governorate stays active and stays in `GET /api/v1/geo/governorates` with `served: false`; quote, checkout and saved addresses refuse it with `409 GOVERNORATE_NOT_SERVED`. Idempotent: closing one that is already closed succeeds. Reopen it with the `PUT` above.

An address a customer saved *before* the governorate closed is not deleted; it is simply refused at checkout until the governorate reopens.

**Success response `200`:** empty body.

**Error responses:** `404 RESOURCE_NOT_FOUND` — `governorateId` doesn't exist.

---

#### `PUT /api/v1/admin/shipping/rates`
**Summary:** Set (create or replace) the price of **one size class** in a zone. One row exists per (zone, size class); this replaces that row rather than adding a second one.

**Request body:**
```json
{
  "zoneId": 5, "sizeClass": "MEDIUM", "baseCost": "300.00",
  "codFee": "0.00", "deliveryDaysMin": 2, "deliveryDaysMax": 5
}
```
`zoneId`, `sizeClass` (`SMALL` \| `MEDIUM` \| `LARGE`) and `baseCost` are required. `baseCost` is the cost of **one unit** of that size, `>= 0` (zero means free). `codFee` and `deliveryDaysMin`/`Max` describe the **whole zone**, not one size: when sent they are written to every size row of the zone, and `null` leaves them unchanged. `deliveryDaysMin`/`Max` are `>= 0`.

`maxWeightGrams`, `costPerExtraKg` and `freeShippingOver` were removed from this request — shipping no longer uses weight or a free threshold. The cap is set separately, below.

**Success response `200`:**
```json
{ "id": 14 }
```

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | `zoneId`/`sizeClass`/`baseCost` missing, `baseCost` negative, or `deliveryDaysMin > deliveryDaysMax` |
| 400 | INVALID_REQUEST_BODY | `sizeClass` is not one of the three values |
| 404 | RESOURCE_NOT_FOUND | `zoneId` doesn't match any shipping zone |

---

#### `PUT /api/v1/admin/shipping/zones/{zoneId}/max-shipping-cost`
**Summary:** Set or remove a zone's shipping cap — the most one order's shipping can cost in that zone.

**Request body:**
```json
{ "maxShippingCost": "1500.00" }
```
`maxShippingCost >= 0`. `null` removes the cap (shipping is then the plain per-unit total).

**Success response `200`:** empty body.

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | `maxShippingCost` is negative |
| 404 | RESOURCE_NOT_FOUND | `zoneId` doesn't match any shipping zone |

---

## Custom Requests
Base paths: `/api/v1/custom-requests` (public), `/api/v1/admin/custom-requests` (admin)

A customer asks for a different size of a ready-made product, a product made to order, or fully custom work. **A request is not a sale.** It reserves no stock — not even a size request on a product that is in stock — and it has no price until staff quote one and the customer accepts. Converting an accepted request into an order is not built yet.

| `type` | Meaning | Product | Dimensions |
|---|---|---|---|
| `SIZE_VARIANT` | A different size of a product we sell | **Required**, must be a ready-made product on sale | **At least one** of `widthCm` / `heightCm` / `depthCm` |
| `MADE_TO_ORDER` | A product made after the order | Optional | Optional |
| `CUSTOM_WORK` | Fully custom work | Optional | Optional |

**Statuses**

```
NEW ──► CONTACTED ──► QUOTED ──► ACCEPTED ──► CONVERTED   (CONVERTED: not reachable yet)
  └────────┴───────────┴──► REJECTED
```

| From | Staff can set directly | Also |
|---|---|---|
| `NEW` | `CONTACTED`, `REJECTED` | giving a quote makes it `QUOTED` |
| `CONTACTED` | `REJECTED` | giving a quote makes it `QUOTED` |
| `QUOTED` | `ACCEPTED`, `REJECTED` | a quote can be revised |
| `ACCEPTED`, `REJECTED`, `CONVERTED` | nothing — final | |

`QUOTED` is only ever reached by quoting, so a quoted request always has a quote, and **`ACCEPTED` is impossible without one**. `CONVERTED` is refused for now; `convertedOrderId` exists and is always `null`.

The `requestNumber` (`REQ-2026-000001`) is sequential **with no gaps**, per year, and requests are never deleted — a rejected request is `REJECTED`, not removed. It is the number the customer is given, so quote it back to them exactly.

### CustomRequestController — public

#### `POST /api/v1/custom-requests`
**Auth:** Public — no token. If a Bearer token is sent, the request is linked to that account (`customerId`); otherwise it is a guest request.
**Summary:** Submit a custom request. **Limited to 10 per hour per client IP** (`429 RATE_LIMITED` beyond that; see *Rate limits and the client IP* in Conventions).

**Request body:**
```json
{
  "type": "SIZE_VARIANT",
  "productId": 501,
  "contactName": "محمد أحمد",
  "phone": "01012345678",
  "altPhone": null,
  "email": "customer@example.com",
  "governorateId": 1,
  "area": "مدينة نصر",
  "streetAddress": "12 شارع التسعين",
  "widthCm": 120, "heightCm": 80, "depthCm": null,
  "quantity": 2,
  "notes": "أريده أعرض قليلاً"
}
```
Required: `type`, `contactName` (max 150), `phone`, `governorateId`. `phone`/`altPhone` are normalized to E.164 (`+201012345678`). `email` must be well-formed, max 255. `widthCm`/`heightCm`/`depthCm` are centimetres, positive, up to 10000. `quantity` defaults to 1 (1–10000). `notes` up to 1000 characters. `area` ≤ 150, `streetAddress` ≤ 255.

**Success response `201`:**
```json
{
  "id": 77,
  "requestNumber": "REQ-2026-000001",
  "status": "NEW",
  "attachmentToken": "77.1791040000.q3x…",
  "attachmentTokenExpiresAt": "2026-10-03T12:00:00Z",
  "createdAt": "2026-10-02T12:00:00Z"
}
```
`attachmentToken` is **returned once and cannot be recovered**. It is the only proof the caller created this request, and `POST /{id}/attachments` requires it (below). Keep it for as long as the customer might still add photos; it is valid for **24 hours**. It is signed and bound to this one request, so it is useless for any other.

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | A required field is missing or too long, a number is out of range, the email is malformed; or for `SIZE_VARIANT`: no `productId`, no dimension at all, or a product that is not ready-made |
| 400 | INVALID_PHONE_FORMAT | `phone` or `altPhone` is not a valid Egyptian mobile number |
| 404 | PRODUCT_NOT_FOUND | `productId` is unknown, a draft or archived — a public caller cannot tell these apart |
| 404 | RESOURCE_NOT_FOUND | `governorateId` doesn't exist. A **closed** governorate is accepted: a request is not a delivery. Staff see `governorateServed: false` on it |
| 429 | RATE_LIMITED | More than 10 requests in an hour from this IP |

---

#### `POST /api/v1/custom-requests/{id}/attachments`
**Auth:** Public, guarded by the request token.
**Summary:** Attach one reference image — e.g. a photo of the part the customer wants changed. Call it once per image. **Limited to 30 attempts per hour per client IP**, failed attempts included.

**Headers:** `X-Request-Token` — the `attachmentToken` from creation. A signed-in customer uploading to **their own** request may omit it.
**Body:** `multipart/form-data`, part `file`.

**Limits** (the same as product images, and they are enforced on the file's real bytes): JPEG, PNG, WebP or AVIF; **up to 5 MB**; **up to 5 images per request**. The file's first bytes must actually be an image of one of those types: a text file, a PDF, a GIF, or HTML renamed to `.png` is refused, whatever `Content-Type` it declares. The stored extension and `contentType` come from the detected type, never from the filename.

Only while the request is `NEW`: once staff have contacted the customer, it stops taking images.

**Success response `201`:**
```json
{ "id": 301, "url": "http://localhost:8081/uploads/custom-requests/2026/10/9f2c….png", "contentType": "image/png", "sizeBytes": 183422, "createdAt": "2026-10-02T12:01:00Z" }
```

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | FILE_REQUIRED | No `file` part |
| 400 | VALIDATION_FAILED | The file is empty, over 5 MB, not an allowed type, not really an image, or the request already has 5 images (`detail` says which) |
| 404 | CUSTOM_REQUEST_NOT_FOUND | No such request — **and also** a missing, wrong, tampered, expired or other-request token. These are deliberately indistinguishable, so request ids cannot be probed |
| 409 | CUSTOM_REQUEST_CLOSED | The request is no longer `NEW` |
| 429 | RATE_LIMITED | More than 30 attempts in an hour from this IP |

---

### AdminCustomRequestController — `/api/v1/admin/custom-requests`
All admin-only. Changes are audited: see *Audit Log*.

#### `GET /api/v1/admin/custom-requests`
**Summary:** Paged list, newest first.

**Query params** (all optional, they compose):
| Name | Type | Meaning |
|---|---|---|
| `status` | enum | `NEW` \| `CONTACTED` \| `QUOTED` \| `ACCEPTED` \| `REJECTED` \| `CONVERTED` |
| `type` | enum | `SIZE_VARIANT` \| `MADE_TO_ORDER` \| `CUSTOM_WORK` |
| `governorateId` | long | |
| `q` | string | A **phone number** in any form (`01012345678`, `+201012345678`, `201012345678`) matches it exactly; any other text matches the contact name or request number, case-insensitively, as a substring. `%` and `_` are plain characters |
| `from` / `to` | date `YYYY-MM-DD` | Created on or after / on or before that day, **inclusive**, in Cairo time |
| `page` / `size` / `sort` | | `size` default 20; default sort `createdAt,desc` |

An unknown `status` or `type` is `400 VALIDATION_FAILED`.

**Success response `200`:**
```json
{
  "content": [
    {
      "id": 77, "requestNumber": "REQ-2026-000001", "type": "SIZE_VARIANT", "status": "QUOTED",
      "contactName": "محمد أحمد", "phone": "01012345678", "governorateName": "القاهرة",
      "quantity": 2, "quotedAmount": 4500.5000, "attachmentCount": 3,
      "createdAt": "2026-10-02T12:00:00Z", "updatedAt": "2026-10-02T15:10:00Z"
    }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1, "first": true, "last": true, "empty": false
}
```
`phone` is in local format, for display.

---

#### `GET /api/v1/admin/custom-requests/{id}`
**Summary:** One request with everything, including its images.

**Success response `200`:**
```json
{
  "id": 77, "requestNumber": "REQ-2026-000001", "type": "SIZE_VARIANT", "status": "QUOTED",
  "product": { "id": 501, "slug": "classic-wallpaper", "name": "ورق حائط كلاسيك", "fulfillmentType": "READY_MADE" },
  "customerId": null,
  "contactName": "محمد أحمد", "phone": "01012345678", "altPhone": null, "email": "customer@example.com",
  "governorateId": 1, "governorateName": "القاهرة", "governorateServed": true, "area": "مدينة نصر", "streetAddress": "12 شارع التسعين",
  "widthCm": 120.00, "heightCm": 80.00, "depthCm": null, "quantity": 2,
  "notes": "أريده أعرض قليلاً",
  "attachments": [ { "id": 301, "url": "http://localhost:8081/uploads/custom-requests/2026/10/9f2c….png", "contentType": "image/png", "sizeBytes": 183422, "createdAt": "2026-10-02T12:01:00Z" } ],
  "quotedAmount": 4500.5000, "adminNote": "شامل التركيب", "convertedOrderId": null,
  "createdAt": "2026-10-02T12:00:00Z", "updatedAt": "2026-10-02T15:10:00Z"
}
```
`product` is `null` when the request is not about a product; `customerId` is `null` for a guest. **`governorateServed` is `false` when we do not deliver to that governorate right now** — a request from a closed governorate is accepted (a request is not a delivery), but it is the signal for staff to check that the work can be delivered *before* quoting it. It is worked out on every read, not stored, so it turns `true` again if the governorate reopens, and it is on every response that returns a request (`GET`, `PATCH status`, `PATCH quote`). The list rows do not carry it. `adminNote` is only the **latest** note and `quotedAmount` only the **latest** quote — earlier ones are in the audit log.

**Error responses:** `404 CUSTOM_REQUEST_NOT_FOUND`.

---

#### `PATCH /api/v1/admin/custom-requests/{id}/status`
**Summary:** Move a request along its statuses. Audited (`CUSTOM_REQUEST_STATUS_CHANGED`).

**Request body:**
```json
{ "status": "CONTACTED", "note": "اتصلنا بالعميل" }
```
`status` required. Settable here: `CONTACTED`, `ACCEPTED`, `REJECTED` — see the table above. `note` (max 1000) becomes the request's latest `adminNote` and the audit entry's reason; **it is required for `REJECTED`**.

**Success response `200`:** the updated request (same shape as `GET /{id}`).

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | `status` missing; or `REJECTED` without a note |
| 400 | INVALID_REQUEST_BODY | `status` is not one of the six values |
| 404 | CUSTOM_REQUEST_NOT_FOUND | |
| 409 | CUSTOM_REQUEST_QUOTE_REQUIRED | `ACCEPTED` from `NEW` or `CONTACTED`: quote it first |
| 409 | INVALID_STATUS_TRANSITION | Anything else the table does not allow: setting `QUOTED` (quote instead), setting `CONVERTED` (not available yet), repeating the current status, or leaving `ACCEPTED` / `REJECTED` / `CONVERTED` |

---

#### `PATCH /api/v1/admin/custom-requests/{id}/quote`
**Summary:** Give or change the quote. Audited (`CUSTOM_REQUEST_QUOTED`, old and new amount).

**Request body:**
```json
{ "amount": 4500.50, "note": "شامل التركيب" }
```
`amount` required: the **tax-inclusive total for the whole request, all units** — what the customer would pay. Greater than zero, at most 100,000,000, rounded to four decimals. `note` is optional; when sent it replaces the latest `adminNote`.

From `NEW` or `CONTACTED` this also moves the request to `QUOTED`. While `QUOTED` it can be revised as often as needed. Once the customer has accepted, or the request is rejected, it can no longer be quoted.

**Success response `200`:** the updated request.

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | `amount` missing, zero, negative or implausibly large |
| 404 | CUSTOM_REQUEST_NOT_FOUND | |
| 409 | INVALID_STATUS_TRANSITION | The request is `ACCEPTED`, `REJECTED` or `CONVERTED` |

---

## Portfolio
Base paths: `/api/v1/portfolio` (public), `/api/v1/admin/portfolio` (admin)

Finished custom work shown on the storefront. **A separate entity, not products of type `CUSTOM_WORK`:** a portfolio piece has no price, no variants and no stock, and putting it in the product table would distort catalogue reports, the dashboard and stock turnover. It never appears in the catalogue, the cart or any stock figure.

**Lifecycle** — two independent flags, and the rule between them:

| State | `published` | `archivedAt` | Public sees it |
|---|---|---|---|
| Draft (new items start here) | `false` | `null` | no |
| Live | `true` | `null` | yes |
| Archived | `false` (forced) | set | no |

- **Archive, never delete** (`DELETE`): sets `archivedAt` and forces `published: false`. The row **and its image files** are kept. Repeating it changes nothing.
- **Restore** (`PATCH .../restore`): clears `archivedAt`. The item comes back as a **draft**, never straight to live — going live again is a deliberate `publish`.
- An **archived item cannot be published** (`409 PORTFOLIO_ITEM_ARCHIVED`) and takes **no image changes** until it is restored. The database also refuses an item that is both archived and published.
- **Every state change is audited** (`PORTFOLIO_STATUS_CHANGED`, who and `DRAFT`/`LIVE`/`ARCHIVED` before and after — see *Audit Log*); calls that change nothing record nothing.
- The **slug is unique across archived items too**: a link already shared on WhatsApp or Facebook never starts showing a different piece, and a new item with the same title cannot take an archived item's slug.

**Slug.** `slug` is the public URL segment: `/portfolio/iron-gate-3` (Latin letters, digits, dashes — max 150). On create it is **generated from the Arabic title** (`titleAr`, falling back to `titleEn`) by the same transliteration product slugs use, with `-2`, `-3` … when the generated one is taken. An admin may send `slug` instead: it is normalised the same way, and **an explicit slug that is already taken is `409 SLUG_ALREADY_EXISTS`** (it is not silently renamed). If no Latin slug can be derived from the title and none is sent, the call fails `400 VALIDATION_FAILED`. Changing a slug later (`PUT` with a different `slug`) **breaks links already shared — there is no redirect**. Editing the title does not change the slug.

**Images** follow the product-image rules exactly, through the same storage service: JPEG, PNG, WebP or AVIF, up to 5 MB, **identified by their real bytes**. The client filename and `Content-Type` are never trusted, and the stored extension comes from the detected type (a PNG uploaded as `page.html` is stored as `.png`; HTML sent as `image/png` is refused). At most **20 images per item**; the first uploaded is the main image; exactly one is main at any time.

---

### PortfolioController — public

#### `GET /api/v1/portfolio`
**Auth:** Public — no token.
**Summary:** Published, non-archived items, paginated. Order is fixed: `displayOrder` ascending, then `completedAt` newest first (undated last), then newest id. The `sort` parameter is ignored. Titles follow `Accept-Language` (`ar` default; `en` falls back to Arabic when the item has no English title).

**Query params:** `categoryId` (optional), `page` (default 0), `size` (default 12, max 50).

**Success response `200`:** `PageResponse<PortfolioSummaryResponse>`
```json
{
  "content": [
    {
      "id": 14, "slug": "bwaba-hdyd-mshghwl", "title": "بوابة حديد مشغول",
      "imageUrl": "https://cdn.velora.com/uploads/portfolio/2026/10/3f2a9c.jpg", "imageAlt": "بوابة حديد مشغول",
      "categorySlug": "gates", "categoryName": "بوابات", "completedAt": "2026-09-14"
    }
  ],
  "page": 0, "size": 12, "totalElements": 1, "totalPages": 1, "first": true, "last": true, "empty": false
}
```
`imageUrl`/`imageAlt` are the main image (`null` when the item has none). `categorySlug`/`categoryName`/`completedAt` may be `null`.

---

#### `GET /api/v1/portfolio/{slug}`
**Auth:** Public — no token.
**Summary:** One item with all its images, in display order.

**Success response `200`:**
```json
{
  "id": 14, "slug": "bwaba-hdyd-mshghwl", "title": "بوابة حديد مشغول",
  "description": "بوابة حديد مشغول يدويًا بتصميم مفرغ.",
  "categorySlug": "gates", "categoryName": "بوابات", "completedAt": "2026-09-14",
  "images": [
    { "url": "https://cdn.velora.com/uploads/portfolio/2026/10/3f2a9c.jpg", "alt": "واجهة البوابة", "main": true, "displayOrder": 0 }
  ]
}
```

**Error responses:**
| Status | Code | When |
|---|---|---|
| 404 | PORTFOLIO_ITEM_NOT_FOUND | Unknown slug, **or** the item is unpublished, **or** archived — one answer for all three, so the public cannot tell a draft from a typo |

---

### AdminPortfolioController
All routes require `ROLE_ADMIN` (`403` otherwise).

#### `GET /api/v1/admin/portfolio`
**Summary:** All items including drafts, ordered by `displayOrder` then newest. **Archived items are left out unless `includeArchived=true`.**

**Query params:** `includeArchived` (default `false`), `categoryId`, `page`, `size` (default 20, max 100).

**Success response `200`:** `PageResponse<PortfolioAdminResponse>`.

#### `GET /api/v1/admin/portfolio/{id}`
**Summary:** One item, archived or not. **Success response `200`:** `PortfolioAdminResponse`. **Errors:** `404 PORTFOLIO_ITEM_NOT_FOUND`.

```json
{
  "id": 14, "slug": "bwaba-hdyd-mshghwl",
  "titleAr": "بوابة حديد مشغول", "titleEn": "Wrought iron gate",
  "descriptionAr": "…", "descriptionEn": "…",
  "categoryId": 31, "categoryName": "بوابات", "completedAt": "2026-09-14", "displayOrder": 0,
  "published": true, "archivedAt": null,
  "images": [
    { "id": 77, "key": "portfolio/2026/10/3f2a9c.jpg", "url": "https://cdn.velora.com/uploads/portfolio/2026/10/3f2a9c.jpg",
      "altTextAr": "واجهة البوابة", "altTextEn": null, "main": true, "displayOrder": 0 }
  ],
  "createdAt": "2026-10-03T09:00:00Z", "updatedAt": "2026-10-03T09:10:00Z"
}
```

---

#### `POST /api/v1/admin/portfolio`
**Summary:** Create an item. It starts **unpublished** (a draft); add images, then publish.

**Request body:**
```json
{
  "slug": null,
  "titleAr": "بوابة حديد مشغول", "titleEn": "Wrought iron gate",
  "descriptionAr": "…", "descriptionEn": "…",
  "categoryId": 31, "completedAt": "2026-09-14", "displayOrder": 0
}
```
`titleAr` required (max 255). Everything else optional; `displayOrder` defaults to `0` (lower shows first); `categoryId` must exist.

**Success response `201`:** `PortfolioAdminResponse`, `published: false`.

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | `titleAr` missing/blank, or no Latin slug derivable and none given |
| 404 | CATEGORY_NOT_FOUND | `categoryId` does not exist |
| 409 | SLUG_ALREADY_EXISTS | An explicit `slug` is already used (including by an archived item) |

---

#### `PUT /api/v1/admin/portfolio/{id}`
**Summary:** Replace an item's content. **Send the whole form back:** `titleEn`, `descriptionAr`, `descriptionEn`, `categoryId` and `completedAt` are **cleared when omitted**. `slug` and `displayOrder` are kept when omitted. Does not change `published` or `archivedAt` and works on an archived item too.

**Request body:** same fields as create. **Success response `200`:** `PortfolioAdminResponse`.
**Errors:** `400 VALIDATION_FAILED`, `404 PORTFOLIO_ITEM_NOT_FOUND`, `404 CATEGORY_NOT_FOUND`, `409 SLUG_ALREADY_EXISTS` (a different `slug` that another item uses).

---

#### `PATCH /api/v1/admin/portfolio/{id}/publish`
**Summary:** Show or hide an item. **Explicit state, not a toggle** — sending the same body twice leaves the same state.

**Request body:** `{ "published": true }` (required boolean).
**Success response `200`:** `PortfolioAdminResponse`.
**Errors:** `400 VALIDATION_FAILED` (`published` missing), `404 PORTFOLIO_ITEM_NOT_FOUND`, `409 PORTFOLIO_ITEM_ARCHIVED` (publishing an archived item; hiding one is always fine).

---

#### `DELETE /api/v1/admin/portfolio/{id}`
**Summary:** **Archive** (not delete). Hides the item everywhere public and forces `published: false`; the row and image files stay. Idempotent.

**Success response `200`:** `PortfolioAdminResponse` with `archivedAt` set. **Errors:** `404 PORTFOLIO_ITEM_NOT_FOUND`.

---

#### `PATCH /api/v1/admin/portfolio/{id}/restore`
**Summary:** Bring an archived item back **as an unpublished draft**. Idempotent on an item that is not archived.

**Success response `200`:** `PortfolioAdminResponse` with `archivedAt: null`, `published: false`. **Errors:** `404 PORTFOLIO_ITEM_NOT_FOUND`.

---

#### `GET /api/v1/admin/portfolio/{id}/images`
**Summary:** The item's images in display order. **Success response `200`:** array of image objects (as in `PortfolioAdminResponse.images`). **Errors:** `404 PORTFOLIO_ITEM_NOT_FOUND`.

#### `POST /api/v1/admin/portfolio/{id}/images`
**Summary:** Upload an image. `multipart/form-data`, part name `file`. See *Images* above for the rules.

**Success response `201`:** one image object.

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | No file, empty file, over 5 MB, the bytes are not a JPEG/PNG/WebP/AVIF image (whatever the filename or `Content-Type` say), or the item already has 20 images |
| 404 | PORTFOLIO_ITEM_NOT_FOUND | |
| 409 | PORTFOLIO_ITEM_ARCHIVED | Restore the item first |

#### `PATCH /api/v1/admin/portfolio/{id}/images/{imageId}`
**Summary:** Update image metadata. Omitted fields are unchanged.

**Request body:** `{ "altTextAr": "…", "altTextEn": "…", "main": true, "displayOrder": 0 }` — `main: true` makes this the main image and unsets the previous one; `false` is ignored (there is always one main image).
**Success response `200`:** one image object.
**Errors:** `404 PORTFOLIO_ITEM_NOT_FOUND`, `404 RESOURCE_NOT_FOUND` (the image is not on this item — one answer whether it does not exist or belongs to another item), `409 PORTFOLIO_ITEM_ARCHIVED`.

#### `DELETE /api/v1/admin/portfolio/{id}/images/{imageId}`
**Summary:** Delete one image **and its file**. If it was the main image, the first remaining one becomes main.

**Success response `204`.** **Errors:** `404 PORTFOLIO_ITEM_NOT_FOUND`, `404 RESOURCE_NOT_FOUND`, `409 PORTFOLIO_ITEM_ARCHIVED`.

---

## Dashboard
Base path: `/api/v1/admin/dashboard`

### `GET /api/v1/admin/dashboard`
**Summary:** Single-call store overview — sales, COD position, action queues, stale orders, low stock, top products, delivery health, customer/catalog stats, and a prioritized alert list.

**Success response `200`:**
```json
{
  "sales": { "revenueToday": "4250.0000", "ordersToday": 3, "revenueThisWeek": "31200.0000", "ordersThisWeek": 22, "revenueThisMonth": "118400.0000", "ordersThisMonth": 87, "averageOrderValue": "1360.92" },
  "codPosition": { "orderCount": 14, "amount": "19850.0000", "oldestDays": 9 },
  "actionQueues": [ { "status": "PENDING", "label": "بانتظار التأكيد", "count": 5 }, { "status": "CONFIRMED", "label": "مؤكد — جاهز للتجهيز", "count": 8 } ],
  "staleOrders": [ { "orderId": 1042, "orderNumber": "VLR-260810-1042", "status": "PENDING", "statusLabel": "بانتظار التأكيد", "customerName": "Mona Adel", "grandTotal": "2450.0000", "hoursWaiting": 31 } ],
  "lowStock": [ { "variantId": 501, "sku": "WATCH-BLK-42", "productName": "ساعة فيلورا الكلاسيكية", "qtyOnHand": 3, "qtyReserved": 3, "available": 0, "minStockLevel": 5 } ],
  "stuckReservations": 2,
  "topProducts": [ { "sku": "PERF-OUD-100", "productName": "عطر العود الفاخر", "unitsSold": 34, "revenue": "40800.0000" } ],
  "deliveryHealth": { "delivered": 61, "failed": 4, "refused": 6, "returnedToSeller": 2, "cancelled": 5, "successRatePercent": "87.5" },
  "customers": { "total": 2140, "newThisMonth": 76, "guestOrdersThisMonth": 12, "repeatCustomers": 340 },
  "catalog": { "activeProducts": 128, "draftProducts": 6, "activeVariants": 402, "outOfStockVariants": 9 },
  "alerts": [
    { "severity": "HIGH", "category": "STOCK", "message": "1 منتج نفد من المخزون ولا يظهر للعملاء — راجع التوريد", "actionPath": "/admin/inventory/low-stock" },
    { "severity": "HIGH", "category": "PAYMENT", "message": "مبلغ 19850.0000 ج.م لم يورّده الكوريير، وأقدم طلب مضى عليه 9 يوم — طالب بالتسوية", "actionPath": "/admin/orders?paymentStatus=PENDING" }
  ],
  "generatedAt": "2026-08-12T10:04:11Z"
}
```
`actionQueues` lists orders by status, **including `AWAITING_SCHEDULE`** ("بانتظار تحديد الموعد"), so orders waiting for a delivery date are visible rather than falling off the screen.

`staleOrders` ("nothing has moved for 24 hours") covers the statuses before shipping: `PENDING`, `CONFIRMED`, `AWAITING_SCHEDULE` and `PROCESSING`. **An order whose `scheduledDeliveryAt` is still ahead is not stale, in any of them** — it is waiting for that date, not stuck, and flagging it would be a false alarm, which teaches people to ignore every alert on this screen, the real ones included. It counts again as soon as the date has passed without the order having moved: then it is late, which is what this list is for. An order with no appointment (including one in `AWAITING_SCHEDULE`, where the wait is on staff who have not agreed a date) follows the plain rule. Rescheduling into the future takes a late order off the list.

`sales` is revenue booked (placed, non-cancelled orders), not cash in hand. `codPosition.amount` is money the courier is still holding on delivered-but-unpaid COD orders. `alerts` is severity-ordered (`HIGH` > `MEDIUM` > `LOW`).

**Error responses:** none beyond the global conventions.

---

## Audit Log
Base path: `/api/v1/admin/audit`

Records staff-initiated changes (price edits, stock adjustments, publish/archive, shipping price and cap changes, opening/closing governorates, invoice cancellations, payment adjustments, permission changes, store-profile changes). Order status/fulfilment history is **not** here — it lives on the order's own timeline.

**Shipping events.** These are commercial decisions — they change what customers pay and which orders can be accepted at all — so each one answers "who did this, and what was it before?". Entries are written only when something actually changed: repeating a call, or closing a governorate that is already closed, records nothing, and a refused call records nothing. `oldValue`/`newValue` hold the before and after.

| `action` | Caused by | `entityType` / `entityLabel` | `oldValue` → `newValue` |
|---|---|---|---|
| `GOVERNORATE_SERVICE_CHANGED` | `PUT` / `DELETE /admin/shipping/governorates/{id}/zone` | `GOVERNORATE` / `Aswan (ASW)` | zone code or `CLOSED`, e.g. `CLOSED` → `UPPER_EGYPT` (opened), `UPPER_EGYPT` → `CLOSED` (closed), `CANAL` → `DELTA` (moved) |
| `SHIPPING_RATE_CHANGED` | `PUT /admin/shipping/rates`, price changed | `SHIPPING_RATE` / `DELTA / MEDIUM` | unit cost, e.g. `200.0000` → `210.0000` (`oldValue` is empty for a size priced for the first time) |
| `SHIPPING_RATE_CHANGED` | `PUT /admin/shipping/rates`, COD fee or delivery days changed (they apply to the whole zone) | `SHIPPING_ZONE` / `DELTA terms` | `codFee=0.0000, deliveryDays=2-4` → `codFee=5.0000, deliveryDays=3-6` |
| `SHIPPING_RATE_CHANGED` | `PUT /admin/shipping/zones/{id}/max-shipping-cost` | `SHIPPING_ZONE` / `DELTA cap` | cap, or `none` when there is no cap, e.g. `1500.0000` → `none` |
| `PORTFOLIO_STATUS_CHANGED` | `PATCH /admin/portfolio/{id}/publish`, `DELETE /admin/portfolio/{id}` (archive), `PATCH /admin/portfolio/{id}/restore` | `PORTFOLIO_ITEM` / the item slug | the state, `DRAFT`, `LIVE` or `ARCHIVED`, e.g. `DRAFT` → `LIVE` (published), `LIVE` → `ARCHIVED`, `ARCHIVED` → `DRAFT` (restored). A call that changes nothing (publishing a live item, archiving an archived one) records nothing |

Amounts are stored to four decimals. `entityId` is the governorate id, the rate id, or the zone id respectively, so `GET /api/v1/admin/audit/GOVERNORATE/{governorateId}` returns everything that ever happened to one governorate — "who closed Aswan?". `actorName` is the staff member's name at the time. Filter all governorate events with `GET /api/v1/admin/audit?action=GOVERNORATE_SERVICE_CHANGED`.

**Custom request events** — who contacted, quoted, accepted or rejected a customer. Same rules: only real changes are recorded, and the request keeps just the latest note and quote, so the audit log is the history.

| `action` | Caused by | `entityType` / `entityLabel` | `oldValue` → `newValue`, `reason` |
|---|---|---|---|
| `CUSTOM_REQUEST_STATUS_CHANGED` | `PATCH /admin/custom-requests/{id}/status`, and the move to `QUOTED` when a request is first quoted | `CUSTOM_REQUEST` / `REQ-2026-000001` | status → status, e.g. `NEW` → `CONTACTED`. `reason` is the staff note |
| `CUSTOM_REQUEST_QUOTED` | `PATCH /admin/custom-requests/{id}/quote`, when the amount changed | `CUSTOM_REQUEST` / `REQ-2026-000001` | amount → amount at four decimals, e.g. `4000.0000` → `5500.0000` (`oldValue` is empty for the first quote). `reason` is the note sent with the quote |

**Order events** — a delivery appointment is a commitment made to the customer, and the order keeps only the latest one, so the audit log is the history of what was promised and when it moved. Same rules: only real changes are recorded (setting the same date again records nothing), and a refused call records nothing.

| `action` | Caused by | `entityType` / `entityLabel` | `oldValue` → `newValue`, `reason` |
|---|---|---|---|
| `ORDER_DELIVERY_SCHEDULED` | `PATCH /admin/orders/{id}/schedule`, when the appointment changed | `ORDER` / the order number, e.g. `VLR-260812-9042` | ISO-8601 in Cairo time, e.g. `2026-10-12T10:00:00+03:00` → `2026-10-14T16:00:00+03:00`. `oldValue` is empty for the first appointment. `reason` is the staff note |

`entityId` is the order id, so `GET /api/v1/admin/audit/ORDER/{orderId}` lists every appointment an order ever had. Status moves are not here: they are on the order's own `timeline`.

### `GET /api/v1/admin/audit`
**Summary:** Paginated audit entries, newest first; optionally filter by action or actor.

**Query params:** `action` (one of the `AuditAction` enum values, case-insensitive), `actorId` (ignored if `action` is set), `page`/`size` (default 50)/`sort`.

**Success response `200`:**
```json
{
  "content": [
    {
      "id": 9931, "action": "PRICE_CHANGED", "entityType": "PRODUCT_VARIANT", "entityId": "501", "entityLabel": "WATCH-BLK-42",
      "oldValue": "3200.0000", "newValue": "2950.0000", "reason": "خصم موسمي",
      "actorId": 7, "actorName": "Youssef Nabil", "createdAt": "2026-08-11T14:22:03Z"
    }
  ],
  "page": 0, "size": 50, "totalElements": 412, "totalPages": 9, "first": true, "last": false, "empty": false
}
```

**Error responses:** `400 INVALID_PARAMETER` for an unknown `action` value; the `detail` lists the valid values.

---

### `GET /api/v1/admin/audit/{entityType}/{entityId}`
**Summary:** Full change history for one entity — e.g. every price change a variant ever had.

**Path params:** `entityType` (upper-cased before lookup, e.g. `PRODUCT_VARIANT`), `entityId` (the entity's id as text).

**Query params:** `page`/`size` (default 50)/`sort`.

**Success response `200`:** same envelope as above, filtered to the one entity.

---

## Export
Base path: `/api/v1/admin/exports`

Both endpoints return a downloadable file, not JSON.

**Shared query params (`OrderExportFilter`):** `dateFrom`/`dateTo` (`yyyy-MM-dd`, inclusive), `fulfillmentStatus`, `paymentStatus`, `governorateId`, `excludeCancelled` (default `true`).

### `GET /api/v1/admin/exports/orders/accounting`
**Summary:** Accounting archive as `.xlsx` — one row per order plus a totals row (customer, governorate, subtotal, discount, shipping, assembly, COD fee, grand total, tax, net). Columns in order: ... الإجمالي الفرعي، الخصم، الشحن، **التركيب**، **رسوم الدفع عند الاستلام**، الإجمالي، منه ضريبة، الصافي ...; the totals row sums the same columns.) RTL sheet, real numbers. Capped at 5,000 rows; cancelled orders excluded by default.

**Success response `200`:** binary `.xlsx`. `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`; `Content-Disposition: attachment; filename="velora-orders-<dateFrom>_<dateTo>.xlsx"` (or `velora-orders-<today>.xlsx`).

**Error responses:** `404 RESOURCE_NOT_FOUND` — no orders match the given filters.

---

### `GET /api/v1/admin/exports/orders/picking-list`
**Summary:** Picking/shipping list as a print-ready PDF, grouped by order — customer, phone, full address, landmark, line items with tick boxes, and the amount the courier must collect. Defaults `fulfillmentStatus` to `CONFIRMED` when omitted. COD-collect amount is printed only for unpaid COD orders. Capped at 300 orders.

**Success response `200`:** binary PDF. `Content-Type: application/pdf`; `Content-Disposition: attachment; filename="velora-picking-<dateFrom>_<dateTo>.pdf"`. Unlike the accounting export, an empty match set still renders a valid empty PDF rather than 404.

**Error responses:** `500 INTERNAL_ERROR` — template/PDF rendering failure.

---

## Store Profile
Base path: `/api/v1/admin/settings/store-profile`

The seller's legal identity, printed on every invoice. Single row; changes only affect invoices issued from now on — already-issued invoices keep their originally printed details.

### `GET /api/v1/admin/settings/store-profile`
**Summary:** Current seller details plus a list of fields still empty (and therefore omitted from invoices).

**Success response `200`:**
```json
{
  "legalName": "VELORA", "legalNameEn": "Velora Trading LLC", "address": "القاهرة، مصر",
  "phone": "01090386165", "email": "billing@velora.com", "taxNumber": null, "commercialRegister": null,
  "website": "https://velora.com", "invoiceFooterNote": "شكراً لتسوقكم من فيلورا",
  "missingFields": ["taxNumber", "commercialRegister"]
}
```

---

### `PUT /api/v1/admin/settings/store-profile`
**Summary:** Update the seller details used on future invoices. Leave `taxNumber` empty until the store is tax-registered.

**Request body:**
```json
{
  "legalName": "VELORA", "legalNameEn": "Velora Trading LLC", "address": "القاهرة، مصر",
  "phone": "01090386165", "email": "billing@velora.com", "taxNumber": "123-456-789",
  "commercialRegister": "45789", "website": "https://velora.com", "invoiceFooterNote": "شكراً لتسوقكم من فيلورا"
}
```
`legalName`: required, max 200. `taxNumber`: optional, must be empty or `NNN-NNN-NNN`. `email`: valid email if present. All other fields optional with max-length constraints.

**Success response `200`:** same shape as `GET`, reflecting the saved values.

**Error responses:** `400 VALIDATION_FAILED` — `legalName` blank, `taxNumber` malformed, `email` malformed, or a field over its max length.

---

## Remittance (COD Settlement)
Base path: `/api/v1/admin/remittances`

Reconciles cash-on-delivery cash the courier is holding against what actually gets handed back. A COD order reaches `DELIVERED` while the money is still with the courier; a remittance batch closes that gap by marking the covered orders `PAID` and recording any shortfall.

### `GET /api/v1/admin/remittances/outstanding`
**Summary:** Delivered COD orders not yet settled, oldest first.

**Success response `200`:**
```json
{
  "orderCount": 2, "totalAmount": "5400.0000",
  "orders": [
    { "orderId": 990, "orderNumber": "VLR-260801-0990", "customerName": "Karim Fathy", "governorate": "الجيزة", "amount": "2450.0000", "deliveredAt": "2026-08-03T09:15:00Z", "daysWaiting": 9 },
    { "orderId": 1001, "orderNumber": "VLR-260802-1001", "customerName": "Hana Samir", "governorate": "القاهرة", "amount": "2950.0000", "deliveredAt": "2026-08-05T12:40:00Z", "daysWaiting": 7 }
  ]
}
```

---

### `POST /api/v1/admin/remittances`
**Summary:** Record a settlement batch — marks the covered orders `PAID` and captures the actual amount received (which may fall short of expected).

**Request body:**
```json
{
  "courierName": "بوسطة", "courierReference": "BATCH-2026-08-11", "settlementDate": "2026-08-11",
  "orderIds": [990, 1001], "receivedAmount": "5000.00", "note": "طلب VLR-260802-1001 مرتجع جزئياً"
}
```
`courierName`: required, max 150. `orderIds`: required, at least one. `receivedAmount`: required, `>= 0`. `note`: required whenever `receivedAmount` differs from the sum of the covered orders' totals.

**Success response `201`:**
```json
{
  "id": 44, "reference": "REM-2026-0044", "courierName": "بوسطة", "courierReference": "BATCH-2026-08-11",
  "settlementDate": "2026-08-11", "status": "SHORT", "expectedAmount": "5400.0000", "receivedAmount": "5000.0000",
  "difference": "-400.0000", "orderCount": 2, "note": "طلب VLR-260802-1001 مرتجع جزئياً",
  "orders": [
    { "orderId": 990, "orderNumber": "VLR-260801-0990", "amount": "2450.0000" },
    { "orderId": 1001, "orderNumber": "VLR-260802-1001", "amount": "2950.0000" }
  ],
  "createdAt": "2026-08-11T18:02:44Z"
}
```

**Error responses:**
| Status | Code | When |
|---|---|---|
| 400 | VALIDATION_FAILED | `orderIds` empty/missing, `courierName` blank, `receivedAmount` missing/negative, or the batch total mismatches `receivedAmount` with no `note`, or an order in `orderIds` is not `COD`/not `DELIVERED`/already settled |
| 404 | ORDER_NOT_FOUND | An id in `orderIds` doesn't exist |

---

### `GET /api/v1/admin/remittances`
**Summary:** Past settlements, newest settlement-date first.

**Query params:** `page`/`size` (default 20)/`sort`.

**Success response `200`:** `PageResponse` of the remittance shape above.

---

### `GET /api/v1/admin/remittances/{remittanceId}`
**Summary:** One settlement with its covered orders.

**Success response `200`:** the remittance object.

**Error responses:** `404 RESOURCE_NOT_FOUND`.

---

### `POST /api/v1/admin/remittances/{remittanceId}/cancel`
**Summary:** Void a settlement and return its orders to unpaid. The remittance row is kept (`status: CANCELLED`) for reconciliation history.

**Query params:** `reason` (required, non-blank — appended to the stored note).

**Success response `200`:** the remittance with `status: "CANCELLED"`. The note becomes `<reason>` if the remittance had no note yet, or `<original note> | CANCELLED: <reason>` if one already existed.

**Error responses:** `404 RESOURCE_NOT_FOUND`, `400 INVALID_PARAMETER` (`reason` omitted from the query string entirely — it is a mandatory `@RequestParam` with no default, so Spring rejects the request before the controller runs), `400 VALIDATION_FAILED` (`reason` sent but blank, e.g. `?reason=`), `409 INVALID_STATUS_TRANSITION` (already cancelled).

---

## Health Check
Base path: `/api/v1`

### `GET /api/v1/ping`
**Auth:** Public
**Summary:** Liveness check — confirms the service is up and returns the current server time in UTC.

**Success response `200`:**
```json
{ "status": "ok", "service": "velora-api", "time": "2026-08-12T10:04:11.123456Z" }
```
