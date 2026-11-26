# App playbooks — JSON schema v1

Branch N (`core/appatlas/playbook`). A **playbook** is a named, parameterized
action of one app (`com.google.android.youtube/search(query)`), learned
**offline** by Claude/Jarvis (adb crawl on a laptop) and replayed on the phone
**deterministically**: no LLM call per step, no screenshots. The local model
only picks *which* playbook to run (`playbook_run`); for frequent commands the
zero-LLM `PlaybookRouter` can pick it from trigger phrases.

Playbooks reach the phone as a **bundle**:

- from a file / content URI: Settings → UI Automation → App Atlas → *Import playbooks (JSON)*;
- from Jarvis: task type `playbooks.sync` on the existing encrypted MQTT task
  channel (protocol v2, AES-256-GCM, HKDF-SHA256 keys — see below).

Import is **strict**: unknown fields, unknown ops and oversized values reject
(unknown field → the whole bundle, anything else → that playbook).

## Bundle

```json
{
  "format": "androllm.playbooks",
  "schemaVersion": 1,
  "generatedAt": 1795000000000,
  "playbooks": [ <Playbook>, ... ]
}
```

| field | type | rules |
|---|---|---|
| `format` | string | must be `androllm.playbooks` |
| `schemaVersion` | int | must be `1` |
| `generatedAt` | long | optional, ms since epoch |
| `playbooks` | array | ≤ 200 items; bundle ≤ 512 KiB of JSON |

## Playbook

| field | type | rules |
|---|---|---|
| `id` | string | **must equal** `<package>/<name>` |
| `package` | string | Java package name; never AndroLLM itself; **never a protected app** (see below) |
| `name` | string | `[a-z][a-z0-9_]{0,39}` |
| `appLabel` | string? | ≤ 80 chars, also checked by the banking/payment heuristic |
| `description` | string | 1..120 plain chars (no `{}[]<>` or control chars) — shown to the model |
| `params` | Param[] | ≤ 6 |
| `versionMin`, `versionMax` | long | app `versionCode` range the playbook was learned/verified on (inclusive) |
| `risk` | `none` \| `confirm` | declared; the importer upgrades it to `confirm` when a step is risky |
| `triggers` | string[] | ≤ 12, ≤ 80 chars, `{slot}` placeholders of declared params, ≥ 1 literal word (ru / en / translit) |
| `steps` | Step[] | 1..30 |
| `postcondition` | Expect? | cheap verifier: what must be visible after the last step → `verified` |
| `learnedAt` | long | optional |

### Param

| field | type | rules |
|---|---|---|
| `name` | string | `[a-z][a-z0-9_]{0,23}`, unique |
| `type` | `text` \| `int` \| `enum` | default `text` |
| `description` | string | ≤ 120 |
| `required` | bool | default `true` |
| `maxLength` | int | 1..500, default 200 |
| `values` | string[] | enum only, 1..20 values ≤ 40 chars |

Slot values are **one line** (no control / line-break characters — a line
break could submit a field). They live in memory for one run only: never
stored, logged, echoed in a tool result or put into a relearn record.

### Step

| field | ops | rules |
|---|---|---|
| `op` | — | `tap` \| `type` \| `scroll` \| `wait` \| `back` \| `open_deeplink` (anything else rejects the playbook) |
| `locators` | tap (required, **at least one `id` / `desc` / `text`** — positional locators only disambiguate), type (optional; empty = focused field) | ≤ 6 weighted locators |
| `text` | type | template ≤ 200 chars, one line, `{slot}` placeholders |
| `uri` | open_deeplink | template ≤ 500 chars; `{slot}` only **after** `scheme://host`; values URL-encoded; opened with `setPackage(<package>)`. Rejected: blocked schemes (`tel`, `sms`, `mailto`, payment, `intent`, `file`, `content`, …); http(s) share/compose links (`wa.me/…?text=`, `t.me/share`, …); for **any** scheme a host/path word `post`, `share`, `send`, `msg`, `compose`, `message`, `tweet`, `pay`, `transfer`, `dm`, `reply`, `forward` … and draft parameters `text`, `message`, `body`, `to`, `url`, `link`, `recipient`, `amount` …; http(s) to any host the app's own manifest deep links (atlas) do not declare |
| `direction` | scroll | `up` \| `down` \| `left` \| `right` |
| `waitMs` | wait | 0..10000 (prefer `expect`) |
| `expect` | any | Expect — must be visible **before** acting (awaited up to `timeoutMs`) |
| `timeoutMs` | any | 100..30000, default 4000 |
| `risky` | tap | declared risky; also computed (see Risk) |
| `minScore` | tap / type | 0.1..5, default 0.7 |

A field that does not belong to the op (e.g. `text` on a `tap`) rejects the playbook.

### Locator (weighted multi-locator)

| `kind` | default weight | value | matches |
|---|---|---|---|
| `id` | 1.0 | resource entry name (`search_button`, not `pkg:id/…`) | exact, case-insensitive |
| `desc` | 0.8 | content-desc ≤ 80 | exact (trimmed, case-insensitive) |
| `text` | 0.7 | app UI string ≤ 80 | exact (trimmed, case-insensitive) |
| `class_index` | 0.4 | class (simple or full name) + `index` 0..200 | index among visible nodes of that class |
| `bounds` | 0.3 | `bounds: [l, t, r, b]` relative 0..1 | node centre inside the rect ± 0.05 |

`weight` (0, 2] overrides the default. Each visible node scores the sum of the
weights of the locators it satisfies; the best node needs ≥ `minScore`, and
for a tap it must match at least one `id` / `desc` / `text` locator (so M's
gate can always classify the element; an element without text, desc and id
would get a tap-only card anyway). Ties
prefer clickable (tap) / editable (type) nodes, then tree order. Which locator
kinds matched is reported per step (`"locators": ["0:id+desc", …]`) for
self-repair statistics — ids only.

### Expect

```json
{ "ids": ["toolbar"], "texts": ["Search"], "match": "all" }
```

≤ 8 ids (entry names) and ≤ 8 app UI strings; `match` = `all` (default) or `any`.

## Risk

A step is risky when declared, or when a locator of kind `id` / `desc` /
`text` looks like send / pay / confirm / delete / call / post / grant
(`SafetyClassifier`, en / ru / az / tr — the vocabulary of branch M's
`RiskyElementGate`). Then the playbook is `confirm`: before that tap the
owner gets a **tap-only** card (`ToolConfirmationManager`; a spoken "yes"
never approves it) naming the **resolved** element, and the tap carries a
`RiskApproval` for exactly that element. Independently, the controller's
`RiskyElementGate` checks the resolved element of **every** tap; an
undeclared risky element raises the same card. The card names the playbook,
its description, the step and the element. After approval the step is
re-resolved: the app must still be in front and the target must be the same
element (id, class, text, desc, position) — otherwise nothing is tapped
(`screen_changed`). The controller re-checks the node the same way right
before the gesture.

## Protected apps

Never imported, never listed, never replayed (checked on import and again at
run time, live): `SensitiveAppGuard` (Tesla, WhatsApp/W4B, the verified
banking/payment list), the owner's list (`SensitiveAppStore`), the atlas
heuristic (bank / wallet / crypto / `*pay` / authenticator …, by package,
`appLabel` or the installed label) and the atlas `sensitive` flag (also set
for apps that handle payment schemes: upi, venmo, metamask, wc, …).

## Replay

- If the app is not in front and step 0 is not `open_deeplink`, the launcher intent opens it.
- Before each step: the foreground package must be the playbook's package and
  `expect` must hold — awaited **event-driven** (wakes on
  `TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOW_CONTENT_CHANGED`, 60 ms debounce,
  150 ms polling fallback), tree only.
- The engine never scrolls on its own (only explicit `scroll` steps).
- Status per installed `versionCode`:
  - in range → normal replay;
  - outside range (app updated) → **unverified**: still replayed, but strict
    (target must be present before acting, semantic locator `id`/`desc`/`text`
    required, a unique best node before any clickable preference); a relearn
    request `version_update` is queued once;
  - **stale** for this version (an earlier mismatch) → not replayed; `playbook_run`
    goes straight to the ui_run fallback (if allowed);
  - **stale(app_updated)** (branch R1, see *App updates* below) → never taken by the
    fast path; `playbook_run` replays it **strict** (like unverified) until a verified
    owner run clears the mark.
- On mismatch (`expect_timeout`, `target_not_found`, `wrong_app`,
  `ambiguous_target`) the run stops with a structured failure — step index,
  expected ids, seen package + seen ids (no screen text) — the playbook is
  marked stale for that `versionCode`, a relearn request is queued, and
  `playbook_run` falls back to the model-driven `ui_run` when `fallback` is true (default).
  **Never for remote (Jarvis) turns**: `RemoteToolPolicy` forbids `ui_run`, so under
  `RemoteToolOrigin` the result is a structured failure plus a relearn request.
- Relearn requests are queued once per (playbook, versionCode); a mismatch replaces an earlier record.
- Postcondition not seen → `verified=false` + relearn request `postcondition` (not stale).

## Jarvis link

### Phone → Jarvis events (branch R1)

Phone-originated messages use their own topic, key and AAD, so an event can never be
replayed as a task or taken for a result:

```
topic      jarvis/phone/event   (QoS 1, not retained)
envelope   {"v":2,"kind":"event","n":b64(nonce12),"c":b64(ct||tag)}
key        HKDF-SHA256(task.key, salt="androllm-jarvislink", info="event-v2"), AAD "androllm/v2/event"
inner      {"id":"ev-<ms>-<hex12>","type":"apps.changed","body":{…},"ts":<ms>}   (≤ 4 KiB)
```

`apps.changed` body — exactly these fields:

```json
{"pkg":"com.example.video","oldVc":150,"newVc":151,"kind":"updated","ts":1795000000000,"sensitive":false}
```

| field | type | meaning |
|---|---|---|
| `pkg` | string | package name |
| `oldVc` | long \| null | versionCode before; `null` when `kind` = `added` |
| `newVc` | long \| null | versionCode now; `null` when `kind` = `removed` |
| `kind` | `added` \| `updated` \| `removed` | `updated` also covers a downgrade |
| `ts` | long | ms since epoch, when the phone saw the change |
| `sensitive` | bool | protected app: report only — never pull, decode or crawl it |

- Queued only while the link is enabled in Settings. The outbox
  (SharedPreferences `jarvis_event_outbox`, excluded from backup) keeps ≤ 200 events,
  ≤ 7 days, oldest first; it is flushed on connect and after each new event, and a
  failed publish stops the flush (retried on the next connect).
- Turning the link off **deletes** the pending queue (Settings toggle, the service
  stopping because the link is disabled, any publish / flush while off). Events queued
  for another broker host / port / user or another key are dropped, never sent to the
  new one (the outbox stores a truncated SHA-256 fingerprint of target + key, not the key).
- Receivers dedup by `id` (redelivery) and by `pkg` + `newVc` (the design's relearn queue).
- Consumer (branch R2, Jarvis host `apps_changed_sub.py`, decoder `event-v2`) maps `kind`
  `added` / `updated` / `removed` to `ADDED` / `REPLACED` / `REMOVED` and appends the change
  to the relearn queue. It re-checks sensitivity on its side (fail closed), so `sensitive`
  is advisory. Its legacy fallback (a result-key envelope on `jarvis/phone/result` carrying
  `"type":"apps.changed"`) is not used by the phone. A cross-language test vector
  (`JarvisEventTest`) pins the exact bytes.
- Broker: the phone user needs `topic write jarvis/phone/event`, the Jarvis reader
  `topic read jarvis/phone/event` (core/jarvislink/broker/mosquitto-acl-snippet.conf).
  Until then the broker refuses the publish and the events wait in the outbox.

### Jarvis → phone typed tasks

Same envelope, keys and AAD as text tasks (`jarvis/phone/task`, protocol v2).
The inner (encrypted) task JSON carries a `type` instead of `text`:

```json
{"id":"pb-2026-11-25-1","type":"playbooks.sync","body":{ <bundle> },"from":"jarvis","ts":1795000000000,"ttl_s":600}
{"id":"pb-relearn-7","type":"playbooks.relearn","body":{"limit":50},"from":"jarvis","ts":1795000000000,"ttl_s":600}
```

- Typed tasks run **without** an agent turn; unknown types → `invalid`.
- Setting "Accept playbooks from Jarvis" (default on): when off, `playbooks.sync` returns `disabled`.
- Re-import: an unchanged playbook keeps its stale / unverified state; a remote bundle never
  replaces a playbook imported from a file unless it is newer (`learnedAt`) and has a `postcondition`.
- Envelope ≤ 384 KiB for typed tasks (text tasks keep 16 KiB).
- `playbooks.sync` result text: `imported N, rejected M: <id>: <reason>; …` (status `ok`, or `invalid` when nothing was imported).
- `playbooks.relearn` (`limit` default 30, max 100) result text:
  `{"requests":[{"package","versionCode","playbookId","failedStep","reason","createdAt"}…],"more":bool}`
  (`failedStep` = -1 for `version_update` and `app_updated`), always < 8000 chars; only the records
  that fit are marked delivered — pull again while `more` is true.

### Jarvis planner tasks (branch W)

Two more typed tasks let Claude on the Jarvis host list and run playbooks **without an agent turn**
(no local model in the loop, slot values never pass through a prompt).

```json
{"id":"pl-1","type":"playbooks.list","body":{"package":"com.example.video"},"from":"jarvis","ts":…,"ttl_s":60}
{"id":"pr-1","type":"playbook.run","body":{"id":"com.example.video/search","args":{"query":"cats"},"deadline_ms":…,"run_window_ms":300000},"from":"jarvis","ts":…,"ttl_s":60}
```

- `playbooks.list` (`package` optional; needs "Accept playbooks from Jarvis") →
  `{"playbooks":[{"id","package","name","description","params":[{"name","type","required","values"?}],
  "status":"ok|unverified|stale","risky","verified","trusted","source","installedVc","versionMin","versionMax"}…],"more":bool}`
  (< 7500 chars). Protected and not-installed apps are never listed.
- `playbook.run` needs its **own** setting "Jarvis may run verified playbooks" (`allowJarvisPlaybookRun`,
  **off by default**; the import flag never enables runs). `ttl_s` ≤ 300 for this type (protocol). `deadline_ms`
  (epoch ms) is required; optional `run_window_ms` is counted from receipt (branch W2, clock-skew proof). The run ends at
  min(`deadline_ms`, receipt + `run_window_ms`, receipt + 330 s); the phone caps it at now + 330 s, starts nothing after it and cancels the whole replay —
  every step and every card wait — at the deadline. Jarvis waits longer than the deadline, so it always gets the
  final answer.
- Runs only a playbook **trusted on this device** with effective status **OK**, re-checked under the runner mutex.
  Trust semantics: `source` = file (the owner imported it — trusted without a run), or `verifiedCount > 0`
  (one owner run with the postcondition seen). Once trusted, Jarvis may run it with **any** slot values; a remote run
  never adds trust; a changed re-sync resets trust.
- The replay happens inside `RemoteToolOrigin`: no ui_run fallback, every risky step raises the remote-labelled
  **tap-only** card (heads-up notification); nothing is auto-approved.
- Result text (ids, counts, machine codes only), `{"outcome", "reason"?, "playbook", …}`:

| status | outcome | reason codes |
|---|---|---|
| `ok` | `ok` | + `verified`, `steps`, `elapsed_ms`, `mode` |
| `denied` | `refused` | `disabled`, `playbook_not_found`, `protected`, `not_installed`, `not_trusted`, `status_stale`, `status_unverified` |
| `denied` | `denied` / `timeout` | `declined` / `card_timeout`, `deadline` |
| `busy` | `busy` | `owner_active` (in the last 20 s: an input-like event — window change, scroll, focus, click, long click, text change, touch start (branch W4) — not within 3 s of one of our own Jarvis-run actions; or a passive content change when our last own action is ≥ 20 s ago; card waits are never masked — branch W3), `screen_locked`, `screen_off` |
| `error` | `failed` | `blocked` (a protected app appeared mid-run; `steps_done` tells how far it got), or the replay failure kind (`target_not_found`, `screen_changed`, `expect_timeout`, `blocked`, …) + `failed_step`, `steps_done`, `steps_total` |
| `invalid` | `invalid` | `missing id`, `missing deadline_ms`, `bad_args` … |

- `RemoteDataResult.status` (core:tools) lets a typed handler name the final status (`denied` / `error` / `invalid` / `busy`);
  `ok` / `accepted` are never taken from it for a failure.

## App updates (branch R1)

The phone notices an app install / update / removal itself (`AppUpdateWatcher`,
core/appatlas `update/`), within seconds, with zero network:

- a runtime receiver for `PACKAGE_ADDED` / `PACKAGE_REPLACED` / `PACKAGE_REMOVED`
  (scheme `package`) while the app process lives (an update's REMOVED/ADDED with
  `EXTRA_REPLACING` are skipped, REPLACED is used);
- `PackageManager.getChangedPackages(seq)` with the sequence number persisted
  (SharedPreferences `app_update_watcher`, excluded from backup), at start-up and from
  a periodic WorkManager job every 15 minutes (`app_update_check`). After a reboot
  (`Settings.Global.BOOT_COUNT` changed) the sequence restarts at 0. The first run only
  records a baseline;
- dedup by package + versionCode: one update is handled once, however many
  broadcasts / polls see it. "Installed" means launchable, as in the atlas;
- a change is committed (last seen versionCode, sequence) only **after** it was
  handled; when the sensitivity lookup or the stale mark fails, nothing is marked
  or published and the next check retries it. Changes are handled one at a time;
- if `BOOT_COUNT` is unavailable, a PackageManager counter below the stored sequence
  is also treated as a reboot;
- off while the App Atlas is off (the baseline is then forgotten): updates during that
  time are not marked; after re-enabling, only the atlas sync's range check applies to
  them. An update caught by the very first broadcast before any baseline (first start
  of this version only) is part of the baseline and is not marked.

Per change:

1. **Protected package** (`SensitiveAppGuard` list, the owner's list, the atlas
   heuristic / flag — the flag is read through the atlas store, not only its in-memory
   cache, which is empty at start-up and in a fresh WorkManager process): reported to Jarvis with `"sensitive": true` — nothing else (no
   stale mark, no relearn request, no pull, no crawl).
2. Otherwise, installed or updated: **every** playbook of the package becomes
   `STALE` with `staleReason = "app_updated"` and `flaggedVersion` = the new versionCode
   (Room `app_atlas.db` v5, column `playbooks.staleReason`). The fast path stops
   matching them immediately (whatever version the atlas cache still shows), and one
   relearn request `{"failedStep":-1,"reason":"app_updated"}` per playbook is queued
   (once per version). `verifiedCount` is kept; the status alone keeps the fast path off.
   - `playbook_run` still replays the playbook strict (weighted locators often still find
     the element under a new id). A success with the postcondition seen on an **owner**
     run (not a Jarvis turn, gate Q N2) that **started on the marked playbook for that
     same version** clears the mark → `OK` (self-heal), and the learned range
     (`versionMin`..`versionMax`) grows to include that version, so the fast path returns.
     A later re-sync of the same playbook without that extension counts as unchanged
     (state, trust and the earned range are kept).
   - A mismatch during such a strict run keeps the mark (reason and the watcher's
     version); only the counters change and the relearn request of that version is
     replaced by the mismatch details.
   - The atlas sync never clears the mark; an unchanged `playbooks.sync` re-import keeps
     it, a changed playbook starts `OK` with `verifiedCount = 0` as before.
3. An `apps.changed` event goes to Jarvis (below). Logs carry package name, kind and
   version numbers only.

## Tools for the model

- `playbook_list(query)` — `id(params) — description [status] (asks to confirm)`, ≤ 12 lines / 900 chars.
- `playbook_run(id, args, fallback?)` — result `verified=true|false`, steps, ms.
- `app_atlas_search` lists up to 3 playbooks per matched app first (`playbook_run <id>(params) — …`).

## Zero-LLM fast path

Setting "Fast playbook commands" (default on). The owner's typed (ChatViewModel)
and spoken (ChatManager) commands are matched by `PlaybookRouter` against the
triggers of installed, non-protected, status-OK and **non-risky** playbooks
before the model is called; a match runs the playbook directly (same engine,
guards and cards, no ui_run fallback). No match, a tie, a remote turn or a
failed replay → the model answers as before.

## Example

```json
{
  "format": "androllm.playbooks",
  "schemaVersion": 1,
  "playbooks": [{
    "id": "com.example.video/search",
    "package": "com.example.video",
    "name": "search",
    "description": "Search videos",
    "params": [{"name": "query", "description": "what to search"}],
    "versionMin": 1520000, "versionMax": 1530999,
    "triggers": ["найди {query} в видео", "search video for {query}", "naydi {query} v video"],
    "steps": [
      {"op": "tap", "expect": {"ids": ["toolbar"]},
       "locators": [{"kind": "id", "value": "menu_search"}, {"kind": "desc", "value": "Search"},
                    {"kind": "bounds", "bounds": [0.75, 0.0, 1.0, 0.12]}]},
      {"op": "type", "locators": [{"kind": "id", "value": "search_edit_text"}], "text": "{query}"},
      {"op": "tap", "locators": [{"kind": "id", "value": "search_go"}], "timeoutMs": 3000}
    ],
    "postcondition": {"ids": ["results_list"]}
  }]
}
```
