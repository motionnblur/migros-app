# Migros App

This is a basic e-commerce mvp application i built using Angular for the frontend, Spring Boot for the backend, and PostgreSQL for the database.

It's a very basic migros clone that i built for my learning purposes. See: www.migros.com.tr

You can visit the site: https://migros-app-client.onrender.com
* Note: You probably will not be able to access the site because I am creating it for learning purposes; I do not have any professional goals in doing this.

## :clipboard: Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Technologies Used](#technologies-used)
- [Prerequisites](#prerequisites)
- [Setup](#setup)
- [Running the Application](#running-the-application)
- [Screenshots](#screenshots)

## :eyes: Overview

This application provides a basic platform for users to browse products, add them to a shopping cart, and potentially simulate a checkout process (depending on the level of implementation). It serves as a foundation for a more comprehensive e-commerce solution.

## :star: Features

* **Registering:** User registering using mail protocol (spring-boot-starter-mail)
* **Admin Dashboard:** For managing orders and products.
* **Product Listing:** Display a list of available products.
* **Product Details:** View detailed information about a specific product.
* **Add to Cart:** Allow users to add products to their shopping cart.
* **View Cart:** Display the items in the shopping cart.
* **Payment Processing:** By using Stripe's payment test api.
* **Basic Security:** User Authentication and Authorization using JWT tokens
* **Live Support:** Basic live support system using websockets and storing them as a fallback

## :computer: Technologies Used

* **Frontend:**
    * [Angular](https://angular.io/)  (19.0.5)
    * [Material UI](https://material.angularjs.org/latest/) (19.0.5)
    * [Bootstrap](https://getbootstrap.com/)
    * [TypeScript](https://www.typescriptlang.org/)
    * [HTML](https://developer.mozilla.org/en-US/docs/Web/HTML)
    * [CSS](https://developer.mozilla.org/en-US/docs/Web/CSS)
* **Backend:**
    * [Spring Boot](https://spring.io/projects/spring-boot) (3.4.0)
    * [Java](https://www.java.com/) (21)
    * [Maven](https://maven.apache.org/)
* **Database:**
    * [PostgreSQL](https://www.postgresql.org/) (postgres:18.3-alpine3.22)
* **Docker:**
    * [Docker](https://www.docker.com/)
* **Gemini:**
    * For writing backend tests
* **CODEX (by OpenAI):**
    * For implementing live support system

## :page_with_curl: Prerequisites

Before you begin, ensure you have the following installed:

* **Docker**
* **Node.js / npm** (for frontend local development)
* **Java 21** (for backend local development)

## :airplane: Setup

Local `.env` files are ignored by Git. Create them from the tracked templates
(see `configs/README.md`):

```powershell
Copy-Item configs/postgres.env.example configs/postgres.env
Copy-Item configs/spring.env.example configs/spring.env
```

Then replace every `replace-with-*` placeholder, including the two independent
JWT signing secrets and `SUPPORT_INTERNAL_KEY`. Never commit the copied files.

There are two network coordinate sets, one per runtime mode:

* **Host processes** (hybrid development): use `localhost`, for example
  `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/migros`.
* **Inside Docker Compose**: use service names (`postgres`, `backend`, `nginx`).
  The backend's container datasource is derived from the `POSTGRES_*` values, so
  its JDBC URL uses the `postgres` service host.

> `SUPPORT_INTERNAL_KEY` protects the inbound internal support bridge
> (`/internal/support/**`) and is **required**: the backend refuses to start
> without it. It is separate from the outbound `SUPPORT_SERVICE_INTERNAL_KEY`
> used to call the support-service. Set it to a random value, for example with
> `openssl rand -base64 32`, in `configs/spring.env`. The internal bridge is not
> reachable through Nginx (the edge proxy returns `404` for `/internal/`), so the
> support-service must call the backend directly over the internal network.

### Payment currency and money representation

Product and order prices are stored as major-unit amounts (`50.25` means ₺50.25)
using fixed-scale `BigDecimal` / `NUMERIC(19, 2)` columns. The backend is the
single source of truth for the charge amount: it converts the cart total to the
integer minor units Stripe requires (`5000` for ₺50.00) and rejects zero,
negative, non-finite, over-precise, or overflowing totals before any Stripe call.

`PAYMENT_CURRENCY` selects the ISO 4217 currency sent to Stripe and defaults to
`try`. Only `TRY` is accepted until currency-specific minor-unit handling exists;
the value is trimmed and lower-cased, and anything other than `try` fails
application startup before any payment is attempted. Change it only together with
every customer-facing currency label; the UI is informational and never sends an
amount or currency. The 8-digit Stripe limit for TRY is enforced before the
gateway is called, and invalid payment amounts return HTTP `400`.

Legacy `REAL` money columns are converted to `NUMERIC(19, 2)` by Flyway on
startup. Migrations live in `backend/src/main/resources/db/migration` and are
applied to both an existing populated schema and a fresh empty database.

* Local development credentials (only created when the active profile set is exactly `local`): admin / admin

> The `admin` / `admin` account is a **local-development-only** convenience. It is
> created only when the active Spring profile set is exactly `{local}`. Any other
> combination (no profile, `prod`, `prod,local`, `local,staging`, ...) is treated
> as non-local: the backend never creates an administrator and refuses to start if
> a legacy default credential exists. See [Administrator provisioning](#administrator-provisioning).

## :rocket: Running the application

### Option A: Full Docker stack
```
docker compose --env-file configs/postgres.env --env-file configs/spring.env up
```

After startup:
* Client UI: http://localhost:5000
* API entrypoint (Nginx reverse proxy + rate limiting): http://localhost:8080

### Option B: Hybrid local development (recommended)

Run infrastructure with Docker, run app servers directly for faster iteration:

1. Start database:
```
docker compose --env-file configs/postgres.env up postgres
```

2. Start backend (explicitly activate the `local` profile):
```powershell
cd backend
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local"
```
The system property is quoted so the Maven Wrapper does not interpret it as a
lifecycle phase on Windows. The `local` profile optionally imports
`configs/spring.env` and `configs/postgres.env` (relative to `backend/`), so the
hybrid backend starts without manually exporting every variable. A real process
environment variable overrides any value imported from those files.
Ordinary startup is debugger-free: no JDWP agent is enabled by default.

For explicit local-only debugging (loopback only, never expose remotely):
```powershell
.\mvnw.cmd -Plocal-debug spring-boot:run "-Dspring-boot.run.profiles=local"
```
The debug listener binds only to `127.0.0.1:5005` and must not be exposed through
port forwarding, Compose, a reverse proxy, a tunnel, or a production deployment.
If you run `.\mvnw.cmd spring-boot:run` without `local`, the backend starts with
the secure default profile: it will **not** create the `admin` / `admin` account,
and it will refuse to start if a legacy `admin` / `admin` row already exists. The
same applies when additional profiles are active (`prod,local`, `local,staging`,
...): local development is only recognized when `local` is the **only** active
profile.

3. Start frontend:
```
cd client
npm i
npm start
```

In this mode:
* Frontend: http://localhost:4200
* Backend: http://localhost:8080

In the full Docker stack, Nginx routes API traffic to the backend through Compose
DNS (`backend:8080`) and the client proxy targets `http://nginx:80`, so the same
edge routing, rate limits, and `/ws/support` upgrade path are exercised on
http://localhost:8080.

For production deployments, set `SPRING_PROFILES_ACTIVE=prod` and provide the required `SPRING_DATASOURCE_*`, `APP_ALLOWED_ORIGINS`, and `APP_PUBLIC_BASE_URL` variables.

For mail provider:
* Local profile defaults to SMTP (`APP_MAIL_PROVIDER=smtp`)
* Prod profile defaults to Resend (`APP_MAIL_PROVIDER=resend`)
* You can override with `APP_MAIL_PROVIDER=auto` to use Resend when `RESEND_API_KEY` is present, otherwise SMTP

Nginx now applies per-IP throttling before requests reach Spring Boot (including stricter limits for login, payment, and support send endpoints) and returns HTTP 429 when limits are exceeded.

## Administrator provisioning

Production requires the `prod` profile to be explicitly activated:

```
SPRING_PROFILES_ACTIVE=prod
```

The backend does **not** create an administrator in production. In fact, it only
ever creates one when the active profile set is exactly `{local}`. In every other
environment — including when `local` is combined with another profile such as
`prod,local` — it never creates an administrator and instead validates the stored
credentials before serving traffic. Provision one manually:

1. Generate a BCrypt hash externally with a trusted tool, using a strong, unique
   password. For example with `htpasswd`:

   ```sh
   htpasswd -bnBC 12 "" 'your-strong-unique-password' | tr -d ':\n'
   ```

   Do not reuse the `admin` / `admin` development password, and never commit the
   generated hash or the plaintext password to the repository.

2. Insert the administrator row directly into the database (no admin-registration
   endpoint exists):

   ```sql
   INSERT INTO admin_entity (admin_name, admin_password)
   VALUES ('operations-admin', '$2y$12$...externally-generated-bcrypt-hash...');
   ```

### Recovering from a legacy `admin` / `admin` account

If a non-`local` environment (any active-profile set other than exactly `{local}`)
already contains the legacy default credential, startup fails with an
`IllegalStateException` before the web server begins serving traffic, instructing
you to rotate or remove it. Before restarting production, either rotate the
password or delete the row:

```sql
-- Rotate to a strong password hash generated as described above
UPDATE admin_entity SET admin_password = '$2y$12$...new-hash...' WHERE admin_name = 'admin';

-- Or remove the legacy account entirely (after provisioning a replacement admin)
DELETE FROM admin_entity WHERE admin_name = 'admin';
```

Do not restart production until the legacy `admin` / `admin` hash has been
rotated or removed.

## :camera: Screenshots

* Current code coverage
<img width="791" height="367" alt="Screenshot 2026-03-05 124449" src="https://github.com/user-attachments/assets/34abb1eb-5424-4434-89bb-2131b45742ae" />

<img width="1913" height="984" alt="Screenshot 2026-03-06 144805" src="https://github.com/user-attachments/assets/ccb0027d-d0d3-491a-b283-0131a959c96f" />

<img width="1890" height="944" alt="Screenshot 2026-03-05 195555" src="https://github.com/user-attachments/assets/ee61d7ca-6e80-4e10-99b4-2a80ccf6921e" />

<img width="1886" height="940" alt="Screenshot 2026-03-05 195657" src="https://github.com/user-attachments/assets/41366479-6ba0-4666-bb06-6ba8e8965cd9" />

<img width="1883" height="941" alt="Screenshot 2026-03-05 201407" src="https://github.com/user-attachments/assets/fba0f37b-1c3d-4799-b878-1e871708d472" />

<img width="840" height="415" alt="Screenshot 2026-03-06 070609" src="https://github.com/user-attachments/assets/1f20da8d-8284-459d-bce1-0913133d1c35" />

<img width="1889" height="942" alt="Screenshot 2026-03-05 201004" src="https://github.com/user-attachments/assets/42386dd7-8569-4dca-8825-8325d45992a3" />

<img width="1888" height="942" alt="Screenshot 2026-03-05 201023" src="https://github.com/user-attachments/assets/7bc4cfc5-0a45-4af1-b68e-bcb4a6309bc2" />

<img width="1903" height="940" alt="Screenshot 2026-03-05 195825" src="https://github.com/user-attachments/assets/2400b7ae-d72e-476a-8bb8-2126535d567d" />

<img width="1902" height="938" alt="Screenshot 2026-03-05 195842" src="https://github.com/user-attachments/assets/b2dd9ac1-be5a-438c-8b10-8c215f6e0e53" />

<img width="1897" height="936" alt="Screenshot 2026-03-05 200848" src="https://github.com/user-attachments/assets/435ed628-2f1e-4bd3-baa2-3b44afda3887" />

<img width="1898" height="938" alt="Screenshot 2026-03-05 200930" src="https://github.com/user-attachments/assets/559cca7f-2907-404f-b0c2-2f8d72ec7a04" />

<img width="1897" height="939" alt="Screenshot 2026-03-05 195927" src="https://github.com/user-attachments/assets/b0471ed9-a66a-4fea-ae46-91119b3051e2" />

<img width="1897" height="934" alt="Screenshot 2026-03-05 200001" src="https://github.com/user-attachments/assets/4ee131c8-69f1-4a1d-ac32-d0a47c6d3cbd" />

<img width="1885" height="941" alt="Screenshot 2026-03-05 202031" src="https://github.com/user-attachments/assets/184d33a4-3432-4638-8ffc-4e4b6fc72b48" />

<img width="1884" height="943" alt="Screenshot 2026-03-05 202157" src="https://github.com/user-attachments/assets/afdcfb3a-cd98-4733-99d6-8cf72e84f49b" />

<img width="1896" height="939" alt="Screenshot 2026-03-05 202641" src="https://github.com/user-attachments/assets/af39c798-1bfe-4ead-8e36-f3bbef2cb069" />

<img width="1896" height="937" alt="Screenshot 2026-03-05 202658" src="https://github.com/user-attachments/assets/bc67a44b-5504-492a-acd4-921ef9c8310d" />

<img width="1881" height="943" alt="Screenshot 2026-03-05 203236" src="https://github.com/user-attachments/assets/6d87cb4f-d431-4d72-a0b0-3b939300f442" />

<img width="1884" height="943" alt="Screenshot 2026-03-05 202718" src="https://github.com/user-attachments/assets/a752d917-0444-4a87-80d9-f43b16ca6616" />

