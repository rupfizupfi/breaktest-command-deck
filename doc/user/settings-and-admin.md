# Settings and administration

What an ADMIN account can do beyond everyday work: manage users, set the rig limits and other
server settings, look after shared records, and — on a simulated deck only — trip faults on
purpose. Everyday use is in [`getting-started.md`](getting-started.md).

The pages below are not in the menu; open them by adding their path to the app's address (for
example `/admin/user`). A non-admin who opens one is refused.

## Users and roles

**`/admin/user`** lists the accounts. Each has a username, a display name, a password and one or
both roles, `USER` and `ADMIN`.

- A new user needs a password. Editing a user without typing a **New Password** keeps the old one.
- No account is created automatically outside a developer machine. The very first ADMIN of a
  deployment comes from its configuration
  ([`ops/docker-and-profiles.md`](../ops/docker-and-profiles.md#required-host-preparation)).

## Rig limits

Three settings describe the bench, and every test parameter is checked against them — when it is
saved and again when a run starts:

| Setting key | Unit | What it limits |
|---|---|---|
| `rig.loadcell.capacity.kn` | kN | the load cell's rated capacity; no shut-off threshold may exceed it (default: the bench's 150 kN cell) |
| `rig.crosshead.speed.max.mm-per-min` | mm/min | the fastest speed a test parameter may set |
| `rig.suction.load-margin.n` | N | how far above the lower shut-off a destructive run's force must rise before a drop through it counts as a break and starts the suction (default 500) |

A capacity or speed that is not positive or above its sanity ceiling, a margin below 0 or above
the capacity, and a capacity below the current margin are refused. Lowering a limit does not
change stored test parameters; one that no longer fits is refused at its next save or run
([`dev/backend/persistence-model.md`](../dev/backend/persistence-model.md#rig-limits)).

## Other settings

**`/system/setting`** edits the server's settings, rig limits included:

| Setting key | What it does | Default |
|---|---|---|
| `testrunner.suck` | whether a destructive run switches on the suction after a break | on |
| `testrunner.suck.duration` | how long the suction runs, in seconds | 10 |
| `file.result.data.directory` | where the server writes force recordings and run logs | `~/breaktester` |
| `file.upload.directory` | where uploaded photos are stored | `~/breaktester/uploads` |

A directory the server must not write into, a value of the wrong type, or an unknown key is
refused. The settings live in a file on the server, so the cloud cms and the bench deck each have
their own. **Sync** re-reads that file and fills in any missing setting with its default.

## Shared records

A record with an empty owner is shared with every user, and only an administrator can edit,
delete or re-assign it. When a non-admin shares a record by mistake, an administrator gives it
an owner again. Administrators see every user's records.

## Simulated faults

On a deck running without hardware (the red **SIMULATED HARDWARE** bar), **`/system/faults`**
lists fault switches that make the simulated load cell or drive misbehave — silence, a dying
stream, a drive that stops answering — so the safety reactions can be watched without risking
the bench. Tick a switch to arm it; **Clear all** disarms every switch, and so does a restart.
On a real bench the page has no switches. The switches and what each one trips are listed in
[`dev/backend/fault-injection.md`](../dev/backend/fault-injection.md).

## When something is wrong

The common failures — a deck that refuses to start, a motor that never moves, a relay that never
fires after a destructive test — and their fixes are in [`ops/runbook.md`](../ops/runbook.md).
