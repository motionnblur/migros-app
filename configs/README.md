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

## Starting the stack

```powershell
docker compose --env-file configs/postgres.env --env-file configs/spring.env up
```

Compose uses these files for variable substitution. `configs/nginx` is mounted
into the Nginx container and contains the shared proxy, WebSocket, forwarded
header, timeout, and request-rate-limit settings.
