# AGENTS Guide - migros-app

## Purpose
This file is a working guide for coding agents contributing to this repository.  
It documents architecture, constraints, and safe change patterns so edits stay consistent with the current system.

## Monorepo Layout
- `client/`: Angular 19 standalone SPA.
- `backend/`: Spring Boot 3.4 API (Java 21, JPA, Security, WebSocket).
- `configs/nginx/`: Reverse proxy and rate limiting config.
- `compose.yaml`: Local multi-service orchestration (client, backend, nginx, postgres).

## System Architecture
- Frontend: Angular app served by `ng serve` in local development and by the
  current client container in Compose. `client/Dockerfile` is a development
  server, not a production static-file server.
- API: Spring Boot REST API on `:8080` with stateless JWT auth.
- DB: PostgreSQL.
- Edge proxy: Nginx on `:8080` in Docker setup, with endpoint-specific throttling and websocket forwarding.
- Realtime support: WebSocket endpoint `/ws/support` plus REST polling fallback in UI.

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
- Security config
  - `SecurityConfiguration` defines open/authenticated/admin-only routes.
  - CORS and WebSocket allowed origins are driven by `app.allowed-origins` and `app.allowed-origin-patterns`.
  - Only `/actuator/health` and its subpaths are public; `/actuator/**` is
    explicitly denied before the catch-all `permitAll`. Actuator exposes only
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
- Persistence and money
  - Flyway migrations live in `backend/src/main/resources/db/migration` and
    run against both existing and fresh databases.
  - Product and order money uses `BigDecimal` / `NUMERIC(19, 2)` major units.
    The backend calculates checkout totals and converts them to Stripe minor
    units; the client never sends an amount or currency for a charge.
- CSRF
  - `GET /csrf` returns the token and header name for the cross-origin SPA;
    Spring also sets the CSRF cookie. Mutating API requests require the header.
    `/payment/webhook` uses Stripe signature verification instead.

## Frontend Architecture
- Standalone Angular app using router-based composition.
- Root routes in `client/src/app/app.routes.ts`:
  - Main shell at `/`, category listing at `/category/:categoryId`, and product
    detail at `/category/:categoryId/product/:productId`.
  - Admin shell at `/admin` with `/admin/products`, `/admin/orders`, and
    `/admin/support` sections selected from route data.
  - User modal flows on named outlet `modal` (cart, profile, login, order
    tracker/history, support); password reset uses `/reset-password/:token`.
- Browsing state
  - Category selection and pagination use `subcategory` and `page` query
    parameters. `pages/main/helpers/category-browse-state.ts` validates and
    normalizes them; keep deep links, back navigation, and product detail
    identity in sync when changing browsing code.
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
  - `SupportRealtimeService` subscribes to `/ws/support`.
  - User chat is in `support-chat.component.ts`; admin chat is in
    `admin-support.component.ts`, composed by `admin-panel.component.ts`.
  - Both sides retain REST polling alongside realtime events for robustness.
- Component state and lifecycle
  - Cart quantity/total rules live in `pages/main/helpers/cart-state.ts`;
    product/cart error and browsing helpers are in the same directory.
  - `ObjectUrlManager` owns blob image URLs; release them on replacement or
    component teardown. Unsubscribe from streams, clear timers, and disconnect
    sockets when their owners are destroyed.
  - `EventService` listeners must be removed with `off` when components are
    destroyed.

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
