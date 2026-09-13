# Deadhead

**Deadhead is live — just open it in your browser:**

### 👉 https://deadhead-planner-71dc1c210944.herokuapp.com/login

No install, no setup, nothing to download. Create an account with an email and a password (10+ characters) and you're in. Everything below is for people who want to *develop* Deadhead; if you just want to *use* it, the link above is all you need.

---

## What it is

*"Deadhead" (aviation): flying an empty plane just to reposition it — the expensive habit this app exists to question.*

A trip planner for pilots flying client trips. Drop in a week's calendar; after every leg the optimizer weighs the real choices — fly the plane home, **leave it there and Uber back**, hotel overnight — and returns the provably cheapest plan for the week.

- **Calendar import** — upload an `.ics` exported from Apple Calendar, or paste a published calendar link and Deadhead re-syncs it every hour. A `from,to,date,time` `.csv` works too.
- **Reads your event titles** — titles with airport codes (`TMB to EYW`, `VDF->07FA`) are matched against 85k airports for free. For plain-English titles ("fly the Hendersons to Love Field, wheels up 9"), add **your own Anthropic API key** and Claude parses them.
- **Cheapest plan for the week** — a shortest-path search over every fly / deadhead / Uber / overnight decision, shown next to the "always fly home" plan so you see what it saves.
- **Your own numbers** — fuel and engine $/hr, hotel, Uber rates, cruise speed, what an hour of your time is worth. Change one and the plan reorganizes on the spot.
- **Find my plane** — live ADS-B position while it's flying, otherwise where the schedule says it landed.

Every pilot gets their own account: their own schedule, their own numbers, their own encrypted API key, and no visibility into anyone else's.

## Using it

1. **Create an account** at the link above.
2. **Open Settings** and set your **Home base** (any code: `KTMB`, `TMB`, `MIA`), tail number, and costs.
3. **Import your calendar** — drop an `.ics` file on the page, or paste your calendar link in Settings and hit **Sync**. Non-flight events (dentist, birthdays) are skipped.
4. **Optional: add your Anthropic API key** in Settings so plain-English event titles get read too. Parsing is billed to your key, never the operator's.
5. **Pick the week's flights** and read the best plan. If the plane is stranded somewhere from last week, set **Plane is at** and the optimizer plans the fetch too.

---

## Local development

Deadhead is one Spring Boot 3.5 app on Java 21, built with Maven. It uses **SQLite** at `data/deadhead.db` locally and **Postgres** in production, picked from `DATABASE_URL` at startup — the same JDBC code path either way.

**Run the server:**

```bash
mvn spring-boot:run
```

Then open `http://localhost:8787`, create an account, and drop in `data/example-week.ics` to see a plan. `data/week.csv.example` shows the CSV format. `data/costs.defaults.properties` supplies a new account's starting values and holds nothing personal.

Copy `.env.example` to `.env` (gitignored) or export variables directly — all are optional locally. With no `DEADHEAD_SECRET_KEY`, a key is generated into `data/secret.key` (gitignored, owner-only permissions).

> ⚠️ **Never commit a real schedule.** `data/flights.json` and `data/deadhead.db` are gitignored: event titles routinely contain client names and addresses.

**Tests:**

```bash
mvn test
```

21 JUnit tests on a throwaway SQLite file (`target/test-deadhead.db`), so a run never touches your local schedule. `AccountIsolationTest` is the one that matters: two accounts on one server, neither able to see the other's flights, settings, or API key. `AuthWebTest` covers the doors — what a signed-out stranger can reach, and what CSRF actually blocks.

## Configuration (production env vars)

| Var | Required | Notes |
|---|---|---|
| `DATABASE_URL` | ✅ | Postgres connection (`postgres://user:pass@host:port/db`). Unset → SQLite at `data/deadhead.db`. |
| `DEADHEAD_SECRET_KEY` | ✅ | Base64, 32 bytes (`openssl rand -base64 32`). Encrypts the API keys pilots store. Unset → a key file on the dyno's disk, which Heroku wipes on restart. |
| `PORT` | optional | HTTP port; Heroku sets it. Defaults to 8787. |

There are no credentials in the repository and no server-wide Anthropic key: AI parsing is bring-your-own-key, per account.

> ⚠️ Rotating `DEADHEAD_SECRET_KEY` makes every stored API key unreadable. The app treats an undecryptable key as "none stored", so pilots are asked to paste theirs again rather than hitting an error — but it is a real re-onboarding.

## Deployment & CI/CD

`.github/workflows/ci.yml` runs on pushes to `newfeature` and `main` and on pull requests into `main`:

```
push to newfeature / PR into main
  → build + run the test suite

push to main
  → build, run the test suite, then push straight to the Heroku app (deadhead-planner)
```

| Environment | App | URL |
|---|---|---|
| Production | `deadhead-planner` | https://deadhead-planner-71dc1c210944.herokuapp.com/login |

- **A merge to `main` is a production deploy** — there is no staging app. Tests run first, and a failure stops the push.
- Deploy auth is the `HEROKU_API_KEY` GitHub Actions secret. Heroku runs `java -jar target/deadhead-0.1.0.jar` (`Procfile`) on Java 21 (`system.properties`).
- The first boot creates the tables.
- ⚠️ **Upgrading an install that predates accounts:** the old single-user `flights` and `blobs` tables are renamed to `*_singleuser_backup` on first startup — nothing is deleted, but those rows belong to whoever ran that install and are not migrated into any new account.

## Architecture

**AI parses at the edges, search decides in the middle.**

```
.ics  ──►  IcsParser  ──►  CalendarParser (Claude, structured outputs)
                            or airport-code fallback
                    ▼
      trips ──► Planner (rules) ──► RoutePlanner (Dijkstra) ──► browser
                    ▲
   this pilot's settings + OurAirports db (85k airports) via GeoCostModel
```

A **node** (`State`) is `(planeAt, pilotAt, time, tripsRemaining)`. An **edge** (`Action`) is one decision — fly a trip, deadhead empty, Uber (plane stays!), overnight — priced by the `CostModel`. The optimal plan is literally the shortest path from `(home, home, week start)` to `(home, home, all trips done)`.

```
src/main/java/deadhead/
  Airport, Trip, State, Action    the data model (records)
  CostModel / GeoCostModel        search decides, cost model estimates
  AirportDb                       OurAirports lookup: TMB / KTMB / IATA all work
  IcsParser                       .ics -> events (start time + human text)
  CalendarParser                  Claude turns event text into trips (pilot's own key)
  WeekFile                        csv fallback
  Planner                         legal moves from a state + their effects
  RoutePlanner                    Dijkstra + plan reconstruction
  HabitPlan                       the "always fly home" baseline
  PlanService                     trips -> plan (stateless; takes the pilot's numbers)
  FlightStore / FlightRepo        one pilot's imported schedule
  CalendarSyncService             hourly re-fetch of each pilot's published calendar
  PlaneLocator                    "find my plane" — live ADS-B, else the schedule
  store/Db, store/Schema          Postgres or SQLite, and the tables
  user/…                          accounts, sign-up, per-account settings + API keys
  user/Secrets                    AES-256-GCM for the keys pilots paste in
  web/SecurityConfig              who can reach what
  web/PlanController              the JSON API (every handler is user-scoped)
  web/AuthController              sign-up and "who am I"
  web/GlobalExceptionHandler      RFC-7807 problem responses
  DeadheadApplication             Spring Boot entry point + beans
src/main/resources/
  static/index.html               the app
  static/login.html, register.html, style.css   the signed-out pages
  application.properties          port 8787, upload limits, cookie policy
```

Rules worth knowing (they were bugs once):

- Waiting for a **same-day** departure is free; waiting across a night must go through `Overnight` so hotels get charged.
- Uber moves only the pilot. Deadheading moves plane *and* pilot.
- Calendar times with a timezone are kept **as written** — the pilot's wall-clock schedule is the truth; only pure-UTC stamps get converted.
- A state where a remaining trip's departure already passed is dead — pruned.

### Accounts and data

- Sign-up is open: email + a password of at least 10 characters, BCrypt-hashed.
- Sessions are cookie-based; every write carries a CSRF token.
- Everything a pilot imports — flights, settings, calendar link — is stored against their user id. Every query in `FlightRepo` and `UserSettings` filters on it, and neither exposes a method that reads a row without one.

### Why bring-your-own-key

There is no supported way to let a pilot "sign in with Claude" and have their Claude subscription pay for an app's API calls. Anthropic's OAuth is restricted to Claude Code and Claude.ai, and third-party subscription access was blocked in April 2026; OpenAI's equivalent works only inside Codex tooling, and Google blocked the Gemini CLI pattern in March 2026. An API key from the Anthropic Console is the only sanctioned path, so that is what Settings asks for.

Keys are AES-256-GCM encrypted with `DEADHEAD_SECRET_KEY` before they touch the database, verified against Anthropic when saved, and never sent back to the browser — the Settings panel only ever shows the last four characters.

## Next ideas

- [ ] Password reset by email (today a forgotten password needs a DB edit)
- [ ] Per-airport overnight hangar/tie-down fees
- [ ] Weather/cancellation risk in the cost model
- [ ] Rate-limit sign-ups if the instance is ever public
