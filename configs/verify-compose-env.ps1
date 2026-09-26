# Verifies that Docker Compose forwards the Stripe configuration into the backend
# service environment. The script renders `docker compose config` with
# non-secret placeholder values, so nothing sensitive is required or committed.
$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $repoRoot 'compose.yaml'
$postgresEnv = Join-Path $repoRoot 'configs/postgres.env'
$tempEnv = Join-Path ([System.IO.Path]::GetTempPath()) ("compose-env-verify-" + [guid]::NewGuid().ToString('N') + ".env")

$dummyStripeKey = 'sk_test_compose_forwarding_check'
$dummyCurrency = 'try'

$content = @"
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/migros
SPRING_DATASOURCE_USERNAME=migros
SPRING_DATASOURCE_PASSWORD=dummy
APP_FRONTEND_BASE_URL=http://localhost:4200
APP_BACKEND_BASE_URL=http://localhost:8080
APP_ALLOWED_ORIGINS=http://localhost:4200
APP_ALLOWED_ORIGIN_PATTERNS=http://localhost:*
JWT_USER_SECRET=dummy
JWT_ADMIN_SECRET=dummy
SUPPORT_INTERNAL_KEY=dummy
STRIPE_API_KEY=$dummyStripeKey
PAYMENT_CURRENCY=$dummyCurrency
APP_MAIL_PROVIDER=smtp
MAIL_HOST=smtp.example.com
MAIL_PORT=587
MAIL_USERNAME=dummy
MAIL_PASSWORD=dummy
APP_MAIL_FROM=no-reply@example.com
"@
[System.IO.File]::WriteAllText($tempEnv, $content)

try {
    $json = docker compose -f $composeFile --env-file $postgresEnv --env-file $tempEnv config --format json
    if ($LASTEXITCODE -ne 0) {
        throw "docker compose config failed with exit code $LASTEXITCODE"
    }

    $config = $json | ConvertFrom-Json
    $environment = $config.services.backend.environment

    if ($environment.STRIPE_API_KEY -ne $dummyStripeKey) {
        throw "backend STRIPE_API_KEY was '$($environment.STRIPE_API_KEY)' but expected '$dummyStripeKey'"
    }
    if ($environment.PAYMENT_CURRENCY -ne $dummyCurrency) {
        throw "backend PAYMENT_CURRENCY was '$($environment.PAYMENT_CURRENCY)' but expected '$dummyCurrency'"
    }

    Write-Output "PASS: rendered backend environment forwards STRIPE_API_KEY and PAYMENT_CURRENCY"
}
finally {
    Remove-Item -LiteralPath $tempEnv -Force -ErrorAction SilentlyContinue
}
