# Nimbus File Manager

![Nimbus File Manager](src/main/resources/static/img/nimbus-file-manager-banner-readme.png)

<img width="1890" height="943" alt="imagem" src="https://github.com/user-attachments/assets/fe31e0b3-02d3-467b-bd31-a2d1fdd59321" />

<img width="1770" height="889" alt="imagem3" src="https://github.com/user-attachments/assets/a41a6242-e60f-4e3e-95ed-131c81f15d42" />

<img width="1882" height="940" alt="imagem2" src="https://github.com/user-attachments/assets/4d9685e2-41a3-4a10-a4a6-6de586555939" />

Local-first file manager built with Java 25, Spring Boot and PostgreSQL for continuously inventorying, monitoring, enriching, organizing and auditing personal files.

Rather than acting as a traditional file explorer, Nimbus File Manager builds an intelligent catalog of your files, extracts metadata, detects duplicates and similar content, tracks filesystem changes in real time, and provides safe organization workflows with full audit history and undo support.

It provides a REST API, OpenAPI documentation and a lightweight Thymeleaf web interface with login, optional 2FA, dashboard, file explorer, organization, execution history and runtime settings/preferences. Inventory runs continuously in the background once configured; it has no dedicated screen or REST endpoint.

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
- FFprobe / FFmpeg (bundled in `tools/ffmpeg/bin` or resolved through `PATH`)
- TwelveMonkeys ImageIO (WebP thumbnail decoding, via the ImageIO SPI)
- Leaflet (interactive media map, via WebJar; OpenStreetMap tiles by default)
- Java Foreign Function &amp; Memory API (Windows-only real-time change source: `ReadDirectoryChangesW` + NTFS USN journal catch-up, via `java.lang.foreign`; inert on other platforms)

## Main Features

- Recursive file inventory with streaming scan.
- Optional SHA-256 and MD5 calculation in a single file read.
- Metadata extraction from filesystem, EXIF, filename patterns and video streams. Filename families cover phone and compact-camera sequences (Sony Cyber-shot, Nikon Coolpix, General Imaging, Pentax, Casio, Samsung Digimax, Panasonic, Olympus and the Kodak/Canon DCIM numbering), GoPro (single shots plus the burst/time-lapse sequence), drones, screenshots, WhatsApp, the month-first `MMddyyHHmmss` stamp of early camera phones and the editors that stamp epoch millis in the name (PhotoGrid, AirBrush, Facebook, FaceApp).
- Metadata rebuild from the settings screen: pick a folder and which fields to reprocess (capture date, MIME, GPS, dimensions, camera, family classification), simulate first if you want, and follow progress with percentage and estimated time while it runs in the background. The same rebuild stays available as a synchronous REST call for scripted use.
- Fully offline GPS reverse geocoding based on administrative boundaries (point-in-polygon), persisted as reusable media metadata.
- Duplicate detection using SHA-256, plus visual similarity for photos and videos (perceptual hashing + SSIM).
- Quarantine for removed media: files soft-deleted by duplicate resolution or left behind by a video conversion are moved into a single quarantine area where they can be restored or permanently purged, with a scheduled purge for long-quarantined items. Restoring, purging and clearing missing records each run as an execution of their own, so the executions screen tells them apart and names any file that could not be handled.
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
- Per-entry menu in the explorer (three-dot button or right click) for properties, rename, download, copy path, open containing folder and delete. Deleting asks whether to quarantine (recoverable, through the same verified move the duplicate screen uses) or erase for good, and erasing a folder states how many inventoried files and how many bytes go with it before the irreversible step. Every destructive action is confined to the monitored library.
- Configurable organization folder layouts (date-only, date+category, category-first, ...), described in [Organization Layouts](#organization-layouts).
- Batch video conversion to H.265/HEVC inside MP4 with FFmpeg, described in [Video Conversion](#video-conversion): two quality profiles, three audio options and a choice of keeping or quarantining the original, carrying over audio, chapters, metadata and every subtitle track MP4 can hold.
- Role-based web UI: the operational screens (Files, Organization, Duplicates, Quarantine, Conversion, Statistics) and their data/export APIs, plus Users, Access history and system settings, are restricted to `ADMIN` accounts; Dashboard, Timeline and Map stay open to any authenticated user.
- Runtime settings stored in PostgreSQL with creation/update audit fields.
- User access history for login, 2FA and logout events, searchable by e-mail.

## Running

Requirements:

- Java 25
- Maven 3.9+
- PostgreSQL 14+ running locally, with the target database and user already created
- Docker (only for the integration tests, which use Testcontainers - the app itself does not need it)
- FFmpeg and FFprobe, for video conversion, video thumbnails and perceptual hashing. Nothing to do
  on Windows, where the application installs them on the first start that finds them missing; on
  Linux and macOS install them with the package manager. See [External Tools](#external-tools).

It runs on Windows, Linux and macOS. Two things differ by platform, and both degrade to a working
default rather than failing: the real-time file-system watcher uses Windows APIs and falls back to
the portable `WatchService` elsewhere, and the external tools are installed by the application on
Windows and by the package manager elsewhere.

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

The feature is disabled by default and turned on from the geographic-database section of the settings
screen. Enabling it is the only step: the dataset is downloaded in the background when it is missing,
and checked for updates once a day at a configurable time (04:00 by default). A machine that is off at
that time runs the check at the first opportunity of the day, because an alarm firing into a sleeping
computer never runs at all. Both the daily check and its time can be changed on the same screen, and
the update can still be triggered by hand at any moment.

Media inventoried before the feature was enabled keeps its coordinates unresolved until a location
rebuild is run, which is a separate action on that same screen.

Disabling asks what to do with the roughly 2 GB the dataset occupies: keeping the files makes enabling
it again nearly instant, deleting them reclaims the space. Nothing is removed without that answer.

An update never leaves the installation worse than it found it. Downloaded files are staged and only
replace the ones resolution reads after the import succeeds, and the import itself runs in a
transaction that starts by clearing the previous rows — so a failure at any point leaves the previous
dataset intact on disk and in the database, still answering queries. A successful update replaces the
old files in place, leaving no earlier copy behind.

Resolution works by administrative containment (point-in-polygon): the application downloads the [geoBoundaries](https://www.geoboundaries.org/) CGAZ global GeoJSON files (ADM0 countries, ADM1 states/provinces, ADM2 municipalities) into `workspace/geodata` and imports them into PostgreSQL. CGAZ dissolves dependent territories into their sovereign state (e.g. Aruba becomes anonymous Netherlands area), so after the main import the application automatically detects every ISO country left without a polygon of its own, fetches each one individually through the geoBoundaries gbOpen API and imports it additively — the smaller territory polygon then wins resolution over the sovereign's. No hardcoded territory list; the download URLs, the API URL and the auto-completion toggle are runtime settings, in the offline-location section of the settings screen. For development, tests or fully air-gapped installs, `nimbus-file-manager.location.boundary.local-dir` (or `NIMBUS_FILE_MANAGER_BOUNDARY_LOCAL_DIR`) points at a local folder with the GeoJSON files instead of downloading. Downloads and extracted files are runtime data and are not versioned. Updates are conditional: the ETag of each downloaded file is remembered, so updating the dataset reuses files that did not change on the server (the import itself always runs). Updating the database invalidates the resolution cache; existing automatic locations can then be rebuilt for pending, low-confidence or all media.

Organization can optionally subdivide the selected layout by country, country/state or country/state/city, with a minimum-confidence rule and an optional `SEM_LOCALIZACAO_CONFIAVEL` fallback folder. Manual locations are represented in the model and take precedence over automatic results; editing them through the UI is reserved for a future version.

Confidence reflects the finest administrative level that actually contains the coordinate: containment in a municipality is unambiguous (very high), a state-only match is partial (medium) and a country-only match is weak (low). Coordinates outside every polygon (photos taken at sea near the coast, over water in flight, coastal GPS noise) fall back to the nearest boundary within 12 nautical miles (22.2 km), stored with low confidence and the measured distance. That figure is the breadth of the territorial sea under UNCLOS - in Brazil, Lei 8.617/1993 - so the water inside it is national territory and attributing it to the coast it belongs to is defensible. The tolerance is needed because the administrative polygons stop at the shoreline: no state or municipality has a maritime limit of its own (the sea belongs to the federal union), and the only projection of state limits onto the sea is the one used to share oil royalties, which is not jurisdiction. A coordinate farther out than the tolerance resolves as **open sea**: no place names, lowest confidence, and a flag of its own - so it shows as "Alto-mar" on the map, timeline and lightbox without ever being mistaken for a country in the statistics or the organization folders. The interface shows the resolved location and this confidence level.

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

First login, when no user exists yet: the very first start creates the administrator and **generates
a password for this installation**, shown once in the startup log and written to
`<workspace>/first-access.txt`:

```text
=====================================================================
 Nimbus File Manager - first access
 user:     admin@nimbus-file-manager.local
 password: <generated for this installation>
 This password is shown once and must be changed at first login.
=====================================================================
```

No password ships with the source. One that did would be identical on every installation and
published with the repository, so anyone reaching the port could sign in before the owner - and,
because changing it is mandatory, keep the account. Delete `first-access.txt` once the password has
been changed.

The generated password is bootstrap-only. Accounts created with it, and accounts still using a
configured `NIMBUS_FILE_MANAGER_ADMIN_PASSWORD` detected on upgrade, are forced to open the Account
screen and choose a different password before accessing any other application page or API endpoint.

Provision the bootstrap user non-interactively - for a container or a CI environment, where the
password has to be known in advance - with:

```text
NIMBUS_FILE_MANAGER_ADMIN_USERNAME=admin@nimbus-file-manager.local
NIMBUS_FILE_MANAGER_ADMIN_PASSWORD=change-me
```

A configured password wins over the generated one, and nothing is written to `first-access.txt`.

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

An alternative to installing Java/Maven/Postgres/FFmpeg manually: `Dockerfile` builds the app
(multi-stage: Maven+JDK to build, a slim JRE image with `ffmpeg` installed from apt to run), and
`docker-compose.yml` wires it up with a Postgres container.

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
- Trying to log in before confirming shows a dedicated message saying the account is still unconfirmed, rather than the generic invalid-credentials one - `LoginFailureHandler` tells the two apart because Spring Security rejects a disabled account (`DisabledException`) before it ever compares the password.
- If the link expires (24h) before it's used, opening it reports an expired token. There's no separate "resend" page - registering again with the same email while the account is still unconfirmed just issues a fresh token/link (and updates the password/name, in case those changed too) instead of failing with the email-already-registered error. That error is reserved for emails that already belong to a confirmed account.

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
- Map (geo-referenced media on an interactive map; see [Media map](#media-map))
- Duplicates *(administrators only; byte-identical SHA-256 groups plus visually similar photos and videos)*
- Quarantine *(administrators only; soft-deleted media, with restore and permanent purge)*
- Conversion *(administrators only; batch conversion of the videos that are not H.265 MP4 yet)*
- Statistics *(administrators only; library totals, codecs, extensions and error breakdowns)*
- Executions (history, live progress, list auto-refreshes while something is running)
- Account (password, optional TOTP 2FA)
- Users *(administrators only)*
- Access history *(administrators only)*
- Settings *(administrators only)*

Files, Organization, Duplicates, Quarantine, Conversion, Statistics, Users, Access history and system settings are restricted to accounts with the `ADMIN` role: the sidebar only shows them to administrators, and the underlying routes (screens and their data/export APIs) reject non-admin access. Dashboard, Timeline, Map and the personal preferences tab stay open to any authenticated user. The OpenAPI/Swagger shortcut lives in that same admin-only area of the sidebar rather than the main navigation.

Inventory runs continuously in the background once a folder is set up through Onboarding; it has no dedicated screen or REST endpoint of its own. Reconciliation has no web screen or REST endpoint either, but it isn't just internal dead code: `InventoryWatchService` calls `OrganizationReconcileService.reconcileAndApply` automatically - once per debounced batch of file-system changes, and again on a fixed 60-second timer regardless of changes - so drift between disk and database (missing files, renames, path mismatches) self-heals in the background without any manual trigger. Although neither has a screen of its own, both are visible in the execution history: a reconcile is persisted as a distinct `RECONCILE` execution only when it actually repairs the catalog (renames, stale-path fixes or missing marks), while the frequent "nothing changed" checks leave only an in-memory heartbeat in the topbar; each execution (inventory and reconcile alike) also records its trigger - `MANUAL`, `FILE_EVENT` or `TIMER`.

The file-system change detection is a pluggable `FileChangeSource`. On Windows the real-time source is **`ReadDirectoryChangesW`** with `bWatchSubtree=true`: a single directory handle on the root, recursive detection, no per-folder lock and **no elevation required**. When the volume can be opened (elevated) the NTFS **USN Change Journal** is added on top purely for startup catch-up of changes made while the app was down. Only if even the single-handle recursive watch cannot be opened does it fall back to the portable per-directory `WatchService`; on Linux that `WatchService` remains the source. Either way the periodic reconcile stays the consistency net.

The settings screen has two tabs. The system tab (admin-only) persists runtime parameters in PostgreSQL; the preferences tab (any authenticated user) stores personal defaults - the file-explorer view and page size, and the organization layout, checkboxes and page size - reusing the same `UserPagePreferenceService` the file explorer already relies on to remember your last-used folder, view and sort.

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

The USER role can browse the shared collection (dashboard, timeline and map) and manage its own account and preferences.

The ADMIN role inherits all permissions of the USER role and additionally has access to the operational and administrative features, such as the file explorer, organization, duplicate resolution, quarantine and statistics, plus configuration, maintenance, user management and technical operations.

All users see and operate on the same collection. The user's role only defines which features can be used, never which media can be accessed.

This model significantly reduces the application's complexity, eliminates unnecessary multi-tenancy concepts, and keeps the architecture aligned with the project's goal: a professional manager for personal media collections.

## Security

- The web UI (`/app/**`) and the REST API (`/api/**`) require a logged-in session. Login supports optional TOTP 2FA and optional Google OAuth2. Idle sessions are logged out automatically after the configured timeout. Only the login/registration pages, static assets, the OpenAPI docs and `/actuator/health` are public.
- Roles form a hierarchy: `ROLE_ADMIN` inherits `ROLE_USER` (a `RoleHierarchy` bean), so operational rules are written as `hasRole("USER")` and administrators satisfy them automatically.
- **Features open to any logged-in user (`USER`):** the dashboard, timeline, map, execution history, the shared media/map/timeline read APIs (which also feed the timeline/map lightbox), and the user's own account and preferences (including the shared folder picker).
- **Administrative features require `ADMIN`:** the file explorer, organization (preview, export, execute, undo), duplicate resolution, quarantine (view, restore, purge) and statistics screens, together with their data/export APIs (`/api/organization/**`, `/api/duplicates/**`, `/api/statistics/**`, `/api/catalog/**`); user/role management (`/app/users/**`), access auditing (`/app/accesses/**`), global system configuration and maintenance (`/app/settings/**`, except the personal `preferences` tab and the shared folder picker), global technical reprocessing (`POST /api/metadata/rebuild` and the `/app/duplicates/phash/**` fingerprint rebuild) and the non-public actuator endpoints.
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
GET    /api/media/{publicId}
GET    /api/media/{publicId}/content

GET    /api/duplicates
GET    /api/duplicates/{sha256}/files
GET    /api/duplicates/summary
GET    /api/duplicates/candidates
GET    /api/duplicates/similar-photos
GET    /api/duplicates/similar-photos/failures
GET    /api/duplicates/similar-videos
GET    /api/duplicates/similar-videos/failures

GET    /api/timeline/index
GET    /api/timeline/items
GET    /api/timeline/undated

GET    /api/map/pins
GET    /api/map/items

GET    /api/statistics
GET    /api/statistics/codecs
GET    /api/statistics/extensions
GET    /api/statistics/folders
GET    /api/statistics/errors
GET    /api/statistics/errors/files
GET    /api/statistics/errors/files/details

GET    /api/catalog/export

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
- Runs as an execution of its own (type `UNDO`), so it appears on the executions
  screen with its own counters, and a file that could not be put back is reported
  against the undo rather than against the organization it reverses.
- Appends history instead of rewriting it: the movement being reversed keeps the
  reason it was moved and is only marked `UNDONE`, while the reversal is stored as
  a movement in the opposite direction. A file organized and undone three times
  leaves six rows in order.

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

If `refresh` is empty or omitted, only `DATE` is rebuilt by default. The REST call is synchronous and answers when the pass ends, so it fits scripts and small folders; for a library-wide pass use the **metadata rebuild panel on the settings screen**, which runs it in the background and reports progress, percentage and estimated time. Either way the file has to be reachable on disk - the rebuild re-reads it instead of recomputing from the stored name - and `limit` caps how many files one pass touches (default 10,000, ceiling 250,000). A folder with more files than the ceiling takes more than one run: the panel states the ceiling on screen and offers **Continue where it stopped**, which skips whatever the previous run already rebuilt (by `last_analysis`) instead of starting over, or **Force all** to redo everything.

## Duplicates

Three tabs: byte-identical duplicates (SHA-256), visually **similar photos** (256-bit DCT pHash confirmed by SSIM) and visually **similar videos**. Video similarity samples several frames at deterministic relative positions in a single ffmpeg pass, hashes each with the same pHash as photos, and matches videos frame-for-frame with a trimmed-mean aggregation plus a concordant-frame quorum — robust to re-encoding, bitrate, resolution, small duration differences and compression. Both similarity kinds are derived off-inventory by a shared background fingerprint backlog, and new algorithms plug in via the `VideoSimilarityAlgorithm` contract without touching the orchestrator.

```bash
curl "http://localhost:8088/api/duplicates/summary"
curl "http://localhost:8088/api/duplicates?page=0&size=50"
curl "http://localhost:8088/api/duplicates/{sha256}/files"
curl "http://localhost:8088/api/duplicates/candidates?page=0&size=50"
curl "http://localhost:8088/api/duplicates/similar-photos?minSimilarity=70&page=0&size=20"
curl "http://localhost:8088/api/duplicates/similar-videos?minSimilarity=70&page=0&size=20"
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

## Video Conversion

The Conversion screen (administrators only) standardises the catalog on **MP4 with
H.265/HEVC video**, using the bundled FFmpeg, in the background, one file at a time.
It is deliberately not an FFmpeg front-end: the screen offers three choices and hides
every encoder knob behind them.

| Choice | Options |
| --- | --- |
| Quality | **High quality** or **Balanced** *(recommended)* - CRF and preset are internal and never shown |
| Audio | **Keep the original**, **Always convert to AAC**, or **Convert to AAC only when needed** *(recommended)* |
| After the conversion | **Keep the original file** or **Move the original to quarantine** |
| Converted file name | free text plus where it goes - **at the end** *(default, `_H265`)* or **at the start**; blank keeps the source name |

The three options are stored per user the moment they change, so reopening the screen
offers what was last used instead of silently resetting - which matters because one of
them decides whether the original file stays. The file selection is kept in the
browser and survives pagination and reloads, so a batch can be assembled across pages;
Clearing the selection empties it, and whatever a batch handled leaves it automatically.

Each row uses the same media card as the other screens: a thumbnail that opens the
video in the shared lightbox player.

The listing shows only videos that are still on disk and are **not an H.265 MP4
yet**, biggest first, so the files with the most to gain come first. That includes a
video which is already H.265 but sits in another container: it only needs the MP4
remux, which takes seconds, copies the video stream untouched and loses no quality
(the quality profile does not apply to it). A video whose codec was never extracted is
kept in the list and decided by ffprobe at conversion time.

### What one conversion does

1. Encodes **in the source folder**, into `<final name>_temp.tmp`. Encoding next to the
   source makes the last step a rename instead of a cross-volume copy of a finished
   multi-gigabyte file, and the `.tmp` extension is one the inventory skips by default,
   so a half-written file is never cataloged, fingerprinted or shown.
2. Carries over everything MP4 can hold: video, audio, subtitles (converted to MP4's
   own `mov_text`), data streams (timecode, GoPro telemetry), metadata and chapters.
   Only the video is re-encoded - and not even that when the source is already H.265.
3. Maps streams by type instead of with a blanket `-map 0`, because the container is
   fixed: an MKV font attachment has no place in MP4, and `0:v` would hand embedded
   cover art to the encoder as a second video stream, so `0:V?` takes real video
   only.
4. Validates the result with ffprobe before anything else happens: it has to be a
   readable H.265 file of essentially the same duration as the source.
5. Gives the validated file its real name (source name plus the affix), through the
   same `SecureFileMove` (SHA-256 baseline + byte-for-byte verify) every other feature
   uses. If that name is somehow taken, "(H.265)" keeps the two apart - nothing is ever
   overwritten.
6. Only then applies the choice for the original - quarantine or keep. When there is no
   affix and the original went to quarantine, the converted file inherits its name.
7. Catalogs the new file immediately, reusing the inventory's own extraction and
   persistence.

If any step fails, the original stays exactly where it is and the file is counted as
an error, never as a conversion. The original is never touched before the replacement
is in place.

The reference command line, as built by `VideoConversionCommandBuilder` (the single
place encoder arguments are assembled, so a future NVENC/Quick Sync/AMF/AV1 encoder is
a change to that one class):

```bash
ffmpeg -y -hide_banner -loglevel error -nostats -progress pipe:1 \
  -i input.mkv \
  -map 0:V? -map 0:a? -map 0:s? -map 0:d? -map_metadata 0 -map_chapters 0 \
  -c:v libx265 -crf 22 -preset medium \
  -tag:v hvc1 -movflags use_metadata_tags \
  -c:a copy -c:s mov_text -c:d copy -ignore_unknown \
  output.mp4
```

`-tag:v hvc1` is what makes Apple and Windows players accept the file at all, and
`-movflags use_metadata_tags` keeps the non-standard MP4 tags that `-map_metadata`
alone drops. `-c:a aac -b:a 192k` replaces `-c:a copy` when AAC was chosen or when the
automatic fallback kicks in, and `-c:v copy` replaces the whole `libx265` block when
the source is already H.265 and only the container has to change.

### Retries that give up only what MP4 cannot hold

At most three attempts are made per file, each dropping one demand the container
refused:

1. The conversion exactly as asked for.
2. **AAC audio** - with the recommended audio option the original audio is copied as
   is; if FFmpeg refuses it ("Could not find tag for codec ...", a failed header
   write), the file is converted again with AAC audio.
3. **No subtitles** - MP4 only defines `mov_text`, so a text track (SubRip, ASS)
   survives the move but an image-based one (PGS, VobSub) cannot. When the error
   blames the subtitle track, the file is converted again without it.

Both are recorded per file in the conversion report, so a track that had to be
re-encoded or left behind is never lost silently. Failures FFmpeg reports for any
other reason are **not** retried: another attempt cannot fix them and would only cost
the user a second full encode.

### Progress, history and limits

Each batch is a `CONVERSION` execution in the history, with the converted, skipped and
failed counts and the space reclaimed. The screen follows two progress dimensions -
files done and how far into the current encode FFmpeg is - so a single long video
never looks frozen; leaving the screen does not stop the batch.

Only one conversion runs at a time, and the transcode has its own concurrency limit in
the external-tool gate (`nimbus-file-manager.processing.ffmpeg-transcode-limit`,
default `1`): an H.265 encode already saturates every core, so a second one finishes
neither sooner and only makes the rest of the application crawl. While a batch is
running the screen locks the selection and the options - the batch already owns the
files it was given, and a half-changed screen would only look like it accepted the
change.

**Cancelling.** The batch can be stopped at any point. ffmpeg is killed between two
progress lines (not at the end of the current file, which could be hours away), the
half-written temporary file is deleted, the source is left untouched and no further
file is started. The execution is recorded as `CANCELLED`, and whatever was not
converted stays on the list.

Originals sent to quarantine land in the same quarantine as the duplicate removal and
are restored or purged from the same Quarantine screen - there is no separate
conversion quarantine. Choosing that option while no quarantine folder is configured
is refused up front, before anything is encoded.

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

`/movements` returns the file movement records for an organization execution (source path, target path, status) as a separate call - they are not embedded in the `/api/executions/{id}` response itself.

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

Flyway applies schema changes at startup. The schema was squashed into a single consolidated baseline (`V1__initial_schema.sql`, on 2026-07-12 for a fresh-database reset); later changes are added as new versions on top of it (currently up to `V3__media_fingerprint_video_payload.sql`, which extends the `media_fingerprint` payload check so the multi-frame video fingerprint algorithm is validated alongside the photo one; `V2__catalog_file_lifecycle_changed_at.sql` adds the retention anchor for the catalog missing-record purge). Example startup log for a new database:

```text
Migrating schema "public" to version "1 - initial schema"
Migrating schema "public" to version "2 - catalog file lifecycle changed at"
Migrating schema "public" to version "3 - media fingerprint video payload"
Successfully applied 3 migrations to schema "public", now at version v3
```

Check applied migrations with:

```sql
SELECT *
FROM flyway_schema_history
ORDER BY installed_rank DESC;
```

## External Tools

FFmpeg and FFprobe power video conversion, video thumbnails and perceptual hashing. ExifTool is
not used: photo EXIF is read in-process by metadata-extractor.

### Installing them

On **Windows**, nothing has to be fetched by hand. A start that finds neither tool installs them
by itself in the background, and the external-tools section of the settings screen has an
install/update button for forcing it — including over an existing build, which the automatic run
never touches. Either way the official FFmpeg package is downloaded, the executables and their
DLLs are kept under `tools/ffmpeg/bin` and the rest is dropped. The package is GPL-licensed and is
downloaded by the machine that runs the application — it is never shipped inside this project —
and its `LICENSE.txt` is stored next to the binaries as `FFMPEG-LICENSE.txt`.

The automatic install can be turned off on the same screen
(`nimbus-file-manager.tools.auto-install`), for an installation that deliberately points at its
own build or runs offline.

On **Linux and macOS**, install them with the package manager (`apt install ffmpeg`,
`brew install ffmpeg`) - the commands resolve through `PATH` and nothing else is needed. The
Docker image installs them and points the environment variables at `/usr/bin` explicitly.

### How a path is resolved

No path is configured by default. The tools are looked up in this order: the value saved on the
settings screen, then the configured property/environment variable, then discovery - the binary
under `tools/ffmpeg/bin` when it is there, and otherwise the bare command, which the operating system
resolves through `PATH`.

Pin an absolute path with an environment variable or on the settings screen:

```text
NIMBUS_FILE_MANAGER_FFPROBE=C:/nimbus-file-manager/tools/ffmpeg/bin/ffprobe.exe
NIMBUS_FILE_MANAGER_FFMPEG=C:/nimbus-file-manager/tools/ffmpeg/bin/ffmpeg.exe
```

The download address itself is a setting (`nimbus-file-manager.tools.download-url`), so it can be
pointed at a mirror without a new release.

### `tools/ffmpeg/bin` is not committed to git

`tools/ffmpeg/bin/*.exe` and `tools/ffmpeg/bin/*.dll` are gitignored (over GitHub's 50 MB per-file warning
threshold, and FFmpeg builds with `--enable-gpl` carry GPL obligations that do not belong inside
this repo's history). The install button rebuilds the folder on a fresh clone; to fill it by hand
instead, download an official **shared** Windows build (a *static* build's self-contained
`ffprobe.exe` fails with `STATUS_DLL_NOT_FOUND` when its DLLs are missing) and place the
executables together with their DLLs directly under `tools/ffmpeg/bin/`.

## Packaging a native application

The `installer` profile builds a self-contained application with the JDK's own `jpackage` — the
bundled runtime removes Java from the list of things to install first:

```bash
./mvnw -Pinstaller -DskipTests package
```

The result is `target/installer/Nimbus File Manager/`, a folder holding the launcher, the
application jar and a trimmed JRE (~200 MB). It runs from anywhere, copied as it is.

A real installer instead of a folder:

```bash
./mvnw -Pinstaller -Dinstaller.type=msi -DskipTests package
```

`msi` (and `exe`) additionally require [WiX](https://wixtoolset.org/) on the `PATH`; without it
`jpackage` fails with a message naming the missing tool. The default stays `app-image` so the
profile works on a plain machine.

### The embedded database

A packaged copy manages its own PostgreSQL: the cluster lives in
`<workspace>/database/cluster`, is created on first start with a generated password, and
listens on 127.0.0.1 only. The port it bound and that password are kept in
`<workspace>/database/cluster.properties`, so a restart reuses what worked.

Whether a run manages its own server is decided in one place —
`EmbeddedDatabaseActivation` — from four signals, in this order:

| Order | Signal | Effect |
| --- | --- | --- |
| 1 | `nimbus-file-manager.database.embedded` | wins in both directions; anything that is not `true` counts as off |
| 2 | `NIMBUS_FILE_MANAGER_DB_HOST` or `SPRING_DATASOURCE_URL` | a database configured by hand keeps its data; the cluster stays down |
| 3 | `jpackage.app-path` | an installed copy manages its own |
| 4 | — | off: a build is a developer machine, which already has a server |

The resolved `spring.datasource.url` deliberately decides nothing — the packaged properties
always define one, so its presence distinguishes nothing. Only a value somebody set counts.

The major version is pinned. A cluster written by another one is refused and left exactly as
it is, rather than opened, migrated or replaced: moving between major versions of the server
is its own decision, to be taken when it comes.

The server binaries are **not** in the repository. Stage them in `tools/postgresql` (the
`bin` folder holding `initdb`, `pg_ctl`, `postgres` and `createdb`) before building the
installer and they are packaged with it. Without them the application starts anyway and
falls back to the configured connection.

### Where an installed copy keeps its data

An installation lives in a folder its user cannot write to, so a packaged run puts the workspace
under `~/Nimbus File Manager/workspace` instead of beside the executable — decided once at
startup by `WorkspaceLocation`, published as `nimbus-file-manager.workspace` before Logback opens
its file, and read from there by everything else. Started from a build, the workspace stays next
to the project as before. Setting `NIMBUS_FILE_MANAGER_WORKSPACE` (or the property) wins over
both.

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
Tests:       2445 run, 0 failures, 0 errors, 9 skipped
JaCoCo:      98.35% instruction, 91.91% branch, 97.90% line, 98.75% method, 100.00% class
```

### Coverage ratchet

Coverage never regresses. The **floor** below is the contract every task has to clear
before it can be considered done; the **goal** is what the floor is being pushed
toward. When a run comes in above the floor, the floor is raised to the new values in
the same commit — that is what makes the ratchet advance. See *Piso de cobertura* in
`AGENTS.md` for the policy.

```text
Floor:  98.47% instruction, 92.01% branch, 98.08% line, 98.74% method, 100.00% class
Goal:   98.75% instruction, 92.50% branch, 98.25% line, 99.00% method, 100.00% class
```

Method and class sit above the floor; instruction, branch and line sit below it. The gap is 28
lines across the two newest domains that no honest test reaches: the I/O `catch` blocks of the
PostgreSQL installer, the cluster service, the download and the backup; and the containment check
that decides whether an archive entry may be written — which the folder filter ahead of it already
rejects every escaping name for, and which stays because that is a property of the filter rather
than a guarantee. The floor was left where it is rather than lowered to match.

**All five metrics reached their goal**, line last — it was the one still short. The
goals above are the next step the ratchet asks for, set from the numbers actually
measured rather than from a round figure: the previous branch goal had been lowered
from 95% to 90% precisely because a target nobody can reach orients nobody, and 90%
landed two passes later.

Where the remaining work is: the classes furthest from the goal are the ones that
touch the file system and the delivery layer, and each needs a handful of real cases
rather than a sweep. The pass that reached the line goal wrote cases for contracts
that had none - a pHash refusing a sample of the wrong size, a quarantine cleanup
that crashes ending its execution as a failure instead of leaving it open forever, a
refused user creation saying why on the screen, a location name that sanitizes down
to dots being dropped instead of becoming a path segment. It also deleted two methods
nobody called (`FileCategory#isOther`, `MediaSubcategory#valueOfNullable`): chasing
coverage keeps finding dead code, which is the useful version of the exercise.

Two candidate tests were dropped rather than written, because the paths they would
have covered cannot happen: the null-name guards of the file-name date rules (the
engine rejects null before any rule sees it) and the `ERROR`/`CANCELLED` switch arms
of the inventory writer and the conversion service (present for exhaustiveness; no
production path produces those values). They are recorded here as residue, not as a
gap to close.

What is genuinely unreachable is a small set: I/O failure paths that need OS-level
permission denial, interrupt handling of the shared executors, utility-class
anti-instantiation guards, a `FilterInputStream` single-byte override the JSON parser
never calls. Part of what a report shows as uncovered is also covered by tests that
*self-skip* on this machine (symbolic-link cases needing privilege), so the same
suite measures higher elsewhere.

The rule still stands: if the remaining tail turns out to be that unreachable set,
coverage stops there and the reason is recorded. The goal never justifies
fabricating a scenario just to flip a branch.

The 9 skipped tests are OS-dependent (symbolic-link / POSIX-permission) cases that
self-abort via JUnit `Assumptions` on platforms where they cannot run (e.g. Windows).

Tests run in parallel (configured in `src/test/resources/junit-platform.properties`):
different test classes execute concurrently while the methods inside one class stay on a
single thread, at ~50% of available cores (dynamic factor `0.5`). Execution is thread-based
(one JVM), so the single JaCoCo agent still aggregates coverage correctly. Each
`@SpringBootTest` class starts its own throwaway PostgreSQL container
(Testcontainers + `@ServiceConnection`), so they are fully isolated and run in parallel with
no shared database - which requires a running Docker engine locally and in CI. The suite was
run back-to-back with no flaky tests, and coverage is reproducible run to run. It used to
move by about 0.02 percentage point on the `@PreDestroy` hook of `QuarantinePurgeScheduler`,
recorded only when a `@SpringBootTest` context happened to close before the JaCoCo agent
dumped; that hook is now covered by its own test, so the numbers no longer depend on
shutdown timing and the floor can sit exactly on the measured values.

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
Line coverage for mutated classes: 10578/10919 (97%)
Generated mutations:              6306
Killed mutations:                 5248
Survived mutations:               837
No coverage:                      196
Timed out:                        25
Run error:                        0
Mutation score:                   84%
Test strength:                    86%
Duration:                         24m38s
```

Where the survivors are: by a wide margin the most common surviving mutator is
`VoidMethodCallMutator` - a call whose removal no assertion notices, typically a
progress update, a log-adjacent notification or a cache invalidation that the test
exercises without checking. The conditional-boundary and math mutators come next,
in the paging and percentage arithmetic. The single largest block of *no coverage*
is one icon-mapping utility reached only from templates: a unit test there would
restate the map rather than verify behaviour, so it stays as declared residue.

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