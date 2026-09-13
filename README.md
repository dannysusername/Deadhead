# Deadhead ✈️

*"Deadhead" (aviation): flying an empty plane just to reposition it — the expensive habit this app exists to question.*

A web app for pilots flying client trips. Sign up, drop in a week's calendar
(`.ics`); after every leg the optimizer weighs the real choices — fly the plane
home, **leave it there and Uber back**, hotel overnight — and returns the
provably cheapest plan for the week.

Every pilot gets their own account: their own schedule, their own numbers, and
no visibility into anyone else's.

## Run it locally

```bash
mvn spring-boot:run
```

Open http://localhost:8787, create an account, and set **Home base** (any code:
`KTMB`, `TMB`, `MIA`). If the plane is stranded somewhere from last week, set
**Plane is at** — the optimizer plans the fetch too. Drop in an `.ics` exported
from Apple Calendar (File → Export → Export…). A `from,to,date,time` `.csv`
works too — see `data/week.csv.example`.

Event titles need an airport code in them — `TMB to EYW`, `VDF->07FA`. To use
plain-English titles instead ("fly the Hendersons to Love Field, wheels up 9"),
add **your own Anthropic API key** under Settings; those events are parsed by
Claude and billed to your account, not the operator's. Non-flight events
(dentist, birthdays) are skipped either way.

Costs are tuned per account in the app's **Settings** panel — fuel/hr, hotel,
uber rates, what an hour of your time is worth. Change a number, and the plan
reorganizes on the spot. `data/costs.defaults.properties` supplies the starting
values for a new account and holds nothing personal.

## Configuration

Copy `.env.example` to `.env` (gitignored) or export these directly. All are optional.

| Variable | Effect |
|---|---|
| `DEADHEAD_SECRET_KEY` | Base64 32 bytes; encrypts the API keys pilots store. **Set this in production.** Unset → generated into `data/secret.key`. |
| `DATABASE_URL` | Postgres connection (`postgres://user:pass@host:port/db`). Unset → SQLite at `data/deadhead.db`. |
| `PORT` | HTTP port. Defaults to 8787. |

There are no credentials in the repository, and there is no server-wide
Anthropic key: AI parsing is bring-your-own-key, per account.

### Why bring-your-own-key

There is no supported way to let a pilot "sign in with Claude" and have their
Claude subscription pay for an app's API calls. Anthropic's OAuth is restricted
to Claude Code and Claude.ai, and third-party subscription access was blocked in
April 2026; OpenAI's equivalent works only inside Codex tooling, and Google
blocked the Gemini CLI pattern in March 2026. An API key from the Anthropic
Console is the only sanctioned path, so that is what Settings asks for.

Keys are AES-256-GCM encrypted with `DEADHEAD_SECRET_KEY` before they touch the
database, verified against Anthropic when saved, and never sent back to the
browser — the Settings panel only ever shows the last four characters.

## Accounts and data

- Sign-up is open: email + a password of at least 10 characters, BCrypt-hashed.
- Sessions are cookie-based; every write carries a CSRF token.
- Everything a pilot imports — flights, settings, calendar link — is stored
  against their user id. Every query in `FlightRepo` and `UserSettings` filters
  on it, and neither exposes a method that reads a row without one.
- **Never commit a real schedule.** `data/flights.json` and `data/deadhead.db`
  are gitignored: event titles routinely contain client names and addresses.

## How it works

**AI parses at the edges, search decides in the middle.**

```
.ics  ──►  IcsParser  ──►  CalendarParser (Claude, structured outputs)
                            or airport-code fallback
                    ▼
      trips ──► Planner (rules) ──► RoutePlanner (Dijkstra) ──► browser
                    ▲
   this pilot's settings + OurAirports db (85k airports) via GeoCostModel
```

A **node** (`State`) is `(planeAt, pilotAt, time, tripsRemaining)`. An **edge**
(`Action`) is one decision — fly a trip, deadhead empty, Uber (plane stays!),
overnight — priced by the `CostModel`. The optimal plan is literally the
shortest path from `(home, home, week start)` to `(home, home, all trips done)`.

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

- Waiting for a **same-day** departure is free; waiting across a night must go
  through `Overnight` so hotels get charged.
- Uber moves only the pilot. Deadheading moves plane *and* pilot.
- Calendar times with a timezone are kept **as written** — the pilot's
  wall-clock schedule is the truth; only pure-UTC stamps get converted.
- A state where a remaining trip's departure already passed is dead — pruned.

## Tests

```bash
mvn test
```

`AccountIsolationTest` is the one that matters: two accounts on one server,
neither able to see the other's flights or settings. `AuthWebTest` covers the
doors — what a signed-out stranger can reach, and what CSRF actually blocks.

## Deploying

`Procfile` and `system.properties` target a Heroku-style buildpack. Set
`DATABASE_URL` (Heroku Postgres provides it) and `DEADHEAD_SECRET_KEY` as config
vars. The first boot creates the tables.

> Rotating `DEADHEAD_SECRET_KEY` makes every stored API key unreadable. The app
> treats an undecryptable key as "none stored", so pilots are asked to paste
> theirs again rather than hitting an error — but it is a real re-onboarding.

> **Upgrading an install that predates accounts:** the old single-user `flights`
> and `blobs` tables are renamed to `*_singleuser_backup` on first startup —
> nothing is deleted, but those rows belong to whoever ran that install and are
> not migrated into any new account.

`.github/workflows/ci.yml` builds and tests every branch, and **pushes `main`
straight to Heroku**. A merge to `main` is a deploy.

## Next ideas

- [ ] Password reset by email (today a forgotten password needs a DB edit)
- [ ] Per-airport overnight hangar/tie-down fees
- [ ] Weather/cancellation risk in the cost model
- [ ] Rate-limit sign-ups if the instance is ever public
