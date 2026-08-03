# Security Policy

## Supported versions

Nimbus File Manager is distributed as a Windows installer and checks for updates on its own, so
only the **latest published release** receives fixes. There are no maintenance branches: a security
fix ships as a new release, and installations that have the update check enabled are told about it
within fifteen minutes of it being published.

| Version | Supported |
| --- | --- |
| Latest release | Yes |
| Anything older | No — update to the latest release |

## Reporting a vulnerability

**Do not open a public issue for a security problem.** An issue is visible to everyone the moment
it is created, including to whoever would exploit it.

Report it privately, either way below:

- **GitHub private vulnerability reporting** — the *Security* tab of this repository, "Report a
  vulnerability". This is the preferred route: it keeps the report, the discussion and the fix in
  one place, private until it is published.
- **Email** — <jorgefrancisco.melo@gmail.com>, with `[security]` in the subject.

What helps most, in rough order of usefulness:

- what an attacker gains, and what they need in order to try (local access? an account? a file the
  victim opens?);
- the steps to reproduce it, and the version it was reproduced on (the tray menu shows the
  installed version);
- what you saw versus what you expected;
- anything from `<workspace>/logs`, with paths redacted if they are private.

You can expect an acknowledgement within **seven days**. If the report is confirmed, the fix and
the release that carries it are discussed in the same thread, and you are credited in the release
notes unless you would rather not be.

## Scope

Nimbus File Manager is a local-first application: it runs on the machine that owns the files, binds
its embedded database to loopback, and by default the only thing that leaves the computer is the
update check. What is in scope is anything that breaks that shape — for example:

- reaching another user's files, catalog or preferences through the web interface;
- privilege escalation through the update flow, the installer, or the external tools the
  application downloads and runs;
- authentication or session handling that lets a request act as somebody it is not;
- an unverified download, or a verification that can be bypassed.

What is **not** a vulnerability here: anything that requires an attacker to already have
administrator access to the machine, or that depends on the owner deliberately exposing the port to
a network the application was never meant to answer on. Reports of that kind are still welcome as
ordinary issues — they may well be worth fixing — but they will not be handled as embargoed.
