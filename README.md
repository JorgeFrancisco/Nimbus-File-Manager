# Nimbus File Manager

![Nimbus File Manager](src/main/resources/static/img/nimbus-file-manager-banner-readme.png)

<img width="1890" height="943" alt="imagem" src="https://github.com/user-attachments/assets/fe31e0b3-02d3-467b-bd31-a2d1fdd59321" />

<img width="1770" height="889" alt="imagem3" src="https://github.com/user-attachments/assets/a41a6242-e60f-4e3e-95ed-131c81f15d42" />

<img width="1882" height="940" alt="imagem2" src="https://github.com/user-attachments/assets/4d9685e2-41a3-4a10-a4a6-6de586555939" />

Local-first file manager built with Java 25, Spring Boot and PostgreSQL for continuously inventorying, monitoring, enriching, organizing and auditing personal files.

Rather than acting as a traditional file explorer, Nimbus File Manager builds an intelligent catalog of your files, extracts metadata, detects duplicates and similar content, tracks filesystem changes in real time, and provides safe organization workflows with full audit history and undo support.

It provides a REST API, OpenAPI documentation and a lightweight Thymeleaf web interface with login, optional 2FA, dashboard, file explorer, organization, execution history and runtime settings/preferences. Inventory runs continuously in the background once configured; it has no dedicated screen or REST endpoint.

Project started on July 5, 2026.

## Stack

- Java 25
- Spring Boot 3.5
- Spring Security
- Thymeleaf
- Spring Data JPA and Hibernate
- PostgreSQL
- Flyway
- Lombok
- Apache Tika
- OpenAPI / Swagger
- JaCoCo
- PIT Mutation Testing
- FFprobe / FFmpeg / ExifTool bundled in `tools/bin`
- TwelveMonkeys ImageIO (WebP thumbnail decoding, via the ImageIO SPI)
- Leaflet (interactive media map, via WebJar; OpenStreetMap tiles by default)
- Java Foreign Function &amp; Memory API (Windows-only real-time change source: `ReadDirectoryChangesW` + NTFS USN journal catch-up, via `java.lang.foreign`; inert on other platforms)

## Main Features

- Recursive file inventory with streaming scan.
- Optional SHA-256 and MD5 calculation in a single file read.
- Metadata extraction from filesystem, EXIF, filename patterns and video streams.
- Fully offline GPS reverse geocoding based on administrative boundaries (point-in-polygon), persisted as reusable media metadata.
- Duplicate detection using SHA-256.
- Statistics and paginated media search.
- Timeline screen for browsing media grouped by date.
- Map screen plotting geo-referenced media: one aggregated pin per location (EXIF media at their real coordinate rounded to ~11 m, coordinate-less media at their administrative region's representative point), each opening the paginated media captured there.
- Organization preview without moving files.
- Organization execution that physically moves files.
- Integrity-checked moves: each physical move is verified and its catalog update plus movement record are written atomically per file, so disk and database never diverge silently.
- Self-healing reconciliation that repairs catalog drift after moves (stale `current_path`, renames, missing files) in the background, with no manual trigger. Each reconcile that actually repairs the catalog is recorded as a distinct `RECONCILE` execution (silent no-op checks are not, to avoid flooding the history) and the topbar shows a lightweight "last reconciliation" heartbeat; every execution also records what triggered it (manual, file event or periodic check).
- Scheduled catalog retention purge that permanently removes records whose file has been missing from disk (`MISSING`) longer than a configurable number of days, anchored on when the record became missing. The window is read from Settings (`nimbus-file-manager.catalog.missing-retention-days`); a blank or non-positive value disables it (fail-safe). `DELETED` records are left to the quarantine purge.
- Organization movement log with original path, target path, status and error message.
- Undo for organization executions.
- Execution history, steps, analysis errors and movement records.
- In-memory operation lock to avoid conflicting inventory, organization and reconciliation runs on overlapping paths.
- Local web UI with login, optional TOTP 2FA QR code and application version.
- File explorer screen with breadcrumb navigation, list/grid views, recent-path suggestions and image/video preview.
- Configurable organization folder layouts (date-only, date+category, category-first, ...), described in [Organization Layouts](#organization-layouts).
- Role-based web UI: Files, Organization, Users, Access history and system settings are restricted to `ADMIN` accounts.
- Runtime settings stored in PostgreSQL with creation/update audit fields.
- User access history for login, 2FA and logout events, searchable by e-mail.

## Running

Requirements:

- Java 25
- Maven 3.9+
- PostgreSQL 14+ running locally, with the target database and user already created
- Docker (only for the integration tests, which use Testcontainers - the app itself does not need it)

Create the application role and database while connected as `postgres` or another PostgreSQL administrator:

```sql
CREATE ROLE nimbus_file_manager WITH LOGIN PASSWORD 'nimbus_file_manager';
CREATE DATABASE nimbus_file_manager OWNER nimbus_file_manager;
```

The database must be owned by `nimbus_file_manager`. Merely granting connection access is not enough: Flyway needs permission to create its history table, application tables, sequences and indexes in the `public` schema. (The integration tests no longer need a local test database - Testcontainers provisions a throwaway PostgreSQL per test class.)

If the database already exists with a different owner, fix it with:

```sql
ALTER DATABASE nimbus_file_manager OWNER TO nimbus_file_manager;
```

Check the owner with:

```sql
SELECT
    d.datname,
    pg_get_userbyid(d.datdba) AS owner
FROM pg_database d
WHERE d.datname = 'nimbus_file_manager'
ORDER BY d.datname;
```

Override the connection with:

```text
NIMBUS_FILE_MANAGER_DB_HOST=localhost
NIMBUS_FILE_MANAGER_DB_PORT=5432
NIMBUS_FILE_MANAGER_DB_NAME=nimbus_file_manager
NIMBUS_FILE_MANAGER_DB_USER=nimbus_file_manager
NIMBUS_FILE_MANAGER_DB_PASSWORD=nimbus_file_manager
```

Run:

```bash
./mvnw spring-boot:run
```

Application:

```text
http://localhost:8088
```

On Windows the change source calls `kernel32` through the Foreign Function & Memory API. The
packaged executable jar (`java -jar`) already declares `Enable-Native-Access: ALL-UNNAMED` in its
manifest, so it runs without the Java 25 restricted-native-access warning. When running **outside**
that jar - from the IDE, `./mvnw spring-boot:run`, or a manual classpath - the manifest does not
apply, so pass the flag explicitly to silence the same warning:

```bash
java --enable-native-access=ALL-UNNAMED -cp ... br.com.jorgemelo.nimbusfilemanager.NimbusFileManagerApplication
```

## PostgreSQL Database Administration

The commands in this section are destructive. Run them as `postgres` or another PostgreSQL administrator, and make sure the Nimbus File Manager application, Maven tests and database clients are disconnected first.

### Integration tests (Testcontainers)

The six `@SpringBootTest` integration tests start their own throwaway PostgreSQL container
via Testcontainers (`@ServiceConnection`), so **no manual test database is required** - only
a running Docker engine. Each test class gets an isolated container, so they run in parallel
and need no shared test DB or `NIMBUS_FILE_MANAGER_TEST_DB_*` variables.

Run only one PostgreSQL integration-test class:

```powershell
./mvnw "-Dtest=InventoryOrganizationReinventoryTest" test
```

Run one method from that class:

```powershell
./mvnw "-Dtest=InventoryOrganizationReinventoryTest#inventoryShouldUpdateExistingFileAfterOrganizationWithoutLazyInitialization" test -e
```

### Verify permissions

Connected to each database, verify that the application role can use and create objects in the `public` schema:

```sql
SELECT
    current_database() AS database_name,
    has_database_privilege('nimbus_file_manager', current_database(), 'CONNECT') AS can_connect,
    has_schema_privilege('nimbus_file_manager', 'public', 'USAGE') AS can_use_schema,
    has_schema_privilege('nimbus_file_manager', 'public', 'CREATE') AS can_create_in_schema;
```

Expected result for both databases:

```text
can_connect = true
can_use_schema = true
can_create_in_schema = true
```

When `public` is owned by `pg_database_owner`, making `nimbus_file_manager` the database owner is normally sufficient. If the schema has been customized and creation is still denied, connect to the affected database and run:

```sql
GRANT USAGE, CREATE ON SCHEMA public TO nimbus_file_manager;
```

### Drop application indexes only

Normally there is no need to drop indexes manually: dropping a database removes all of its tables, indexes, sequences, constraints and Flyway history automatically.

For diagnostics or a controlled index rebuild, the following block drops every ordinary, non-constraint index from the `public` schema of the database to which you are currently connected. Primary-key and unique-constraint indexes are preserved because PostgreSQL manages them through constraints.

```sql
DO $$
DECLARE
    index_record RECORD;
BEGIN
    FOR index_record IN
        SELECT
            schemaname,
            indexname
        FROM pg_indexes
        WHERE schemaname = 'public'
          AND indexname NOT IN (
              SELECT conindid::regclass::text
              FROM pg_constraint
              WHERE conindid <> 0
          )
    LOOP
        EXECUTE format(
            'DROP INDEX IF EXISTS %I.%I',
            index_record.schemaname,
            index_record.indexname
        );
    END LOOP;
END
$$;
```

Run it while connected to `nimbus_file_manager`. Flyway will not recreate indexes from an already-applied migration automatically; after dropping them, recreate the database or restore the indexes explicitly from the migration SQL.

List the current indexes before removing anything:

```sql
SELECT
    schemaname,
    tablename,
    indexname,
    indexdef
FROM pg_indexes
WHERE schemaname = 'public'
ORDER BY tablename, indexname;
```

### Drop the database and the role

PostgreSQL cannot drop a database while sessions are connected to it. Connect to another database, normally `postgres`, then terminate active sessions and remove the Nimbus File Manager database:

```sql
SELECT pg_terminate_backend(pid)
FROM pg_stat_activity
WHERE datname = 'nimbus_file_manager'
  AND pid <> pg_backend_pid();

DROP DATABASE IF EXISTS nimbus_file_manager;
```

(If you still have the legacy `nimbus_file_manager_test` database from before the Testcontainers
migration, it is no longer used and can be dropped too: `DROP DATABASE IF EXISTS nimbus_file_manager_test;`.)

After both databases are gone, remove the shared application role:

```sql
DROP ROLE IF EXISTS nimbus_file_manager;
```

If PostgreSQL reports that the role still owns objects or privileges elsewhere, inspect them before removing anything:

```sql
SELECT
    datname,
    pg_get_userbyid(datdba) AS owner
FROM pg_database
WHERE datdba = (SELECT oid FROM pg_roles WHERE rolname = 'nimbus_file_manager');
```

For a complete cleanup of objects owned by the role inside another database, connect to that database and use the following only after reviewing the impact:

```sql
REASSIGN OWNED BY nimbus_file_manager TO postgres;
DROP OWNED BY nimbus_file_manager;
```

Then retry:

```sql
DROP ROLE IF EXISTS nimbus_file_manager;
```

### Recreate a clean local environment

After dropping the databases and role, recreate everything with:

```sql
CREATE ROLE nimbus_file_manager WITH LOGIN PASSWORD 'nimbus_file_manager';
CREATE DATABASE nimbus_file_manager OWNER nimbus_file_manager;
```

Start the application or run the test suite so Flyway creates the schema (the tests bring up
their own PostgreSQL via Testcontainers, so only the main database is created here):

```powershell
./mvnw clean test
./mvnw spring-boot:run
```

## Offline GPS location

Nimbus File Manager can resolve GPS coordinates from photos and videos into country, state/province and city without calling an online map service during processing. Resolved locations are global media metadata reused by Files, Timeline and Organization.

The feature is disabled by default. An administrator can configure it in **Configurações → Localização offline** and manage the geographic database in **Administração da base geográfica**:

1. Download/import the database.
2. Enable `nimbus-file-manager.location.enabled`.
3. Use **Reconstruir localizações** for media that was inventoried before the feature was enabled.

Resolution works by administrative containment (point-in-polygon): the application downloads the [geoBoundaries](https://www.geoboundaries.org/) CGAZ global GeoJSON files (ADM0 countries, ADM1 states/provinces, ADM2 municipalities) into `workspace/geodata` and imports them into PostgreSQL. CGAZ dissolves dependent territories into their sovereign state (e.g. Aruba becomes anonymous Netherlands area), so after the main import the application automatically detects every ISO country left without a polygon of its own, fetches each one individually through the geoBoundaries gbOpen API and imports it additively — the smaller territory polygon then wins resolution over the sovereign's. No hardcoded territory list; the download URLs, the API URL and the auto-completion toggle are runtime settings (Settings screen, "Localização offline" section). For development, tests or fully air-gapped installs, `nimbus-file-manager.location.boundary.local-dir` (or `NIMBUS_FILE_MANAGER_BOUNDARY_LOCAL_DIR`) points at a local folder with the GeoJSON files instead of downloading. Downloads and extracted files are runtime data and are not versioned. Updates are conditional: the ETag of each downloaded file is remembered, so "Atualizar base" reuses files that did not change on the server (the import itself always runs). Updating the database invalidates the resolution cache; existing automatic locations can then be rebuilt for pending, low-confidence or all media.

Organization can optionally subdivide the selected layout by country, country/state or country/state/city, with a minimum-confidence rule and an optional `SEM_LOCALIZACAO_CONFIAVEL` fallback folder. Manual locations are represented in the model and take precedence over automatic results; editing them through the UI is reserved for a future version.

Confidence reflects the finest administrative level that actually contains the coordinate: containment in a municipality is unambiguous (very high), a state-only match is partial (medium) and a country-only match is weak (low). Coordinates outside every polygon (photos taken at sea near the coast, over water in flight, coastal GPS noise) fall back to the nearest municipality within 10 km, stored with low confidence and the measured distance. The interface shows the resolved location and this confidence level.

## Media map

The **Mapa** screen (available to any operational user) plots geo-referenced media on an interactive [Leaflet](https://leafletjs.com/) map. Pins are always aggregated - the API never returns one row per media:

- Media with EXIF coordinates always plot at their real point, grouped by latitude/longitude rounded to 4 decimals (~11 m); the original coordinates are preserved untouched in the database.
- Media without coordinates but with a resolved administrative region fall back to a representative point of that region (interior point → centroid → bounding-box centre), computed from the offline boundary polygons. EXIF media never contribute to an administrative pin.
- Each pin is visually differentiated (real GPS vs approximate) and reports the location label plus total media, photos and videos. Media with no coordinates and no resolved region never appear.
- The view adapts to density: when at most 50 pins are visible the markers become representative thumbnails (the group's most recent media, reusing the warm 320px thumbnail cache); when the view is crowded they collapse back to lightweight count pins (canvas-rendered). Recomputed on pan/zoom.
- Clicking a single-media pin opens it straight in the shared media lightbox (`NimbusFileManagerLightbox`, the same viewer used by Files, Duplicates and Timeline). A pin with several media opens a small thumbnail grid in a popup; each cell opens in that same lightbox (with its prev/next navigation). Large locations expose a "View all" that opens a paginated side panel (default 50 per page).
- Pins load by viewport: `GET /api/map/pins` takes the visible bounding box (`minLat`/`minLon`/`maxLat`/`maxLon`) and a `limit` (default 2000, capped at 5000), so the payload and marker count stay proportional to what is on screen rather than the whole library. The box is padded, so small pans reuse the loaded set; the map only refetches when the view leaves the loaded area or the zoom changes (debounced, with in-flight requests aborted). EXIF pins are bounding-box filtered and capped in PostgreSQL (indexed via `ix_media_metadata_lat_lon`); the client shows a "zoom in" hint when the cap is hit. Calling `/api/map/pins` with no bounding box still returns every pin.

The map background is fully configurable at runtime (Settings, stored as `AppSetting`s), so an administrator can point it at a self-hosted tile server or disable the screen without a redeploy: `nimbus-file-manager.map.enabled` (default `true`), `nimbus-file-manager.map.tile-url` (default the OpenStreetMap tile template), `nimbus-file-manager.map.tile-attribution` and `nimbus-file-manager.map.max-zoom` (default `19`).

Geographic data is provided by [geoBoundaries](https://www.geoboundaries.org/) (CGAZ) under the [Creative Commons Attribution 4.0 License](https://creativecommons.org/licenses/by/4.0/).

Swagger UI:

```text
http://localhost:8088/swagger-ui.html
```

OpenAPI JSON:

```text
http://localhost:8088/v3/api-docs
```

Health check:

```text
http://localhost:8088/actuator/health
```

Default local login, when no user exists yet:

```text
email: admin@nimbus-file-manager.local
password: admin
```

The built-in `admin` password is bootstrap-only. Accounts created with it, including existing
bootstrap accounts detected on upgrade, are forced to open the Account screen and choose a
different password before accessing any other application page or API endpoint.

Override the bootstrap user with:

```text
NIMBUS_FILE_MANAGER_ADMIN_USERNAME=admin@nimbus-file-manager.local
NIMBUS_FILE_MANAGER_ADMIN_PASSWORD=change-me
```

The first administrator is created only when the `app_user` table is empty. Changing `NIMBUS_FILE_MANAGER_ADMIN_USERNAME` or `NIMBUS_FILE_MANAGER_ADMIN_PASSWORD` after a user already exists does not update or reset that existing account. After the first login, use the Account screen to change the password and the Users screen to create additional users.

Google login is enabled by default. The application starts without Google credentials, but the Google button remains unavailable until OAuth2 credentials are configured:

Google OAuth2 does not bypass application 2FA: when the matching local account has TOTP enabled,
Google authentication is followed by the same `/login/2fa` challenge used after password login.

```text
NIMBUS_FILE_MANAGER_GOOGLE_LOGIN_ENABLED=true
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID=your-client-id
SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET=your-client-secret
```

Disable Google login with:

```text
NIMBUS_FILE_MANAGER_GOOGLE_LOGIN_ENABLED=false
```

## Running with Docker

An alternative to installing Java/Maven/Postgres/FFmpeg/ExifTool manually: `Dockerfile` builds the
app (multi-stage: Maven+JDK to build, a slim JRE image with `ffmpeg` and `libimage-exiftool-perl`
installed from apt to run), and `docker-compose.yml` wires it up with a Postgres container.

```bash
cp .env.example .env
# edit .env: at minimum set NIMBUS_FILE_MANAGER_ADMIN_PASSWORD and NIMBUS_FILE_MANAGER_LIBRARY_PATH
docker compose up --build
```

Application: `http://localhost:8088` (or `NIMBUS_FILE_MANAGER_PORT` from `.env`).

The folder set as `NIMBUS_FILE_MANAGER_LIBRARY_PATH` in `.env` (your real media library on the host) is
mounted into the container at `/library`, always at that same path regardless of what it's called
on the host. Once the app is running, use `/library/...` - not the host path - when setting the
watch folder (Settings screen) or any `sourcePath`/`targetPath` from the UI or API.

`NIMBUS_FILE_MANAGER_ADMIN_PASSWORD` has no default in `docker-compose.yml` on purpose: compose refuses
to start rather than silently booting with the well-known `admin`/`admin` bootstrap credentials
(see "Security" below).

App data (Postgres data, and the app's own workspace folder - database migration lock files,
logs, exports, temp, backup) is kept in named Docker volumes, so it survives `docker compose down`
(but not `docker compose down -v`).

## Email Sending

Self-registration (`/register`) creates the account disabled until the confirmation link is used. With no email provider configured (the default), that link is only logged to the console - fine for local development, but real users need an actual email sent to them. Gmail SMTP is supported out of the box:

### Confirmation Flow

- Registering creates the account with `enabled=false`, a random confirmation token and a 24-hour expiry (`CONFIRMATION_TOKEN_VALID_HOURS` in `AppUserAccountService`).
- Opening the confirmation link (`/confirm?token=...`) sets `enabled=true` and clears the token, so the account can log in normally from then on.
- Trying to log in before confirming shows a dedicated message ("Sua conta ainda não foi confirmada...") instead of the generic "E-mail ou senha inválidos" - `LoginFailureHandler` tells the two apart because Spring Security rejects a disabled account (`DisabledException`) before it ever compares the password.
- If the link expires (24h) before it's used, opening it shows "Token de confirmação expirado". There's no separate "resend" page - registering again with the same email while the account is still unconfirmed just issues a fresh token/link (and updates the password/name, in case those changed too) instead of failing with "E-mail já cadastrado.". That error is reserved for emails that already belong to a confirmed account.

### Gmail SMTP Setup

1. Turn on 2-Step Verification on the Google account that will send the emails: Google Account > Security > 2-Step Verification. This is required - app passwords aren't available without it.
2. Generate an app password at https://myaccount.google.com/apppasswords (name it anything, e.g. "Nimbus File Manager"). Google shows a 16-character password - it works with or without the spaces it's displayed with, so it's simplest to just remove them.
3. Set these environment variables:

```text
NIMBUS_FILE_MANAGER_EMAIL_GMAIL_ENABLED=true
NIMBUS_FILE_MANAGER_SMTP_USERNAME=your-gmail-address@gmail.com
NIMBUS_FILE_MANAGER_SMTP_PASSWORD=the-16-character-app-password
```

In Eclipse: Run Configurations > your launch configuration > Environment tab > Add, one variable at a time (the Value field accepts spaces as-is, no quoting needed - it isn't a shell command line). Never commit real values into `application.properties` or anywhere else in the repository.

Emails are sent from that Gmail address directly (not a dedicated "no-reply" address) - fine for personal/self-hosted use, not meant as a high-volume transactional email service.

Without any provider configured, registration still works end-to-end for local testing: grab the confirmation link from the application log (`Confirmation link for <email>: <url>`) and open it manually.

**Security note:** that log line includes the raw confirmation token, which grants access to enable the account. This is fine for a local/dev log file, but if you run without an email provider on an installation whose logs are exposed (shared hosting, centralized log aggregation, etc.), anyone who can read the log can confirm arbitrary registrations. Configure an email provider before exposing the application, or otherwise keep log access restricted to trusted operators.

Additional providers (SendGrid, Amazon SES, Mailgun, Postmark, ...) can be added later by implementing `EmailProvider` in `br.com.jorgemelo.nimbusfilemanager.notification`, each with its own property namespace and, if needed, its own `JavaMailSender` (or HTTP client, for API-based providers) - `EmailService` automatically sends through the first configured provider it finds (priority controlled by `@Order`), so no other code needs to change.

## Web Interface

The Thymeleaf UI is available at:

```text
http://localhost:8088/app
```

Screens currently available:

- Dashboard
- File Explorer *(administrators only; breadcrumb navigation, list/grid views, image and video preview)*
- Onboarding (shown automatically on first run, to pick the folder to monitor)
- Organization *(administrators only; preview and execute, with the folder-layout picker described below)*
- Timeline (media browsing grouped by date, with cursor-based pagination)
- Duplicates *(shown to administrators; SHA-256 duplicate groups)*
- Executions (history, live progress, list auto-refreshes while something is running)
- Account (password, optional TOTP 2FA)
- Users *(administrators only)*
- Access history *(administrators only)*
- Settings *(administrators only)*

Files, Organization, Users, Access history and system settings are restricted to accounts with the `ADMIN` role: the sidebar only shows them to administrators, and the underlying routes reject non-admin access. The OpenAPI/Swagger shortcut lives in that same admin-only area of the sidebar rather than the main navigation.

Inventory runs continuously in the background once a folder is set up through Onboarding; it has no dedicated screen or REST endpoint of its own. Reconciliation has no web screen or REST endpoint either, but it isn't just internal dead code: `InventoryWatchService` calls `OrganizationReconcileService.reconcileAndApply` automatically - once per debounced batch of file-system changes, and again on a fixed 60-second timer regardless of changes - so drift between disk and database (missing files, renames, path mismatches) self-heals in the background without any manual trigger. Although neither has a screen of its own, both are visible in the execution history: a reconcile is persisted as a distinct `RECONCILE` execution only when it actually repairs the catalog (renames, stale-path fixes or missing marks), while the frequent "nothing changed" checks leave only an in-memory heartbeat in the topbar; each execution (inventory and reconcile alike) also records its trigger - `MANUAL`, `FILE_EVENT` or `TIMER`.

The file-system change detection is a pluggable `FileChangeSource` (see `docs/design-watcher-escala-e-reconcile.md` and `docs/adr/0001-windows-change-source.md`). On Windows the real-time source is **`ReadDirectoryChangesW`** with `bWatchSubtree=true`: a single directory handle on the root, recursive detection, no per-folder lock and **no elevation required**. When the volume can be opened (elevated) the NTFS **USN Change Journal** is added on top purely for startup catch-up of changes made while the app was down. Only if even the single-handle recursive watch cannot be opened does it fall back to the portable per-directory `WatchService`; on Linux that `WatchService` remains the source. Either way the periodic reconcile stays the consistency net.

The Settings screen has two tabs: "Sistema" (admin-only) persists runtime parameters in PostgreSQL, and "Preferencias" (any authenticated user) stores personal defaults - default Arquivos view/page size and default Organizacao layout/checkboxes/page size - reusing the same `UserPagePreferenceService` that Arquivos already relies on to remember your last-used folder, view and sort.

Each system parameter stores:

- key
- value
- type
- description
- created by
- created at
- updated by
- updated at

The following settings remain in `application.properties` because they are needed before the database is available:

- `server.port`
- datasource configuration
- workspace/bootstrap folders
- default bootstrap e-mail/password

## Security and Access Architecture

Nimbus File Manager is a personal media collection manager with a single shared collection per installation.

The system does not implement multiple collections, multi-tenancy, organizations, or data isolation between users.

Users exist exclusively for authentication, auditing, and access control to the application's features.

There are only two roles:

- USER
- ADMIN

The USER role has access to all of the system's operational features.

The ADMIN role inherits all permissions of the USER role and additionally has access to administrative features, such as configuration, maintenance, user management, and technical operations.

All users see and operate on the same collection. The user's role only defines which features can be used, never which media can be accessed.

This model significantly reduces the application's complexity, eliminates unnecessary multi-tenancy concepts, and keeps the architecture aligned with the project's goal: a professional manager for personal media collections.

## Security

- The web UI (`/app/**`) and the REST API (`/api/**`) require a logged-in session. Login supports optional TOTP 2FA and optional Google OAuth2. Idle sessions are logged out automatically after the configured timeout. Only the login/registration pages, static assets, the OpenAPI docs and `/actuator/health` are public.
- Roles form a hierarchy: `ROLE_ADMIN` inherits `ROLE_USER` (a `RoleHierarchy` bean), so operational rules are written as `hasRole("USER")` and administrators satisfy them automatically.
- **Operational features are open to any logged-in user (`USER`):** the file explorer, statistics, timeline, organization (preview, export, execute and undo), quarantine (view, restore and purge), duplicate resolution (browse, select and delete), the read data APIs and the user's own account and preferences (including the shared folder picker). Modifying, moving or deleting media is a normal operation, not an administrative one.
- **Administrative and technical features require `ADMIN`:** user/role management (`/app/users/**`), access auditing (`/app/accesses/**`), global system configuration and maintenance (`/app/settings/**`, except the personal `preferences` tab and the shared folder picker), global technical reprocessing (`POST /api/metadata/rebuild` and the `/app/duplicates/phash/**` fingerprint rebuild) and the non-public actuator endpoints.
- `/actuator/health` is public; other actuator endpoints require `ADMIN`.
- CSRF protection stays at Spring Security's default: enabled for every state-changing request, including `/api/**` mutations (which ride the same session). The only public actuator endpoints are read-only GETs, which CSRF never guards.

## Workspace

By default, the application uses:

```text
workspace/
  database/
  logs/
  exports/
  temp/
  backup/
```

The database itself is a separate PostgreSQL instance (not stored under `workspace/`); see the connection environment variables in the Running section.

The workspace root can be changed with:

```text
NIMBUS_FILE_MANAGER_WORKSPACE=C:/path/to/workspace
```

## Safe Local Example Paths

For local validation, prefer isolated folders under a test workspace:

```text
C:/nimbus-file-manager/workspace/temp
C:/nimbus-file-manager/workspace/organized
```

All organization paths are confined to the configured workspace (`nimbus-file-manager.workspace`) or the
folder monitored by inventory. The validator resolves existing ancestors and symbolic links before
checking containment. It also rejects source and target being the same path and target paths inside
the source path.

Organization execution and undo are restricted to administrators in both the REST API and web UI.
The REST operations require an authenticated admin session and a valid CSRF token; preview remains
available under the general API policy, but it is subject to the same path confinement.

## API Flow

Inventory has no REST endpoint: it is set up once on the Onboarding screen (the folder to watch)
and then runs continuously in the background - there is no `POST /api/inventory` to call. The
REST API picks up from there:

1. Review duplicate summary if needed.
2. Generate organization preview.
3. Resolve conflicts.
4. Execute organization.
5. Inspect execution history and movement results.
6. Undo when needed.

Important behavior:

- `/api/organization/preview` only builds a plan; it does not persist a plan and does not move files.
- `/api/organization/execute` recalculates the plan internally; there is no `previewId`.
- `/api/organization/execute` moves files physically.
- `/api/organization/execute/{executionId}/undo` moves files back using stored movement records.
- There is no `dryRun` flag for organization execution.
- There is no COPY mode; the current behavior is MOVE.

## Endpoints

```text
POST   /api/metadata/rebuild

POST   /api/organization/preview
POST   /api/organization/preview/export
POST   /api/organization/execute
POST   /api/organization/execute/{executionId}/undo

GET    /api/media

GET    /api/duplicates
GET    /api/duplicates/{sha256}/files
GET    /api/duplicates/summary
GET    /api/duplicates/candidates

GET    /api/statistics
GET    /api/statistics/codecs
GET    /api/statistics/folders
GET    /api/statistics/errors
GET    /api/statistics/errors/files
GET    /api/statistics/errors/files/details

GET    /api/executions
GET    /api/executions/{id}
GET    /api/executions/{id}/steps
GET    /api/executions/{id}/errors
GET    /api/executions/{id}/errors/summary
GET    /api/executions/{id}/movements
```

There is no `POST /api/inventory` and no `POST /api/organization/reconcile` endpoint in the current
API - `OrganizationReconcileService` isn't wired to a controller, because it already runs
automatically in the background (see the Web Interface section above); a manual REST trigger isn't
needed for the normal flow.

## Organization Layouts

The `layout` field accepted by organization preview/execute/preview-export:

- `DEFAULT` / `YEAR_MONTH_DAY_SUBCATEGORY_FILE_TYPE` - year-month / day / subcategory / file type, eg. `2026-07/10/Fotos/IMAGE` (most detailed; `DEFAULT` is an alias for this one).
- `YEAR_MONTH_DAY` - year-month / day only, eg. `2026-07/10` (no subcategory/file-type split).
- `YEAR_MONTH_SUBCATEGORY_FILE_TYPE` - year-month / subcategory / file type, eg. `2026-07/Fotos/IMAGE` (no per-day folder).
- `SUBCATEGORY_YEAR_MONTH_DAY` - subcategory / year-month / day, eg. `Fotos/2026-07/10` (groups by category first).

The web UI's Organization screen lists the same label, description and example for each option, both sourced from `OrganizationLayout` on the backend so the page and the API can't drift apart.

## Organization Preview

Request:

```bash
curl -X POST "http://localhost:8088/api/organization/preview" \
  -H "Content-Type: application/json" \
  -d '{
    "sourcePath": "C:/nimbus-file-manager/workspace/temp",
    "targetPath": "C:/nimbus-file-manager/workspace/organized",
    "recursive": true,
    "layout": "DEFAULT",
    "limit": 10000,
    "rebuildMetadata": false,
    "skipAlreadyOrganized": true
  }'
```

Useful optional filters:

```json
{
  "onlyCategories": ["MEDIA"],
  "onlySubcategories": ["CAMERA"],
  "onlyExtensions": ["jpg", "mp4"],
  "onlyFileTypes": ["PHOTO", "VIDEO"]
}
```

Typical response shape:

```json
{
  "sourcePath": "C:\\nimbus-file-manager\\workspace\\temp",
  "targetPath": "C:\\nimbus-file-manager\\workspace\\organized",
  "layout": "DEFAULT",
  "execute": false,
  "summary": {
    "totalFiles": 8,
    "filesWithDate": 8,
    "filesWithoutDate": 0,
    "alreadyOrganized": 0,
    "plannedMoves": 8,
    "conflicts": 4,
    "targetAlreadyExists": 0,
    "duplicateTargets": 4
  },
  "items": [
    {
      "catalogFileId": 1,
      "fileName": "20251230_115630.jpg",
      "sourcePath": "...workspace\\temp\\dup1\\20251230_115630.jpg",
      "targetPath": "...workspace\\organized\\202512\\30\\CAMERA\\IMAGENS\\20251230_115630.jpg",
      "samePath": false,
      "missingDate": false,
      "targetExists": false,
      "duplicateTarget": true,
      "conflict": true,
      "conflictType": "DUPLICATE_TARGET"
    }
  ]
}
```

## Organization Preview Export

Streams a ZIP file containing the JSON organization preview.

```bash
curl -X POST "http://localhost:8088/api/organization/preview/export" \
  -H "Content-Type: application/json" \
  -o organization-preview.zip \
  -d '{
    "sourcePath": "C:/nimbus-file-manager/workspace/temp",
    "targetPath": "C:/nimbus-file-manager/workspace/organized",
    "recursive": true,
    "layout": "DEFAULT",
    "limit": 10000,
    "rebuildMetadata": false,
    "skipAlreadyOrganized": true
  }'
```

## Organization Execute

Request with conflict rejection:

```bash
curl -X POST "http://localhost:8088/api/organization/execute" \
  -H "Content-Type: application/json" \
  -H "Cookie: JSESSIONID=<admin-session-id>" \
  -H "X-CSRF-TOKEN: <csrf-token>" \
  -d '{
    "sourcePath": "C:/nimbus-file-manager/workspace/temp",
    "targetPath": "C:/nimbus-file-manager/workspace/organized",
    "recursive": true,
    "layout": "DEFAULT",
    "limit": 10000,
    "rebuildMetadata": false,
    "skipAlreadyOrganized": true,
    "allowConflicts": false,
    "overwriteExisting": false
  }'
```

If the recalculated plan has conflicts and `allowConflicts` is `false`, the execution is rejected without moving files:

```json
{
  "executionId": 2,
  "status": "REJECTED",
  "plannedMoves": 8,
  "moved": 0,
  "skipped": 8,
  "errors": 0,
  "rejected": true,
  "message": "Organization rejected because the plan contains 4 conflict(s). Run preview and fix conflicts, or execute with allowConflicts=true."
}
```

A conflict rejection is a distinct `REJECTED` status (not a failure): no files are moved and no error is recorded. A real failure mid-run reports `ERROR` with `errors >= 1`.

Request allowing conflicted items to be skipped:

```json
{
  "sourcePath": "C:/nimbus-file-manager/workspace/temp",
  "targetPath": "C:/nimbus-file-manager/workspace/organized",
  "recursive": true,
  "layout": "DEFAULT",
  "limit": 10000,
  "rebuildMetadata": false,
  "skipAlreadyOrganized": true,
  "allowConflicts": true,
  "overwriteExisting": false
}
```

Typical response:

```json
{
  "executionId": 3,
  "status": "FINISHED",
  "plannedMoves": 8,
  "moved": 4,
  "skipped": 4,
  "errors": 0,
  "rejected": false,
  "message": "Organization finished. moved=4, skipped=4, errors=0."
}
```

## Organization Undo

Undo uses movement records from a previous organization execution.

```bash
curl -X POST "http://localhost:8088/api/organization/execute/3/undo" \
  -H "Cookie: JSESSIONID=<admin-session-id>" \
  -H "X-CSRF-TOKEN: <csrf-token>"
```

Behavior:

- Moves files from target path back to original path.
- Updates the database path after the move.
- Skips movements already undone.
- Does not overwrite an existing original file.
- Reports partial results when some files cannot be undone.

## Metadata Rebuild

```bash
curl -X POST "http://localhost:8088/api/metadata/rebuild" \
  -H "Content-Type: application/json" \
  -d '{
    "sourcePath": "C:/nimbus-file-manager/workspace/temp",
    "refresh": ["DATE", "MIME", "GPS", "DIMENSIONS", "CAMERA", "SUBCATEGORY"],
    "captureDateNull": false,
    "dateSource": null,
    "limit": 10000,
    "dryRun": false
  }'
```

If `refresh` is empty or omitted, only `DATE` is rebuilt by default.

## Duplicates

```bash
curl "http://localhost:8088/api/duplicates/summary"
curl "http://localhost:8088/api/duplicates?page=0&size=50"
curl "http://localhost:8088/api/duplicates/{sha256}/files"
curl "http://localhost:8088/api/duplicates/candidates?page=0&size=50"
```

Example summary:

```json
{
  "groups": 2,
  "duplicatedFiles": 4,
  "totalSize": {
    "bytes": 15501998,
    "formatted": "14.78 MB"
  },
  "wastedSize": {
    "bytes": 7750999,
    "formatted": "7.39 MB"
  }
}
```

## Media Search

```bash
curl "http://localhost:8088/api/media?fileType=PHOTO&extension=jpg&year=2025&page=0&size=50"
```

Available query parameters:

```text
fileType
codec
folder
extension
year
month
minSizeBytes
maxSizeBytes
page
size
sort
```

## Executions

```bash
curl "http://localhost:8088/api/executions"
curl "http://localhost:8088/api/executions/{id}"
curl "http://localhost:8088/api/executions/{id}/steps"
curl "http://localhost:8088/api/executions/{id}/errors"
curl "http://localhost:8088/api/executions/{id}/errors/summary"
curl "http://localhost:8088/api/executions/{id}/movements"
```

`/movements` returns the file movement records for an organization execution (source path, target path, status, undo timestamp when available) as a separate call - they are not embedded in the `/api/executions/{id}` response itself.

## Statistics

```bash
curl "http://localhost:8088/api/statistics"
curl "http://localhost:8088/api/statistics/codecs"
curl "http://localhost:8088/api/statistics/folders"
curl "http://localhost:8088/api/statistics/errors"
curl "http://localhost:8088/api/statistics/errors/files"
curl "http://localhost:8088/api/statistics/errors/files/details"
```

## Database Migrations

Flyway applies schema changes at startup. The schema was squashed into a single consolidated baseline (`V1__initial_schema.sql`, on 2026-07-12 for a fresh-database reset); later changes are added as new versions on top of it (currently up to `V2__catalog_file_lifecycle_changed_at.sql`, which adds the retention anchor for the catalog missing-record purge). Example startup log for a new database:

```text
Migrating schema "public" to version "1 - initial schema"
Migrating schema "public" to version "2 - catalog file lifecycle changed at"
Successfully applied 2 migrations to schema "public", now at version v2
```

Check applied migrations with:

```sql
SELECT *
FROM flyway_schema_history
ORDER BY installed_rank DESC;
```

## External Tools

The project expects these executables under `tools/bin`:

```text
tools/bin/ffprobe.exe
tools/bin/ffmpeg.exe
tools/bin/exiftool.exe
```

They can still be overridden by environment variables or by the Settings screen:

```text
NIMBUS_FILE_MANAGER_FFPROBE=C:/nimbus-file-manager/tools/bin/ffprobe.exe
NIMBUS_FILE_MANAGER_FFMPEG=C:/nimbus-file-manager/tools/bin/ffmpeg.exe
NIMBUS_FILE_MANAGER_EXIFTOOL=C:/nimbus-file-manager/tools/bin/exiftool.exe
```

### `tools/bin` is not committed to git

`tools/bin/*.exe` and `tools/bin/*.dll` are gitignored (~130 MB total, over GitHub's 50 MB
per-file warning threshold, and FFmpeg builds with `--enable-gpl` carry GPL obligations that
don't belong inside this repo's history). Set the folder up locally after cloning:

1. **ffmpeg / ffprobe** - download an official "shared" Windows build from
   https://www.gyan.dev/ffmpeg/builds/ (or copy the executables + DLLs from another install that
   already has them, eg. Shutter Encoder's `app/Library` folder). A "shared" build is required -
   a "static" build's single self-contained `ffprobe.exe` will fail to launch with a
   `STATUS_DLL_NOT_FOUND` exit code if the dependency DLLs listed below aren't next to it.
2. **exiftool** - download from https://exiftool.org and rename `exiftool(-k).exe` to
   `exiftool.exe`.
3. Place everything directly under `tools/bin/`:

```text
tools/bin/ffprobe.exe
tools/bin/ffmpeg.exe
tools/bin/exiftool.exe
tools/bin/avcodec-62.dll
tools/bin/avdevice-62.dll
tools/bin/avfilter-11.dll
tools/bin/avformat-62.dll
tools/bin/avutil-60.dll
tools/bin/swresample-6.dll
tools/bin/swscale-9.dll
```

(DLL version numbers depend on the specific ffmpeg build; match whatever ships alongside the
`ffprobe.exe`/`ffmpeg.exe` you downloaded.)

## Organization Safety Notes

- Preview should be reviewed before execute.
- Execute recalculates the plan; do not assume a previous preview is stored.
- Use `allowConflicts=false` to reject plans with conflicts.
- Use `overwriteExisting=false` unless overwriting is intentional.
- Undo never overwrites an existing original file.
- Prefer temporary workspace folders for validation.

## Tests And Quality

Run unit/integration tests with JaCoCo:

```bash
./mvnw clean test
```

Most recent clean local build (PostgreSQL):

```text
Tests:       1457 run, 0 failures, 0 errors, 9 skipped
JaCoCo:      96.03% instruction, 85.81% branch, 95.32% line, 96.35% method, 99.68% class
```

The 9 skipped tests are OS-dependent (symbolic-link / POSIX-permission) cases that
self-abort via JUnit `Assumptions` on platforms where they cannot run (e.g. Windows).

**AssertJ style — Sonar rule `java:S5853` is disabled on purpose.** The project keeps AssertJ
assertions as separate statements (one `assertThat(...)` per line) instead of chaining them onto
a single subject. Separate assertions fail independently — a run reports every failing check with
its own message rather than short-circuiting at the first link of a chain — and the diffs stay
line-oriented and easy to review. `java:S5853` ("Join these multiple assertions subject to one
assertion chain") flags exactly this convention; it is a pure formatting preference with no
correctness value, so it is deactivated in the project's Java quality profile. (Audited across the
whole project: all occurrences were this same style, none a real defect.)

Tests run in parallel (configured in `src/test/resources/junit-platform.properties`):
different test classes execute concurrently while the methods inside one class stay on a
single thread, at ~50% of available cores (dynamic factor `0.5`). Execution is thread-based
(one JVM), so the single JaCoCo agent still aggregates coverage correctly. Each
`@SpringBootTest` class starts its own throwaway PostgreSQL container
(Testcontainers + `@ServiceConnection`), so they are fully isolated and run in parallel with
no shared database - which requires a running Docker engine locally and in CI. The suite was
run 5× back-to-back with byte-identical JaCoCo metrics (no flaky tests, no coverage jitter).

Run PIT mutation testing:

```bash
./mvnw -Ppitest org.pitest:pitest-maven:mutationCoverage
```

The `pitest` profile excludes the Testcontainers / `@SpringBootTest` classes: booting a
Spring context (and a Postgres container) once per mutation is intractable. So production
code covered *only* by integration tests shows up as *no coverage*, and **test strength**
(killed / covered mutations) is the more meaningful figure than the raw mutation score
here. The class-level exclusions mirror the JaCoCo / Sonar coverage exclusions
(`**/domain/model/**`, `**/dto/**`, `**/*Repository`, config, native glue, …).

Most recent local PIT run:

```text
Line coverage for mutated classes: 8088/8658 (93%)
Generated mutations:              4906
Killed mutations:                 3951
Survived mutations:               709
No coverage:                      246
Timed out:                        14
Run error:                        0
Mutation score:                   81%
Test strength:                    85%
Duration:                         10m16s
```

Reports:

```text
target/site/jacoco/index.html
target/pit-reports/index.html
```

## License

This project is licensed under the MIT License.

## Author

Jorge Francisco

GitHub: https://github.com/JorgeFrancisco
