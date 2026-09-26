# Local configuration

This directory contains configuration that supports local development and Docker
Compose. The Nginx files are shared, non-secret configuration and are committed
to the repository. Local `.env` files are intentionally ignored by Git.

## First-time setup

In PowerShell, create your local files from the tracked templates:

```powershell
Copy-Item configs/postgres.env.example configs/postgres.env
Copy-Item configs/spring.env.example configs/spring.env
```

Edit the copied files and provide local database and mail credentials. Do not
commit these copies, and do not use the placeholder values outside local setup.

`spring.env` also requires two independent JWT signing secrets. Generate each one
separately so they differ, for example with OpenSSL:

```sh
openssl rand -base64 32
openssl rand -base64 32
```

Set the first output as `JWT_USER_SECRET` and the second as `JWT_ADMIN_SECRET`.
Each secret must decode to at least 32 bytes, the two values must differ, and
there is no fallback: the backend refuses to start when either is missing, too
short, or identical. User and admin tokens are signed with different keys and
carry a signed `session_type` claim, so a compromised user key cannot mint an
administrator token.

`spring.env` also requires `SUPPORT_INTERNAL_KEY`, the shared key that protects
the inbound internal support bridge (`/internal/support/**`). The backend refuses
to start when it is missing, so generate one and add it to your local
`configs/spring.env`:

```sh
openssl rand -base64 32
```

This is the **inbound** key. It is distinct from `SUPPORT_SERVICE_INTERNAL_KEY`,
which is the **outbound** key the backend uses when calling the separate
support-service. Do not reuse the same value for both.

## Starting the stack

From the repository root:

```powershell
docker compose --env-file configs/postgres.env --env-file configs/spring.env up
```

Compose uses these files for variable substitution. `configs/nginx` is mounted
into the Nginx container and contains the shared proxy, WebSocket, forwarded
header, timeout, and request-rate-limit settings.

Traffic flows `client -> nginx -> backend -> postgres`. Nginx resolves the
backend through Compose DNS (`backend:8080`), and the backend datasource is
derived from the `postgres` service and the `POSTGRES_*` values. The backend
application port is intentionally not published on the host.

## Hybrid development (Postgres in Compose, apps on the host)

Run Postgres only, then the backend and the Angular dev server on the host:

```powershell
# 1. From the repository root: start only the database
docker compose --env-file configs/postgres.env up postgres

# 2. From the repository root: start the backend with the exact-local profile
cd backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"

# 3. In another terminal: start the frontend
cd client
npm i
npm start
```

Unix shells use the same commands with `./mvnw` and single quotes around the
Maven system property:

```sh
./mvnw spring-boot:run '-Dspring-boot.run.profiles=local'
```

The `local` profile optionally imports `configs/spring.env` and
`configs/postgres.env` through `optional:file:` imports relative to the
`backend/` working directory. Missing files are ignored, and any real process
environment variable always overrides a value from those files. The hybrid
fallback JDBC URL uses `localhost:5432`.

There are two coordinate sets, one per runtime mode:

- **Host processes (hybrid):** use `localhost` (for example
  `jdbc:postgresql://localhost:5432/migros`, backend on `:8080`, UI on `:4200`).
- **Inside Compose:** use service names (`postgres`, `backend`, `nginx`).

The outbound support-service integration is optional. Set
`SUPPORT_SERVICE_BASE_URL` and `SUPPORT_SERVICE_INTERNAL_KEY` in
`configs/spring.env` to enable it; when `SUPPORT_SERVICE_BASE_URL` is unset the
integration is disabled instead of calling `localhost:3000` inside a container.


## Profiles and administrator provisioning

Docker Compose explicitly sets `SPRING_PROFILES_ACTIVE=local` for the backend.
When running the backend directly, activate `local` explicitly (for example
`.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"` on PowerShell).
The quoted system property keeps the Maven Wrapper from interpreting it as a
lifecycle phase on Windows. To get the local-development convenience account
`admin` / `admin`, the active profile set must be exactly `{local}`. Ordinary
startup is debugger-free; for opt-in loopback-only debugging use
`.\mvnw.cmd -Plocal-debug spring-boot:run "-Dspring-boot.run.profiles=local"`
(listens only on `127.0.0.1:5005`, never publish it via Compose or forwarding).

Local development is recognized only when `local` is the **only** active profile.
Any other combination (no profile, `prod`, `prod,local`, `local,staging`, ...) is
treated as non-local: the backend never creates an administrator, and it refuses
to start before serving traffic if the legacy `admin` / `admin` account still
exists. Production requires `SPRING_PROFILES_ACTIVE=prod` and a manually
provisioned administrator row in `admin_entity` using a trusted, externally
generated BCrypt hash. See the "Administrator provisioning" section of the root
`README.md` for commands and recovery steps.

## Production environment

`configs/production.env.example` is the committed production contract. It is a
**template only**: copy it into the hosting provider's environment or secret
store, replace every `replace-with-*` value, and never commit the populated
copy. It is intentionally non-runnable until real secrets are supplied.

Keep `SPRING_PROFILES_ACTIVE=prod` for hosted deployments. Adding `local`
(for example `prod,local`) makes the runtime non-local, so the local
`admin` / `admin` convenience account is never created and a legacy default
administrator blocks startup. Do not add it to a production environment.

The template documents, at minimum:

- Datasource credentials (`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
  `SPRING_DATASOURCE_PASSWORD`).
- Independent JWT signing secrets (`JWT_USER_SECRET`, `JWT_ADMIN_SECRET`).
- Stripe (`STRIPE_API_KEY`, `STRIPE_WEBHOOK_SECRET`) and the payment currency.
- Support integration (`SUPPORT_INTERNAL_KEY`, `SUPPORT_SERVICE_BASE_URL`,
  `SUPPORT_SERVICE_INTERNAL_KEY`).
- Public origins (`APP_FRONTEND_BASE_URL`, `APP_BACKEND_BASE_URL`) and allowed
  CORS/WebSocket origins (`APP_ALLOWED_ORIGINS`, `APP_ALLOWED_ORIGIN_PATTERNS`),
  all HTTPS.
- Cookie security (`AUTH_COOKIE_SECURE`, `AUTH_COOKIE_SAMESITE`).
- Mail/Resend settings used by signup confirmation and password reset.
- A persistent upload directory (`APP_UPLOAD_DIR`).

`ProductionConfigurationContractTest` keeps the template complete and
secret-free: it verifies the template documents every property referenced by
`application-prod.properties`, contains no real-looking secret or private
endpoint, and does not introduce insecure production fallbacks.

A separately named production verifier is intentionally **not** added here so
that local Compose assumptions (see `verify-compose-env.ps1`) and hosted
production assumptions stay separate. The production readiness gate is manual
and external; see the "Production deployment" section of the root `README.md`.
