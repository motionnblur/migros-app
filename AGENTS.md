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
- Frontend: Angular app served in dev via `ng serve`, and in Docker via containerized client.
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
- Support subsystem
  - User REST endpoints in `controller/user/support`.
  - Internal support bridge in `controller/internal/InternalSupportController` using `x-internal-key`.
  - Realtime broadcasts in `websocket/SupportChatWebSocketHandler`.
  - Outbound internal event publishing in `service/support/SupportInternalEventService`.
- Product/order flows
  - User shopping and order history under `controller/user/supply` and `service/user/supply`.
  - Admin product/order management under `controller/admin/panel` and `service/admin/supply`.

## Frontend Architecture
- Standalone Angular app using router-based composition.
- Root routes in `client/src/app/app.routes.ts`:
  - Main shell at `/`
  - Admin shell at `/admin`
  - User modal flows on named outlet `modal` (cart/profile/login/order/support)
- HTTP integration
  - Central API service: `client/src/services/rest/rest.service.ts`.
  - Credential attachment: `auth.interceptor.ts` + `shouldAttachCredentials` in `app/config/backend.config.ts`.
  - API/WS URLs derive from environment config.
- Auth state
  - `AuthService` owns user/admin login state and session refresh logic.
  - Components rely on `refreshUserSession()` / `refreshAdminSession()` and observables.
- Support UI behavior
  - `SupportRealtimeService` subscribes to `/ws/support`.
  - UI still polls support messages (`support-chat.component.ts`) for robustness.

## Runtime and Profiles
- No Spring profile is active by default (`application.properties` no longer sets `spring.profiles.default`). The omitted profile behaves as non-local.
- Local development is recognized only when the active-profile set is exactly `{local}`. Profiles are additive, so `@Profile("local")` / `@Profile("!local")` are not sufficient: `prod,local` must be treated as non-local.
  - `config/AdminStartupProfilePolicy` centralizes this decision (`isLocalDevelopment()`), and both the seeder and the guard consult it so they cannot disagree.
  - `config/AdministratorStartupConfiguration` holds both startup components:
    - `localDefaultAdministratorInitializer` (a `CommandLineRunner`) performs no database write unless the policy reports exact-local; when it writes, it seeds `admin` / `admin` only if absent.
    - `nonLocalDefaultAdministratorGuard` (a `SmartInitializingSingleton`, so it runs after repositories are initialized but before the web server lifecycle starts) fails startup with `IllegalStateException` if the policy is non-local and an `admin` account still matches the default password.
  - `config/StartupConfiguration` seeds categories in every profile.
- `application-local.properties` configures local DB defaults and permissive cookie settings.
- `application-prod.properties` expects explicit datasource/origin values and secure cookie defaults. Production requires `SPRING_PROFILES_ACTIVE=prod`.
- Production administrators are provisioned manually in `admin_entity` with an externally generated BCrypt hash; there is no admin-registration or bootstrap endpoint.
- Important env vars include:
  - `SPRING_PROFILES_ACTIVE`
  - `SPRING_DATASOURCE_*`
  - `APP_ALLOWED_ORIGINS`, `APP_ALLOWED_ORIGIN_PATTERNS`
  - `JWT_USER_SECRET`, `JWT_ADMIN_SECRET`
  - `STRIPE_API_KEY`
  - `SUPPORT_SERVICE_BASE_URL`, `SUPPORT_SERVICE_INTERNAL_KEY`, `SUPPORT_INTERNAL_KEY`
  - `APP_UPLOAD_DIR`

## Local Development Commands
- Full stack:
  - `docker compose --env-file configs/postgres.env --env-file configs/spring.env up`
- Hybrid (recommended):
  - `docker compose --env-file configs/postgres.env up postgres`
  - `cd backend && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local`
  - `cd client && npm i && npm start`

- Backend tests:
  - `cd backend && ./mvnw test`
- Frontend tests:
  - `cd client && npm test`

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
- When touching auth or route protection:
  - update `SecurityConfiguration` and session-related frontend behavior together.
- When touching support chat:
  - verify both websocket event flow and REST polling behavior.
- Preserve API path conventions currently used by `RestService`.
- Keep exception semantics aligned with `GlobalExceptionHandler`.

## Testing Expectations For Changes
- Backend domain or controller changes: run relevant `./mvnw test` scope at minimum.
- Frontend service/component changes: run relevant Angular test scope at minimum.
- Auth/support changes should be validated end-to-end locally:
  - user/admin login session refresh
  - support message send/receive and realtime refresh behavior

## Known Design Realities
- Many endpoints use verb-style paths (e.g. `getProductData`) rather than strict REST resources; follow existing style unless doing a coordinated refactor.
- Some request handlers use `GET` for state-changing operations in legacy flows; avoid broad behavior changes unless requested.
- Support message consistency relies on DB state + websocket notification + periodic polling.
