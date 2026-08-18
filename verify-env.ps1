# Temporary verification environment. Development values only. Deleted after use.
$env:DB_URL       = 'jdbc:postgresql://127.0.0.1:5432/evoting_verify'
$env:DB_USERNAME  = 'postgres'
$env:DB_PASSWORD  = $env:LOCAL_PG_PASSWORD  # set this in your own shell first — never hardcode it here

$env:EVOTING_MASTER_KEY = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Minimum 0 -Maximum 256 }))
$env:EVOTING_JWT_SECRET = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Minimum 0 -Maximum 256 }))
$env:EVOTING_FINGERPRINT_PEPPER = 'verify-pepper-do-not-reuse'
$env:EVOTING_MACHINE_BOOTSTRAP_SECRET = 'verify-bootstrap-secret'
$env:EVOTING_ADMIN_API_KEY = 'verify-admin-api-key'
$env:EVOTING_ADMIN_USERNAME = 'admin'
$env:EVOTING_ADMIN_PASSWORD = 'verify-admin-password'

$env:SERVER_PORT = '8443'
$env:SERVER_SSL_ENABLED = 'true'
$env:SERVER_SSL_KEYSTORE = 'file:./evoting-dev.p12'
$env:SERVER_SSL_KEYSTORE_PASSWORD = 'devkeystore'
$env:SERVER_SSL_KEY_ALIAS = 'evoting'
$env:EVOTING_SIMULATION_ENABLED = 'true'
