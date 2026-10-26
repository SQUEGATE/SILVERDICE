# Comp Manager — Handoff Document

_Last updated: October 22, 2026_

This document explains what Comp Manager is, how it is used day to day, how the pieces fit together, and what each part of the code does. It is written for whoever maintains or extends the app next.

> **Security note:** this document intentionally contains **no passwords, tokens, or secrets**. Secrets live only in Cloudflare Worker secrets and in each user's head. Never commit them to the repository.

---

## 1. What the app is

**Comp Manager** is a customer, billing, and statement manager for a pool service company (currently Silver Dice LLC). It tracks:

- **Customers** — contact info, service address, weekly service day, route order, monthly charge, status (Active/Inactive), starting date, notes.
- **Statements** — a running ledger per customer of charges (e.g. monthly pool service) and payments (Zelle, check, cash…), with a balance.
- **PDF statements** — printable/emailable statements with the company's branding.
- **Employees** — staff logins with fine-grained permissions and optional restriction to specific customers or service days.
- **Multiple companies** — a "master" account can host several companies, each with its own isolated data.

There are **two front-ends** sharing **one cloud database**:

| Front-end | Technology | Who uses it | Where it lives |
|---|---|---|---|
| **Desktop app** | Java 17 Swing (Windows `.exe` via jpackage) | Office / main PCs | `src/main/java/...` |
| **Web app (PWA)** | Single HTML file, vanilla JS | Phones, tablets, any browser | `cloudflare/worker/public/index.html` |

Both talk to the same **Cloudflare Worker API**, which reads/writes a **Turso (libSQL/SQLite) cloud database**.

**Live URL (API + web app):** `https://silverdice.josueguerra5555.workers.dev`
**Source repository:** `https://github.com/SQUEGATE/SILVERDICE`

---

## 2. Architecture overview

```
 ┌───────────────────────┐        ┌──────────────────────────┐
 │ Desktop app (Java)    │        │ Web app / PWA (phone)    │
 │ Swing UI + PDFBox     │        │ index.html (HTML/CSS/JS) │
 └──────────┬────────────┘        └────────────┬─────────────┘
            │  HTTPS + Bearer token (JSON)     │
            └──────────────┬───────────────────┘
                           ▼
            ┌──────────────────────────────────┐
            │ Cloudflare Worker "silverdice"   │
            │ cloudflare/worker/src/index.js   │
            │ - login / signed sessions        │
            │ - permissions & tenant isolation │
            │ - REST endpoints /v1/...         │
            │ - serves /public (web app)       │
            └──────────────┬───────────────────┘
                           ▼  Turso HTTP pipeline API
            ┌──────────────────────────────────┐
            │ Turso cloud database (SQLite)    │
            │ companies, accounts, customers,  │
            │ statement_records, record_types, │
            │ pdf_settings                     │
            └──────────────────────────────────┘
```

### Modes of the desktop app

- **Cloud mode (current production):** `api.base.url` is set in `config.properties`, so every read and write goes through the Worker. All devices see the same data.
- **Local mode (legacy / offline fallback):** `api.base.url` is blank, so the app uses local SQLite files (`master.db` and one `.db` file per company). This was the original design and is still supported in code (`DatabaseManager` and `MasterDatabaseManager` branch on `isRemote()` / `getApiBaseUrl()`).

### How changes propagate between devices

There is no push/live sync. Each screen **re-reads from the API when it is opened or refreshed**:

- **Desktop:** switching screens, selecting a customer, saving, or pressing Refresh reloads data. The PDF settings reload every time a statement is generated.
- **Web:** every navigation re-fetches. The statement page also refreshes PDF settings each time.
- **Closing and reopening the app is never required.** Just navigate away and back, or press Refresh.

---

## 3. User roles and permissions

| Role | How they log in | What they can do |
|---|---|---|
| **MASTER** | Master username | Manage companies (create/edit/delete), open any company's system, generate the desktop ZIP, get the phone link. |
| **COMPANY** | Company username | Full access to its own company: customers, statements, PDF settings, employees, backup/restore. |
| **EMPLOYEE** | Employee username | Only what their permission flags allow, optionally limited to certain customers and/or service days. |

Employee permission flags (stored as JSON in `accounts.permissions_json`, modeled in `EmployeeProfile.java`):

| Flag | Grants |
|---|---|
| `canViewCustomerDetails` | Customer Details screen (desktop), open a single customer |
| `canViewCustomers` | Customers list |
| `canEditCustomers` | Create/edit/delete customers, reorder routes |
| `canViewStatements` | Statements, Charged Summary, Record Types (view) |
| `canEditStatements` | Add/delete records, Monthly Statement, record type changes |
| `canAccessVisits` | Visits tab: search assigned customers, view/add/edit visits. Opt-in for employees; existing employees do not get it automatically. |
| `canAccessSalesQuote` | Sales Quote tab: prepare/save/edit/delete quotes and export quotation PDFs for assigned customers. Off by default for employees. Conversion additionally requires `canEditStatements`. |
| `canViewRevenueSummary` | Revenue Summary |
| `canViewPdf` | View PDF settings and generate statements |
| `canEditPdf` | Change PDF settings |

Plus `allowedCustomerIds` and `allowedDays`. If either list is non-empty, the employee only sees customers matching **either** list. This is enforced **server-side** in `customerAccessSql()` in the Worker.

---

## 4. Day-to-day usage (functional walkthrough)

### 4.1 Logging in
- **Desktop:** `LoginFrame` asks for a username and password. It tries master, then company, then employee login and opens the matching window.
- **Web:** the login screen posts to `/v1/auth/login`. The returned token is stored in `localStorage` (`cm.token`, `cm.account`), and sessions last 24h (`SESSION_TTL_SECONDS`).

### 4.2 Master dashboard
- **Desktop** (`MasterDashboardFrame`):
  - Shows a company list with search.
  - Lets you add, edit or delete a company.
  - **Open** a company enters that company's full system as a COMPANY session.
  - **Generate App ZIP** builds a distributable desktop ZIP into `Downloads`.
  - **Phone Link** shows the web app URL with Copy / Open in browser buttons.
- **Web** (`vCompanies`): company list, a phone link card (copy/share), and "Open" to enter a company. The master token is remembered, so a **Back to Master** button returns to the master view.

### 4.3 Customers
- Add or edit customers: ID (auto-suggested via `/v1/customers/next-id`), name, address, city/state/zip, phone, email, service day, **route order**, monthly amount charged, starting date (MM/DD/YYYY), status, notes.
- **Filter by service day.** When filtered to one day, customers can be **reordered**:
  - **Desktop:** drag rows in the table.
  - **Web:** ▲▼ buttons.
- The order is saved to `route_order`, and the **Order** field in customer details always matches the position in the filter. Changing the Order field to N moves that customer to position N and renumbers everyone else on that day (`placeInRoute` in the Worker).
- **Web only:** tapping an address opens Google Maps (`mapLink`).

### 4.4 Statements
- Pick a customer to see their ledger: date, type, amount, running balance.
- **Add record:** date (MM/DD/YYYY), type (from Record Types), amount. If the type contains "check", a check number is asked for and the type becomes `Check: #1234`.
- **Sign convention (important):**
  - **Charges** (types classified as "credit", e.g. "Pool Service") are stored as **positive** amounts.
  - **Payments** (types classified as "debit", e.g. "Zelle Payment") are stored as **negative** amounts.
  - **Balance = sum of amounts.** Above $0 means the customer owes money.
- Classification is by **keywords** (`recordTypeCreditKeywords` / `recordTypeDebitKeywords` in `pdf_settings`), editable via the **Keywords** button (desktop).
- Records are shown **newest to oldest** (screen and PDF): date (parsed as MM/DD/YYYY), then charges/payments, then ID, all reversed. The web app computes the running balance oldest-first, then reverses for display.

### 4.5 Monthly Statement (bulk billing)
Choose a credit type. The app adds a record to **every Active customer** using their **amount charged**, dated the **last day of the current month**.
- **Desktop:** `runMonthlyStatement()`
- **Web:** `vMonthly()`

### Visits (desktop and web)
- Open **Visits**, search customers and/or filter by service day, then select a customer.
- The visit page displays the customer's name and the live current date/time. **Past visits** lists saved visits newest-first; selecting one fills the form for editing. **New Visit** clears the form.
- Sliders have fixed choices: PH `7.0, 7.2, 7.4, 7.6, 7.8, 8.0`; CHLO `1-2, 2-4, 3-6, 5-10, 7.5-15, 10-20`; STB `100, 90, 80, 70, 60, 50, 40, 30`. New forms start at PH 7.4, CHLO 3-6, STB 50; these are initial selections, not recommended values.
- ALK and CAL are optional; blank values are stored as SQL NULL, not zero. When provided, each must be a positive whole number from 1 through 2147483647. Notes allow up to 16000 characters.
- Each visit can have **one optional photo**, saved with the visit. Upload/replace/remove the photo, then press **Save**. Selecting a past visit restores its photo along with its readings.
- Select a saved entry in **Past visits**, then press **Delete Visit** and confirm to permanently remove that visit and its photo. Delete is disabled for unsaved New Visit forms. The existing Visits employee permission includes deletion, scoped to assigned customers. Other devices see the deletion when they reload the customer's visits.
- **Web:** **Take Photo** requests the device camera using a file input with `capture="environment"`; the browser/device controls which capture interface opens (the app cannot force a preferred camera app). **Upload Photo** uses the normal file chooser. Unsupported formats such as HEIC on browsers that cannot decode them produce an error; use JPEG or PNG instead.
- **Desktop:** **Upload Photo** only, as requested. Choose PNG/JPEG (up to 25 MB). `VisitPhoto.java` reads, scales, and compresses the image. Both apps resize photos to at most 1280 pixels on the longest side and compress them to a JPEG data URL within 600000 characters. This is a resized copy, not archival storage of the original file.
- Photos are stored in `customer_visits.photo_data`, shared with authorized devices, and included in cloud backups. Existing visits without photos continue to work. Visit writes allow up to 700 KB. A PUT from an older client that omits `photo_data` preserves the existing photo; an explicit empty string removes it.
- Recommended values are shown only for STB (50-100), ALK (70-120), and CAL (250-500), as requested.
- **Save** creates a visit with the server's UTC timestamp (local desktop mode uses the computer's clock). Saving a selected past visit updates that record without changing its original date/time. Both apps display timestamps in the device's local timezone.
- Employee editors include a **Visits** permission, initially off. It grants viewing, adding, and editing visits only for customers allowed by the employee's customer/day assignments. It does not grant billing or customer editing rights. Employees should sign out and back in after permissions change.
- Visits are shared through the API, not pushed live: reopen Visits/select the customer again to load another device's changes. Cloud backups include visits; restoring an older backup without visits replaces visit history with an empty history.
- Desktop implementation: `VisitsPanel.java` owns the asynchronous search/form/history UI; `VisitRecord.java` defines choices and validation; `DatabaseManager.getVisits/saveVisit` handles local SQLite and remote API persistence.

### 4.6 Charged Summary
Lists Active customers with a balance above $0.00, highest first, with a total.
- **Desktop:** `refreshChargedSummary()`
- **Web:** `vCharged()`

### Sales Quote (desktop and web)
- Open **Sales Quote**, search customers and/or filter by service day, then select a customer. Each customer can have multiple saved quotes.
- Quotes reuse the statement record types and credit/debit classification but keep their own dated description/price lines. Saving a quote alone does **not** add statement entries or change the customer's balance.
- Add/remove/edit lines and notes, then save. The system assigns a company-scoped quote number in the form `SQ-000001`; deleting a quote does not make its number available for reuse.
- Generate a quotation PDF based on the supplied sample: company header/logo, **SALES QUOTATION** title, quote number, customer address block, notes, Date / Description / Price rows, total, advance-payment message, and payment methods. Company details/logo/payment instructions come from PDF settings. No quantity, tax, or expiration fields were requested.
- **Convert to Statement** is an explicit, confirmed action for an accepted, saved quote. It copies its dated signed lines into the customer's statement exactly once. Repeating conversion is rejected instead of duplicating entries.
- Company accounts can convert quotes. An employee must have **both Sales Quote and Edit Statement Data** permissions, and access to that customer.
- Converted quotes become **read-only** and cannot be deleted; PDF export remains available. Conversion does not process a payment or collect money.
- Save line/form changes before exporting or converting: a numbered saved quote, not an unsaved draft, is the source of both operations. Conversion preserves the stored sign/date and uses `type: description` for the statement description (or just the type when description is empty or identical).
- Quote data is shared through the API and included in company backups. Other devices see updates after reopening/reloading the customer's quotes, not by live push.
- Limits: 1-200 lines, nonzero prices up to 999999999.99 in absolute value with at most two decimal places; notes and terms up to 16000 characters each. Quote create/update bodies allow up to 2 MiB. Combined company backup restore retains its separate 50 MiB cap.
- Regression checks: run the canonical desktop `.\gradlew.bat test`, and from `cloudflare\worker` run `node --test test\quotes.sqlite.test.js test\quotes-web.test.js`. These cover stored quotes, numbering, permissions, conversion/retries/rollback, API contracts, PDF content/pagination, and form behavior.

### 4.7 Revenue Summary
Sum of `amount_charged` for non-inactive customers, grouped by service day, with a grand total.

### 4.8 PDF statements
- **Desktop** — **Generate Statement**:
  - `createStatementPDF()` uses Apache PDFBox and saves it as `LAST_FIRST_POOL_STATEMENT.pdf`.
  - **Send Email** opens Outlook with the PDF attached.
  - **Send SMS** opens the SMS handler (or Google Messages) with a text summary.
- **Web** — **Print / Save PDF**:
  - `printStatement()` renders an HTML statement into `#printarea` and calls `window.print()`.
  - The page title is set to the same file name so "Save as PDF" uses it.
- **Layout (both, modeled on the sample "1023 POOL STATEMENT.pdf"):**
  - Grey company name, bold address and phone, logo top-right, and a blue centered "STATEMENT".
  - A customer box (name/address left; Account No., Phone, Service Day such as "4 THURSDAY", and Starting Date right), then a Note box.
  - A blue payment-info bar, then the table: Date | Description (uppercase) | Price/Credit, where payments show as `($x)` and rows are shaded alternately.
  - "Total:" at the bottom, then footer text bottom-left, and the generated date plus "Page X of Y" bottom-right.
- **PDF Settings:**
  - Company name/address/phone/email, footer text, show generation date.
  - **Payment Info** (items separated by `|`), logo, show logo.
  - Desktop only: fonts, colors, margins, page size/orientation and element offsets.
- **Logo sharing:**
  - The desktop logo is a file path on that PC. The desktop also embeds a resized PNG copy (`logoData`, a base64 data URL) in the database so the web app and other PCs can use it.
  - This syncs automatically at startup, on save, and when generating statements (`syncEmbeddedLogo`).
  - The web app can also upload a logo directly in PDF Settings.

### 4.9 Record Types
A list of statement types per company (e.g. "Pool Service", "Zelle Payment", "Check").
- Add, rename or delete types. Renaming updates existing records.
- **Normalize** (`/v1/statement-types/normalize`) re-signs all records of a type after a reclassification.

### 4.10 Favorites / Quick switch
Every view button has a ☆ star. Starred views appear in the **Quick switch** bar in the order they were starred.
- **Desktop:** stored with `java.util.prefs.Preferences`.
- **Web:** stored in `localStorage` under the key `cm.fav.<user>.<companyId>`.

### 4.11 Employees
Company accounts can create, edit or delete employees: names, contact info, username/password, permission flags, allowed customers and allowed days.
- **Desktop** also has **Customer View**, which previews the app as that employee.

### 4.12 Backup / Restore
- **Cloud:**
  - `GET /v1/backup` exports the company's customers, statement records, record types and PDF settings as JSON.
  - `POST /v1/backup/restore` replaces them.
- **Local mode:** copy or replace the `.db` file.

---

## 5. Repository layout

```
DATABASE/
├── build.gradle            Gradle build (Java 17 toolchain, deps, packaging tasks)
├── settings.gradle
├── gradlew / gradlew.bat   Gradle wrapper (always use this)
├── README.md               Original setup notes (local + cloud setup)
├── HANDOFF.md              This document
├── run.bat / starter.bat / build.bat   Convenience scripts (use system Gradle)
├── migrate-turso.bat/.ps1  One-time import of local SQLite data into Turso
├── customer.db*            Legacy local SQLite databases / backups (keep as backups)
├── src/main/java/com/poolapp/...       Desktop app source (see §6)
├── src/main/resources/
│   ├── config.properties   Runtime config (API URL, email/SMS, update repo)
│   └── logo.jpg            App icon source (window + .exe icon)
├── cloudflare/worker/      Cloud API + web app (see §7)
├── gradle-build/           Default Gradle build output (generated)
├── gradle-build-*/         Old one-off build output folders (safe to delete)
├── bin/, out/              IDE compile outputs (generated; safe to delete)
└── .vscode/                Launch config ("Starter") + settings pinning JDK 17
```

---

## 6. Desktop app code (Java) — what each file does

Package root: `src/main/java/com/poolapp/`

### 6.1 Entry points
| File | Purpose |
|---|---|
| `Main.java` | Starts the Swing UI: opens `LoginFrame` and kicks off the background `UpdateChecker`. Gradle `run` uses this. |
| `Starter.java` | Thin wrapper calling `Main.main`. The packaged `.exe` and the VS Code "Starter" launch config use it. |

### 6.2 `ui/` — screens
| File | Purpose |
|---|---|
| `LoginFrame.java` | Login window. Tries master, then company, then employee authentication through `MasterDatabaseManager`, then opens `MasterDashboardFrame` or `PoolAppFrame`. |
| `MasterDashboardFrame.java` | Master console: company table and search, company details, add/edit/delete company, open a company's system, **Generate App ZIP** (`generateAppPackage` → `AppPackageGenerator`), **Phone Link** dialog (`showPhoneLink`, which copies or opens the web URL). |
| `PoolAppFrame.java` | **The main application window** (~3,800 lines). A `CardLayout` with screens `CustomerDetails`, `Customers`, `Statements`, `RevenueSummary`, `PDF`, `ChargedSummary` and `Employees`. See the breakdown below. |
| `AppWindowStyle.java` | Applies the app icon (from `logo.jpg`) to windows. |

#### `PoolAppFrame.java` breakdown (by area, approximate line numbers)

| Area | Key methods | What it does |
|---|---|---|
| Construction & layout | constructors (~124–280), `initComponents` (~292), `resolveWindowTitle` | Builds all screens, the left navigation, and the logout button. Loads PDF settings and record-type keywords, and syncs the logo. |
| Navigation & favorites | `showScreen`, `withStar`, `loadFavorites`/`saveFavorites`, `updateStar`, `updateMiniTabs` | Switches screens (reloading data as needed); star buttons and the quick-switch bar. |
| Customer details form | `refreshDetailCustomerList`, `showCustomerByIndex`, `selectCustomerById`, `showPrevious/NextCustomer`, `populateFieldsWithCustomer`, `searchCustomerInDetails`, `clearForm`, `buildCustomerFromForm`, `saveCustomer`, `placeCustomerInRoute`, `isValidDate`, `setNewCustomerId`, `formatPhoneField`, `formatAmountField` | Viewing and editing one customer. Saving validates dates (MM/DD/YYYY) and places the customer at the requested route order. The form sits inside a `ScrollableFormPanel` so fields stay visible in small windows. |
| Customers list & route order | `loadCustomers`, `loadFilteredCustomers`, `resetFilters`, `populateFormFromSelection`, `updateRouteOrderEnabled`, `canReorderCustomers`, `installCustomerRowDragging`, `saveCustomerRowOrder` | Table with search and day filter. Drag-and-drop reordering when a day is filtered calls `dbManager.reorderCustomers`. |
| Permissions | `canAccessScreen`, `getDefaultAccessibleScreen`, `canEditCustomerData`, `canEditStatementData`, `canEditPdfData`, `applyCustomerEditPermissions`, `applyStatementEditPermissions`, `filterAccessibleCustomers`, `canAccessCustomer` | Hides or disables UI by employee permission (the server enforces the same rules). |
| Employees | `createEmployeesPanel`, `refreshEmployeesScreen`, `openEmployeeEditor`, `deleteSelectedEmployee`, `openEmployeeCustomerView`, `cloneEmployee` | Employee CRUD and "view as employee". |
| Statements screen | `openStatementForCustomer`, `loadStatementRecordsForCustomer`, `addStatementRecord`, `deleteSelectedRecord`, `updateCustomerBalanceLabel`, `formatRecordAmountField`, `promptForCheckNumber` | Ledger table, add or delete records, balance label. |
| Record types & keywords | `addCustomRecordType` (manage dialog), `loadRecordTypesFromDatabase`, `populateRecordTypeCombo`, `classifyRecordType`, `loadRecordTypeKeywords`, `manageRecordTypeKeywords`, `saveRecordTypeKeywords`, `reconcileStatementRecordAmountsForCurrentTypeRules` | Types list and credit/debit keyword classification, plus re-signing of amounts. |
| Monthly / Charged / Revenue | `runMonthlyStatement`, `createChargedSummaryPanel`, `refreshChargedSummary`, `refreshRevenueSummary` | Bulk monthly billing, balances list, revenue by day. |
| PDF settings screen | `loadPdfSettings`, `savePdfSettings`, `createPdfPanel`, `drawPdfPreview`, `updateElementOffset`, `setContainerEnabled` | Settings editor with live preview. **Note:** the preview still draws the *old* layout; only the generated PDF uses the new one. |
| **PDF generation** | `generateStatement`, `buildStatementFileName`, `createStatementPDF`, `drawStatementTableHeader`, `drawStatementFooters`, `loadStatementLogo`, `syncEmbeddedLogo`, `refreshEmbeddedLogo`, plus helpers `drawPdfText`, `drawPdfBox`, `pdfTextWidth`, `fitPdfText`, `wrapPdfText`, `pdfSafeText`, `splitCompanyAddress`, `formatStatementPhone`, `statementServiceDay`, `formatStatementAmount` | Builds the statement PDF (layout in §4.8). Colors are the `STMT_*` constants. Multi-page with a repeated table header; text is made WinAnsi-safe. |
| Email / SMS | `sendEmailToCustomer`, `sendSmsToCustomer`, `createTemporaryStatementPdf`, `buildStatement`, `getStatementSender*` | Creates a temporary PDF and hands off to `EmailService` / `SmsService`. |
| Backup / restore | `backupDatabase`, `restoreDatabase`, `backupCloudDatabase`, `restoreCloudDatabase` | Local file copy, or cloud JSON export/import. |

### 6.3 `db/` — data access
| File | Purpose |
|---|---|
| `DatabaseManager.java` | **Per-company data access.** Every public method (customers, statements, record types, PDF settings, revenue, reorder, backup) has two branches: **remote** (calls the Worker through `CompManagerApiClient`) and **local** (JDBC against a SQLite file). Local mode creates and migrates tables in `initializeDatabase` and `addColumnIfMissing`. Statement records are sorted by `parseRecordDate` (MM/DD/YYYY). |
| `MasterDatabaseManager.java` | **Accounts and companies.** Login (`authenticateMaster/Company/Employee`, which call `/v1/auth/login` in cloud mode), company CRUD, employee CRUD, and creating `DatabaseManager`s for a company or for an employee preview. Local mode keeps a `master.db`. |
| `SalesQuoteStore.java` | Quotation persistence and once-only conversion to statement records, using local SQLite or the authenticated API client exposed by `DatabaseManager.getApiClient()`. |
| `CompManagerApiClient.java` | Minimal HTTP JSON client (GET/POST/PUT/DELETE with Bearer token). Turns API `{error}` responses into exceptions. |
| `AppApiConfiguration.java` | Resolves the API URL. Precedence: env `COMP_MANAGER_API_URL`, then system property `comp.manager.api.url`, then `config.properties` (packaged), `./config.properties`, and `%USERPROFILE%\.compmanager\config.properties`. `getWebAppUrl()` is used for the phone link. |
| `TursoHttpClient.java` | Direct Turso HTTP client, used **only** by the migrator. |
| `TursoDataMigrator.java` | One-time import from local SQLite (master and company DBs) into Turso. Applies `schema.sql`, re-hashes passwords to PBKDF2, and upserts everything. Run via `migrate-turso.bat` or `gradlew migrateTurso`. Safe to rerun. |

### 6.4 `model/` — plain data classes
| File | Purpose |
|---|---|
| `Customer.java` | Customer fields: id, names, address, city/state/zip, phone, email, serviceDay, amountCharged, notes, startingDate, status, routeOrder. |
| `StatementRecord.java` | id, date (MM/DD/YYYY string), type, amount (`BigDecimal`). |
| `SalesQuote.java` | Saved quotation identity/number, date, customer, notes, payment terms, signed line items, and conversion/creation/update timestamps. |
| `CompanyProfile.java` | Company id, name, contact info, username, local DB path. |
| `EmployeeProfile.java` | Employee identity, permission flags, allowed customers and days, API session token. |
| `PdfSettings.java` | All PDF settings: fonts, colors, margins, offsets, company info, footer, logo path/size/show, `paymentInfo`, `logoData`, page size, landscape. |

### 6.5 `service/` and `update/`
| File | Purpose |
|---|---|
| `service/AppPackageGenerator.java` | Behind the master's **Generate App ZIP** button. Locates the project, runs `gradlew packageAppImage` into a temp build folder, verifies `Comp Manager.exe` exists, then zips the app image to `Downloads\Comp-Manager-cloud-test-<timestamp>.zip`, retrying if antivirus locks files. Requires the source checkout on that PC. |
| `service/EmailService.java` | Opens a new Outlook message (classic `outlook.exe` or new `olk.exe`) with the PDF attached. SMTP settings in `config.properties` are legacy. |
| `service/SmsService.java` | Opens the Windows `sms:` handler, or falls back to Google Messages for Web, with a text summary (no attachment). |
| `service/SalesQuotePdf.java` | PDFBox quotation export based on the supplied sample, including the company logo/header, quote/customer details, line table, totals, advance-payment terms, and payment instructions. |
| `update/UpdateChecker.java` | On startup, checks GitHub Releases of `update.repo` (`SQUEGATE/SILVERDICE`) and offers to open the download if a newer version exists. Skipped when not running from a packaged jar. |

---

## 7. Cloud code (`cloudflare/worker/`)

| File | Purpose |
|---|---|
| `wrangler.jsonc` | Worker config: name **`silverdice`** (this defines the URL, so do not rename it), entry `src/index.js`, static assets from `./public`, and `SESSION_TTL_SECONDS=86400`. |
| `package.json` | Only dependency is `wrangler`. Scripts: `dev`, `deploy`. |
| `schema.sql` | Base Turso schema (tables in §8). Some newer columns are added automatically at runtime (see `turso()` below). |
| `.dev.vars.example` | Template for local-dev secrets (never commit real values). |
| `src/index.js` | **The API** (described below). |
| `public/index.html` | **The whole web app** (described below). |
| `public/manifest.webmanifest`, `public/icon.svg` | PWA metadata, so the web app can be "Added to Home Screen". |

### 7.1 `src/index.js` — Worker API

**Request flow:** `fetch()` checks the route. Public routes are `/`, `/v1/health` and `/v1/auth/login`. Everything else first passes `authenticateRequest()`, which verifies the HMAC-signed token and confirms the account still exists. The handler then checks permissions and runs SQL scoped to `company_id`. Errors become `{error}` JSON; 5xx responses are logged and returned generically.

**Endpoints:**

| Method & path | Who | Does |
|---|---|---|
| `GET /v1/health` | public | Readiness check. |
| `POST /v1/auth/login` | public | Verifies a PBKDF2 password and returns `{token, account}`. |
| `GET/POST /v1/admin/companies` | master | List or create/update companies. |
| `GET/DELETE /v1/admin/companies/:id` | master | Company details / delete. |
| `POST /v1/admin/companies/:id/session` | master | Issues a COMPANY "preview" token (the master opens a company). |
| `GET/POST /v1/admin/companies/:id/employees` | master or that company | List or save employees. |
| `DELETE /v1/admin/employees/:id` | master or that company | Delete an employee. |
| `POST /v1/companies/employees/:id/session` | company | Employee preview token ("view as employee"). |
| `GET /v1/customers[?q=&order=id]` | customer view permissions, visits, or sales quote | List, sorted by day then route order. Also **self-heals** route orders to 1..N (`healRouteOrders`). |
| `GET /v1/customers/next-id` | view customers | Next numeric customer ID. |
| `POST /v1/customers/reorder` | edit customers | `{service_day, customer_ids[]}` → sets route_order 1..N. |
| `POST /v1/customers`, `PUT/DELETE /v1/customers/:id`, `GET /v1/customers/:id` | edit/view | Customer CRUD. Create and update call `placeInRoute` so the Order field and route stay consistent. Delete cascades the customer's statement records. |
| `GET /v1/customers/:id/balance` | view statements | Sum of amounts. |
| `GET/POST /v1/customers/:id/statements` | view/edit statements | List or add records. |
| `GET/POST /v1/customers/:id/visits` | visits | Newest-first history or create a visit; creation time comes from the server. |
| `PUT /v1/customers/:id/visits/:visitId` | visits | Update readings/notes, preserving the original timestamp. Every visit operation enforces company and customer assignments. |
| `DELETE /v1/customers/:id/visits/:visitId` | visits | Permanently delete the selected visit and its photo. Company and customer assignment restrictions apply; missing visits return 404. |
| `GET/POST /v1/customers/:id/quotes` | sales quote | List a customer's saved quotations or create a numbered quote. |
| `PUT/DELETE /v1/customers/:id/quotes/:quoteId` | sales quote | Edit/delete an unconverted quote; converted quotes are locked. |
| `POST /v1/customers/:id/quotes/:quoteId/convert` | sales quote + edit statements (company accounts allowed) | Atomically copy quote lines to the statement once and lock the quotation. |
| `DELETE /v1/statements/:id` | edit statements | Delete a record. |
| `GET /v1/record-types`, `POST`/`PUT`/`DELETE` | view statements or sales quote for GET; edit statements for writes | Manage types (rename updates existing records). Quotation access alone never grants type editing. |
| `GET /v1/statement-types` | view statements | Distinct types in use. |
| `POST /v1/statement-types/normalize` | edit statements | Force a type's amounts positive or negative. |
| `GET /v1/revenue` | revenue | Sum of charges by service day. |
| `GET/PUT /v1/settings` | view PDF or sales quote for GET; edit PDF for PUT | PDF settings key/value. Values are limited to 16,000 chars, except `logoData` (600,000 chars, PNG/JPEG data URL only). Settings requests may be up to 700 KB. Quotation access needs read-only settings for company branding and credit/debit rules. |
| `GET /v1/backup`, `POST /v1/backup/restore` | company manager | JSON export / full replace. |

**Helper functions:**

| Group | Functions | Purpose |
|---|---|---|
| Auth & sessions | `signSession` / `verifySession` | HS256 JWT-style tokens, signed with `SESSION_SIGNING_SECRET`. |
| | `hashPassword` / `verifyPassword` | PBKDF2-SHA256, 100k iterations. |
| | `constantTimeEqual` | Timing-safe comparison. |
| Access control | `requirePermission` | MASTER is blocked from company data endpoints; COMPANY is always allowed; EMPLOYEE needs the flag. |
| | `requireMaster`, `requireCompanyManager`, `requiredCompanyId` | Role and tenant checks. |
| | `customerAccessSql`, `findAccessibleCustomer` | Employee restriction to allowed customers or days. |
| Database | `turso()` → `tursoCore()` | Runs SQL through the Turso HTTP pipeline. If a "no such column" error happens, it runs the auto-migrations (`ALTER TABLE customers ADD COLUMN starting_date / status / route_order`) and retries. |
| | `tursoTransaction()` | Runs several statements atomically (used for reorders and restore). |
| Validation & HTTP | `readJson` (body-size limit), `validateCustomer`, `requiredString`, `positiveInteger` | Input validation. |
| | `corsHeaders`, `json`, `httpError` | HTTP response helpers. |

**Worker secrets** (set with `npx wrangler secret put <NAME>`, never in files):
- `SILVERDICE_URL` — the Turso database URL (`TURSO_HTTP_URL` is also accepted).
- `SILVERDICE_AUTH_TOKEN` — the Turso auth token (`TURSO_AUTH_TOKEN` is also accepted).
- `SESSION_SIGNING_SECRET` — at least 32 random bytes.

> The README mentions `TURSO_AUTH_TOKEN`. The Worker accepts either name, but `wrangler.jsonc` lists the `SILVERDICE_*` names as required.

### 7.2 `public/index.html` — Web app (PWA)

The page holds one `<style>` block (mobile-first, rem-based so it adapts to the device's font-size setting, with a side drawer on phones and a fixed sidebar on wide screens) and one `<script>` block.

Script sections, in order:

| Section | Key functions | Purpose |
|---|---|---|
| State & helpers | `S` (token, account, view, cached settings `S.kw`), `$`, `esc`, `money`, `flash`, `mapLink` | Global state and HTML-safe helpers. |
| API | `api(path, opts)` | Fetch wrapper with Bearer token. Signs out on 401. |
| Permissions | `can(flag)`, `isManager()` | Mirror of the server's rules, used to hide UI. |
| Dates | `parseDate`, `fmtDate`, `dateInput`, `cmpRecords` | MM/DD/YYYY parsing, auto-formatting, and record sorting. |
| Classification | `loadKw`, `kwList`, `isCredit` | Loads PDF settings and keywords; decides charge vs payment. |
| Favorites & nav | `VIEWS`, `favs`, `toggleFav`, `renderChrome`, `go(view,arg)` | Menu, stars, quick switch, router. |
| Login & session | `showLogin`, `start`, `signOut`, `backToMaster` | Login form and session storage. |
| Master | `vCompanies`, `vCompany` | Companies list, phone link card, open company. |
| Customers | `vCustomers` (`draw`, `saveOrder`, ▲▼ reordering), `vPickCustomer`, `vCustomer` | List with search and day filter; customer edit form (Order field enabled only when a day is set). |
| Statement | `vStatement`, `printStatement`, `stmtPhone`, `stmtAmt`, `stmtAddr` | Ledger, add or delete records, branded printable statement. |
| Other views | `vMonthly`, `vCharged`, `vRevenue`, `vTypes`, `vPdf`, `vEmployees`, `vEmployee`, `vBackup` | Same features as the desktop (see §4). |
| Boot | last lines | Resumes the session if a token exists, otherwise shows login. |

---

## 8. Database schema (Turso)

| Table | Key | Main columns |
|---|---|---|
| `companies` | `company_id` | company_name, phone, email, address, username |
| `accounts` | `account_id` | username (unique), password_hash (PBKDF2), role (MASTER/COMPANY/EMPLOYEE), company_id, employee_id, names, phone, email, `permissions_json`, `allowed_customer_ids_json`, `allowed_days_json` |
| `customers` | (`company_id`, `customer_id`) | names, address, city, state, zip, phone, email, service_day, amount_charged, notes, starting_date, status, **route_order** (added at runtime) |
| `statement_records` | (`company_id`, `record_id`) | customer_id, record_date (MM/DD/YYYY text), type, amount (+charge / −payment) |
| `customer_visits` | (`company_id`, `visit_id`) | customer_id, visited_at (UTC ISO timestamp), ph, chlo, stb, nullable alk/cal, notes, photo_data (PNG/JPEG data URL or empty string). Created/migrated automatically for existing databases, preserving saved visits when ALK/CAL become nullable. Local company SQLite uses visit_id as its primary key. |
| `sales_quotes` | (`company_id`, `quote_id`) | quote_number, customer_id, quote_date (MM/DD/YYYY), notes, terms, lines_json, converted_at (NULL until converted), created_at, updated_at. |
| `sales_quote_sequences` | `company_id` | next_id, a durable company-specific high watermark so quote numbers are not reused after deletion. |
| `record_types` | (`company_id`, `type_name`) | — |
| `pdf_settings` | (`company_id`, `setting_key`) | setting_value. Keys include companyName, companyAddress, companyPhone, companyEmail, footerText, showGenerationDate, paymentInfo, logoPath, logoData, showLogo, logoWidth, logoHeight, fonts, colors, margins, offsets, pageSize, landscape, recordTypeCreditKeywords, recordTypeDebitKeywords. |

Every company-scoped query filters by `company_id` taken from the **signed session**, never from the client, which keeps tenants isolated.

---

## 9. Configuration

`src/main/resources/config.properties` is packaged into the app. It can be overridden by `./config.properties` or `%USERPROFILE%\.compmanager\config.properties`.

| Key | Meaning |
|---|---|
| `api.base.url` | Worker URL. Blank means local SQLite mode. |
| `update.repo` | GitHub repo checked for new releases. |
| `email.enabled`, `mail.smtp.*` | Legacy SMTP settings (email now opens Outlook). |
| `sms.enabled`, `twilio.*` | SMS toggle and unused Twilio placeholders. |

---

## 10. Build, run, package, deploy

### Desktop
- **Requirements:** JDK 17 (installed at `C:\Program Files\Java\jdk-17`). The Gradle wrapper downloads Gradle 9.2.
- **Compile:** `.\gradlew.bat compileJava`
- **Visits regression tests:** `.\gradlew.bat test` (JUnit validation, real SQLite persistence, and headless Swing form flows). If OneDrive locks build output, supply `-PverificationBuildDir=<absolute temporary directory outside OneDrive>`.
- **Run from source:** `.\gradlew.bat run`, or in VS Code use the **Starter** launch config.
  - `.vscode/settings.json` pins VS Code's Java to JDK 17.
  - If you ever see `UnsupportedClassVersionError (class file version 69)`, delete the `bin` folder and run *Java: Clean Java Language Server Workspace*.
- **Package:**
  - `.\gradlew.bat packageAppImage` produces a portable folder `gradle-build\dist\Comp Manager\Comp Manager.exe`.
  - `.\gradlew.bat packageExe` produces an installer (needs WiX Toolset).
  - Or, from the Master dashboard, click **Generate App ZIP**, which writes a ZIP to `Downloads`.
- **Distributing:** unzip on the target PC and run `Comp Manager.exe`. It contains its own Java runtime and the API URL, so no setup is needed.
- **Any Java change requires a rebuild and a new ZIP** for other PCs.

### Web + API
- From `cloudflare/worker`: run `npm install` once, then `npx wrangler deploy`.
- Changes to `index.js` and `index.html` are live as soon as you deploy. Users just refresh the page.
- Health check: `https://silverdice.josueguerra5555.workers.dev/v1/health`.
- Quick JS syntax check before deploying: extract the `<script>` block to a file and run `node --check`.

---

## 11. Conventions and gotchas

- **Dates:** always `MM/DD/YYYY` in both apps and stored that way as text. Sorting parses them; don't compare the raw strings.
- **Amounts:** charges are positive and payments negative. Balance is the sum. The PDF shows negatives as `($x)`.
- **Route order:** always 1..N within a service day. The server renumbers automatically, so never write raw `route_order` values without going through `placeInRoute` or `/reorder`.
- **Statement file name:** `LAST_FIRST_POOL_STATEMENT.pdf` (uppercase, non-alphanumerics → `_`) on both desktop and web.
- **Logo on web:** requires `logoData` in the database. The desktop uploads it automatically from the logo file, or upload it in the web PDF Settings.
- **The URL / Worker name `silverdice`** is intentionally kept. Renaming it changes the URL, which breaks every installed desktop and phone link.
- **Line endings:** Java sources are CRLF. Keep them consistent when editing with scripts.
- **Build output clutter:** `gradle-build-*` folders, `bin/` and `out/` are generated and can be deleted. The real build dir is `gradle-build/`.
- **Local `.db` files** (`customer.db*`) are legacy backups from before the cloud migration. Keep them; don't commit new ones.

---

## 12. Known limitations and ideas for next steps

1. **The desktop PDF Settings preview** still draws the old layout. Update `drawPdfPreview` to mirror `createStatementPDF`.
2. **No real-time sync.** Data refreshes on navigation or Refresh only. A periodic refresh or Server-Sent Events could be added if needed.
3. **Web "Page X of Y"** relies on CSS `@page` margin boxes (supported in current Chrome/Edge, not all mobile browsers).
4. **iPhone Safari** may ignore the page title when saving a PDF, so it may not use the statement file name.
5. **Monthly Statement on web** posts one request per customer. For very large customer lists, a bulk endpoint would be faster and atomic.
6. **Charged Summary on web** fetches each customer's balance separately. A single server-side `GROUP BY` endpoint would be faster.
7. **`schema.sql`** doesn't list `starting_date`, `status` or `route_order` (they are auto-added at runtime). Add them for clarity on fresh installs.
8. **README** still describes some legacy local-mode details and older secret names. Treat this handoff as the current reference.
9. **App ZIP generation** only works on a PC that has the source checkout and JDK 17 (it runs Gradle).
10. **Uncommitted changes:** at the time of writing, the latest PDF restyle, logo sync and statement file-name changes in `PoolAppFrame.java` and `index.html` are not committed yet. Commit and push them, then rebuild the desktop ZIP.
