# Code Rules — Nimbus File Manager

> **English translation of `AGENTS.md`.** The pt-BR original is normative: where the two
> disagree, `AGENTS.md` wins. This file exists for contributors who do not read Portuguese.
> It is deliberately not referenced by `CLAUDE.md` — the agent reads the original, so the
> instruction context never carries the same rule twice.
>
> Kept in step by `AgentsTranslationTest`, which fails the build when `AGENTS.md` changes
> and this file has not been retranslated. Update both in the same commit, and refresh the
> marker below with the hash the test reports.

<!-- agents-sha256: 27309c185ff5f67db45e8897f03a4618e47c360d1be5a1ba4b0794fee79088ce -->

> **Permanent reference document of the project.**

This document defines the permanent development policies of Nimbus File Manager. Its purpose is to keep architectural consistency, quality and predictability, and to make review — by humans and by AI — easier.

It holds only **permanent** rules, derived from the real code rather than from personal preference. Information that changes often (metrics, coverage, version, features, stack) belongs in the README, never here.

## Document hierarchy

On conflict, this order prevails:

1. `editor/.editorconfig` — mechanical editing rules.
2. The Eclipse formatter (`.settings/org.eclipse.jdt.core.prefs`) — Java formatting.
3. This document — permanent development policies.
4. ADRs — specific architectural decisions, in `docs/adr/` (one file per decision, e.g. `docs/adr/0001-title.md`).
5. README — the current state of the project (metrics, coverage, features, stack, requirements).

A **new rule that conflicts with the current code** has to be decided explicitly before it enters — either the code adjusts to the rule, or the rule records the exception.

`AGENTS.en.md` is a **translation of that document**, not a document of its own: it does not enter the hierarchy above, and where the two diverge `AGENTS.md` prevails. It exists for those who do not read Portuguese, and for that reason it is **not** included by `CLAUDE.md` — the agent reads the original, and the instruction context never carries the same rule twice. Both change in the same commit; what enforces that is `AgentsTranslationTest`, which breaks the build when `AGENTS.md` changes without the translation following.

---

# Principles

- Clarity before cleverness.
- Simple code beats complex code.
- Safety before performance; performance before micro-optimisation.
- Testability is part of the design.
- Avoid duplicated logic.
- Comments explain the **why**; the code explains the **how**.
- Every rule is born of a recurring problem, derived from the real code — not from personal preference.

---

# Code style

## Scope

The rules apply equally to **production** (`src/main/java`) and **tests** (`src/test/java`). Formatting, vertical spacing, imports and names apply to test classes without exception.

## Mechanical rules (`editor/.editorconfig`)

The editor configuration artefacts live in `editor/` (outside the root, as a versioned reference — because it sits in a subdirectory, the `.editorconfig` is **not applied automatically** to `src/`: it documents the rule, and what makes it binding is this document): `editor/.editorconfig` (mechanical rules, the canonical source) and `editor/eclipsejava.importorder` (the *Organize Imports* order). Summary of `editor/.editorconfig`:

- UTF-8 encoding; CRLF line endings; **no final newline** (`insert_final_newline = false`); trailing spaces removed (except in `.md`).
- Indentation: **tab** in `java`, `xml`, `html`, `css`, `js`; **space** in `sql` (4), `json`/`yml`/`yaml` (2) and `md` (2).
- Maximum width of 120 columns in Java only.

**No final newline — the last byte of the file is the `}`, not `\n`.** The rule is `insert_final_newline = false`, and it applies to the end-of-line **character**, not only to visibly blank lines: a file ending in `}\n` **breaks** the rule just as much as one ending in `}\n\n`. In `git diff` conformity shows up as `\ No newline at end of file` on the last line. Blank lines **between** blocks, those are part of the style (see Vertical spacing).

**Java text blocks (`"""`):** the trailing-space and indentation rules **do not apply inside** a text block. The content between `"""` is significant and governed by Java's own rules (incidental whitespace / `\s`), not by the editor. Automatic trimming or reindentation must **preserve the interior — never rewrite it**. Text blocks are used heavily in the repository queries (`@Query("""…""")`), so a naive line-by-line trim would corrupt the SQL.

**Careful when detecting a text block by scanning:** a delimiter **closes and reopens on the same line** — `""", countQuery = """` (used in `MediaFingerprintRepository` and `MapRepository`). A scan that treats "any `"""` on the line" as the end of the block **inverts the parity** from there on, treating query interiors as code (and code as interior). The correct state **toggles on every occurrence** of `"""`, not once per line. Reindenting a query line by mistake inserts a **literal tab** into the string and raises `java:S2479`.

## Formatter (Eclipse, Ctrl+Shift+F)

Mechanical Java formatting is the **exclusive** responsibility of the Eclipse formatter (Ctrl+Shift+F), configured in the project itself in `.settings/org.eclipse.jdt.core.prefs` (`org.eclipse.jdt.core.formatter.*` keys, versioned with the code) and consistent with `editor/.editorconfig`. Specifics worth knowing while writing:

- **Code at 120 columns**, counted **from column 0** with **tab = 4**.
- **Comments at 80 columns**, counted **from the column where the comment starts** — the `/` of `//`, `/*` or `/**` — and **not** from column 0 (this is Eclipse's `count_line_length_from_starting_position`). See *The 80-column comment limit* below: the two widths use different origins, and confusing them is the most common cause of a wrong scan.
- Continuation: wrapped lines indent **2 levels** (tabs).
- K&R braces: `{` at the end of the line; `} else {` and `} catch` on the same line as the `}`.
- At most **1** consecutive blank line; **1** before each method; **none** between consecutive fields; imports in groups separated by 1 blank line.
- The formatter does not insert a line at the end of the file (matching `editor/.editorconfig`).
- **One annotation per line** (`alignment_for_annotations_on_type=49`: one per line, forced). What decides this is the *alignment* key, not `insert_new_line_after_annotation_on_type` — the latter says what comes after an annotation, the former says how many fit on a line. With the default `0` (do not wrap), Eclipse rejoined `@Slf4j` and `@Service` on every Ctrl+Shift+F even with `insert_new_line_*` set to `insert`, and touching `join_wrapped_lines` does not help (it was tried and had no effect).
- **Annotation arguments are wrapped** (`alignment_for_arguments_in_annotation`). Without that key Eclipse uses its default — *do not wrap* — and an OpenAPI `@Operation(summary = …, description = …)` becomes a 200+ column line on every Ctrl+Shift+F, undoing any hand-made wrapping.

> **Where the formatter is configured:** in the *project scope*, in `.settings/org.eclipse.jdt.core.prefs` — the `org.eclipse.jdt.core.formatter.*` keys Eclipse applies on top of the workspace profile, with the **complete** set written down (via *Properties → Java Code Style → Formatter → Enable project specific settings*), so that no key depends on the machine of whoever opens the project. These files are **versioned by an explicit exception** in `.gitignore` (`.settings/*` ignored, with `!` for `org.eclipse.jdt.core.prefs`, `org.eclipse.jdt.ui.prefs` — the `formatter_settings_version` — and `org.eclipse.core.resources.prefs`, which pins the UTF-8 of the encoding rule); the rest of `.settings/` stays out, being generated by m2e. **A missing key falls back to the workspace profile** — that is how long annotations came to break the 120 columns.
>
> **Careful when rewriting through the dialog:** Eclipse rewrites the whole file from the "Unmanaged profile" and, on that path, resets `comment.count_line_length_from_starting_position` to the default `false` — which would change the comment limit from 80-from-the-comment to 80 absolute and reflow every indented Javadoc in the project. After touching the dialog, **check that it went back to `true`** (that is the regime the code is in: comments at 1 tab reach 84 absolute columns, at 2 tabs 88).
>
> There is no formatting plugin in `pom.xml` (neither `formatter-maven-plugin` nor `spotless`): **nothing in the build fails on formatting**, which makes the manual check (see *Mechanical verification*) mandatory.

### The 80-column comment limit

The 80-column comment limit is measured **from the starting column of the comment itself**, with **tab = 4**. A Javadoc indented by 1 tab can therefore reach **84 absolute columns** without breaking anything — measuring from column 0 produces hundreds of false positives, and was the cause of previous scans getting it wrong.

In character budget, the limit works out to:

| Form | Prefix | Maximum text |
| --- | --- | --- |
| Javadoc/block body (` * text`) | `* ` | **77** characters |
| Line comment (`// text`) | `// ` | **77** characters |
| Single-line Javadoc (`/** text */`) | `/** ` + ` */` | **73** characters |

Reflow rules, all observable in the already-formatted code:

- The paragraph is **reflowed as a whole, greedily** (each line takes as many words as fit) — it is not enough to break the line that overflowed: the break **propagates** to the end of the paragraph.
- These are **paragraph boundaries** (they never join the neighbouring text): the blank `*` line, `<p>`, `<ul>`/`</ul>`/`<ol>`/`</ol>`/`<li>`, and each block tag (`@param`, `@return`, `@throws`, `@see`, …).
- The inline tags `{@link …}`, `{@code …}` and `{@literal …}` are **atomic units**: they fit entirely on the line or move entirely to the next.
- The interior of `<pre>` and `{@snippet}` is **verbatim** — never reflow it.
- A single-line Javadoc that overflows becomes a multi-line block; the reverse path **does not** exist (the formatter never collapses a block into a one-liner).

### The 120-column code limit

Measured in **absolute columns from column 0**, with **tab = 4**. Wrapping uses a continuation indent of **2 tabs**, filling each line to the maximum.

These are **legitimate exceptions** (the formatter does not wrap them; leave them as they are):

- The **interior of text blocks** (`"""…"""`) — already exclusively Java territory, see above.
- `import` / `import static` lines.
- A line whose width comes from **a single indivisible token** — a long `String` literal, an annotation with a long `description`/`example` — where no possible break would bring the line within 120.

Outside those three, a line above 120 is a violation and must be wrapped.

## Mechanical verification

A mechanical rule is not verified by eye — that is exactly where previous scans failed. Before closing any task, **measure**, across the **whole project** and not only the files touched, and reach zero on each item:

1. **Final newline** — no file ends in `\n` (the last byte is the content's). **Non-authored files are out of scope**: `mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` (generated by the Maven Wrapper) and `LICENSE` (verbatim legal text) — the project style does not rewrite third-party files.
2. **Comments** — no comment above 80 columns **measured from the start of the comment**, except the interior of `<pre>`/`{@snippet}` (verbatim by definition).
3. **Java code** — no line above 120 absolute columns outside the three exceptions above.
4. **Trailing spaces** — zero, except in `.md` and inside text blocks.
5. **Line endings** — CRLF.

Items 1, 4 and 5 apply to **every kind of file** (`.java`, `.html`, `.css`, `.js`, `.sql`, `.xml`, `.json`, `.yml`), through the `[*]` section of `editor/.editorconfig`; items 2 and 3 are Java only.

## Vertical spacing

Not expressible in `editor/.editorconfig` nor reliably enforced by a formatter/linter — it is a project convention, checked in review:

- One blank line **between groups of logical steps** inside a method. Strongly related statements (one same step) stay **together**; the blank line separates one group from the next.
- One blank line **before the `return`** when there is a distinct preceding step; **not** when the `return` concludes the same logical group.
- **Kept together** (no blank line between members) are only statements of the **same family/type**: a group of `when(...).thenReturn/thenAnswer`, a group of `verify(...)`, a group of `assertThat(...)`/`Assertions.*`, **variable declarations of the same type** (e.g. two `byte[]`, two `PhotoPerceptualFingerprint`), or **repetitions of the same operation** (e.g. two `service.compute(file)`). **Between groups, one blank line** — each distinct family/type/logical step separates, **including inside the arrange** (creating the file, building the input and constructing the service are separate steps, since they have different types/purposes). Different families of verification also separate from each other — e.g. a group of `assertThat(...)` and a group of `verify(...)` take a blank line between them.
- The rules above apply to **normal blocks** (method body, `if`/`for`/`while`/`try`). **Skip** the interior of **lambdas**, **switch expressions** (`case X ->`) and **multi-line expressions**: there the statement is part of a cohesive construct.
- One blank line **after the `{` that opens the class**, before the first member; there is **no** blank line after the `{` that opens a method. Records and enums follow the same rule when they have a body (including enums that only list constants).
- **No** blank line before the `}` that closes a method or the class.
- One blank line **between members** (methods, constructors, groups of fields).
- **Never** two consecutive blank lines.
- Imports in groups separated by one blank line (statics first), keeping the ordering Eclipse generates.

## Conventions

- Identifiers, comments and Javadoc in **English**. In documentation, the language follows the reader the document addresses: **pt-BR** in the ADRs and in the documents under `docs/`, which record internal decisions and discussion; **English** in the README, the front door of a distributed product. This document is pt-BR and normative, with a translation in `AGENTS.en.md` (see *Document hierarchy*). A new document picks one language and does not mix.
- **Constructor dependency injection** (`@Autowired` on the constructor), `private final` fields.
- **At most 7 parameters per method or constructor** (Sonar rule S107). When that limit is exceeded, group cohesive data parameters into a *Parameter Object* (`record`/DTO) or reconsider the responsibility of the class/method. For dependency-injection constructors, prefer splitting the class's responsibilities over wrapping dependencies in an object.
- **No nested types.** `class`, `record`, `enum` and `interface` (functional or not) are declared in their **own file (top-level)**, never nested inside another class. A *Parameter Object* extracted to resolve the item above is also born in its own file.
- **Smallest possible visibility.** Every type, method, field and constructor has the **smallest visibility that serves the real usage**: used only in its own class → `private`; only in its own package → package-private (no modifier); `protected` only where there is real inheritance; `public` **only** when there is legitimate cross-package usage (or a framework/override requirement — `@GetMapping`/`@Bean`/`@Override` handlers, `record`/`@Entity` accessors, `@ConfigurationProperties` binding, `@Test`). **Never widen visibility to accommodate a test or a misplaced dependency** — co-locate the test in the target's package (using package-private access) or fix the layer, instead of going `public`. **Do not change the access modifier without real need:** when moving/refactoring/renaming, **preserve the original visibility**; change it only when the legitimate usage actually changed, and then in the direction of **restricting**, not widening. A `public` referenced only within its own package is debt to fix. **Exception: data constants** — see the rule below.
- **Data constants in a `<Domain>Constants` class.** Data constants (`static final` `String`/numeric/`boolean` — preference/setting keys, page identifiers, limits, messages, well-known names) do **not** sit inline in the behaviour class (service, controller, component, advice, helper). Each domain has a `<Domain>Constants` class (general) in a `<domain>/application/constants` package (e.g. `organization/application/constants/OrganizationConstants`), symmetric to `<domain>/application/dto`, with **all contract constants `public`**, referenced by `import static`. A domain **may have more than one holder** in that same `constants` package when the constants form a cohesive, self-describing group — e.g. `ExecutionMessages` (message keys), `WorkspaceFolders` (folder names), `FingerprintAlgorithm` (algorithm ids), `UsnReason` (reason codes) — instead of a grab-bag inside `<Domain>Constants`. **Every constant holder lives in `<domain>/application/constants`.** Exception: **native protocol** constants tied to infrastructure glue (e.g. `WindowsUsnConstants`/`WindowsRdcwConstants`, on the FFM/kernel32 side) stay co-located with the native code in `infrastructure/**/windows`, and do not move up to `application/constants`. This gives constants a single, predictable home, prevents a constant from being "made `public` out of necessity" inside a controller/service (which used to create cross-feature coupling), and keeps behaviour classes focused on behaviour. A consumer in another domain references `SettingsConstants.WATCH_FOLDER`, not the owning behaviour class. **Not domain constants** (and they stay where they are): `LOGGER`/`serialVersionUID`; `@ConfigurationProperties` binding values; `enum`/`record`/`@Entity` constants; bean names/`@Qualifier` of a `@Configuration` (framework contract, in its natural owner); and a **`private` constant no other class reads** — an algorithm parameter declared next to the formula that uses it (`C1`/`C2` of SSIM, the Earth's radius, the sample side), a buffer/batch size, a timeout, a tolerance, the name of the handler's own view or redirect, and the delay/period of a `@Scheduled` on the bean itself. The test is **visibility**: if it is `private` and nobody outside the class (not even the test) references it, it is not a contract, it cannot create cross-feature coupling — which is the problem this rule exists to prevent — and moving it to a holder would only take the value away from the one place that explains it. **A `package-private` or `public` constant read by another production class is still a violation**: it is a contract and its place is the holder. `package-private` read only by the **co-located test** remains the accepted pattern (it is what the visibility rule tells you to do instead of widening to `public`); and if nobody reads it from outside, the right answer is `private`. Cross-cutting constants (used by `shared` infrastructure) go to `shared/application/constants/SharedConstants`.
- **Imports always organised** before finishing the task. Remove every unused import, add the necessary ones, and organise the imports to produce exactly the same result Eclipse (*Organize Imports* / Ctrl+Shift+O) would generate, honouring the project configuration in `editor/eclipsejava.importorder` (groups `java`, `javax`, `org`, `com`, and finally the rest — `br.com.*`, `jakarta.*`, `lombok.*`… — in a single alphabetical group; statics first; groups separated by one blank line). Never leave unused imports, and never use an ordering different from the one Eclipse generates.
- **No fully qualified names inline:** reference types and static members by their simple name with an `import` (e.g. `AtomicBoolean`, not `java.util.concurrent.atomic.AtomicBoolean`; `doThrow(...)` with `import static`, not `org.mockito.Mockito.doThrow(...)`).
- **Test names** in camelCase describing the behaviour verified (e.g. `raisesWhenAKeyIsAbsentFromTheBaseBundle`).
- **Unnamed variable (`_`) for an unused binding.** Every lambda parameter **not referenced** in the body uses the unnamed name `_`, never a real name that is then ignored (`e`, `x`, `ignored`) nor a `_`-prefixed one (`_x`). The same rule applies to the other unused bindings: the `catch` variable, pattern components (`instanceof`/`switch`) and the `for` variable. E.g. `(_, _) -> {}`, `.map(_ -> Optional.empty())`, `catch (IOException _)`. The project uses Java 25, which supports `_` fully, so the parameter becomes self-explanatory (it signals "deliberately unused") and raises no unused-variable warning. When **more than one** unused binding coexists in the same scope, all of them are `_` (the language allows repeating `_`).
- **Javadoc** only to explain the *why* of non-obvious decisions, never to repeat the obvious.

---

# Architecture

The code is **grouped by domain** (`catalog`, `inventory`, `organization`, `duplicate`, `metadata`, `geolocation`, `media`, `execution`, `security`, `timeline`, `settings`, `thumbnail`, `processing`, `quarantine`, `conversion`, `map`, `telemetry`, `statistics`, `preferences`, `notification`, `time`) and, **inside each domain**, separated into **hexagonal layers** (ports & adapters). The bootstrap (`NimbusFileManagerApplication`) sits at the root of the package.

## Layers per domain

Each domain `<d>` is organised into three layers:

- **`<d>/domain`** — the business core. `domain/model` (`@Entity` entities + value objects), `domain/enums` (domain enums) and `domain/repository` (Spring Data interfaces = **ports**, with `domain/repository/projection` for the projections those ports return).
- **`<d>/application`** — use cases and orchestration. Services, coordinators, runners and rule helpers (`resolver`, `rule`, `batch`, `watch`, `explorer`, `fingerprint`…), plus **`application/dto`** (every data DTO/record of the domain — request, response, view, raw).
- **`<d>/infrastructure`** — adapters. `infrastructure/rest` (REST controllers), `infrastructure/web` (MVC controllers + view-models), `infrastructure/persistence` (custom JDBC repositories — concrete adapters with no Spring Data interface) and `infrastructure/config` when the domain has its own `@Configuration`/`@ConfigurationProperties`. External glue (ProcessRunners, FFM/native, HTTP adapters, mail providers) lives in `infrastructure`.

**Direction of dependency (inviolable):** `infrastructure → application → domain`. The `domain` knows nothing of the delivery framework or of adapters; the `application` knows nothing of `infrastructure`. That is why the projections returned by a port live in `domain/repository/projection` (never in `infrastructure`).

## Shared kernel (`shared`)

**Cross-cutting** model and adapters (used by ≥3 domains, or with no single owner) live in `shared`, with the same layer structure: `shared/domain/model` (e.g. `CatalogFile`, `Execution` and family, `Movement`, `Photo`, `Video`, `MediaMetadata`, `StatusMessage`), `shared/domain/enums` (e.g. `FileType`, `LifecycleStatus`, `ExecutionStatus`), `shared/domain/repository` (ports over kernel entities, e.g. `CatalogFileRepository`, `ExecutionRepository`), `shared/application/dto` (generic DTOs such as `PagedResponse`, `SizeResponse`) and `shared/infrastructure` (global advices/handlers, bootstrap config). General utilities (`util`, `i18n`, `concurrent`) live in `shared`. **Domains depend on `shared`, never the other way round.** An entity/enum with a single owner stays in the owner's `domain`; it only moves up to `shared` when it really is cross-cutting.

## Controllers (`infrastructure/rest` · `infrastructure/web`)

- They receive requests, validate input and orchestrate calls; they **never** implement business rules.
- Global cross-cutting advices/handlers (`RestExceptionHandler`, `AppViewModelAdvice`) live in `shared/infrastructure`. Since there is no longer a single `api`/`web` package, the `basePackages` of those advices/handlers **list explicitly** the `*.infrastructure.rest` (REST) or `*.infrastructure.web` (MVC) packages. When creating a domain with a new controller, **include its package in that list** — otherwise the advice/handler stops firing for it.

## Services (`application`)

- They concentrate business rules and coordinate transactions; they know nothing of the delivery layer.

## Repositories (`domain/repository` · `infrastructure/persistence`)

- Data access exclusively; no business rules (see Persistence).

## Entities (`domain/model`)

- They represent persistence; avoid complex logic. Excluded from coverage via `**/domain/model/**`.

## DTOs (`application/dto`)

- Data transport; no behaviour.
- **Every DTO without logic** (a record/class that is purely data) **lives in the `application/dto` package of its domain** (generic ones in `shared/application/dto`). It stays outside the coverage measurement (JaCoCo excludes `**/dto/**`), avoiding charging coverage for generated accessors/records. A Parameter Object extracted because of rule S107 is also born in that package.

## Configuration (`infrastructure/config`)

- **Functional configuration, grouped or belonging to a Nimbus File Manager namespace** (`nimbus-file-manager.*`) must use `@ConfigurationProperties` — a typed class/record, registered in `@EnableConfigurationProperties`, injected by constructor (e.g. `NimbusFileManagerProperties`, `BoundaryDatasetProperties`, `InventoryWatchProperties`). A dedicated class when the namespace is its own; a component of the aggregate when it fits an existing record. Typed binding centralises defaults, validates at start-up and preserves testability (the test builds the properties with no Spring context). Spring wiring (bootstrap config) and the aggregate properties live in `shared/infrastructure/config`.
- **`@Value` is only acceptable** for isolated infrastructure values or native Spring properties, **when a dedicated class would bring no real gain in cohesion or testability**.

---

# Hexagonal architecture and abstractions

Hexagonal architecture is to be applied **pragmatically, not ceremonially**.

- **Domain isolation.** No class in `**/domain/**` depends on `**/application/**`, `**/infrastructure/**`, a framework, a technology or an external system. The dependency always points inwards (`infrastructure → application → domain`); domains depend on `shared`, never the reverse. What a repository port **returns or receives is a domain contract**: projections, filters and query value objects live in `<domain>/domain/repository/projection`, never in `application/dto`. *Verifiable:* no `import` of `.application.`/`.infrastructure.` inside `src/main/java/**/domain/**` (the build must keep that at zero). **The rule is about production code.** A **co-located integration test** in a `domain` package may import the `application` service it exercises — precisely the service that writes through the repository under test (e.g. `DuplicateExclusionRepositoryIntegrationTest`, `MovementSummaryQueryIntegrationTest`). That is **not** a dependency inversion: the published artefact still has an isolated `domain`.
- **Ports and adapters at the real boundaries.** Adapters for external I/O (ffmpeg/exiftool/mediainfo, HTTP, filesystem, mail, native glue) live **only** in `infrastructure`. Domain support that unavoidably crosses the framework boundary (e.g. `ClockHolder`, the static bridge for the `@PrePersist`/`@PreUpdate` callbacks of entities, which receive no injection) lives in the domain (`shared/domain`), not in `application`.
- **Abstraction only where it pays.** Create a port/interface when it isolates a real boundary — an external system, a technology that may change, or a concrete testability gain. Do **not** create abstraction as ritual: an interface with a single implementer that merely wraps the framework, with no variation point and no test value, is ceremony — avoid it.
- **Conscious pragmatic exceptions.** JPA entities (`@Entity`) **are** the domain model and Spring Data repositories (`extends JpaRepository`) **are** the ports — they live in the `domain` even while carrying technology annotations. We do not separate a POJO model from the JPA entities, nor create an adapter just to wrap Spring Data: the mapping boilerplate does not pay for itself in an application (unlike a complex domain library). It is an explicit decision — the isolation of the first item applies to dependencies **between project classes**; JPA/Spring inside the `domain` is the accepted pragmatic boundary.

---

# Single responsibility

Each class must have one predominant responsibility. When distinct responsibilities appear, prefer extracting methods, components or new classes. Avoid classes that concentrate persistence, cache, integration, logging and rules all at once.

**The name reflects the responsibility, not the feature:**

- **The class name reflects the real, broadest responsibility — never one specific feature it happens to serve.** A general/shared class used by several screens does not take the prefix of one of them. E.g. the endpoint that delivers media detail/content to the lightbox (used by timeline, map, files, duplicates, quarantine) is `MediaContent*`, **not** `TimelineMedia*` — a feature prefix makes it look like it belongs to that feature. If a feature name has stopped describing what the class does (because it grew to serve others), **rename it**.
- **Shared/cross-feature logic does not live inside a feature class.** Utilities consumed by more than one feature (e.g. media streaming — range/content-type/safe name) live in a neutral class of their own responsibility (e.g. `MediaContentService`), **not** hidden inside a feature service (e.g. `TimelineService`) just because they appeared there first. A feature class never accumulates general utilities other features also use — that is cross-feature coupling in disguise.

---

# Technical debt and cleanliness

- **No dead code.** Do not leave methods, classes, fields, variables, imports, CSS, JavaScript, resources or dependencies unused. Unreferenced code is removed, not commented out.
- **Remove the obsolete when replacing.** When one implementation replaces another, the old one goes in the same step — no "old feature" living alongside the new.
- **No duplicated logic between classes.** The same rule/conversion/validation/handling lives in a single place. **Reuse the existing implementation before creating a new one.**
- **A new class only with a responsibility of its own.** Do not create artificial abstractions just to save a few lines; consolidate only when it really is a single, cohesive responsibility (see [Single responsibility](#single-responsibility)).
- **Comments and Javadoc in English, up to date and correct.** The comment explains the *why*; keep it in step with the code. Remove or fix orphaned, outdated or incorrect comments/Javadoc. Every new comment/Javadoc is born in English.
- **A comment never repeats volatile information.** A comment or Javadoc **never reproduces a value or fact that lives somewhere else and can change without anyone rereading the comment**: a configuration default ("the 90-day default"), a limit/window editable in Settings, a count of items or classes, a coverage percentage, a dependency version, a generated file name, a list of existing screens/domains. The value lives in a single place (a constant, an `AppSetting`, `@ConfigurationProperties`, the README) and the text refers to it **by name** — "the default documented in the constant", "the window configured in Settings" — instead of reproducing it. *Reason:* a comment that repeats the value becomes a silent lie on the first change, and whoever made the change has no way of knowing the comment needed updating; the reference by name stays true forever.

  Out of scope is the case where the number **is** the subject of that passage and is declared right there: the CRF of a quality profile, the expected value of an assertion, an example derived from a formula documented just above (`for n = 5: 10%, 30%, …`). In those cases the value sits in the code next to it, changes with it, and the comment explains the choice, not the number.

---

# Persistence

- **A migration that changes the shape of data carries the data with it.** Creating a column is additive and demands nothing; but **renaming, changing the type, splitting, merging, moving to another table or removing** a column or table obliges the migration to **transport the existing data** in the same file — `UPDATE`/`INSERT ... SELECT` before the `DROP`, never the DDL alone. *Reason:* the application is installed by people whose database is already populated — years of catalogue, perceptual hashes that cost hours, resolved locations. A migration that only touches the structure passes cleanly on an empty test database and erases the work of whoever uses the product, with no error and no warning. The empty database is the rare case; the populated one is the normal one.
  This also applies to travelling through an old backup: it is the migration that knows how yesterday's data becomes today's, and without that transport no restore across versions is possible.
- **Never `@Lob` on a `String`:** `TEXT` columns mapped with `@Lob` are read as a Large Object and break outside a transaction (a 500 error in auto-commit). Leave `String` without `@Lob`.
- **Data access only through the repository layer:** services and components do **not** touch `JdbcTemplate`/`NamedParameterJdbcTemplate` or the `EntityManager` directly. This applies to bulk operations too: the native query goes in as `@Modifying(nativeQuery = true)` on a Spring Data repository, or as a method of a custom `@Repository` over `NamedParameterJdbcTemplate` when the set-based/streaming pattern justifies avoiding the JPA session. The component keeps only the orchestration (parsing, progress, transaction).
- **A repository lives in the right layer of its domain:** the **Spring Data interfaces** (`extends JpaRepository`/`Repository`) are **ports** and live in `<domain>/domain/repository` (with `domain/repository/projection` for the projections they return). The **custom JDBC repositories** (`@Repository` on a concrete class, with no Spring Data interface) are **adapters** and live in `<domain>/infrastructure/persistence`. **There is no central repository package.** A **cross-feature** repository (used by more than one domain) lives in the domain that **owns the entity** it manages — ports over kernel entities live in `shared/domain/repository`. The scan covers the whole application (`@EnableJpaRepositories(basePackageClasses = NimbusFileManagerApplication.class)`), so there is no need to register each subpackage.
- **Path-prefix matching with `LIKE` (Windows/PostgreSQL/HQL):** when filtering "descendants of a folder" with `LIKE`, in PostgreSQL the `\` is the **default `LIKE` escape** and file names contain `_`/`%` (wildcards) — a naive pattern `like concat(:folder, :sep, '%')` **fails for Windows paths** (it only matches by accident at a drive root, `D:\`). Build the pattern with `PathUtils.descendantLikePattern(folder, separator)` (which guarantees a trailing separator and escapes `\ % _`) and use `like :pattern escape '\'` — in **HQL** the backslash of a bound parameter is treated as **literal**, so the explicit `escape '\'` is mandatory (unlike native SQL). **Validate through Hibernate** (a Testcontainers integration test inserting paths with backslashes as data — it runs on the Linux CI, since they are just strings); never rely on a raw JDBC probe alone, which uses the native `LIKE` and masks HQL's behaviour, nor on tests that use only `/` (they do not cover Windows).

---

# A clean clone runs

The whole project assumes that whoever arrived **has just cloned it**, on a machine where nothing was prepared by hand: no ffmpeg, no PostgreSQL, no tools folder, no workspace, no environment variable. In that state, **the suite runs and the application starts** — downloading whatever is missing during the run itself. It is the same promise as the installer: copy and open.

What that requires:

- **No manual prerequisites.** If a feature needs an external binary, the application is what fetches it (as ffmpeg and the embedded PostgreSQL already do), not a README step someone has to remember to run.
- **Every folder is created on demand**, at first use. No execution path assumes a pre-existing directory.
- **A test that depends on an external binary skips itself** (`@EnabledIf`) instead of failing. Failing because of a missing external dependency turns "I just cloned it" into a red build, and teaches people to ignore red. The CI installs what it needs and, there, those tests really run.
- **No path depends on an artefact that exists only on the developer's machine.** If a test passes locally because a folder was downloaded months ago, it is not testing what it appears to — that is how the binary lookup came to be broken in the packaged application without anyone noticing.
- **Writes go to the workspace**, never inside the installation: an installed program may live in a read-only folder, and a downloaded artefact is user data, not part of the program.

*Reason:* the product is being distributed. "It works here" is a statement about one person's machine; "it works on a clean clone" is a statement about the product — and it is the only one that matters to whoever installs it.

---

# File handling

- **Physical files only:** never follow a symlink, junction or `.lnk` shortcut. Use `PhysicalFilePolicy.isProcessable`; never `FileVisitOption.FOLLOW_LINKS`.
- **Centralised safe move:** every movement of a **user's** file goes through `SecureFileMove` (SHA-256 baseline + byte-by-byte verification + rollback) — this covers organization, dedup and undos. Never `Files.move` directly on a user file.
- **Legitimate exception:** internal/regenerable artefacts that are **not** user media may use `Files.move` directly — for example moving the temporary file of a dataset download to its destination, or a generated thumbnail to the cache. The strong guarantee (hash + rollback) exists for the user's irreplaceable data, not for artefacts the system regenerates.

---

# Internationalisation

- No text shown to the user may be hardcoded: in templates via `#{key}`, in the backend via `message(key, args...)`. Every text lives in the bundles (`messages.properties`, pt-BR default, + `messages_en.properties`).
- The backend has **no fallback in the code** — the key exists only in the bundles; a missing key raises `NoSuchMessageException`.
- Every new key exists in **all supported languages**. Parity is locked in the build by dedicated tests (`backend.*` keys used in the code, and pt×en parity) — the build breaks if one is missing.

---

# Front-end × Back-end responsibilities

The **back-end is the single source of truth of the domain**; the front-end (Thymeleaf templates, JS, CSS) is responsible **only for presentation, interaction and rendering**.

The **back-end** decides and delivers ready: business rules, validations, permissions, calculations, domain states, classifications, decisions, business parameters, business messages and **all internationalisation**. No business text is hardcoded in a `Controller`, `Service`, `Exception`, `Validator`, DTO, `enum` or any Java class — it is resolved through the `MessageSource` (see Internationalisation), with the text living in `messages.properties`/`messages_en.properties`.

The **front-end** does **only**: render screens, display information, user interaction, purely visual state, layout, navigation and components. It knows no business rules and translates no domain — it merely shows the already-resolved texts the API or the template delivers.

**Forbidden on the front:**
- **Domain translation** — a `switch`/`if`/ternary/`Map`/object/`enum`/array that translates a status, type, category, reason, message or description. The translation lives in the back-end bundles; the API/the `MessageSource` delivers the finished text.
- **Business rules** — deciding by status, calculating, classifying, blocking, business filtering, ordering by a rule, domain validation, or combining fields to infer a state.
- **Permission decisions** — the front never decides "can edit/delete/undo/move/download/execute". The back-end says so explicitly through fields (`canEdit`, `canDelete`, `canUndo`, `canMove`, `canDownload`, …).
- **Comparison by translated text** — never `if (status === "Processado")` nor `if (message === "Arquivo já existe")`. Always compare by code/enum/flag/technical identifier, never by the displayed text.
- **Domain duplication** — lists of statuses/categories/types/reasons are not replicated on the front; the API provides them.

Contracts must **deliver the finished decision** instead of raw fields for the front to decide on — e.g. prefer `{"status":"PROCESSING","canDelete":false,"canRetry":true}` over `{"status":"PROCESSING","owner":true,"locked":false}` leaving the front to combine them.

**May stay on the front:** purely visual text with no domain concept (a button label, placeholder, component tooltip, fixed interface label) in the front-end i18n; and CSS, layout, visual organisation, components, visual state, animations and interface-only behaviour.

---

# Interface and preferences

- **Screen/UI preference, per user:** every option the user chooses on a screen is stored per user (`UserPagePreference`/`UserPagePreferenceService`) and reapplied when the screen is reopened. Never reset to the default on each visit.
- **Global application configuration:** parameters that apply to the whole installation (not per user) live in `AppSetting`/`AppSettingService` (typed key-value, editable on the settings screen, seeded with defaults). E.g. the application time zone, providers, limits.
- Do not confuse the two: what is a personal display choice is `UserPagePreference`; what is application behaviour is `AppSetting`.
- **An action the system did not perform tells the user in a dialog, with the reason.** Every user-triggered action the system refuses, ignores or only partly fulfils — a resource busy with another operation, an item that changed state between the listing and the click, a missing file, insufficient permission — ends in a **modal** saying *why* and, where applicable, *what to do about it*. Logging it is not enough (the user does not read logs), and neither is returning counters for the screen to interpret: a status line reading "0 deleted, 0 errors" reads as success. The **reason comes ready from the back-end** — the contract carries an already-localised message and the screen merely displays it, as [Front-end × Back-end responsibilities](#front-end--back-end-responsibilities) requires. When the action does what was asked, no modal: interrupting to confirm success is noise.
- **Secondary actions** use `.button.secondary` (with a border) from `components.css`, never an ad-hoc link. When creating/changing UI, validate contrast in **both light and dark** themes, reusing the theme variables.

---

# Observability

Log levels:

- **ERROR** — only failures that require investigation.
- **WARN** — unexpected but recoverable behaviour.
- **INFO** — relevant lifecycle events.
- **DEBUG** — technical detail.
- **TRACE** — deep investigation.

**Never log stack traces (not even at ERROR) for expected situations** — for example, failures caused by a shutdown in progress are DEBUG, not ERROR.

---

# Performance

- Do not optimise prematurely; measure before optimising.
- Avoid unnecessary O(n²) and repeated queries.
- Prefer incremental and streaming processing where applicable.

---

# Tests

- **Base rule:** every new feature or change comes with a test (unit; and integration when a database, HTTP or an external process is involved). No change may lower coverage.
- Every new conditional exercises the **positive, negative and boundary** paths.
- Tests validate **observable behaviour**. Never write a test purely to raise a coverage percentage.
- **A test path that goes through normalisation is absolute and real (`@TempDir`).** When the code under test calls `toAbsolutePath()`/`normalize()` (or compares a `Path` against what the service returns), the test **may not** build the path from a Windows drive literal (`Path.of("D:", "library")`, `"D:\\trash"`): on the **Linux CI** that is a *relative* single-segment path, which normalisation prefixes with the runner's working directory — the test passes on Windows and breaks on CI. Use `@TempDir` (a method or `@BeforeEach` parameter) and derive everything from it with `resolve`: being genuinely absolute, normalisation is the identity and the assertion holds on both operating systems. A drive-letter literal is only acceptable when the path **does not** go through normalisation — when it is merely passed along (an opaque `String`/`Path`) or matched with `eq(...)` against the same object.

## Legitimate coverage exclusions

Classes outside the measurement (configured in `pom.xml` and mirrored in the Sonar exclusions), because they are not meaningfully unit-testable — they are covered by integration tests or manual verification:

- `NimbusFileManagerApplication` (bootstrap) and `**/infrastructure/config/**` (Spring wiring).
- `**/domain/model/**`, `**/dto/**` and `**/application/constants/**` (data with no logic — entities, DTOs and domain constant holders).
- `**/repository/**` and `**/*Repository` (data-access contracts).
- `**/*ProcessRunner` (external process glue: ffmpeg/exiftool/mediainfo).
- `**/GeoBoundariesSource` (the HTTP adapter that downloads the geographic dataset) and `**/windows/**` (native FFM/kernel32 glue, Windows only).
- `**/infrastructure/desktop/**` (AWT tray glue: `SystemTray`/`TrayIcon`/`PopupMenu` and the hand-off to `explorer.exe` — there is no tray on the headless CI, where every call is a no-op, and what it does on a desktop is only visible on one).

Real logic **never** lives in those excluded classes — it lives in the service that uses them, which is tested. The numeric coverage targets and the current state live in the README.

## The coverage ratchet

Coverage **never regresses**: the quality block of the README records the **current floor** of the five JaCoCo metrics (instruction, branch, line, method, class), and no task may close below it. The numbers live in the README — this document fixes only the policy, because the floor moves with every advance and a metric does not belong in a permanent document.

How to operate the ratchet:

- **Before closing**, run the full suite and compare the five metrics with the README floor. Below any of them → the task **is not done**; cover what was lost before delivering.
- **Went up?** Update the floor in the README to the new values, in the same commit. That is what makes the ratchet advance — an outdated floor allows regression for free.
- **The floor is a floor, not a target.** The README also records the **goal** being pursued; reaching it promotes the goal to floor and a new goal is set.

### The measurement varies between runs

Two consecutive runs of the same suite, without a line changed, give different numbers — observed at up to **0.16 point** on branch and ~0.03 on the others. Two causes, both from the project itself:

- **Parallel execution.** `src/test/resources/junit-platform.properties` runs test classes concurrently. Which branches of shared code get exercised changes from run to run: a cache that now populates and now hits, a contention path, a timeout that now fires.
- **Tests that skip themselves.** Those depending on ffmpeg (`@EnabledIf`) skip when `tools/ffmpeg/bin` does not exist, and the methods they would cover count as uncovered. That folder is gitignored, so a worktree or a fresh clone measures differently from the main tree.

How to operate in the face of that:

- A metric **a few hundredths** below the floor is not, by itself, a regression. Before writing a test chasing the number, **check whether the new code has an uncovered part** (the JaCoCo report per class/method). If it does not, it is noise: **remeasure** instead of inventing a test.
- A drop that **repeats** between runs, or that points at a new class/method with no coverage, is a real regression and the rule above applies.
- **Do not lower the floor** because of oscillation, and **do not round** the decimal place: the drop may be real, and a coarser ruler would hide exactly what the ratchet exists to catch.
- Always measure with `clean` and with the complete main tree (see *The floor requires a clean build*); comparing numbers taken under different conditions produces a wrong conclusion.

### Recalculating the floor

A large feature brings paths **no honest test reaches** — an I/O `catch` that requires the operating system to deny something, a guard that only fires on a race, a `continue`/`break` the compiler only reaches through an impossible condition. Since the floor is a percentage over the whole project, that code **drags the metric down without anything having regressed**. In that case — and **only** in that case — the floor may be rewritten below the previous one.

It is not a shortcut, and the order matters:

1. **First harvest what is honest, anywhere in the project.** The metric is global, so a legitimate gap in old code pays for an unreachable path in the new code — and the search is not limited to what the task touched. Recalculating before that sweep is loosening the ruler with work still to do.
2. **Classify what remains, line by line.** A target reachable by a test that asserts observable behaviour is not residue, it is work. Confirm in the JaCoCo report that the line really is unreachable before accepting it: a line marked as missed may be a jump the compiler routes elsewhere, in which case the "missing test" would move nothing.
3. **Record the nature of the residue in the README** — how many lines and of what kind — and not just the new numbers. A lower floor without that accounting is indistinguishable from a regression.
4. **Write down the measured values** from a clean build, in the same commit.

A metric that **went up** raises the floor with it, always: recalculating is not a synonym for lowering all five.

The base rule still holds — an artificial test is **never** written to move a percentage. If the only way to hold the floor is to instantiate a private constructor by reflection or exercise a getter, the right answer is to recalculate and declare the residue.

### Unreachable code and `@CoverageGenerated`

Code that **no honest test reaches** may leave the measurement, annotated with `@CoverageGenerated("reason")` (in `shared/application`). The name carries "Generated" because that is the only hook JaCoCo offers — it filters members annotated with an annotation whose simple name contains `Generated` and whose retention is `CLASS`/`RUNTIME`, the same mechanism as `lombok.Generated`. Nothing there is generated.

**It fits two cases, and the reason goes in the argument:** framework wiring that exists only so the container can build the object (an `@Autowired` constructor that merely forwards to another one the test calls directly), and an I/O failure path that requires the operating system to deny something — permission, an unreadable volume, a handle that dies mid-scan.

**It does not fit** one-line delegation, a branch that is merely laborious to set up, nor anything a restructuring would make reachable. Between annotating and restructuring, **restructure**: chasing coverage has already found an unreachable `return` and a redundant guard in this project, and deleting those was worth more than hiding them. The annotation also **does not reach a block** — a `catch` or an `if` only leaves the measurement if the whole method does, which would hide the covered path along with it; in those cases either the path becomes reachable, or it stays as declared residue.

The ratchet **does not** authorise a shortcut: it still holds that a test validates observable behaviour and that **a test is never written just to move a percentage** (see the base rule above). If the only way to raise a metric is an artificial test — instantiating a private constructor by reflection, exercising a getter, asserting the obvious — the right answer is to **leave the metric where it is** and record why, not to invent a test. Legitimately unreachable code (an OS-dependent I/O error path, an anti-instantiation guard, an override required by a contract but never called) is accepted residue, not debt.

---

# Static quality (Sonar)

- **Every task must end without creating a single new Sonar issue.** Run the analysis at the end and compare the total — and the count **per rule** — with the previous state.
- **Pre-existing** issues may remain only when they are **outside the scope** of the task.
- Any **increase per rule** — including a new issue that appeared as a **side effect** of another fix — must be **investigated and eliminated before closing**. A task that introduces debt is not delivered, however trivial.
- False positives and legitimate cases (an idiomatic pattern, a library/spec requirement, a hotspot safe by design) are **marked as accepted/reviewed in Sonar with a justification**, never "resolved" with artificial code.
- **Recurring accepted case — `java:S3516` in an MVC handler that returns a redirect.** A screen's `@PostMapping` handler always returns **the same `redirect:`**, because what changes between the paths is the *flash attribute* (`success`/`error`), not the destination — the originating page re-renders with the message. Sonar reads that as "the method always returns the same value" and the issue is **accepted**, never worked around. **Do not refactor to a single `return`** (extracting the guards into a method that returns the message key): that closes the issue, but leaves the handler unlike all its siblings in the project, and consistency is worth more than zeroing a rule whose pattern has already been decided. There are already several accepted this way (`SettingsGeodataWebController`, `OrganizationWebController`, `SettingsWebController`). When creating a new handler of this kind, **accept the issue in Sonar** as the previous ones.
- **Recurring accepted case — `java:S2479` in `@Query("""…""")`.** The indentation of the JPQL query text block leaves significant whitespace in the string, and Sonar complains about the unescaped character. It is **idiomatic and deliberate** (the query stays readable in the code), so the issue is **accepted in Sonar**, never worked around with concatenation or artificial escapes. There are already dozens accepted this way in the repositories. A **new** S2479 in those files, however, is a sign that someone **reindented a line of the query** — in that case the right answer is to revert the reindentation, not to accept the issue.

---

# Compiler and static-analysis warnings

Before finishing any task, check the files created or modified for compiler and static-analysis warnings.

- Do not introduce new warnings.
- Fix warnings that appeared as a side effect of the task itself, even when not reported by every analysis tool.
- Do not hide warnings with `@SuppressWarnings`, analysis exclusions or changes to the tools' configuration, unless there is a documented technical justification.
- Close resources implementing `AutoCloseable` properly, preferably with try-with-resources.
- Declare `serialVersionUID` in exceptions and other serialisable classes where applicable.
- Use the unnamed variable `_` for every unused lambda parameter, `catch` variable, pattern component or `for` variable (see *Unnamed variable* in Code style → Conventions); besides making the intent explicit, it removes the unused variable/parameter warning.

---

# Versioning

The version lives in `pom.xml` `<version>`, in the format **`MAJOR.MINOR.PATCH.BUILD`**. Classification considers the **impact on the user**, not the number of files modified:

- **MAJOR** — an incompatible change or a deep architectural one. Increments MAJOR, zeroes MINOR and PATCH, increments BUILD.
- **MINOR** — a new compatible feature. Increments MINOR, zeroes PATCH, increments BUILD.
- **PATCH** — a bug fix or a small improvement. Increments PATCH and BUILD.
- **BUILD** — an ever-increasing historical counter. A refactor, test, internal doc or config with no change in public behaviour raises **only the BUILD**.

When and how to apply it:

- Change the version **once per task**, **only after** the implementation is finished and reviewed, and only if there was a real change in the repository — never for pure analysis or diagnosis.
- Run the applicable tests **first**; if they fail because of the change, **do not** increment. If the environment does not allow running the tests, say so and still increment if the implementation is finished and reviewed.
- On finishing, report: the previous version, the new version, and the reason for the classification.

---

# Git

- **Never commit without an explicit request** from the developer. Implementing, reviewing, testing and versioning are done freely; the commit is always a requested action.

---

# README

The README represents the **current state** of the project — it is where metrics, coverage, version, features, stack and requirements live. Always update it when features, public architecture, stack, requirements or coverage change.

Coverage: at the end of every build that runs the full suite (`mvn test`/`verify`), update the README quality block with the `QualitySummary` values (test count and JaCoCo metrics). Do not leave the numbers stale — they are the public quality reference and must reflect the last clean local build.

**No date on a recurring metric.** Metric blocks that are redone on every build (coverage, test count, mutation score/PIT) **carry no date** — label them "last run" / "most recent run", never "generated on `<date>`". A date stamped on a recurring metric becomes debt immediately: it ages on the next build and suggests staleness even when the numbers are current. **Exception — dates of a unique historical event** (when something happened, rather than a state that repeats: e.g. the migration squash "on 2026-07-12") **remain**, since they record a one-off fact, not a metric.

---

# Evolution of this document

New rules only enter when they solve a recurring problem, remove ambiguity or represent a permanent architectural decision. Avoid temporary rules or ones specific to a single implementation. A rule that conflicts with existing code is decided explicitly before it enters.