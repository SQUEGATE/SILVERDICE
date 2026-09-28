# Pool Service Customer Manager

This is a Java desktop app for managing pool service customers.

## What it includes
- Add and edit customer records
- Store customer data in a local SQLite database file (`customer.db`)
- Search customers by first or last name
- Group customers by service day
- Calculate daily and total revenue by weekday group
- Generate service statements
- Open an email client or SMS app for statements when configured

## How to run
1. Open the project folder in a terminal.
2. Use the Gradle wrapper:

```bat
gradlew.bat build
gradlew.bat run
```

3. If you do not have the wrapper, use your installed Gradle instead:

```bat
gradle build
gradle run
```

## Configuration
Edit `src/main/resources/config.properties` or create a local `config.properties` in the project root.

To send a customer statement by email with its PDF attached, configure an SMTP mailbox that is authorized to send from the company or employee email saved in the app:
```properties
email.enabled=true
mail.smtp.host=auto
mail.smtp.port=587
mail.smtp.username=your-email@example.com
mail.smtp.password=your-app-password
```

For Gmail, Outlook, Yahoo, iCloud, or Proton Mail, set `mail.smtp.host=auto` and the app detects the host from the sender email. Other domains use the configured fallback host automatically. You still need to replace the username and password with the mailbox email and an app password.

`Send SMS` prepares the statement in the computer's SMS handler. SMS cannot include a PDF attachment unless an MMS/SMS provider and a public PDF link are configured.

To enable SMS support:
```properties
sms.enabled=true
```

## Notes
- The app uses `com.poolapp.Main` as the entry point.
- Customer data is persisted in `customer.db` using SQLite.
- Gradle automatically downloads the SQLite JDBC dependency defined in `build.gradle`.

## Shared Cloud Database Setup
The Cloudflare Worker API and tenant-scoped Turso schema are in `cloudflare/worker`. The desktop app can use the API when `api.base.url` is configured; leaving it empty preserves local SQLite mode. Keep all local `.db` files as backups.

1. Install Node.js LTS, open a terminal in `cloudflare/worker`, run `npm install`, then `npx wrangler login`.
2. Create a short-lived, database-scoped read/write token with `turso db tokens create silverdice-database --expiration 1h`. From the `DATABASE` folder, run `migrate-turso.bat` and paste only the JWT at its masked prompt. The importer validates Turso access before writing, safely upserts master users, companies, employees, customers, statements, record types, and PDF settings, and leaves SQLite files unchanged. Rerunning it is safe.
3. Deploy from `cloudflare/worker` with `npx wrangler deploy`. Copy the deployed Worker URL.
4. Add **Worker secrets** (not only Cloudflare account-level secrets) using `npx wrangler secret put TURSO_AUTH_TOKEN` and `npx wrangler secret put SESSION_SIGNING_SECRET`. Wrangler prompts for each value. Use a random session-signing value with at least 32 random bytes. Never commit either secret.
5. Check `https://<worker-host>/v1/health`; it should return `{"ready":true}`. If it fails, check the Worker secret names and Turso URL/token.
6. On each desktop, create `%USERPROFILE%\.compmanager\config.properties` containing `api.base.url=https://<worker-host>`, then restart Comp Manager. The app will use cloud mode on the next login. Do not switch users until the migration and login checks are successful.

If running the Gradle task manually, enter the token through a masked prompt rather than writing it in a command or config file:
```powershell
$env:TURSO_DATABASE_URL = 'libsql://silverdice-database-squegate.aws-us-east-1.turso.io'
$secureToken = Read-Host 'Turso database token' -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
try {
	$env:TURSO_AUTH_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
	.\gradlew.bat migrateTurso
} finally {
	[Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
	Remove-Item Env:TURSO_AUTH_TOKEN -ErrorAction SilentlyContinue
	Remove-Item Env:TURSO_DATABASE_URL -ErrorAction SilentlyContinue
}
```
