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
- SpotBugs + find-sec-bugs (bytecode and security analysis)
- FFprobe / FFmpeg (downloaded into the workspace on first start, or resolved through `PATH`)
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
- Scheduled catalog retention purge that permanently removes records whose file has been missing from disk (`MISSING`) longer than a configurable number of days, anchored on when the record became missing. It runs as an execution of its own, so the screen says when it ran and how many records it removed instead of leaving nothing but a log line, and a day with nothing past the window is not queued at all. The window is read from Settings (`nimbus-file-manager.catalog.missing-retention-days`) when the purge runs, not when it is queued; a blank or non-positive value disables it (fail-safe). `DELETED` records are left to the quarantine purge.
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
- Catalog backup and restore from the settings screen, described in [Catalog backup](#catalog-backup): what the files on disk cannot rebuild is the catalog, and a backup taken on an older version still restores.
- Diagnostics archive (`GET /api/diagnostics/export`) collecting what a bug report needs about an installation.
- Runtime settings stored in PostgreSQL with creation/update audit fields.
- User access history for login, 2FA and logout events, searchable by e-mail.

## Installing

Every entry of the [releases page](https://github.com/JorgeFrancisco/Nimbus-File-Manager/releases)
carries a Windows `.msi` built by the pipeline, with its SHA-256 beside it. Installing is a double
click: the application lands in Programs and Features and in the Start menu, and uninstalls from
the same place.

Nothing has to be installed first. The bundled runtime takes Java off the list, and PostgreSQL and
FFmpeg are fetched into the workspace by the first start that finds them missing — which is why
that first start takes minutes, and why it reports its progress through the tray icon rather than
through a window that does not exist yet.

Checking the download against the published checksum, in PowerShell:

```powershell
(Get-FileHash '.\Nimbus.File.Manager-<version>.msi' -Algorithm SHA256).Hash.ToLower()
```

A machine that answers the double click with **2502 and 2503 followed by 1603** is not describing a
broken package — those codes mean "called out of sequence", which is the symptom of the `msiexec`
server failing to elevate, and the cause is local. Any other MSI is the cheapest way to tell the
two apart: one that fails the same way proves the machine is at fault. The case seen here was the
`Users` group having no write permission on `C:\Windows\Temp`, where that server creates its own
temporary files, fixed with `icacls C:\Windows\Temp /grant '*S-1-5-32-545:(CI)(S,WD,AD,X)'`. Every
failed attempt also leaves an `msiexec` behind, which answers 1500 ("another installation is in
progress") until it is killed.

## Updating

An installed copy checks whether a newer version has been published — a couple of minutes after it
starts, then every fifteen minutes — and the settings screen has a *Check now* alongside it. When
one is found it is announced where it will actually be seen: a badge next to the version in the top
bar of every screen, and the tray icon's tooltip and a balloon. Once per version, not once per
check — the same notice four times an hour is not a reminder, it is a reason to turn notifications
off.

The screen also shows when the endpoint was last asked — including when the answer was that nothing
could be reached, because otherwise "nothing newer" and "the check stopped running" look identical,
and only one of them is good news. That distinction is not hypothetical: the check was written as a
Spring `@Scheduled` method, this application has no `@EnableScheduling`, and it therefore never
fired on a timer at all. It looked like it worked because the button calls the same code. It now
runs on its own daemon thread, the way every other periodic task here does.

Fifteen minutes is four requests an hour against an endpoint that allows sixty from an address that
does not authenticate. A minute would sit exactly on that ceiling, and the first thing to share the
address would push it over — after which the answer is a refusal and updates silently stop being
found. The interval is `nimbus-file-manager.update.check-interval`, so a test can lower it.

Installing asks first, saying the application will close and reopen. The download then runs in the
background with its progress on the screen: it is over a hundred megabytes, and holding the request
until it landed left the browser on a blank minute. The SHA-256 is compared against the one
published beside the installer, and only then is it started. A file that does not match is deleted
rather than kept, because an installer of unknown provenance sitting under the name of a real
release is worse than no download at all.

The run then ends, because the MSI replaces the files it is executing from — through the same
graceful path the tray's *Exit* uses, so the embedded PostgreSQL is stopped rather than left
behind. What survives the process is a small script: it waits for the installer, deletes it, and
**opens the application again**. Neither half is a nicety. Without the relaunch an update ends with
the window gone and nothing back, which reads as a crash; without the delete, every update leaves
another hundred-megabyte installer in the workspace forever.

**Only releases whose `MAJOR.MINOR.PATCH` moved are offered.** Windows Installer records three
fields, so two releases differing only in the build are the same version to the machine that would
install them — announcing one would offer an upgrade that cannot be applied. This costs nothing:
by the versioning policy a build-only bump is refactoring, documentation or a test, which has
nothing to deliver to anyone.

This check is the only thing in the application that reaches the network without being asked, so it
is switchable and the reasoning is written down in
[ADR 0001](docs/adr/0001-verificacao-de-atualizacao-sai-do-computador.md):

```text
nimbus-file-manager.update.enabled=false
nimbus-file-manager.update.release-url=https://your-mirror/releases/latest
```

Nothing identifying is sent — no installation id, and not even the installed version, which never
leaves the machine because the comparison happens locally. A run with no manifest of its own (from
the IDE, from Maven) does not check at all, so a development machine never contacts the endpoint.

Everything from here on is about running from source.

## Running

Requirements:

- Java 25
- Maven 3.9+
- Docker (only for the integration tests, which use Testcontainers - the app itself does not need it)
- FFmpeg and FFprobe, for video conversion, video thumbnails and perceptual hashing. Nothing to do
  on Windows, where the application installs them on the first start that finds them missing; on
  Linux and macOS install them with the package manager. See [External Tools](#external-tools).

**PostgreSQL is not one of them.** A fresh clone starts without a server installed: every run
manages its own cluster under the workspace, created on first start, described in
[The embedded database](#the-embedded-database). `./mvnw spring-boot:run` is the whole procedure.

It runs on Windows, Linux and macOS. Two things differ by platform, and both degrade to a working
default rather than failing: the real-time file-system watcher uses Windows APIs and falls back to
the portable `WatchService` elsewhere, and the external tools are installed by the application on
Windows and by the package manager elsewhere.

### Pointing it at a PostgreSQL of your own

Supported, and the rest of this section is about that case only — setting
`NIMBUS_FILE_MANAGER_DB_HOST` or `SPRING_DATASOURCE_URL` is what keeps the embedded cluster down,
because a database somebody configured by hand is one whose data they mean to keep.

Create the application role and database while connected as `postgres` or another PostgreSQL administrator:

```sql
CREATE ROLE nimbus_file_manager WITH LOGIN PASSWORD 'nimbus_file_manager';
CREATE DATABASE nimbus_file_manager OWNER nimbus_file_manager;
```

The database must be owned by `nimbus_file_manager`. Merely granting connection access is not enough: Flyway needs permission to create its history table, application tables, sequences and indexes in the `public` schema. (The integration tests no longer need a local test database - Testcontainers provisions the throwaway PostgreSQL containers.)

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

### Roles

> **The architecture in full:** [`docs/architecture/worker-architecture.md`](docs/architecture/worker-architecture.md)
> describes how App and Worker fit together - the queue, ownership and leases, path exclusion,
> durable results, and what the build enforces. The decisions behind it are ADRs 0003 to 0008 in
> [`docs/adr/`](docs/adr/); what is left to accept, to weigh up or deliberately not to do is in
> [`docs/backlog-operacional.md`](docs/backlog-operacional.md).

**Normal operation is two processes.** The installed application runs an App and a Worker in
separate JVMs: the App owns the product's lifecycle, the screens and the API, starts the embedded
PostgreSQL and supervises it, and starts and supervises the Worker. The Worker runs the background
jobs in a JVM of its own and works against that same database - it never starts a second cluster.
That is the shape on an end user's machine, not an option or a later step.

The same jar starts in one of three roles, chosen by profile:

| Profile | Process | Role |
| --- | --- | --- |
| `app` | main JVM | screens, API, embedded PostgreSQL, supervising the worker, and producing work |
| `worker` | second JVM | claiming that work from the queue and running it, against the App's database |
| `app-worker-combined` | one JVM | both of the above together, for development |

`app` is the default: starting the jar with no profile argument - which is what the installed copy,
the tray and every launcher do - gets the application, and the Worker it starts.

**`app-worker-combined` is for development.** Running both roles in one JVM is convenient to launch
and to debug from an IDE, and that is all it is for: it is not the deployment topology of the
installed product, and something that only works there has not been shown to work.

Everything that moves, writes or deletes a file of yours runs in the worker: organizing, undoing an
organization, converting video, sending duplicates to quarantine, restoring a selection from
quarantine and purging it for good, renaming, deleting and quarantining from the Files screen, plus
the inventory walk and the reconcile that follows it. The application queues the request and the
screen follows the row.

The heavy analysis that touches nothing goes the same way, and by now that is all of it:
fingerprinting the library, rebuilding the metadata of a folder (and the dry run that says what such
a pass would change), resolving locations from GPS coordinates, and downloading and importing the
boundary dataset those answers are read from. Nothing about any of them is held in memory: a run
appears in the executions history, its progress and its estimate are read from the row, and a run
interrupted by a restart is picked up again because the work is a query rather than a checkpoint -
whatever is still missing a fingerprint, or has not been re-read since the last pass.

Two of them cannot run at once - replacing the boundaries under a running location rebuild would
change the answers halfway through - and that is expressed as data rather than as a flag: both name
the geodata folder as their path, and the lock every execution takes over the paths it names is what
keeps them apart. Across processes, and without either of them knowing the other exists. Everything
else carries on beside them.

Three background jobs stay in the application on purpose: installing an update (it ends the
application), installing ffmpeg (the worker needs it to exist first), and backing up or restoring the
database (the restore drops every connection, the worker's included).

The Files screen still answers the way it always did when the work is quick. Renaming, deleting for
good and sending to quarantine are written to the queue and the answer waits a second for them: with
a worker idle, that is long enough for the whole thing, and the dialog reports what happened. When it
is not - a large folder, or a worker busy with something else - the answer says the work was accepted
and is still coming, and the listing updates itself once it finishes. Nothing is refused for taking
too long, and nothing is carried out by the application instead.

What stays in the application is the conversation, not the work. Restoring a single quarantined file
can raise a question - a name collision, a missing origin folder - and a question is put to you
before anything is queued; the new name for a collision is chosen while you are still there. What
reaches the worker is a destination already decided, moved under the same locks and the same
verified move as everything else. The answer waits a second for it and says the restore is on its
way when it takes longer, exactly like the Files screen.

The worker learns of a new request through a PostgreSQL notification published in the same
transaction that writes the row, so it starts within milliseconds instead of waiting for its next
poll. The notification carries nothing: the request is the row, and the worker still polls on its own
schedule, so a lost signal delays work rather than losing it. Each worker also writes a heartbeat,
which is what lets the application tell "your request is being processed" from "your request is
queued and there is nothing running to pick it up" - readable at `GET /api/worker`.

For Run/Debug from an IDE, start `NimbusFileManagerApplication` as a Java Application with:

```text
--spring.profiles.active=app-worker-combined
```

Eclipse users have this ready: **Nimbus - App Worker Combined.launch** is versioned with the project,
so a fresh clone can Run or Debug it without typing arguments.

### Memory

Each role gets its own heap, which is one of the reasons the roles are separate processes at all.

| Role | Heap | Where it is set |
| --- | --- | --- |
| App (installed) | `-Xms256m -Xmx1g` | `--java-options` in the jpackage step of `pom.xml`, from the `installer.app.*-heap` properties |
| Worker (second JVM) | `-Xms512m -Xmx4g` | the application builds the command line, from `nimbus-file-manager.worker.initial-heap` / `.max-heap` |
| `app-worker-combined` | `-Xms512m -Xmx4g` | the shared Eclipse launch configuration |

One source each, and no property pretends to resize a JVM that is already running - a heap can only
be chosen as a process starts. The combined profile is one JVM, so the two budgets are **not** added
up: it takes the worker's, because that is the half doing the heavy work.

These bound the **Java heap** only. Thread stacks, native buffers, the JVM itself and the ffmpeg and
PostgreSQL processes all live outside it, so neither number is a limit on what the application costs
the machine. Every start logs the heap it actually got, the processors it can see and its role.

Both halves then live in one JVM, so a breakpoint in a controller and one in a job handler are hit by
the same debugger. It is a Spring profile group - it activates `app` and `worker` and adds nothing of
its own, so work still travels the queue, the claim, the lock and the lease exactly as in production.
What it does not give is the isolation the split exists for: one JVM means one heap and one garbage
collector, which is why production runs the two apart.

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

The `@SpringBootTest` integration tests start throwaway PostgreSQL containers via
Testcontainers (`@ServiceConnection`), so **no manual test database is required** - only
a running Docker engine. They run in parallel and need no shared test DB or
`NIMBUS_FILE_MANAGER_TEST_DB_*` variables. A class either owns its container, or extends
`SharedPostgresIntegrationTest` and shares one container - and, what actually costs the
build time, one Spring context - with every other class that does. Only tests whose every
write is undone by the test transaction may share; that class comment says why.

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

The first administrator is created only when the `app_user` table is empty. Changing `NIMBUS_FILE_MANAGER_ADMIN_USERNAME` or `NIMBUS_FILE_MANAGER_ADMIN_PASSWORD` after a user already exists does not update or reset that existing account — a restart must never undo a password its owner has since chosen. After the first login, use the Account screen to change the password and the Users screen to create additional users.

### Locked out

For an administrator account whose password is gone — forgotten, saved wrong by a browser, or
never known on this machine in the first place, which is what a restored backup leaves behind
when it brings the users of the installation it was taken on. The way back in is a property of
its own:

```text
NIMBUS_FILE_MANAGER_ADMIN_PASSWORD_RESET=temporary-value
```

The next start resets the administrator to it, clears any lockout, and requires a change at
sign-in. Clear the value afterwards — while it is set, every start resets the account again. It
grants nothing new: whoever can write the configuration of an installation can already read the
database password sitting next to it.

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

Every screen carries the same activity bar, just below the title: what is running now, how far along
it is, and how many other things are waiting behind it, with a link straight to the execution. It
polls `GET /api/execution-activity`, which answers what is active rather than reporting on an
execution the page was told about when it rendered - so work started after the page was drawn shows
up on its own, and when one thing finishes the next one takes its place without a reload. Work that
is only queued is shown as queued, because that is what most of the wait looks like when something
else holds the lock. Work whose progress has no denominator - a purge, a reconcile - says what it is
doing and shows no percentage, rather than a bar frozen at zero.

There are two bars when the work reports two levels, the way an unpacker shows them: the overall one
counts the items that are done, and the one under it is the item still being worked on. Counting
finished items is not the same as being finished - a geodata update that has imported all three
administrative levels reads 3 of 3 while it is still writing the supplemental territory files - and
the second bar is what says so. It appears only when there is a step to report.

Inventory runs continuously in the background once a folder is set up through Onboarding; it has no dedicated screen or REST endpoint of its own. Reconciliation has no web screen or REST endpoint either, but it isn't just internal dead code: it is queued as a `RECONCILE` execution automatically - once per debounced batch of file-system changes by `InventoryWatchService`, and again on a timer by `ReconcileScheduler` regardless of changes, at the interval `nimbus-file-manager.inventory.reconciliation-interval-millis` names - so drift between disk and database (missing files, renames, path mismatches) self-heals in the background without any manual trigger. Neither of the two runs in the process serving the screens: both only enqueue, and the worker claims and executes them. Although neither has a screen of its own, both are visible in the execution history, and each execution records its trigger - `MANUAL`, `FILE_EVENT` or `TIMER`. One narrow exclusion applies to the Dashboard list only: the timer reconciles that finished having repaired nothing are hidden, because on a library that is not moving they are hundreds of identical rows a day. Every one of them is still a row in the table, so the queue and the technical audit stay complete.

The file-system change detection is a pluggable `FileChangeSource`. On Windows the real-time source is **`ReadDirectoryChangesW`** with `bWatchSubtree=true`: a single directory handle on the root, recursive detection, no per-folder lock and **no elevation required**. When the volume can be opened (elevated) the NTFS **USN Change Journal** is added on top purely for startup catch-up of changes made while the app was down. Only if even the single-handle recursive watch cannot be opened does it fall back to the portable per-directory `WatchService`; on Linux that `WatchService` remains the source. Either way the periodic reconcile stays the consistency net for what *left* - it retires catalog entries whose file is gone and repairs renames and stale paths. Cataloguing what *arrived* is the inventory's job alone, which is why every source reports the arrival of a folder as a change: a folder moved in from outside brings files that were never created under a watched directory and so raise no notification of their own.

The journal is the preferred source and the application asks for what it needs to read it: on
Windows, a start that cannot open a volume handle restarts itself elevated, raising one UAC
prompt. The reason is what the journal keeps rather than how fast it is - it is a durable record
on the volume, so changes made while the application was closed can still be read afterwards,
while real-time events live only in the memory of a process that was not running and a later
reconcile can only compare two snapshots.

Declining the prompt is a supported answer: the application then starts unelevated, without the
journal, and finds those changes on the next scan. The restart never happens outside an installed
copy (it needs `jpackage.app-path`, which the IDE and the build do not have), never happens twice
in a chain (the restart carries a marker argument), and never happens when the volume already
opens. Set `nimbus-file-manager.inventory.usn.elevate-on-start=false` to stop asking at all - it
is read before Spring starts, so it is a system property or environment variable, not a setting
on a screen.

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

Everything the application writes lives in one folder, in the user's home and in every mode -
started from a build or from an installed copy:

```text
<user home>/Nimbus File Manager/workspace/
  database/
  logs/
  exports/
  temp/
  backup/
  tools/
```

`logs/` holds **one file per process**, named after the role that writes it —
`nimbus-file-manager-app.log` and `nimbus-file-manager-worker.log`, or
`nimbus-file-manager-combined.log` when both roles run in one JVM for development. They are separate
because a rolling file is not safe to share between two JVMs; see
[ADR 0009](docs/adr/0009-um-arquivo-de-log-por-processo.md).

One location on purpose: while a build wrote beside the project instead, the layout that ships was
exercised only by the packaged copy, and its bugs were found by running it rather than by any test.
It is also the folder that is guaranteed writable — an installation may not be.

An external database is a separate PostgreSQL instance and is not stored under `workspace/`; the
embedded one is, under `database/`. See the connection environment variables in the Running section.

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

- `/api/organization/preview` queues the building of a plan and answers `202` with the execution; it moves no files.
- The plan is published by a worker, read back from `/api/organization/preview/{executionId}` one page at a time, and expires on its own short schedule (12 h by default, `nimbus-file-manager.organization.plan.ttl-hours`).
- `/api/organization/execute` recalculates the plan internally; there is no `previewId`. A published plan reports `catalogChanged` when the library moved since it was built, so the difference is stated rather than discovered afterwards.
- `/api/organization/execute` moves files physically.
- `/api/organization/execute/{executionId}/undo` moves files back using stored movement records.
- There is no `dryRun` flag for organization execution.
- There is no COPY mode; the current behavior is MOVE.

## Endpoints

```text
POST   /api/metadata/rebuild                            202, queues the rebuild

POST   /api/organization/preview                        202, queues the plan
GET    /api/organization/preview/{executionId}          the published plan, paginated
GET    /api/organization/preview/{executionId}/export   the published plan as a ZIP
POST   /api/organization/execute
POST   /api/organization/execute/{executionId}/undo

GET    /api/media
GET    /api/media/{publicId}
GET    /api/media/{publicId}/content
GET    /api/media/{publicId}/thumbnail

GET    /api/files/properties
POST   /api/files/rename
POST   /api/files/delete

GET    /api/duplicates
GET    /api/duplicates/{sha256}/files
GET    /api/duplicates/summary
GET    /api/duplicates/candidates
GET    /api/duplicates/similar-photos          200 published · 202 queued
GET    /api/duplicates/similar-photos/failures
GET    /api/duplicates/similar-videos          200 published · 202 queued
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
GET    /api/diagnostics/export

GET    /api/execution-activity

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

The answer is the queued execution. Poll it, then read the published plan a page at a time:

```bash
curl "http://localhost:8088/api/organization/preview/{executionId}?page=0&size=50&onlyConflicts=false"
```

Typical response shape:

```json
{
  "sourcePath": "C:\\nimbus-file-manager\\workspace\\temp",
  "targetPath": "C:\\nimbus-file-manager\\workspace\\organized",
  "layout": "DEFAULT",
  "catalogChanged": false,
  "page": 0,
  "size": 50,
  "totalItems": 8,
  "summary": {
    "totalFiles": 8,
    "alreadyOrganized": 0,
    "plannedMoves": 8,
    "totalSizeBytes": 41231234,
    "conflicts": 4
  },
  "items": [
    {
      "catalogFileId": "0193f1a2-7c4d-7000-8000-000000000001",
      "fileName": "20251230_115630.jpg",
      "sourcePath": "...workspace\\temp\\dup1\\20251230_115630.jpg",
      "targetPath": "...workspace\\organized\\202512\\30\\CAMERA\\IMAGENS\\20251230_115630.jpg",
      "conflict": true,
      "conflictType": "DUPLICATE_TARGET"
    }
  ]
}
```

## Organization Preview Export

Streams the published plan as a ZIP containing its JSON. It reads what a worker published rather than
recalculating, so the file describes exactly the plan the screen showed - and it reads in pages, so a
plan at the item cap never has to be held in memory to be serialized.

```bash
curl "http://localhost:8088/api/organization/preview/{executionId}/export" \
  -o organization-preview.zip
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

Three tabs: byte-identical duplicates (SHA-256), visually **similar photos** (256-bit DCT pHash confirmed by SSIM) and visually **similar videos**. Video similarity samples several frames at deterministic relative positions in a single ffmpeg pass, hashes each with the same pHash as photos, and matches videos frame-for-frame with a trimmed-mean aggregation plus a concordant-frame quorum — robust to re-encoding, bitrate, resolution, small duration differences and compression. Both similarity kinds are derived off-inventory by a shared fingerprint backlog, itself a queued execution the worker drains, and new algorithms plug in via the `VideoSimilarityAlgorithm` contract without touching the orchestrator.

The grouping itself is **durable**: a worker runs it as a queued execution and publishes the result, which the screen and the API then read instead of recomputing. A published analysis records which files it examined, so it can say that the library has moved since - and it stays on screen, still usable, while a new one is being computed and after a failed recomputation. A half-built result is never visible. When no analysis has been published for the current parameters, the similarity endpoints answer `202 Accepted` with the execution to follow rather than grouping the library inside the request.

A published analysis also **keeps itself up to date**. Photos and videos that arrive are incorporated into it as an arrival, without recomparing the library; and when an operation changes only *who* takes part - excluding a file or a folder and lifting either, sending files to quarantine from the Duplicados or the Arquivos screen, restoring them, deleting permanently, converting a video and quarantining the original, organizing or undoing an organization across an excluded folder, a reconciliation marking files missing, an inventory finding files it had given up on, or switching library - the result is regrouped from the comparisons already stored instead of recomputed. One request per operation, whatever its size, and none at all for an operation that changed nothing. A full reanalysis stays what it always was: something the user asks for.

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

Every long-running or file-changing operation is one of these rows, including administrative ones:
changing the monitored library queues a `LIBRARY_SWITCH` that forgets the old library's catalog and
adopts the new one, so it survives a restart and is followed on the executions screen like anything
else. Nothing about it runs in the process serving the screen.

## Statistics

```bash
curl "http://localhost:8088/api/statistics"
curl "http://localhost:8088/api/statistics/codecs"
curl "http://localhost:8088/api/statistics/extensions"
curl "http://localhost:8088/api/statistics/folders"
curl "http://localhost:8088/api/statistics/errors"
curl "http://localhost:8088/api/statistics/errors/files"
curl "http://localhost:8088/api/statistics/errors/files/details"
```

## Catalog backup

The backup tab of the settings screen creates a backup, restores one, deletes one, and cancels a
run in progress. Files land in `<workspace>/backup` as `nimbus-catalog-<timestamp>.zip`.

What it protects is not the media — those are on disk, and a filesystem backup already covers them.
It is the catalog: extracted metadata, perceptual hashes that cost hours of ffmpeg, resolved
locations, the movement history that makes an organization undoable, and the duplicate decisions
taken by hand. Losing the database with every file intact still means starting all of that over,
which is the difference between reinstalling and continuing, and reinstalling and beginning again.

Each archive holds a logical dump plus a manifest naming the schema it came from. The dump carries
the tables and their shape rather than rows alone, so a backup taken before a migration renamed a
column still restores: what comes back is the database as it was, and the migrations bring it
forward from there. Rows alone could only ever load into tables shaped exactly as they were on the
day the dump was taken.

The dump and restore shell out to `pg_dump`/`pg_restore` from the same PostgreSQL the application
manages, so the client always matches the server — a client older than the server refuses to read
what it wrote.

A backup is checked three times before it is kept, and the three answer different questions. The
dump is read back with `pg_restore --list`, which walks the whole compressed file, so a truncated
one is discarded while taking it again is still an option. The finished archive is then opened and
read to the end of every entry: the first pass proves the archive has the index a restore will look
for, and the second confronts the CRC-32 each entry stores, which is what catches a byte that
changed after it was written. Only then is it delivered, and the delivery compares the SHA-256 of
what left with the SHA-256 of what arrived — when the destination is another disk, the move is a
copy that nothing else would verify. An archive that fails any of these is discarded in staging: the
previous backup stays where it is, and nothing that looks like a backup is left behind.

## Database Migrations

Flyway applies schema changes at startup. The schema was squashed into a single consolidated
baseline (`V1__initial_schema.sql`, on 2026-07-12 for a fresh-database reset); every later change is
a new version on top of it, and the folder `src/main/resources/db/migration` is the current list —
reading it beats any count repeated here.

A migration that changes the shape of a column carries the data across in the same file, rather than
running the DDL alone: an installation being upgraded has a populated catalog, and structure-only
changes pass a clean test database while silently discarding years of work on a real one. Several
of the versions on top of the baseline exist only to re-queue rows for reprocessing after a bug was
fixed (`V10`, `V12`, `V13`, `V14`), which is the same idea seen from the other side — the migration
knows how yesterday's data becomes today's.

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

On **Windows**, nothing has to be fetched by hand. Every start that finds this installation
without its own copy downloads one in the background, and the external-tools section of the
settings screen has an install/update button for forcing it — including over an existing build,
which the automatic run never touches. Either way the official FFmpeg package is downloaded, the
executables and their DLLs are kept under `<workspace>/tools/ffmpeg/bin` and the rest is dropped.
The package is GPL-licensed and is downloaded by the machine that runs the application — it is
never shipped inside this project — and its `LICENSE.txt` is stored next to the binaries as
`FFMPEG-LICENSE.txt`.

On **Linux and macOS**, install them with the package manager (`apt install ffmpeg`,
`brew install ffmpeg`) - the commands resolve through `PATH` and nothing else is needed.

### How a path is resolved

There is nothing to configure. The tools are looked up in one order, in every mode: the copy this
application downloaded under `<workspace>/tools/ffmpeg/bin`, and otherwise the bare command, which
the operating system resolves through `PATH`.

The downloaded copy comes first deliberately. A binary already on `PATH` is of unknown provenance
and may be old enough to lack the codecs this application asks for, so it is what keeps the
features working until the download succeeds, never the preferred answer - and a start that found
only `PATH` tries the download again.

There is no setting naming where the binaries live, and that is the point: the value used to be
stored in the catalog, so it travelled inside a backup and landed describing another machine. A
restored installation spent seventeen hours failing every ffprobe call against a folder that
existed only on the installation the backup came from.

Every external tool lives in `<workspace>/tools/<tool>/bin`, in every mode: a build and an
installation resolve it identically. A downloaded binary is the user's data rather than part of the
program, and an installation may sit in a folder nobody can write to. `nimbus-file-manager.tools`
points that folder elsewhere - at tools the machine already has, and at a real `pg_dump` for the
test run, which writes its own throwaway workspace under `target/`.

The download address is a property (`nimbus-file-manager.tools.download-url`), the same way the
embedded PostgreSQL takes its own, so it can be pointed at a mirror without a new release.

### The binaries are never in git

They live in the workspace, outside the repository entirely - too large for git (over GitHub's
50 MB per-file warning threshold), and FFmpeg builds with `--enable-gpl` carry GPL obligations that
do not belong inside this repo's history. A fresh clone fills the folder by itself on first start,
or from the install button. To fill it by hand instead, download an official **shared** Windows
build (a *static* build's self-contained `ffprobe.exe` fails with `STATUS_DLL_NOT_FOUND` when its
DLLs are missing) and place the executables together with their DLLs directly under
`<workspace>/tools/ffmpeg/bin/`.

## Packaging a native application

The `installer` profile builds a self-contained application with the JDK's own `jpackage` — the
bundled runtime removes Java from the list of things to install first:

```bash
./mvnw -Pinstaller -DskipTests package
```

The result is `target/installer/Nimbus File Manager/`, a folder holding the launcher, the
application jar and a trimmed JRE (~200 MB). It runs from anywhere, copied as it is.

No external binary goes inside it. PostgreSQL and FFmpeg are fetched on first start into the
workspace, which means the machine building the installer needs neither - a pipeline never has them
- and an installation that lands in a read-only folder still works, because nothing is ever written
back into it.

A real installer instead of a folder — an entry in Programs and Features, a Start menu
shortcut and an uninstaller:

```bash
./mvnw -Pinstaller,installer-msi -DskipTests package
```

It is a second profile rather than a switch on the first because it needs tooling the image
does not. Three prerequisites, on the machine that builds — never on the machine that runs:

| | | |
| --- | --- | --- |
| **WiX 5** | `winget install --id WiXToolset.WiXCLI --version 5.0.2.0` | `jpackage` accepts WiX v3 (`light.exe`/`candle.exe`) or v4/v5 (`wix.exe`). v5 is the cheapest of the three: v3 drags in the .NET Framework 3.5 Windows feature, and v6+ asks you to accept the Open Source Maintenance Fee EULA — a licensing decision this project has not taken. |
| **`WixToolset.Util.wixext`** | bundled with the WiX 5 install | |
| **`WixToolset.UI.wixext`** | bundled with the WiX 5 install | |

The two extensions are the non-obvious part: `jpackage` invokes `wix build` with `-ext
WixToolset.Util.wixext -ext WixToolset.UI.wixext` and does not install them, so on a WiX 4+
toolchain that has only the base tool the build fails inside `wix` with no mention of what is
missing. The WiX 5 installer ships them, so nothing else is needed — but `wix extension list
--global` is what confirms it, and their version has to match the toolset: adding them by name
alone fetches the newest from NuGet, which a v5 `wix.exe` then refuses.

On a machine without `winget` — a CI runner, for one — the same toolset comes from the .NET tool
feed, and there the extensions are **not** bundled, so all three are named explicitly and pinned to
the same version:

```powershell
dotnet tool install --global wix --version 5.0.2
wix extension add -g WixToolset.Util.wixext/5.0.2
wix extension add -g WixToolset.UI.wixext/5.0.2
```

The MSI is packaged from the image the first profile produced (`--app-image`), so what ships is
what was already built rather than a second pass over the jar. `--win-upgrade-uuid` is fixed in
the `pom.xml` and must stay that way: Windows decides "upgrade this installation" against
"install a second one" by that code alone, and `jpackage` invents a fresh one per build when it
is absent.

Building it by hand is not how it reaches anyone, though. Pushing a tag `v*` runs the
`release.yml` workflow on a Windows runner, which builds the MSI exactly as above, refuses to go
on if the tag disagrees with `<version>` — a release whose name contradicts the installer under it
is worse than no release — and publishes both the installer and its SHA-256 under a release named
after the tag. Running the same workflow from the Actions tab builds without publishing and leaves
the MSI as a workflow artifact, which is how a change to the packaging gets tried before a tag is
spent on it: a tag cannot be pushed a second time. Installing what comes out is
[Installing](#installing).

### No console, an icon by the clock

The packaged application opens no window of its own. What it opens is a tray icon, installed from
`main` before Spring exists — which is the point of it, because a first start spends minutes
fetching a database server before anything is listening, and a program with no sign of life reads
as one that failed to open. The tooltip carries every bootstrap step; a notification is raised
twice only, at the first step and when the application is ready, the second one carrying the
address that opens it.

Its menu names the running build — the version is read from the jar manifest, so it answers "which
one is installed?" without opening anything — and opens the application, the log folder and the
workspace, and ends the run. A double click on the icon opens the application too, which is the
gesture Windows treats as an icon's default action. Ending from
the menu matters more than it looks: the embedded PostgreSQL is stopped by the application's own
shutdown, so leaving through the task manager left a server behind, and until now the only way to
close properly was Ctrl+C in a console somebody had to keep open. Everything the menu opens is
handed to `explorer.exe` rather than to `java.awt.Desktop` — this process may be running elevated
to read the USN journal, and a browser started from it would inherit that.

Where there is no tray — a container, a Linux server, a session without one — every call is a
no-op and the application runs exactly as it did before.

### The embedded database

Every run manages its own PostgreSQL: the cluster lives in
`<workspace>/database/cluster`, is created on first start with a generated password, and
listens on 127.0.0.1 only. The port it bound and that password are kept in
`<workspace>/database/cluster.properties`, so a restart reuses what worked.

Whether a run manages its own server is decided in one place —
`EmbeddedDatabaseActivation` — from three signals, in this order:

| Order | Signal | Effect |
| --- | --- | --- |
| 1 | `nimbus-file-manager.database.embedded` | wins in both directions; anything that is not `true` counts as off |
| 2 | `NIMBUS_FILE_MANAGER_DB_HOST` or `SPRING_DATASOURCE_URL` | a database configured by hand keeps its data; the cluster stays down |
| 3 | — | on: nobody who runs this application installed a server first |

Running from the IDE takes the same path as the installed copy, and on purpose: it is the
only way the packaged behaviour gets exercised before it is packaged, and it is what lets a
fresh clone start without anyone installing PostgreSQL. Both open the **same** cluster under
the user's home, so the IDE and the installed application cannot run at the same time — the
second one to start finds the port taken. The suite is the exception and says so explicitly
(`nimbus-file-manager.database.embedded=false`, set by Surefire): every `@SpringBootTest`
brings its own throwaway container, and a cluster beside it would be a second server for
nothing.

The resolved `spring.datasource.url` deliberately decides nothing — the packaged properties
always define one, so its presence distinguishes nothing. Only a value somebody set counts.

The major version is pinned. A cluster written by another one is refused and left exactly as
it is, rather than opened, migrated or replaced: moving between major versions of the server
is its own decision, to be taken when it comes.

The server binaries are neither in the repository nor in the installer. They live in
`<workspace>/tools/postgresql/bin` (`initdb`, `pg_ctl`, `postgres`, `createdb`, and the client
programs the backup uses), and the first start that finds them missing downloads them —
before the context opens, because there is no application without a database. Shipping them
would put the installer's size and its licensing on this project; fetching them at first start
leaves both where they belong.

### Where the workspace lives

Everything a run writes goes to `~/Nimbus File Manager/workspace`, in every mode: from the IDE,
from `mvn spring-boot:run`, and from the installed executable. An installation cannot write
beside its own executable, and having a build write somewhere else would mean the layout people
actually use is never the one being developed against. It is decided once at startup by
`WorkspaceLocation` and published as `nimbus-file-manager.workspace` before Logback opens its
file, so everything downstream reads one answer. `NIMBUS_FILE_MANAGER_WORKSPACE` (or the
property) wins over it — which is what the suite uses to keep its writes inside `target/`.

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
Tests:       3887 run, 0 failures, 0 errors, 10 skipped
JaCoCo:      98.64% instruction, 92.94% branch, 98.15% line, 99.04% method, 100.00% class
```

### Coverage ratchet

Coverage never regresses. The **floor** below is the contract every task has to clear
before it can be considered done; the **goal** is what the floor is being pushed
toward. When a run comes in above the floor, the floor is raised to the new values in
the same commit — that is what makes the ratchet advance. See *Piso de cobertura* in
`AGENTS.md` for the policy.

```text
Floor:  98.61% instruction, 92.78% branch, 98.11% line, 98.99% method, 100.00% class
Goal:   98.75% instruction, 93.00% branch, 98.25% line, 99.00% method, 100.00% class
```

**The run above clears the floor on all five, and the floor stays where it was.** No margin is
wide enough to raise into: against the drift this suite is known to have between runs (*A medição
varia entre execuções* in `AGENTS.md`: up to 0.16 on branch and ~0.03 on the rest), instruction sits
0.03 above its floor and branch 0.16 - both exactly at the edge of the band - while line at 0.04 and
method at 0.05 clear it by a hundredth or two. A floor set at any of those readings would leave the
next measurement of the same tree no room at all, which is how ordinary noise becomes a red build.

**Method is the one this slice had to earn back.** The first measurement of it came in at 98.90,
0.09 under the floor and well outside the drift - not noise, and the JaCoCo report said exactly why:
five methods this slice added had no test touching the real object, because the tests around them
mocked the collaborator they belonged to. Covering the five put it at 99.04. That is the ratchet
working as intended: the number fell, the report named the cause, and the answer was tests of
behaviour rather than a lower floor.

**Queue order stopped depending on the machine's time zone.** The reservation ranks pending work by
priority plus how long it has waited, capped at five points - and it measured that wait against the
database's `now()`, rendered in the JDBC session's zone, while `created_at` holds local time in the
zone the application is configured with. Wherever the two differ, every pending row was credited
with the offset as waiting it had not done, and the cap turned that into an inversion: a row already
at five gained nothing from the phantom hours while a young one pocketed all of them. Measured on a
UTC machine against a Sao Paulo setting, a request created seconds earlier at priority two scored
5.0000083 against 5.0 for one that had genuinely waited five hours. The age is now measured against
the same `:now` the rest of the statement already binds - the application's clock, the one that wrote
the column.

**Nine test classes were reading a different clock than the code they test.** They compared values
the application had written through its configured-zone clock against `LocalDateTime.now()`, which
reads the JVM's zone. On the developer's machine the two coincide and everything passed; on any
runner they do not, and a fresh lease looked hours expired while an expired one looked fresh - which
is what twenty of the twenty-one failures on the Linux pipeline were. Four more were Windows-shaped
path literals that only describe a path on Windows: normalised on Linux they become a relative
segment under the working directory, so a folder repoint matched no row at all. Those fixtures now
stand on a real temporary root.

**The global progress bar stopped lying, in five kinds of execution at once.** A dataset update
read "3 of 3, 100%" while a second bar underneath still moved - and the cause was not geography. The
first bar is `filesFound / totalExpected`, computed once, and five producers were writing the total
into the counter it divides: the bar was full from the first item on. The conversion did worse,
alternating between the total and a real count, so it went backwards between one file and the next;
the location rebuild did the opposite, writing a constant zero that never moved at all. Each is now
the count of items actually concluded, and a contract test replays those readings through the real
formula to hold it: nothing starts full, nothing goes backwards, a hundred per cent arrives only
with the last item. The dataset update models its own pipeline as nine fixed stages - three
acquisitions, three imports, territories, publication, finish - so a stage that costs no time still
names itself and still counts, and the run reaches nine of nine only after the last functional work.
Where nothing can be measured the second bar is taken away rather than parked at zero.

**A folder that arrives is a change, and a change already asked for outlives a queue that refused
it.** Two ways the watcher could lose a filesystem change outright, both closed here and both held
by a test that fails without the fix. A folder moved into the library from outside arrives already
full: its files were never created under a watched directory, so on the portable `WatchService`
source not one of them raised an event, and nothing went looking for them afterwards - the
reconcile retires what left, it never catalogues what arrived. The folder's own arrival is the
notice now, which is what the Windows sources already reported and why they screen nothing. And the
pending a change raises is no longer cleared before the work is on the queue: an enqueue that failed
used to leave the flag down and the file uncatalogued until something else happened to touch it. The
same slice made the depth setting part of the identity the poll follows, which is prevention rather
than repair - every writer of it already reconfigures, but the folder is written from the worker and
the day the depth follows it, a poll comparing only the folder would notice nothing. Ten tests, one
of them moving a full folder against the real `WatchService` and waiting for the event that used to
never come.

**That drift was measured directly this time rather than inferred.** Two clean runs of one unchanged
tree, minutes apart, read 98.62/92.83/98.11/99.00 and 98.60/92.83/98.08/99.00: three hundredths of
spread on instruction and on line, from nothing but which shared paths the parallel classes happened
to exercise. Branch held still across both, and what moved it afterwards was the fencing tests
themselves.

**Nothing was lowered and nothing was written to move a number.** The first reading of this slice
came in 0.02 under the instruction floor, and it was not drift: three paths the rebuild rework added
had no test at all - dropping the debts of files that stopped being candidates, on each medium, and
the video reader's rebuild branch. They were closed where they were opened, and the reading went
back over the floor. Each of the three states a behaviour worth holding - which target a rebuild
asks about, and which question it asks - rather than existing to move a percentage.

**A rebuild of fingerprints no longer empties the library to start.** It used to delete every
fingerprint of an algorithm, because "still to do" meant "has no row", so a run interrupted after
that delete left the library without an entire algorithm until something recomputed it - and every
consumer read the remains as the truth. What a rebuild writes down now is what it owes, and each
file is replaced in a transaction of its own together with the conclusions drawn from the hash it
replaces. An interruption leaves a library that is part old and part new; a later run picks the work
up from the database alone, without knowing which execution began it.

**The startup no longer inventories the library twice.** When the watcher adopts a folder it runs a
USN journal replay of the window the application was down for and, in the same breath, starts a walk
of the whole tree - and the changes the replay recovered were then queued as if they were news,
costing a reconcile and a second full pass that between them catalogued nothing. The adoption now
hands that backlog to the walk it is starting, which is the one thing that already covers it. The
hand-over is undertaken where both facts are known and is withdrawn if it stops being true: a live
notification during the walk, a journal that could not be replayed, a path that is no longer on disk
for a walk to find, or an inventory that ended as anything other than finished all put the ordinary
recovery back. Seventeen tests hold each of those apart, and the reading above is measured with them
in.

**The watcher's startup was taken apart in the same pass.** A journal cursor that only ever moved
when the change source was built left every restart replaying a window as wide as the previous
uptime, which regularly could not be replayed and ended in a reconcile and a re-inventory that
found nothing; the cursor now advances to a watermark read before each full inventory and stored
only once that inventory has finished. The folder is adopted once instead of twice, and the three
reasons a source can ask for recovery are told apart rather than all reported as an overflow. The
tests that prove it wait for conditions instead of sleeping: one `Thread.sleep` is left in the
suite, in the benchmark, where elapsed time is the thing being measured.

**A worker can now die at any point of its lease.** Recovery of abandoned executions used to run
only when a process started, so a worker killed with minutes still on its lease left a row RUNNING
that nothing would ever look at again - and a row that reads as running is what tells the rest of
the product the system is busy. It runs on a timer now, the same rule the start of a worker runs,
and within one renewal interval of a lease lapsing. Making it safe to run continuously was the
larger half: every recovery write now carries the condition it was decided on, so a lease renewed
between the reading and the write keeps its owner, and a completion or a cancellation that lands
first is never overwritten. And ownership became something a write is checked against rather than
something checked once at the start - a worker that lost its turn, even one that takes the same row
again a moment later under the same name, cannot finish, fail, release or report progress on the
run that replaced it. Method coverage crossed 99% for the first time; line sits exactly on its
floor.

**Branch reached its goal and a new one was set.** 92.67 measured against a goal of 92.50: the goal
becomes the floor (drift-adjusted, at 92.51) and 93.00 takes its place, which is what the ratchet
does when a target is met rather than leaving a goal nothing has to clear.

Moving the
writers to the worker added code whose remaining gaps are the residue the policy already accepts -
private anti-instantiation constructors, which may not be covered by reflection, and failure paths
that need the operating system to refuse something. That residue alone is larger than the shortfall:
36 methods are uncovered and the shortfall is one. It closes and reopens by a
few units as each slice removes a fully covered bean along with the workload that used it, which
lowers a percentage without anything having gone untested. Lowering the floor needs
the *Recalcular o piso* procedure, which is a deliberate decision rather than a side effect of a task,
so it is tracked as an open item in [`docs/backlog-operacional.md`](docs/backlog-operacional.md)
instead of being resolved by editing the numbers here.

**Branch clears the floor and the floor was not raised with it.** Retiring the last in-memory
liveness check and then closing the claim-to-attempt window left branch six hundredths above the
floor - well within the width
of the drift this suite is known to have between runs (*A medição varia entre execuções* in `AGENTS.md` records up
to 0.16 on branch, from parallel test classes and self-skipping tests). Raising the floor to the top
of that band would turn ordinary noise into a red build on the next task, which is the opposite of
what a ratchet is for. It rises when a run clears it by more than the drift.

**Videos went incremental, and method came in two hundredths short.** The slice that gave videos durable
relations, coverage, REGROUP and ADD - and removed their candidate cap - added 3603 tests in total and
cleared the floor on instruction, branch, line and class. Method measured 98.92 against a floor of 98.94:
two hundredths, inside the drift this suite is documented to have on the non-branch metrics. The JaCoCo
report was read per class before that was accepted as noise rather than regression, and the new classes
carry no uncovered method - the one that was uncovered, `VideoSimilarityService.analysedThresholds()`,
gained a test of the behaviour that reaches it. The floor is not lowered and the shortfall is recorded
rather than rounded away.

**The video relation builder cleared the floor without moving it.** Proving that the two video
groupings agree added 29 tests and two production classes - the video producer of relations and the
bookkeeping both producers now share - and every one of their lines is covered, so instruction and
line each came in three hundredths above the floor. Three hundredths is exactly the drift this
suite has on those two metrics, so the readings are recorded and the floor is left where it is, by
the same rule branch was left by above. Branch, method and class did not move at all.

Instruction, line and method were recalculated again when the application became a producer and a
supervisor. Branch rose. What is left uncovered in the new code is the one method that starts the
worker process: it can only be exercised by starting a JVM, which is the same reason the project
already excludes `**/*ProcessRunner`. The command line it builds - the profile and flags that make
the second process a worker - is asserted without starting anything, and every other class the change
added is fully covered.

Instruction and line were recalculated down by three hundredths when the role profiles arrived. The
cause is what is measured, not what is covered: the composition tests start a context per role, and a
role that leaves a bean out still loads the configuration class that decided to. Those classes joined
the denominator without any of them being new code that went untested - every class added in the same
change is covered, and the reduction reproduces exactly across clean runs rather than drifting.

Method rose and line was recalculated downward by a tenth when the update domain arrived - the
check, the download, the verification and the installer that ends the run. The order *Recalcular o
piso* asks for was followed: the honest harvest came first, and it came from anywhere in the
project rather than only from the new code. In the new domain it covered the release document in
every shape a server can answer with, the installer being refused when its bytes disagree with the
published hash, and both HTTP adapters against a real server on the loopback interface. In old
code it covered three paths that had never been exercised: changing or resetting the password of
an address that belongs to nobody - which has to be refused by the lookup rather than by the
password check, or the answer would tell a registered address from an unregistered one - and
restoring a file whose catalog record was purged between the listing and the click. That harvest
carried instruction back to its floor and pushed method above it.

What it could not reach is 13 lines and 42 instructions of residue: the `catch (InterruptedException)`
of two HTTP calls, which needs the thread interrupted mid-request; the `catch` for SHA-256 being
absent, which the platform guarantees it is not; the I/O failure paths of re-reading and deleting a
file that was just written; and the anti-instantiation guard of a utility class, which *Piso de
cobertura* forbids covering by reflection. `SpringApplicationShutdown` is out of the measurement
under `@CoverageGenerated`, because its two methods start the thread that ends the process, and in
a test the process is the suite - what *decides* to end the run stayed in `UpdateInstallService`
and is asserted there, in both directions.

Earlier, instruction, branch and line were recalculated downward by a hundredth each, and no code lost
coverage to earn it: the tool paths leaving the catalog **deleted** a fully covered class
(`ExternalToolPathRefresh`), a properties record, a seeding helper and the eleven tests that
exercised them. Removing code that sat above the project average lowers the average, which is the
denominator moving rather than a regression. It was verified class by class in the JaCoCo report
before the floor was touched — every class the change touched is at zero missed instructions and
zero missed branches, except two gaps that predate it: the `catch (InterruptedException)` of
`FfmpegBuildSource`, which only a thread interrupted mid-download reaches. The one branch the
change did add uncovered — a download URL that is null rather than blank — was covered instead of
declared, since an address the configuration never declared has to be refused with a reason, and
that is worth asserting on its own. Two consecutive clean runs of the same code returned the same
reading, so this is not the between-run spread described below.

Instruction and branch rose; line and method held. The tray arrived as 272 uncovered instructions
of AWT glue — there is no `SystemTray` on the headless CI, so every call there is a no-op — and
that glue is now excluded by package, the same treatment the process runners and the native FFM
code already get. What is not excluded is the part that decides anything: the menu's words and the
address it opens live in `TrayText`, which is unit-tested, including the two ways it refuses to
guess — a key that is not in the bundle, and a bundle that is not on the classpath.

That still left instruction a hundredth short, and the missing hundredth was real: seven
instructions of a `catch` that only a broken classpath stream reaches. *Recalcular o piso* asks for
the honest harvest first, so it was taken before touching the floor, in the quarantine listing that
had the two branches worth asserting — a file whose name carries no extension is offered no preview
rather than a lightbox with nothing in it, and a recorded original path with no folder above it is
refused with a reason instead of restoring into nowhere.

Branch and line keep the floor a few hundredths under the reading, set from the lower of two
consecutive runs of the same code (98.06 and 98.08 for line): branch is the metric that moves most
between runs, and pinning either floor to the higher reading would fail the next task for no reason
— see *A medição varia entre execuções* below for where that spread comes from.

Branch, method and class rose; instruction and line were recalculated downward, by 0.03 and 0.05,
after the embedded-database and backup domains landed. The rule that allows this is *Recalcular o
piso* in `AGENTS.md`, and it was applied in that order: coverage was first harvested wherever it
could be earned honestly — the account lock refusing a blank username, a destination name that
climbs out of the target folder, a boundary name already in real Unicode, a catalog record deleted
between reconcile and pairing, an inventory that hands its folder lock back when the scan cannot
start, a geographic import keeping the reason it failed, and the one statistics endpoint missing
from the delegation sweep. That pass moved branch and method above the old floor and covered 16 of
the missing lines; what it could not reach is what the floor was then set to.

Of the 237 lines still uncovered, 141 are I/O `catch` blocks, 28 are the anti-instantiation guards
of utility classes, and the remainder is largely unreachable by construction — a `continue` or
`break` the compiler reaches only through a condition that cannot occur, a symlink branch that
needs the operating system to refuse something. 29 of the 237 are in the two newest domains. None
of it is coverable by a test that asserts real behaviour, and the alternative — instantiating
private constructors by reflection — is what *Piso de cobertura* forbids by name.

The goals above are the next step the ratchet asks for, set from the numbers actually
measured rather than from a round figure: the previous branch goal had been lowered
from 95% to 90% precisely because a target nobody can reach orients nobody, and 90%
landed two passes later. All five metrics had reached their goal once, before the
embedded database and the pg_dump backup arrived — the two features that put the most
untestable I/O in the project.

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
Line coverage for mutated classes: 11710/12095 (97%)
Generated mutations:              6784
Killed mutations:                 5701
Survived mutations:               932
No coverage:                      126
Timed out:                        25
Run error:                        0
Mutation score:                   84%
Test strength:                    86%
Duration:                         29m39s
```

Where the survivors are: by a wide margin the most common surviving mutator is
`VoidMethodCallMutator` - a call whose removal no assertion notices, typically a
progress update, a log-adjacent notification or a cache invalidation that the test
exercises without checking. Negated conditionals, conditional boundaries and math
follow it, clustered in the paging and percentage arithmetic. The largest blocks of
*no coverage* are the fingerprint backlog drain and the JDBC
URL parsing the backup relies on - production code that the integration tests do
cover and this profile excludes, which is the effect described above rather than a
hole in the suite.

Reports:

```text
target/site/jacoco/index.html
target/pit-reports/index.html
```

### Bytecode and security analysis

Run SpotBugs with the find-sec-bugs detectors:

```bash
./mvnw -Pspotbugs verify
```

Sonar reads the source; this reads the bytecode. What justifies it here is find-sec-bugs —
taint analysis for path traversal, injection, weak cryptography and XXE, over an application
that takes paths from the user, runs external processes and downloads and verifies files.
The `check` goal fails the build on the first finding, so the profile is expected to stay
green; findings are settled by fixing the code or by an entry in `spotbugs-exclude.xml` that
carries its reason. See *Bytecode analysis* in `AGENTS.md` for the policy and
`docs/adr/0002-analise-de-bytecode-com-spotbugs.md` for what the first run measured.

Report:

```text
target/spotbugs.html
```

## License

Nimbus File Manager is source-available under the
[PolyForm Noncommercial License 1.0.0](LICENSE):

An [unofficial Brazilian Portuguese translation](LICENSE.pt-BR.md) is available
for convenience. The English license remains the controlling text.

- Personal, noncommercial use is permitted.
- Use by charitable organizations, educational institutions, public research,
  public safety or health organizations, environmental protection organizations,
  government institutions, and autarchies is permitted.
- Commercial use, including use in a business and commercial competing products
  or services, requires a separate paid commercial license.

Commercial licensing inquiries may be submitted through the project
[repository](https://github.com/JorgeFrancisco/Nimbus-File-Manager).

This is a source-available license, not an OSI-approved open-source license.
Copies and versions released before August 3, 2026 remain under the MIT terms
that accompanied them. The relicensing does not revoke permissions already
granted for those earlier versions.

## Author

Jorge Francisco

GitHub: https://github.com/JorgeFrancisco