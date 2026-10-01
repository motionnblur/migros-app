# AGENTS Guide - migros-app

## Purpose
This file is a working guide for coding agents contributing to this repository.  
It documents architecture, constraints, and safe change patterns so edits stay consistent with the current system.

## Monorepo Layout
- `client/`: Angular 19 standalone SPA.
- `backend/`: Spring Boot 3.5 API (Java 21, JPA, Security, WebSocket).
- `configs/nginx/`: Reverse proxy and rate limiting config.
- `compose.yaml`: Local multi-service orchestration (client, backend, nginx, postgres).

## System Architecture
- Frontend: Angular app served by `ng serve` in local development and by the
  current client container in Compose. `client/Dockerfile` is a development
  server, not a production static-file server.
- API: Spring Boot REST API on `:8080` with stateless JWT auth.
- DB: PostgreSQL.
- Edge proxy: Nginx on `:8080` in Docker setup, with endpoint-specific throttling and websocket forwarding.
- Realtime support: WebSocket endpoint `/ws/support` (users) and `/admin/ws/support`
  (admins, because the `admin_session` cookie is scoped to `/admin`), plus REST
  polling fallback in UI. The handshake is authenticated by session cookie; the
  client-supplied `userMail` query parameter is never trusted.

## Backend Architecture
Backend packages follow a mostly standard layered layout:
- `controller/**`: Request mapping and DTO IO.
- `service/**`: Business logic.
- `repository/**`: Spring Data JPA access.
- `entity/**`: Persistence models.
- `dto/**`: API contracts and service-level transfer objects.
- `config/**`, `filter/**`, `helper/**`: security/config/shared cross-cutting concerns.

### Key backend modules
- Auth + session
  - JWT is validated by `filter/JwtRequestFilter`.
  - Login endpoints issue HttpOnly cookies through `config/security/AuthCookieService`.
  - User session cookie: `user_session`, path `/`.
  - Admin session cookie: `admin_session`, path `/admin`.
  - Session check endpoints:
    - `GET /user/session` -> `{ userMail }`
    - `GET /admin/session` -> `{ adminName }`
  - Admin login failures are logged by `AdminSignupService` with the account
    name, a fixed reason, and the resolved client address only. Never log the
    submitted password or any hash derived from it, including the hash of a
    non-existent account: a password-derived value in a log file is a
    credential-equivalent secret.
  - `service/global/LogService` resolves the client address. It trusts
    `X-Forwarded-For` **only** when the immediate peer matches
    `app.trusted-proxies` (comma-separated addresses/CIDR blocks, parsed by
    `helper/TrustedProxyList`). Empty is the default and means trust nothing, so
    a forged forwarding header cannot spoof the address recorded next to a
    failure. Proxies *append* to the header, so the chain is walked from the
    right and the first entry that is not itself a trusted proxy wins; taking
    the left-most entry records an address the caller chose. Every hop between
    the client and the backend therefore has to be listed. Forwarded values must
    also be IP literals, are length-capped, and hostnames are never resolved;
    anything malformed falls back to the peer address.
- Signup and reset tokens
  - The database is the only store (`PendingSignupStorage`). There is no
    in-memory fallback and no expiry scheduler: a fallback would let a request
    report success for a token that only exists in one process's heap.
  - Storage failures propagate so the request fails instead of reporting success.
  - The token is **committed before** the mail is sent, so a delivered link can
    never reference a token that a later rollback or crash removes. The issuing
    methods (`signup`, `verifyUserMail`) are therefore deliberately **not**
    transactional and `PendingSignupStorage.store` commits on its own. A mail
    failure revokes the already-committed token with `deleteCommitted` and still
    reports `MailSendingFailedException`; a failing cleanup is logged, never the
    token, and never replaces the reported failure. That revocation only happens
    if the failure is translated, so the send must catch Spring's **unchecked**
    `MailException` (`MailSendException` from an SMTP refusal,
    `MailPreparationException` from an unassemblable message) alongside the
    checked `MessagingException`. Catching only the checked one skips the
    revocation and surfaces a raw framework error.
  - `PendingTokenPurpose` binds each token to one flow. `confirm` and
    `confirmUserMail` accept only `SIGNUP`; `resetPassword` accepts only
    `PASSWORD_RESET`. A mismatch is reported as "token not found" so a caller
    cannot distinguish a wrong-purpose token from an unknown one.
  - V6 added `token_purpose` as `NOT NULL` with **no** column default, and
    deleted pre-existing rows whose purpose was unknowable. Do not add a default
    and do not repurpose an existing migration.
- Support subsystem
  - User REST endpoints in `controller/user/support`.
  - Internal support bridge in `controller/internal/InternalSupportController` using `x-internal-key`.
  - Realtime broadcasts in `websocket/SupportChatWebSocketHandler`.
  - Outbound events use a **transactional outbox**:
    - `SupportInternalEventService` is the producer. It only writes a durable
      row; it must be called from inside the transaction that mutates the chat,
      so the message and the event it owes commit or roll back together.
    - `SupportOutboxStore` owns enqueue, claim, and completion as separate
      commits. Worker transitions are fenced on a lease token; a stale worker
      gets `STALE_CLAIM` and changes nothing.
    - `SupportOutboxDispatcher` performs the HTTP call with no transaction and
      no row lock held, and retries with exponential backoff. `SupportOutboxJob`
      schedules delivery and retention.
    - Delivery is **at least once**. The `eventId` is the row's primary key and
      is never regenerated, so every retry resends a byte-identical payload and
      the receiver deduplicates on it. Never mint a new id per attempt.
    - Per-customer ordering: a row is claimable only when no earlier row for the
      same `user_mail` is still `PENDING`/`PROCESSING`. Do not "optimize" the
      claim scan by dropping that predicate; it is what keeps a retry from
      overtaking a conversation's earlier event.
    - Ordering that predicate depends on is only meaningful if `sequence_no` is
      *commit* order. A sequence is handed out at INSERT time, so
      `SupportOutboxStore.enqueue` first takes a `pg_advisory_xact_lock` keyed on
      the mailbox, which is held until the caller's transaction ends. Never
      replace it with a plain insert: an uncommitted earlier row is invisible to
      the claim scan, so a later event would be delivered first.
    - Delivery never terminates. There is no `FAILED` status and no give-up
      threshold; `support.outbox.alert-after-attempts` only raises the log to
      ERROR. A terminal state would both lose the event and unblock the
      customer's queue behind it. V8 re-arms rows an earlier schema parked.
    - The outbound HTTP call has finite connect/read timeouts. They are spent
      one after the other, so their **sum** must stay below the delivery lease
      with a margin for the completion update; startup fails otherwise, because
      a delivery that outlives its lease is delivered twice concurrently. Each
      claim and each retry is timed by reading the clock at that moment, never
      once per batch: a batch is a sequence of independent sends, and a lease
      measured from the start of the batch is already spent by the time the
      event is claimed.
    - Stored payloads may contain customer message text: never log them and
      never expose them through the API. Retention deletes `DELIVERED` rows
      only.
- Security config
  - `SecurityConfiguration` defines open/authenticated/admin-only routes. The
    catch-all is `denyAll`, so a route that is not explicitly opened is denied
    rather than open.
  - CORS and WebSocket allowed origins are driven by `app.allowed-origins` and `app.allowed-origin-patterns`.
  - Only `/actuator/health` and its subpaths are public; `/actuator/**` is
    explicitly denied before the catch-all `denyAll`. Actuator exposes only
    `health` over HTTP with details/components never public; the readiness group
    includes the database and liveness does not.
- Public URLs
  - `PublicUrlProperties` binds `app.frontend-base-url` / `app.backend-base-url`
    (`APP_FRONTEND_BASE_URL` / `APP_BACKEND_BASE_URL`), validates absolute
    http(s) origins with a host and no path/query/fragment/credentials, and
    normalizes one trailing slash in one place.
  - Signup confirmation links use the backend origin; password-reset links use
    the frontend origin. There is no hard-coded `localhost`.
- Support subsystem
  - User REST endpoints in `controller/user/support`.
  - Internal support bridge in `controller/internal/InternalSupportController` using `x-internal-key`.
  - Realtime broadcasts in `websocket/SupportChatWebSocketHandler`.
  - Outbound internal event publishing in `service/support/SupportInternalEventService`.
- Product/order flows
  - User shopping and order history under `controller/user/supply` and `service/user/supply`.
  - Admin product/order management under `controller/admin/panel` and `service/admin/supply`.
  - Order status, cancellation, and deletion all decide from a status read, so
    each takes a `PESSIMISTIC_WRITE` lock on the order group (or the legacy
    order) first. Without that lock two transactions both observe `Pending` and
    both restock. Read-only display paths must keep using the non-locking finders.
  - An `orderId` may name an order group or a legacy order line, and those id
    spaces have independent sequences. Every fallback from the group lookup to
    the line lookup must therefore be restricted to `orderGroup IS NULL`
    (`findByIdAndOrderGroupIsNull*`). An unrestricted fallback resolves an id
    that names no order to a line inside somebody else's group.
  - Restocking goes through `service/user/supply/OrderStockRestocker`, which
    aggregates per product, applies one atomic `productCount = productCount + n`
    statement, and always applies products in ascending id order so overlapping
    orders take the same lock order.
- `entity/user/OrderStatus` owns the status strings. Admin-supplied values
  are stored verbatim, so API compatibility is preserved.
- Shared user writes
  - The user row owns several independent concerns: the cart list, the profile
    columns, the password hash and the moderation ban flag. Writers therefore
    name the columns they own (`UserEntityRepository.updateProfileColumnsByUserMail`,
    `updatePasswordByUserMail`, `updateBannedByUserMail`) instead of saving a
    whole entity. A `save` of a previously loaded `UserEntity` rewrites every
    column and would silently revert whatever a concurrent cart change, password
    reset or moderation did in between.
  - Every cart writer (`addProductToCart`, `removeProductFromCart`,
    `updateProductCountInCart`, `clearUserCart`, `reconcileCart`,
    `prepareCheckout`) takes the same `PESSIMISTIC_WRITE` lock on the user row
    and reads the cart only from that locked state. `prepareCheckout` resolves
    only the scalar mailbox first and locks by `findByUserMailForUpdate`: loading
    the entity and then running a locking query by id returns the already-managed
    instance without refreshing it, so the cart could be read from a pre-lock
    snapshot.
  - `UserCartService.getCartData` is a pure read. It never saves and never dirties
    the stored list; it only clamps quantities to current stock and omits
    deleted/out-of-stock products. Do not reintroduce a write here.
  - That read hides what it cannot render, while `prepareCheckout` reserves from
    the stored list. So `reconcileCart` exists as an explicit, customer-triggered
    `POST /user/supply/reconcileCart` (not another `GET`): it removes deleted and
    sold-out entries, reduces over-stock quantities to the remaining stock, and
    **reports** both as separate lists. The report is the point — silently
    dropping lines from an order is its own defect. The client reconciles on the
    first checkout press and requires a fresh confirmation when anything changed,
    so no quantity is ever charged for that the customer did not review. It
    reserves nothing, creates no checkout and moves no money.
  - The client must not leave an *empty* view as an excuse to skip reconciling: a
    cart whose every entry became unbuyable renders identically to one the
    customer emptied, and checkout is unreachable from an empty cart. So the empty
    view carries its own explicit "check the cart" action, and the reconciliation
    it issues is the only way to tell the two apart. It must stay reachable from a
    DOM click — a disabled buy button plus a test that calls the component method
    directly proves nothing.
  - On the client, a checkout press **persists the staged cart edits first and
    awaits them, then reconciles**. Reconciling while a write is in flight answers
    for a cart the server has not accepted yet, and adopting that response
    confirms a view that differs from what checkout will reserve. The staged set
    is the source of truth for what is unsaved: an edit is dropped from it only
    once its own write succeeded, so a refusal stays staged and the retry resends
    exactly what is still unsaved. Both backend writers are idempotent, which is
    what makes that retry safe.
  - `isSameCartContent` (order-insensitive, compares count, price and stock) is
    what decides whether a reconciliation still stands as the customer's
    confirmation. Empty `removedProductIds`/`reducedProductIds` is not proof the
    displayed and stored carts match; only the cart itself is. A response is also
    never adopted over an edit the customer made *while* the request was in
    flight — the cart rows are disabled for the duration of a press and of the
    payment phase, and the component additionally compares against a press-time
    snapshot.
  - A missing profile user raises `UserNotFoundException` rather than an NPE.
- Product edit version
  - `product_entity.version` is a JPA `@Version` column (V11). **Every writer of a
    product row must advance it.** JPA-managed writers (the admin update, the
    locked checkout decrement) do so automatically; the bulk JPQL
    `ProductEntityRepository.incrementStock` advances it explicitly in the same
    statement, because a bulk update bypasses entity version handling. Without
    that, every restock is invisible to an open admin edit form.
  - An **image-only** edit dirties no product column — the image lives in
    `product_image_entity` — so dirty checking issues no `UPDATE` and the version
    would not move. `AdminSupplyService.advanceVersionForImageChange` forces it
    with `PESSIMISTIC_FORCE_INCREMENT`, inside the same transaction and before
    the version is read. It must be the *pessimistic* form: the row is already
    held at `PESSIMISTIC_WRITE` by `findByIdForUpdate`, and Hibernate's lock
    upgrade is ordinal-ordered, so the lower-ranked `OPTIMISTIC_FORCE_INCREMENT`
    is skipped as already-satisfied and silently does nothing. A genuine no-op
    with no image change still leaves the version alone.
  - `POST /admin/panel/updateProduct` returns the version its own write produced,
    in the additive `X-Product-Version` response header
    (`helper/ProductEditVersionHeader`). It is read after the flush, inside the
    transaction — never by a separate post-commit read, which would be a *later*
    version and would vouch for a stock count the editor never displayed. The
    header is listed in CORS `setExposedHeaders`; without that a cross-origin
    browser discards it and the editor never learns its own version.
  - `POST /admin/panel/updateProduct` **requires** `expectedVersion`. This is an
    intentional tightening of the request contract: backend and frontend ship
    together. Missing/invalid gives 400; stale gives 409 `PRODUCT_EDIT_CONFLICT`
    (`ProductEditConflictExceptionHandler`, deliberately a separate advice so
    `GlobalExceptionHandler`'s existing mappings and tests cannot drift).
  - The row lock serializes an edit with checkout/restock; `expectedVersion`
    detects a browser that has held the form since before that. Both are needed:
    the lock alone does not stop a form opened minutes ago from restoring its
    stale absolute stock count.
  - A rejected edit writes no file and creates no cleanup work, because the
    version comparison runs before any mutation.
- Product creation
  - `service/admin/supply/ProductCreationPolicy` is the single owner of product
    normalization/validation for JSON creation, multipart upload and edit.
    `ProductDetails` is the transport-independent value object; the policy takes no
    multipart or DTO types. Length and scale bounds are derived from the real
    schema (`VARCHAR(255)`, `NUMERIC(19,2)`) so bad input is a 400 rather than a
    driver error mid-insert.
  - `addProduct` resolves its category by **name** and refuses a name matching no
    row or more than one (`category_name` has no unique constraint). It never
    falls back to a default category. The multipart path keeps resolving
    `categoryValue` by id.
  - `ProductEntity.adminEntity` and `categoryEntity` are set explicitly on the
    saved product. The admin's `itemEntities` collection is the inverse side and is
    deliberately not appended to.
  - An absent description normalizes to `""` and an absent discount to `0`, since
    both columns are `NOT NULL` with no default.
  - `ProductCreationPolicy.applyTo` is also the **single writer** of
    `effective_price`, `package_amount` and `package_unit`. Every create and edit
    path already funnels through it, so a third hypothetical writer cannot forget
    the derived columns and leave a row whose sort key or package size is stale.
- Catalogue search (Prompt 1)
  - `GET /user/supply/searchProducts` is the one filtered, sorted, paged catalogue
    query. It is listed in `SecurityConfiguration` with the other anonymous GET
    catalogue reads, before the `denyAll` catch-all; no mutation is opened by that.
  - `service/user/supply/ProductSearchService` owns validation and the response.
    `ProductSearchAvailability` (`ALL` default, `IN_STOCK`, `OUT_OF_STOCK`) and
    `ProductSearchSort` (`DEFAULT`, `PRICE_ASC`, `PRICE_DESC`) bind from the query
    string, so an unrecognized value is Spring's own type mismatch → 400.
  - **Every parameter is optional.** `ALL` availability is the default precisely
    because it keeps sold-out products visible; filtering them out is a choice, not
    the answer to a search that did not ask for it.
  - `subcategory` **without** `categoryId` is a 400 (`GeneralException`). Names
    repeat across categories, so a subcategory alone would answer with products
    from categories the customer did not ask about.
  - `q` is trimmed, bounded at 100 characters, and `LIKE`-escaped by
    `ProductSearchSpecifications.toLikePattern` (escape char `!`). It is lowered on
    both sides in SQL, not in Java, so the comparison uses the database's
    case-folding rather than the JVM's.
  - `ProductSearchRepositoryImpl` is a Criteria implementation, not three derived
    finders: the page, `countMatching` and `countMatchingBySubcategory` all build
    their predicate from one `ProductSearchSpecifications.toPredicate` call, which
    is what keeps the list, the total and the facet counts describing one set. There
    is **no in-memory filtering anywhere** in this path — filtering after the page
    window paginates before it filters.
  - `countMatchingBySubcategory` deliberately applies `criteria.withoutSubcategory()`,
    so the counts stay comparable, and returns empty when no category was
    requested.
  - Every sort mode ends in the product id. Without that tie-break PostgreSQL is
    free to return two equal-priced or two sold-out rows in a different order for
    two consecutive page queries, which duplicates and drops rows in the listing.
- Product package metadata (Prompt 3)
  - V14 adds `package_amount NUMERIC(12,3)` and `package_unit VARCHAR(8)`, both
    nullable with **no backfill and no default**. Nothing is inferred from a product
    name or description: a guessed quantity is a unit price that looks
    authoritative and is wrong.
  - The pair is all-or-nothing, positive, no finer than three decimals, and `ADET`
    must be whole. V14's three CHECK constraints restate invariants
    `ProductCreationPolicy` already enforces; the **unit whitelist is deliberately
    not** a constraint, because it belongs to the policy, which reports it as a 400
    and is expected to grow.
  - `ProductDto` carries the two fields for JSON creation and edit; `uploadProduct`
    and `updateProduct` take them as optional multipart params. All three paths
    normalize through `ProductCreationPolicy.normalize`.
  - `helper/ProductUnitPricePolicy` is the only owner of the unit-price division
    (`G`/`ML` → 1000 first, then divide; `KG`/`L`/`ADET` → divide; `HALF_UP` to 2).
    It returns `null` — never a guess — for absent metadata, a non-positive amount,
    or a unit outside the closed set, and `UserCatalogReadService` turns that into
    absent DTO fields the client hides. `helper/ProductPackageUnit` owns the closed
    set and `helper/ProductPriceBasis` the `KG | L | ADET` basis it maps to.
  - Package metadata is written by the same `applyTo` call that writes the price, on
    the same locked row, after the version comparison — so a metadata-only edit is
    an ordinary version-checked edit that advances `@Version`, and a stale one
    mutates nothing.
- Pagination
  - `helper/PageRequestPolicy` is the single bound for the product and order
    listings: `page >= 0`, size `1..100` inclusive, validated **before**
    `PageRequest` construction so an invalid page costs no database access.
    Oversized requests are rejected with 400, never clamped. The native
    admin-order union query stays unsorted so its `order_id DESC, source_rank ASC`
    ordering survives. Support customer search has its own `limit` contract
    (`SupportCustomerDirectoryService`) and is deliberately not routed through
    this policy.
- Image cleanup queue
  - Replaced images and deleted products enqueue a row in
    `product_image_cleanup_entity` (V12) **in the same transaction** as the
    product/image change. A crash after commit therefore cannot lose the only
    reference to an obsolete file, which an in-memory after-commit callback would.
  - `ProductImageCleanupWorker` claims due work with a bounded lease, re-checks
    that nothing still references the canonical file identity, deletes outside any
    transaction, and fences completion on the lease token, so a stale worker
    changes nothing. Already-absent files count as success, which makes
    at-least-once delivery free.
  - This is **not** the support outbox and shares nothing with it: no payloads, no
    HTTP delivery, no ordering, no event ids.
  - There is deliberately no terminal failure state. A permanently undeletable
    file stays visible as pending work; `alert-after-attempts` only raises the log.
    A terminal state would abandon the work silently.
  - `FileService.canonicalFileIdentity` is shared by the reference check and path
    resolution so the two cannot drift. Deletion is confined to `APP_UPLOAD_DIR`;
    traversal or outside-directory paths are never deleted.
  - New uploads always draw a fresh UUID name and no endpoint accepts a
    client-supplied path, which is what makes the check-then-unlink window safe.
  - No automatic broad filesystem sweep exists. Pre-existing orphan reconciliation
    is a separate operational task requiring a reference inventory and a dry run.
- Product images
  - `service/global/FileService` writes with `CREATE_NEW`: a colliding name
    fails loudly instead of truncating another product's image.
  - `AdminProductImageOperations` names files from a UUID, never a timestamp.
  - `AdminSupplyService` resolves every database reference (category, admin,
    product) before writing any file, so an invalid reference cannot leave an
    orphan image.
  - The product row and its image row are written in one transaction
    (`uploadProduct`, `updateProduct`, `addProduct`). A newly written file is
    registered with a `TransactionSynchronization` and deleted whenever the
    transaction does not commit, which covers a rollback that happens after the
    method has already returned.
  - `updateProduct` creates the image row when the product has none. Skipping the
    write because there is "nothing to update" leaves the new file unreachable.
- Persistence and money
  - Flyway migrations live in `backend/src/main/resources/db/migration` and
    run against both existing and fresh databases.
  - Product and order money uses `BigDecimal` / `NUMERIC(19, 2)` major units.
    The backend calculates checkout totals and converts them to Stripe minor
    units; the client never sends an amount or currency for a charge.
  - `helper/ProductPricingPolicy` is the single owner of the effective
    (discounted) unit price. The discount factor is rounded to 6 decimals before
    the multiply and the result to 2, both `HALF_UP`, so catalog display and the
    checkout charge agree for every valid product.
  - The two callers differ only in invalid-data handling, and that is explicit:
    checkout uses `requireValidPrice`/`requireValidDiscount` and rejects, while
    the catalog listing keeps a documented legacy null-to-zero fallback in
    `UserCatalogReadService.legacyListingPrice`. An invalid payable price is never
    silently treated as zero. Stripe minor-unit conversion and its limits stay in
    `PaymentAmountConverter`.
- CSRF
  - `GET /csrf` returns the token and header name for the cross-origin SPA;
    Spring also sets the CSRF cookie. Mutating API requests require the header.
    `/payment/webhook` uses Stripe signature verification instead.

## Frontend Architecture
- Standalone Angular app using router-based composition.
- Root routes in `client/src/app/app.routes.ts`:
  - Main shell at `/`, search results at `/search`, category listing at
    `/category/:categoryId`, and product detail at
    `/category/:categoryId/product/:productId`.
  - `/product/:productId` is the **category-less** detail, used by a card in a
    listing that is not scoped to one category (a search result). The filters that
    produced it stay in the query string, so the breadcrumb and the return link
    restore the same results.
  - Admin shell at `/admin` with `/admin/products`, `/admin/orders`, and
    `/admin/support` sections selected from route data.
  - User modal flows on named outlet `modal` (cart, profile, login, order
    tracker/history, support); password reset uses `/reset-password/:token`.
- Browsing state
  - `pages/main/helpers/catalog-listing-state.ts` is the **single** bound between a
    listing URL and the `searchProducts` request: `q`, `subcategory`, `page`,
    `availability`, `minPrice`, `maxPrice`, `discounted`, `sort`. Both customer
    listings read and write their state through it, so a second copy of the
    translation is how two pages start answering different questions for one URL.
  - The UI page is **one-based** and the endpoint page is zero-based; the
    conversion happens only inside `toProductSearchQuery`. `page=1` and absent
    `page` mean the same thing on the wire.
  - Invalid values are normalized on the way in (an unrecognized `availability` or
    `sort` collapses to the endpoint's default; an inverted price pair drops the
    maximum) and the URL is rewritten to that canonical form with `replaceUrl`. A
    hand-edited link therefore renders a real listing instead of erroring.
  - `subcategory` is only forwarded together with a category, because the endpoint
    answers 400 for a subcategory on its own. The client cannot produce that
    request even from a hand-edited URL.
  - `pages/main/helpers/category-browse-state.ts` still owns the page-count math,
  the clamp to the last page, and `PRODUCT_PAGE_SIZE`.
  - `ProductPageComponent` is **two-mode**: with a `categoryId` it is the category
    listing/detail, without one it is only the detail and its return link points
    back at `/search`. Keep the two from drifting apart.
  - `ProductListingComponent` is the one presentation both listings render
    (toolbar, chips, grid, paginator, loading/error/empty states). It filters
    nothing: every count and ordering is the server's answer, so a narrowed result
    is a genuinely narrowed list rather than a narrowed page of the last broad one.
- HTTP integration
  - Domain API clients in `client/src/services/rest/`: `account-api`,
    `admin-api`, `cart-orders-api`, `catalog-api`, `payment-api`, and
    `support-api`. `RestService` is a compatibility facade for existing
    components and tests; add new HTTP calls to the appropriate domain client.
  - Request and response contracts live in `client/src/interfaces/`; preserve
    existing HTTP methods, paths, parameters, and response shapes.
  - Credential attachment: `auth.interceptor.ts` + `shouldAttachCredentials`
    in `app/config/backend.config.ts`.
  - `csrf.interceptor.ts` attaches the in-memory token to POST/PUT/PATCH/DELETE
    API requests. `CsrfTokenService` fetches `/csrf` at app initialization and
    on demand. Only a `403` with code `CSRF_INVALID` triggers one refresh and
    replay; mutations fail closed when the token is unavailable.
  - API/WS URLs derive from environment config.
  - `client/proxy.conf.cjs` forwards `/csrf`, API prefixes, and `/ws` locally;
    HTML navigation to `/admin` stays in the SPA while admin XHR goes to the
    backend.
- Auth state
  - `AuthService` owns user/admin login state and session refresh logic.
  - Components rely on `refreshUserSession()` / `refreshAdminSession()` and observables.
- Support UI behavior
  - `SupportRealtimeService` subscribes to `/ws/support` (users) or
    `/admin/ws/support` (admins); broadcasts are scoped to the target customer
    and admin sessions only.
  - User chat is in `support-chat.component.ts`; admin chat is in
    `admin-support.component.ts`, composed by `admin-panel.component.ts`.
  - Both sides retain REST polling alongside realtime events for robustness.
- Component state and lifecycle
  - Cart quantity/total rules live in `pages/main/helpers/cart-state.ts`;
    product/cart error and browsing helpers are in the same directory.
  - `pages/main/helpers/product-package-price.ts` is presentation only: the
    package size, unit price and basis arrive already computed from the server, and
    this module renders them or hides the line. An absent field must never be
    formatted as a zero, a dash, or a quantity derived from the product name.
  - `ObjectUrlManager` owns blob image URLs; release them on replacement or
    component teardown. Unsubscribe from streams, clear timers, and disconnect
    sockets when their owners are destroyed.
  - `EventService` listeners must be removed with `off` when components are
    destroyed.
- Cart and payment dialogs
  - The hand-rolled modals (no Material dialog) share
    `pages/main/components/payment/dialog-a11y.ts` for the three jobs nothing does
    for free: naming the dialog, moving focus in and out, and containing Tab. The
    payment dialog is nested inside the cart, so the cart ignores Escape and Tab
    while it is open.
  - Turkish copy for both surfaces is centralized as `PAYMENT_COPY` and
    `CART_COPY`; Turkish money formatting is `money-format.ts` (`1.234,56 TL`).
    The currency still comes from the server — only the label is derived.
  - The `--dialog-*` tokens in `styles.css` are additive to the existing
    `--landing-*` palette: the scrim, the two message levels, and a focus ring that
    stays visible on the orange surface. Keep new dialog surfaces on them rather
    than introducing a second brand.
  - The `--layer-*` rungs in `styles.css` are the whole stacking order, and a
    layer names one of them rather than a number. `--layer-floating` is for
    controls that belong to the *page behind* a modal (the support launcher), and
    every dialog sits on one of the four rungs above it, paired scrim-then-surface
    so a nested dialog is raised by moving up a rung. The launcher and the support
    panel used to declare an unlayered `2100` of their own, which put them above
    every dialog: on a 390px phone the cart fills all but 8px of the viewport, so
    the launcher painted over the cart's own checkout button and took the press.
  - A dialog's `aria-modal="true"` promises the page behind it is out of reach, so
    a control on that page must not merely sit *under* the scrim — it is
    **withdrawn** while any modal occupies the `modal` outlet. `MainComponent`
    drives that from the outlet's own `activate`/`deactivate` events rather than
    from the URL, so it holds for a deep link and a Back-button open as well, and
    a navigation the router cancels changes nothing. Do not read the URL for this.
  - `PaymentComponent` must keep recovering the **same** checkout after an
    ambiguous charge. Closing, cancelling or retrying may never prepare a
    replacement, and `completePayment` is the only place allowed to say the money
    was taken.

## Runtime and Profiles
- No Spring profile is active by default (`application.properties` no longer sets `spring.profiles.default`). The omitted profile behaves as non-local.
- Local development is recognized only when the active-profile set is exactly `{local}`. Profiles are additive, so `@Profile("local")` / `@Profile("!local")` are not sufficient: `prod,local` must be treated as non-local.
  - `config/AdminStartupProfilePolicy` centralizes this decision (`isLocalDevelopment()`), and both the seeder and the guard consult it so they cannot disagree.
  - `config/AdministratorStartupConfiguration` holds both startup components:
    - `localDefaultAdministratorInitializer` (a `CommandLineRunner`) performs no database write unless the policy reports exact-local; when it writes, it seeds `admin` / `admin` only if absent.
    - `nonLocalDefaultAdministratorGuard` (a `SmartInitializingSingleton`, so it runs after repositories are initialized but before the web server lifecycle starts) fails startup with `IllegalStateException` if the policy is non-local and an `admin` account still matches the default password.
  - `config/StartupConfiguration` seeds categories in every profile.
- `application-local.properties` configures local DB defaults and permissive cookie settings.
- `application-prod.properties` expects explicit datasource/origin values and secure cookie defaults. Production requires `SPRING_PROFILES_ACTIVE=prod`; adding `local` makes the runtime non-local.
- Production administrators are provisioned manually in `admin_entity` with an externally generated BCrypt hash; there is no admin-registration or bootstrap endpoint.
- `configs/production.env.example` is the committed production contract; `ProductionConfigurationContractTest` keeps it complete and secret-free.
- `SPRING_PROFILES_ACTIVE=prod` exposes `GET /actuator/health` (plus `/liveness` and `/readiness`); readiness includes the database and liveness does not.
- Important env vars include:
  - `SPRING_PROFILES_ACTIVE`
  - `SPRING_DATASOURCE_*`
  - `APP_ALLOWED_ORIGINS`, `APP_ALLOWED_ORIGIN_PATTERNS`
  - `APP_FRONTEND_BASE_URL`, `APP_BACKEND_BASE_URL`
  - `JWT_USER_SECRET`, `JWT_ADMIN_SECRET`
  - `STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET`
  - `PAYMENT_CURRENCY` (currently only `try` is supported)
  - `APP_MAIL_PROVIDER` (`smtp` locally, `resend` in prod by default)
  - `SUPPORT_SERVICE_BASE_URL`, `SUPPORT_SERVICE_INTERNAL_KEY`, `SUPPORT_INTERNAL_KEY`
  - `APP_UPLOAD_DIR`
  - `APP_TRUSTED_PROXIES` (empty by default: trust nothing)
  - `SUPPORT_OUTBOX_*` (optional outbox tuning; see the support subsystem notes)
- Static client images use `staticImageUrl` in
  `client/src/app/config/supabase-assets.ts`: local assets in development and
  the configured Supabase public base URL in production.

## Local Development Commands
- Full stack:
  - `docker compose --env-file configs/postgres.env --env-file configs/spring.env up`
- Hybrid (recommended):
  - `docker compose --env-file configs/postgres.env up postgres`
  - `cd backend; .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"`
  - `cd client; npm i; npm start`
  - The exact-local profile optionally imports `../configs/spring.env` and
    `../configs/postgres.env` relative to `backend/`; real environment variables
    override imported values. Hybrid uses `localhost:5432`; Compose uses service
    names (`postgres`, `backend`, `nginx`).
- Local-only debugging (opt-in, loopback-only):
  - `cd backend; .\mvnw.cmd -Plocal-debug spring-boot:run "-Dspring-boot.run.profiles=local"`
  - Default runs must not enable JDWP. Any debugger must be opt-in via the
    `local-debug` Maven profile and bound to `127.0.0.1:5005` only.
  - Runtime/deployment manifests must not publish debug ports.

- Backend tests:
  - `cd backend && ./mvnw test`
- Frontend tests:
  - `cd client && npm test`
  - Headless one-shot run: `cd client && npm test -- --watch=false --browsers=ChromeHeadless`
- Frontend production build: `cd client && npm run build`

## Nginx Notes
- Nginx applies pre-backend rate limits with dedicated limits for:
  - `/admin/login`
  - `/user/login`
  - `/payment/checkouts/*/charge`
  - `/user/support/send`
- Global overflow response is JSON `429`.
- WebSocket upgrade is enabled for `/ws/support`.

## Stripe Payment Durability
- One immutable checkout can produce at most one economic charge and one order.
- `PaymentComponent` prepares a server-owned checkout snapshot and displays the
  returned total/currency. Preparing removes reserved items from the live cart;
  cancelling an untouched `PREPARED` checkout returns stock. A checkout that
  may be processing must keep its identity and be recovered through status
  polling; never start a replacement after an ambiguous charge response.
- `PaymentAttemptService` owns the durable state machine; every provider call
  uses the stable server-derived key `checkout:<uuid>:charge-v1`.
- The Stripe network call never runs inside a database transaction; a bounded
  lease plus verified webhooks plus `PaymentReconciliationJob` resolve crashes
  and ambiguous network outcomes.
- `POST /payment/webhook` verifies the raw-body `Stripe-Signature` against
  `STRIPE_WEBHOOK_SECRET`. The secret is required outside exact-local profile.
- Local webhook testing: run `stripe listen --forward-to
  http://localhost:8080/payment/webhook`, put the printed `whsec_...` value in
  `STRIPE_WEBHOOK_SECRET` (or `configs/spring.env`) and restart the backend.
  Unsigned or invalidly signed payloads are always rejected.

## Deferred Architectural Options

Documented deliberately rather than half-implemented. Neither is part of the
current fixes, and neither should be started without a plan of its own.

- **Payment services accept raw JWT strings.** Unlike the cart, profile and
  support flows, which resolve an authenticated principal, the payment package
  takes the caller's token and validates it inside the service. A future
  coordinated controller/service refactor can pass authenticated identity
  instead, but it must preserve role checks, checkout ownership, cookie/CSRF
  behavior and every recovery path. Do not remove token validation from a
  service while leaving its caller unauthenticated.
- **`StripePaymentGateway` exposes Stripe SDK types.** It is already an
  interface, so provider-neutral results are worthwhile if a second provider or
  SDK isolation is ever actually required. A full payment-provider abstraction
  rewrite is not part of these fixes and should not be started speculatively.

## Change Guidelines For Agents
- Keep backend layering intact:
  - Controller for IO/HTTP concerns.
  - Service for business rules.
  - Repository for DB queries.
- Prefer extending existing DTOs/services over duplicating parallel flows.
- Do not break cookie-based auth assumptions in frontend and backend.
- Keep the CSRF bootstrap/interceptor and backend `/csrf` contract aligned;
  never send a mutating request without a usable token.
- When touching auth or route protection:
  - update `SecurityConfiguration` and session-related frontend behavior together.
- When touching support chat:
  - verify both websocket event flow and REST polling behavior.
  - the outbox row is written in the same transaction as the chat write; do not
    move publishing onto a request thread or an `@Async` boundary.
- Preserve API path conventions currently used by `RestService`.
- Keep `RestService` delegating to domain API clients while existing callers
  depend on it; avoid creating a second HTTP path for the same operation.
- Preserve route-driven browsing and named-outlet URLs when changing UI state.
- Do not infer a successful payment from a network response alone; follow the
  existing checkout status and reconciliation rules.
- Keep exception semantics aligned with `GlobalExceptionHandler`.

## Testing Expectations For Changes
- Backend domain or controller changes: run relevant `./mvnw test` scope at minimum.
- Frontend service/component changes: run relevant Angular test scope at minimum.
- For frontend refactors, run the headless suite and production build; inspect
  build warnings and bundle size before claiming the refactor is complete.
- Auth/support changes should be validated end-to-end locally:
  - user/admin login session refresh
  - support message send/receive, realtime refresh, and REST reload behavior
- Cart/payment changes should cover quantity, checkout preparation and
  cancellation, and same-checkout recovery for ambiguous payment outcomes.
  Never perform a real charge as part of a routine smoke test.

## Known Design Realities
- Many endpoints use verb-style paths (e.g. `getProductData`) rather than strict REST resources; follow existing style unless doing a coordinated refactor.
- Some request handlers use `GET` for state-changing operations in legacy flows; avoid broad behavior changes unless requested.
- Support message consistency relies on DB state + websocket notification + periodic polling.
