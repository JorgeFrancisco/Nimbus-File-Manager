# Before anything else

Nimbus File Manager is source-available and is also offered under separate commercial terms. An
external pull request can only be merged after a signed contributor agreement — see
[CONTRIBUTING.md](../blob/main/CONTRIBUTING.md), and talk to the project owner **before** writing
code, so that nobody's afternoon is wasted.

## What this changes

<!-- What the application does differently after this, from the point of view of whoever uses it.
     If it fixes something, what was broken. -->

## Why

<!-- What made it worth doing. If there is an issue, link it. -->

## Checklist

The project's rules live in [`AGENTS.md`](../blob/main/AGENTS.md) — these are the ones a pull
request is checked against:

- [ ] Tests cover the change (positive, negative and boundary paths for new conditional logic).
- [ ] `./mvnw clean verify` is green, and coverage is not below the floor recorded in the README.
- [ ] `./mvnw -Pspotbugs verify` is green, or a new exclusion in `spotbugs-exclude.xml` carries the
      reason it was checked against the code.
- [ ] No new Sonar issue.
- [ ] No user-facing text hardcoded — every string comes from `messages.properties` **and**
      `messages_en.properties`.
- [ ] Formatting untouched by hand: Eclipse formatter, CRLF, no final newline.
- [ ] README updated if features, stack, requirements or coverage changed.
- [ ] Version in `pom.xml` bumped, with the reason for the classification.