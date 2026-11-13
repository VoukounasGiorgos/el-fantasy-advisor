# EL Fantasy Advisor

Android app that recommends players for each round of the **EuroLeague Fantasy Challenge**, using statistical analysis of the data the game already exposes.

> Personal, non-commercial project. Not affiliated with Euroleague Basketball or Dunkest/Fantaking.

## What it does

For the next round (matchday) it suggests ~16 players split by position and price tier, plus the top 3 captain picks:

| | Premium (≥ 14 cr) | Mid (9–14 cr) | Budget (< 9 cr) |
|---|---|---|---|
| Guards | 2 | 2 | 2 |
| Forwards | 2 | 2 | 2 |
| Centers | 1 | 1 | 2 |

Every suggestion comes with three scores and a short explanation:

1. **xFP – expected fantasy points**
   `PIR per minute (recent form + season) × expected minutes (+ minutes freed by injured teammates)
   × opponent defense vs position × home/away factor × (1 + 10% × P(win))`
2. **Value** – xFP per credit and the expected price change. The price model is a least-squares fit
   (`Δprice ≈ b0 + b1·FPT + b2·price`) on every player's match log, which also gives the
   *break-even* score a player needs to keep his price.
3. **Risk (0–100)** – PIR volatility, minutes stability, missed last game, sample size →
   label *Safe / Balanced / Risk-Upside*.

Ranking per tier: premium by raw xFP, mid by risk-adjusted xFP, budget by value + expected price rise.
Captains: highest xFP, preferring players of the first game day (Turn 1) so you can still switch.

### How the numbers are derived
- **Fantasy points = PIR × 1.1 when the team wins.** The app recomputes PIR from each box-score line, so
  comparing it with the fantasy points tells it who won each game → team results → **Elo ratings** → P(win).
- **Defense vs position**: how much PIR each team allows to guards / forwards / centers relative to those
  players' season averages (shrunk towards 1.0 for small samples).
- **Injuries**: the game's own `is_injured` flag; minutes of injured players who were in the rotation are
  redistributed to healthy teammates of the same position.

## Data & sync
- Source: the EuroLeague Fantasy Challenge web API (`fantaking-api.dunkest.com`): player table & prices,
  per-player match logs (price, fantasy points, minutes, box score), profiles, upcoming matches, game config.
- Stored locally as JSON in the app's internal storage.
- **Automatic**: a WorkManager job runs every ~2h and decides what to do:
  - new prices after a round, or a new target round → full sync, new recommendations, notification;
  - within 12h of the deadline → re-check injuries, notify if the recommendations changed.
- **Manual**: the *Refresh* button runs a full sync (~1–3 minutes).
- History screen: predictions vs actual points per round.

## Tech
Kotlin · Jetpack Compose (Material 3) · Coroutines · WorkManager · OkHttp · kotlinx.serialization.
Built with GitHub Actions (no Android Studio needed) – see `.github/workflows/build.yml`.

## Build
Push to `main` (or run the workflow manually) → **Actions → Build APK → Artifacts → ELFantasyAdvisor-apk**.
