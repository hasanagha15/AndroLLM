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
    goes straight to the ui_run fallback (if allowed).
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
  (`failedStep` = -1 for `version_update`), always < 8000 chars; only the records
  that fit are marked delivered — pull again while `more` is true.

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
