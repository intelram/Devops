# CAD $50 Automated Crypto Plan: Saskatchewan, Canada

**Research and verification date:** 2026-10-13. Fees and policies change, so re-check every item marked *verify* before you fund anything.
**Status:** The software is built and tested in **paper mode only**. Live trading stays off until you go through the approval steps in §6.

> **Bottom line.** With CAD $50, the costs you can control (exchange fees, spread, and especially the **CAD $10 Kraken e-Transfer withdrawal fee**) matter more than strategy cleverness. My recommendation is to use **one asset (BTC) on Kraken Pro**, place **post-only limit orders**, and run a **200-day trend filter checked once a week**. **Paper-trade it for at least 8 weeks first.** This strategy did **not** beat buy-and-hold on long-run return. Its advantage was smaller drawdowns. If you care more about upside than about surviving drawdowns, a single buy-and-hold BTC purchase was historically better, and you don't need a bot for it.

---

## Legend: how sure each claim is

| Tag | Meaning |
|---|---|
| **[V-API]** | I checked it myself against the live exchange API on 2026-10-13 |
| **[V-DOC]** | From the provider's official site or docs, read on 2026-10-13 |
| **[3P]** | From a third-party review or news source only, so treat it as unverified |
| **[A]** | My assumption |

---

## 1. Platform research (Step 1)

### Regulatory filter (applied first)
I only considered platforms that appear as **registered crypto trading platforms in Canada**. The New Brunswick FCNB registry lists **Kraken (Payward Canada Inc., restricted dealer)**, **Coinbase Canada Inc. (restricted dealer)**, **Shakepay Inc. (investment dealer)**, **NDAX**, **Newton**, **Coinsquare/Bitbuy**, **Netcoins**, **Wealthsimple** and **Crypto.com** **[V-DOC]** ([FCNB list](https://fcnb.ca/en/check-licence-and-registration/registered-crypto-asset-trading-platforms)). In Saskatchewan the regulator is the **FCAA**. *Verify* the platform there and on the CSA national list before you deposit.

**Excluded:** offshore and unregistered venues. Pionex appears in many "free bot" lists, but I found **no evidence it is registered for Canadian residents**, so it fails the filter. Binance, Bybit and OKX have also restricted Canadian users in recent years **[3P]**. The free bots built into an unregistered exchange are not worth giving up investor protection, even at CAD $50.

### Exchanges

| | **Kraken Pro** | **NDAX** | **Coinbase (Advanced)** | **Shakepay** | **Newton** |
|---|---|---|---|---|---|
| CAD deposit | Interac e-Transfer, **free** **[V-DOC]** ([Kraken support](https://support.kraken.com/ca/articles/4412259063444)) (Finder says 0.5% **[3P]**) | Interac **free** **[3P]** | Interac, free **[3P]** | Interac **[3P]** | Interac **[3P]** |
| CAD withdrawal | **CAD $10** via e-Transfer **[V-DOC]** | CAD $1.50 e-Transfer **[3P]** | variable / reported ~1% **[3P]** | **[3P]** | **[3P]** |
| Trading fee | **0.40% maker / 0.80% taker** (tier $0+); app 1% instant/recurring **[V-DOC]** ([fee schedule](https://www.kraken.com/features/fee-schedule)) | 0.2% flat **[3P]** | 0.40% / 0.60% **[3P]** | spread-based, 0.5–3% quoted **[3P]** | spread-based (~0.5–2.5%) **[3P]** |
| Direct BTC/CAD and ETH/CAD | **Yes** **[V-API]** | Yes **[3P]** | **No.** The Coinbase Exchange API lists only **USDC-CAD**, so CAD→BTC takes two legs and two fees **[V-API]** | Yes (app) | Yes (app) |
| Min order | BTC **0.00005** (≈CAD 6), ETH **0.001** (≈CAD 3.60), SOL 0.06, cost min CAD 1 **[V-API]** | **[3P]** unknown | **[3P]** | small | small |
| Public trading API | **Yes**, documented; key permissions are granular and withdraw can be disabled **[V-DOC]** | Yes (AlphaPoint) **[A]**, not verified | Yes **[3P]** | **No public trading API** **[A]** | No **[A]** |
| Free automation | Recurring buy in the app (1% fee) **[V-DOC]**; API + free open-source bot | API + self-built bot | API | Recurring buys (claimed no fee/spread on BTC recurring **[3P]**) | none |
| Notable security history | Bloomberg reported Kraken **fended off** 2025 bribery and social-engineering attempts **[3P]** | none found in my search | **May 2025 breach:** bribed outsourced support agents leaked data of about 69.5k customers (names, addresses, partial SSNs). Coinbase refused a $20M ransom **[3P]** ([BankInfoSecurity](https://www.bankinfosecurity.com/bribery-led-coinbase-hack-affects-70000-crypto-customers-a-28465)) | none found | none found |

Live market snapshot, 2026-10-13 05:10 UTC **[V-API]**: BTC/CAD bid 118,913 / ask 118,940 (spread about 0.02%), 24h volume about 3.76 BTC. ETH/CAD about 3,586. The CAD order books are thin compared with USD, but they are more than enough for CAD $50 orders.

### Bots

| Bot | Cost | Fit for CAD $50 |
|---|---|---|
| **This repo (`cryptobot`)** | Free, standard-library Python only (no third-party packages, so less supply-chain risk) | Built around your rules: CAD 50 hard cap, live-trading gates, kill switch, fee budget. You run it on your own PC. |
| **Freqtrade** | Free and open source; supports Kraken through CCXT **[3P]** | Mature, but far more complex. Kraken serves only 720 historical candles via the API **[3P]**. Using CAD as the stake currency is **unverified**. |
| **Paid cloud bots** (3Commas, Cryptohopper, etc.) | Monthly subscriptions | **Rejected.** One month of fees would be a large share of CAD $50, and they also need your exchange API keys on their servers. |

---

## 2. Three best combinations (Step 6.1 and 6.2)

| # | Combination | What you pay (CAD $50, ~4 trades/yr) | Free features | Trade-offs |
|---|---|---|---|---|
| **1 (recommended)** | **Kraken Pro + this repo's bot** (post-only limit orders) | Maker 0.40%: about **CAD $0.20 per trade**, roughly $0.80/yr. If orders end up crossing the spread, taker 0.80% is about $1.60/yr. Deposit free. **Withdrawal to bank CAD $10.** | Everything: paper mode, risk engine, logs, alerts (ntfy.sh, free) | You host it (PC, laptop or Raspberry Pi running hourly cron). The live path is code-complete, but it has not run against a real account. |
| 2 | **Kraken Pro + Freqtrade** | Same Kraken fees | Backtesting, dry-run, Telegram control | Steep learning curve. Large dependency tree. CAD stake unverified. Easy to misconfigure into over-trading. |
| 3 | **No-code: platform recurring buy** (Kraken app recurring 1%, or Shakepay recurring BTC) | Kraken app: 1% of each buy. Shakepay: spread **[3P]** | Built in, nothing to host | Can only buy on a schedule; no trend filter and no risk controls. Shakepay has no API. |

**Fee-cheapest alternative to watch:** NDAX's reported 0.2% flat fee and CAD $1.50 withdrawal **[3P]** would be cheaper than Kraken. I did **not** build or verify an NDAX connector, so it is not recommended yet.

**The withdrawal trap:** cashing CAD $50 out of Kraken by e-Transfer costs **CAD $10 (20%)**. Plan to leave funds on the exchange until the balance is large enough for that fee not to matter, or withdraw BTC to a wallet you control. That costs a network fee instead; *verify* the current amount on Kraken's withdrawal page.

---

## 3. Strategy research (Step 2)

### Asset selection
- **BTC:** largest market cap and deepest CAD liquidity on Kraken **[V-API]**. It has the longest security track record of any chain.
- **ETH:** second-largest, with a large developer ecosystem. Its minimum order is tiny (0.001 ETH) **[V-API]**, so it is *possible* to hold. Historically it has had deeper drawdowns than BTC (see the backtest below).
- **SOL, XRP and others:** have CAD pairs **[V-API]**, but higher volatility and more concentration risk. **Not included:** there is no evidence they improve risk-adjusted outcomes, and every extra asset costs another fee.
- Trending or social-media coins are **excluded by rule**. Only assets in `config.json → universe` can ever trade.

### Diversification decision
70/30 BTC/ETH **did not convincingly help**. In 30 rolling 2-year windows it beat 100% BTC only **11/30** times, had a **deeper** worst case (−54.8% vs −53.6%), and lost money in 9/30 windows against 7/30 for BTC alone. At CAD $50, adding ETH mostly adds fees. **Recommendation: 100% BTC.** The universe allows ETH up to 40% in case you decide otherwise.

### Strategies tested
All strategies start with the same CAD $50 and never add money. Signals use only completed daily candles and fill at the **next day's open**, so there is no look-ahead (there is a test for this). Fees and slippage are charged on every trade.

1. **Buy & hold 100% BTC.** This is the benchmark.
2. **Buy & hold 70/30 BTC/ETH.**
3. **Scheduled entry:** deploy the fixed $50 in 5 × $10 buys, 14 days apart, then hold. No new deposits.
4. **Trend filter:** hold BTC while the last close is above its 200-day simple moving average, otherwise hold CAD. Checked **once a week (Monday)**.
5. **Trend filter 70/30.**
6. **Rebalance 70/30:** checked weekly, trades only when drift exceeds 10 percentage points.
7. *(Sensitivity only, not a candidate)* 100-day trend filter. Tested to show how sensitive the result is to the parameter.

**Fee scenarios (per side):** maker 0.40% + 0.05% slippage; taker 0.80% + 0.15% slippage; app 1.00% + 0.50% spread.

---

## 4. Backtest results (Step 6.5)

Reproduce with `python -m cryptobot fetch-data && python -m cryptobot backtest`. Full output is in `data/backtest_report.txt` and `data/backtest_results.json`.

### A. Actual CAD market: Kraken BTC/CAD and ETH/CAD, 2025-05-11 → 2026-10-12 (after the 200-day warm-up)

| Strategy (taker scenario) | Final CAD | Return | Max drawdown | Trades | Fees |
|---|---:|---:|---:|---:|---:|
| Buy & hold BTC (benchmark) | 39.66 | −20.7% | −52.1% | 1 | 0.40 |
| Buy & hold 70/30 | 42.18 | −15.6% | −57.5% | 2 | 0.40 |
| Scheduled entry BTC | 39.87 | −20.3% | −51.9% | 5 | 0.40 |
| **Trend filter BTC 200d** | **54.26** | **+8.5%** | **−17.8%** | 3 | 1.20 |
| Trend filter 70/30 | 54.10 | +8.2% | −24.1% | 6 | 1.21 |
| Rebalance 70/30 | 45.25 | −9.5% | −53.6% | 4 | 0.49 |
| *Sensitivity: trend 100d* | 40.79 | −18.4% | −37.2% | 9 | 3.34 (hits fee budget) |

### B. Long history (USD proxy): Coinbase BTC/USD and ETH/USD, 2017-07-20 → 2026-10-12

| Strategy (taker scenario) | Final (from 50) | CAGR | Max drawdown | Trades | Fee budget hit? |
|---|---:|---:|---:|---:|---|
| **Buy & hold BTC** | 1,792 | 47.3% | **−83.8%** | 1 | no |
| Buy & hold 70/30 | 1,441 | 43.9% | −84.5% | 2 | no |
| Scheduled entry BTC | 1,193 | 41.0% | −81.0% | 5 | no |
| Trend filter BTC 200d | 331 | 22.7% | −64.9% | 35 | **yes, 2019-04** |
| Rebalance 70/30 | 1,258 | 41.8% | −80.9% | 5 | no |

### C. 30 rolling 2-year windows (quarterly starts, taker fees, USD proxy)

| Strategy | Median 2-yr return | **Worst 2-yr return** | Beats B&H | Shallower drawdown than B&H | Windows that lost money |
|---|---:|---:|---:|---:|---:|
| Buy & hold BTC | 92.1% | −53.6% | n/a | n/a | 7/30 |
| Scheduled entry | 101.2% | −53.4% | 12/30 | 20/30 | 6/30 |
| **Trend filter BTC 200d** | 64.3% | **−31.5%** | 11/30 | **24/30** | 6/30 |
| Rebalance 70/30 | 105.1% | −57.7% | 13/30 | 12/30 | 9/30 |

### What the results mean
- **No strategy reliably beats buy-and-hold on return.** The trend filter's big win in table A came from **one bear-then-recovery period**. Over 9 years it ended at roughly a fifth of buy-and-hold, because it was out of the market during sharp rallies and paid fees on 35 trades.
- **What the trend filter reliably did** was cut the worst outcomes: worst 2-year result −31.5% vs −53.6%, and a shallower drawdown in 24 of 30 windows. That fits your goal of *survival during downturns*.
- **The fee budget works as intended.** Over 9 years, the CAD $2.50 budget would have paused the trend filter in 2019 and asked you to re-approve. On $50, fees really do compound into a meaningful drag.
- **Parameter sensitivity is real.** The 100-day version did worse in the CAD window and hit the fee budget. I fixed the parameter at 200 *before* looking at results (a common published default) and did not optimize it, so the result is not curve-fitted.

### Limitations (please read)
- The CAD data covers only 720 days (the Kraken API limit), leaving **≈520 testable days**. The long test uses **USD prices as a proxy** and ignores CAD/USD exchange-rate moves.
- Daily bars only. Real fills (post-only orders that may not fill, partial fills) will differ, and slippage is an assumption.
- Past performance does not predict future results. Crypto regimes change.
- No taxes are modelled (see §9).

### Paper-trading result so far
I ran one **paper cycle** against live Kraken data on 2026-10-13 05:55 UTC. Signal: BTC above its 200-day SMA (last close 116,766 vs SMA 100,425), so target 100% BTC. The paper buy filled at 119,410.69 CAD, with a CAD $0.40 fee charged. Equity showed −0.9% right away, which is the cost of fees plus spread. A second run the same day did **not** trade again (rate limits worked), and the kill switch blocked a third run.
**This is a smoke test, not a performance result.** A meaningful paper record needs at least 8 weekly decisions. Run it yourself (see §6).

---

## 5. Recommended strategy and risk policy (Steps 2, 3, 6.3, 6.4)

### Proposed allocation (≤ CAD $50)

| Asset | Target | Max allowed | Why |
|---|---:|---:|---|
| BTC | **100% when trend is up, 0% when down** | 100% | Most liquid, lowest fee drag, longest security record |
| ETH | 0% | 40% | Allowed in the universe but not targeted; backtest showed no clear benefit |
| CAD cash | 100% when trend is down | n/a | Kraken pays no yield here **[A]** |

### Why it suits CAD $50
- It trades about **4×/year**, so fees are roughly CAD $0.80–1.60/yr.
- It makes one decision a week, so there is nothing to day-trade and no chasing.
- It sidesteps the largest historical drawdowns, which helps you stay in the plan.
- It is simple enough for you to audit the strategy logic yourself (`cryptobot/strategy.py`, about 100 lines).

### Risk limits (enforced in code: `cryptobot/risk.py`, `cryptobot/config.py`)

| Rule | Value | Enforcement |
|---|---|---|
| Total capital | **≤ CAD 50.00** | Config validation refuses values above 50. Buys cannot push cost basis above 50. |
| Deposits | **Never** | The client has **no** deposit or withdraw functions (a test checks this) |
| Loss recovery | **Never** | Buys are sized on `min(equity, 50)` with only the bot's own cash. Losses are never topped up and position size never increases after a loss. |
| Per-asset max | BTC 100%, ETH 40% | Checked on every buy |
| Orders per day / 30 days | 2 / 6 | Checked on every order |
| Min days between trades on same asset | 6 | Checked on every order |
| Min / max order | CAD 5 / CAD 50, plus the exchange minimums | Checked on every order |
| Fee budget | **CAD 2.50 lifetime (5%)** | When reached, the bot stops trading until you re-approve |
| Drawdown circuit breaker | Equity < CAD 25 → **new buys blocked** | Requires your review. The bot does *not* panic-sell. |
| Order type | **Limit only, post-only** (maker), 1-hour expiry | Market, stop, margin and futures orders do not exist in the code |
| Leverage / margin / shorting / martingale | **Impossible** | Targets must sum to ≤ 1, no negative weights, sells ≤ holdings |

### Automatic pauses (fail closed)
- Kraken status is not `online` (maintenance, `cancel_only`, and so on).
- Server data is more than 120 s off, or the daily candle is stale.
- Spread above 1%, or a crossed or invalid order book.
- 24-hour move above ±15% (abnormal volatility).
- Ticker deviates more than 10% from the last daily close (bad data).
- 3 consecutive API or data errors.
- Exchange balance is lower than the ledger says (reconciliation mismatch).
- The API key turns out to have **withdraw** permission (see §7).

### About stop-losses
**A stop-loss does not guarantee your sale price.** In a fast or gapping market, a stop order triggers and then fills at whatever price is available, which can be far below your stop. A stop-limit can fail to fill at all. On thin CAD order books this risk is higher. This bot uses **no stop orders**. Its downside control is the weekly trend exit, which also cannot guarantee a price.

---

## 6. Beginner setup guide (Step 6.6)

**Phase A: paper only (start here, takes 8+ weeks)**
1. Install Python 3.10+ on your computer. No other packages are needed.
2. `git clone` this repo, `cd Devops`
3. `python -m unittest discover -s tests` should report 30 tests OK.
4. `python -m cryptobot fetch-data && python -m cryptobot backtest` reproduces the tables above.
5. `python -m cryptobot run` runs one paper cycle. `python -m cryptobot status` shows the portfolio.
6. Schedule it hourly (Linux/macOS `crontab -e`):
   `7 * * * * /full/path/Devops/scripts/run_bot.sh >> /full/path/Devops/state/cron.log 2>&1`
   On Windows: Task Scheduler → hourly → `python -m cryptobot run` in the repo folder.
7. Optional phone alerts: install the free **ntfy** app, subscribe to a long random topic, and set `CRYPTOBOT_NTFY_TOPIC` to that topic. Anyone who knows the topic name can read it, so make it unguessable.
8. Review `state/events_paper.jsonl` weekly. **Go/no-go for live:** at least 8 weekly decisions with no unexplained errors, every rejected order makes sense to you, and you accept the drawdowns in §4.

**Phase B: exchange account (no bot yet)**
9. Create a Kraken account at **kraken.com**. Type the address yourself; never use a link from an ad, email or DM. Complete identity verification.
10. Turn on **2FA with an authenticator app or hardware key, not SMS**, for sign-in, funding and the **Global Settings Lock**. Enable the **withdrawal address lock**.
11. Deposit **at most CAD $50** by Interac e-Transfer. The sender name must match your Kraken legal name **[V-DOC]**.

**Phase C: restricted API key**
12. Kraken → Settings → API → Create key. **Enable only:** *Query Funds*, *Query Open Orders & Trades*, *Query Closed Orders & Trades*, *Create & Modify Orders*, *Cancel/Close Orders*. **Leave disabled:** *Withdraw Funds*, *Deposit Funds*, *Export Data*, *Ledger entries*, staking and earn options.
13. If your home IP is stable, set an **IP allowlist** on the key, and set a key **expiry date** (for example 90 days).
14. Store the key **outside the repo** in `~/.config/cryptobot/env` and run `chmod 600` on it. The run script refuses looser permissions. The format is in `scripts/run_bot.sh`.

**Phase D: explicit approval to go live** (all four gates are required, and the bot checks each one on every run)
15. Edit `config/config.json` and set `"live_trading_enabled": true`. Do this *first*, because it changes the config hash.
16. Run `python -m cryptobot approve`, read the limits, and type the exact approval phrase. This records a hash of your risk config, so **any later change to any limit automatically revokes approval**.
17. Add `CRYPTOBOT_LIVE=I_AUTHORIZE_REAL_MONEY` to your env file.
18. Make sure the kill switch is absent: `python -m cryptobot unkill`.
19. Before the first live cycle, the bot checks that your key **cannot withdraw** and refuses to trade if it can.

---

## 7. Security checklist and emergency shutdown (Step 4, 6.7)

**Checklist**
- [ ] The bot never asks for or stores your exchange password, seed phrase or private keys, and the code has no place to put them.
- [ ] Trade-only API key: withdraw **off**, deposit **off**. The bot verifies this at startup with a canary call to `WithdrawMethods`, which per [Kraken docs](https://docs.kraken.com/api/docs/rest-api/get-withdrawal-methods) needs the *Withdraw* permission; if that call succeeds, the bot refuses to trade.
- [ ] Withdrawal address lock and Global Settings Lock enabled on Kraken.
- [ ] Authenticator or hardware-key 2FA, with a unique password stored in a password manager.
- [ ] Key file is `chmod 600`, outside the git repo, and listed in `.gitignore` (`state/`, `.env`).
- [ ] IP allowlist and key expiry set; key rotated every 90 days.
- [ ] Zero third-party Python dependencies. Review `git diff` before any update.
- [ ] Request signing verified against Kraken's published test vector (unit test).
- [ ] Your bot machine has full-disk encryption, an OS that is up to date, and no remote-desktop tools exposed.

**Third-party service risk:** every service you hand an API key to (cloud bots, portfolio trackers, tax tools) becomes part of your attack surface. Its breach is your breach. Even a trade-only key can be abused to **churn your balance into fees** or trade into an illiquid pair as part of a pump. The 2025 Coinbase incident showed that insiders and support staff are a real attack path. Give out read-only keys wherever possible, and **never** give anyone a key with withdraw permission.

**Emergency shutdown (fastest first)**
1. **Kraken website → Settings → API → delete the key.** This works immediately, even if your computer is compromised.
2. On the bot machine, run `python -m cryptobot kill`. The next run cancels all open orders and refuses to trade.
3. Disable the cron job (`crontab -e`, comment out the line).
4. If you suspect compromise: change your Kraken password, reset 2FA, review Kraken's login and API history, and contact Kraken support through the official site only.
5. To resume: create a new key, then `unkill`, then re-run `approve`.

---

## 8. Legitimate free-crypto opportunities (Step 5, 6.8). Verified 2026-10-13.

| Opportunity | What you receive | ID required | Deposit or purchase required? | Legitimacy evidence | Risks and notes |
|---|---|---|---|---|---|
| **Coinbase Learning Rewards** (coinbase.com/learn, inside a logged-in account) | **Real tokens**, small amounts; the token rotates and is **often not ETH** | Yes, full KYC | No | Run by Coinbase itself; Canada listed as eligible **[3P]** ([CoinLedger](https://coinledger.io/tools/learn-and-earn-crypto), [RetireHappy](https://retirehappy.ca/coinbase-review/)). Coinbase's help page returned 403 to me, so **current Canadian availability is unverified**. | Quizzes come and go. Withdrawals cost network fees and Coinbase offers no CAD pairs for the token. Treat any email or text about "rewards" as phishing and go only through the app. |
| **Security bug bounties paid in crypto** (e.g. Immunefi, project-run programs) | **Real ETH or stablecoins** for valid findings | Usually yes for payouts | No | Long-running industry programs **[A, not verified this session]** | This is real work, not a giveaway. As a cybersecurity SME, it is the most realistic way for you to *earn* meaningful ETH. Read each program's scope and legal safe-harbour terms. |
| **Open-source contribution grants and bounties** (e.g. Gitcoin) | Real tokens or stablecoins | Varies | No | **[A, not verified this session]** | Again, earned through work. Check that the bounty is posted from the project's official repo or account. |
| **Shakepay "Shaking Sats"** | Small amounts of real **BTC** (not ETH) | Yes (Shakepay account) | Historically tied to account activity **[3P]** | Shakepay is a registered investment dealer **[V-DOC]**; program existence per 2022 Globe and Mail **[3P]** | **I could not confirm the program still runs in 2026.** Check inside the Shakepay app. |
| **Ethereum testnet faucets** (Sepolia, Hoodi) from [ethereum.org/developers/docs/networks](https://ethereum.org/developers/docs/networks/), e.g. [Google Cloud Sepolia faucet](https://cloud.google.com/application/web3/faucet/ethereum/sepolia), [Alchemy](https://www.alchemy.com/faucets/ethereum-sepolia) | **Testnet ETH: NOT real ETH, no monetary value, cannot be sold or withdrawn as money** **[V-DOC]** | Some ask for a login or captcha | No | Listed on ethereum.org | Useful only for safely *learning* wallets and transactions. Anyone offering to "buy" or "convert" your testnet ETH is running a scam. |

**Airdrops:** I'm not listing any specific airdrop. Nothing currently passed verification, and fake airdrop sites are one of the most common wallet-drainer lures. If you ever pursue one: get the link from the project's official docs or GitHub, use a fresh wallet with no funds, **never sign `setApprovalForAll` or `permit` requests**, never pay a "release" or "gas" fee to an address someone else gives you, and never enter a seed phrase into any website.

**Always excluded:** "mining" apps and bots, guaranteed returns, "doubling" schemes, anything asking for a seed phrase or private key, any "free" reward that needs a payment to unlock, and Telegram/Discord DMs offering help or recovery.

**Tax note (Canada):** crypto received as rewards is generally taxable, and each crypto→CAD sale by the bot is a disposition. Keep the bot's `events_*.jsonl` ledger for your records and *verify* the details with CRA guidance or a tax professional **[A]**.

---

## 9. Realistic expectations (Step 6.9)

- **You can lose all CAD $50.** Exchanges can fail or be hacked, BTC can fall more than 80% (it did in the test period: −83.8% max drawdown), and bugs or misconfiguration can cause losses. Platform registration in Canada is **not** deposit insurance.
- **Realistic range:** history shows 2-year outcomes for the recommended strategy anywhere from **−31.5%** to large gains. In dollars, a bad 2 years could leave around **CAD $34**, a median one around **CAD $82**, before taxes and before the CAD $10 bank-withdrawal fee. These are historical figures, **not a forecast and not a promise**.
- **Fees are a meaningful share:** about CAD $1–1.60/year is 2–3% of capital, and the CAD $10 withdrawal fee is 20%.
- **What actually grows the account is time and new savings.** The bot will never deposit more for you. If you want to add money later, that is your own decision, made outside the bot, and it requires you to change the cap and re-approve.
- **If paper trading shows you're uncomfortable with the drawdowns or the rejected orders, the right choice is to keep holding CAD (or make one manual BTC buy) and not run the bot.** Not trading is a valid outcome of this plan.

---

## Sources (accessed 2026-10-13)
- Kraken public API (AssetPairs, Ticker, OHLC, SystemStatus): https://api.kraken.com/0/public/
- Kraken fee schedule: https://www.kraken.com/features/fee-schedule
- Kraken minimums: https://support.kraken.com/ca/articles/205893708-Minimum-order-size-volume-
- Kraken Interac e-Transfer: https://support.kraken.com/ca/articles/4412259063444
- Kraken API docs: [AddOrder](https://docs.kraken.com/api/docs/rest-api/add-order), [WithdrawMethods](https://docs.kraken.com/api/docs/rest-api/get-withdrawal-methods)
- Coinbase Exchange products API: https://api.exchange.coinbase.com/products
- FCNB registered platforms: https://fcnb.ca/en/check-licence-and-registration/registered-crypto-asset-trading-platforms
- Finder (Canada) reviews: [Kraken](https://www.finder.com/ca/cryptocurrency/exchanges/kraken-exchange-review), [NDAX](https://www.finder.com/ca/cryptocurrency/exchanges/ndax-review), [Coinbase](https://www.finder.com/ca/coinbase-exchange-review)
- Newton fees: https://www.newton.co/blog/a-broader-newton-signature-program
- Coinbase breach: https://www.bankinfosecurity.com/bribery-led-coinbase-hack-affects-70000-crypto-customers-a-28465 ; Kraken/Binance attempts: https://forklog.com/en/bloomberg-hackers-target-binance-and-kraken-in-coinbase-style-attacks/
- Learning rewards: https://coinledger.io/tools/learn-and-earn-crypto ; https://retirehappy.ca/coinbase-review/
- Ethereum testnets and faucets: https://ethereum.org/developers/docs/networks/
- Freqtrade exchange notes: https://www.freqtrade.io/en/stable/exchanges/
