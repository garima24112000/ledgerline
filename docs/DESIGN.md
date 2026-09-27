# Ledgerline — Design Notes

## Architecture

```mermaid
flowchart LR
    client([Merchant backend]) -->|REST + Idempotency-Key| gw[gateway-api :8080]

    subgraph gateway
        gw --> pg[(Postgres 16<br/>ledger + outbox)]
        gw --> redis[(Redis 7<br/>rate limits)]
    end

    gw -->|authorize / capture| bank[mock-bank :8082]
    pg -. outbox relay .-> kafka{{Kafka<br/>payment.events}}
    kafka --> wd[webhook-dispatcher :8081]
    wd -->|retry topics| kafka
    wd -->|exhausted| dlt{{payment.events DLT}}
    wd -->|HMAC-signed webhook| dm[demo-merchant :8083]
```

| Module | Port | Responsibility |
|---|---|---|
| `gateway-api` | 8080 | Payments REST API, idempotency, double-entry ledger, outbox relay, rate limiting |
| `webhook-dispatcher` | 8081 | Consumes `payment.events`, delivers signed webhooks with retry topics + DLT |
| `mock-bank` | 8082 | Fake card processor: randomly succeeds, fails, is slow, or times out |
| `demo-merchant` | 8083 | Receives webhooks, verifies HMAC, logs them, can be told to fail |

Every app exposes `/actuator/health` (with liveness/readiness probes for Kubernetes) and
`/actuator/prometheus`, and runs request handling on Java 21 virtual threads
(`spring.threads.virtual.enabled=true`). gateway-api serves Swagger UI at `/swagger-ui.html`.

## Decisions

### Project layout
- **Maven multi-module with one parent pom.** The parent inherits `spring-boot-starter-parent` 3.3.13
  so all modules share one dependency set. Dependencies every app needs (web, actuator,
  Prometheus registry, test starter) are declared once in the parent.
- **Dependencies arrive with the features that use them.** For example, Kafka and Redis clients are
  not on the classpath yet, so auto-configuration doesn't try to connect to services nothing uses.

### Database
- **Flyway owns the schema, Hibernate only validates it** (`ddl-auto: validate`). This fits the
  rule that invariants live in Postgres: constraints and triggers are written by hand in SQL
  migrations, never generated. `V1__init.sql` is an empty baseline; `V2__ledger.sql` adds merchants
  and the ledger (see [Ledger](#ledger)).
- Flyway 10 needs `flyway-database-postgresql` alongside `flyway-core`.
- `open-in-view: false`, so no lazy loading can happen in the web layer, and each transaction
  boundary is explicit in the service layer.

### Testing
- **Unit tests are `*Test` (Surefire); integration tests are `*IT` (Failsafe, run during `mvn verify`).**
  This keeps the fast feedback loop fast, while `mvn verify` still runs everything.
- **Integration tests use real Postgres through Testcontainers**, wired in with `@ServiceConnection`
  (no hand-written JDBC URL properties). An in-memory H2 would hide Postgres-specific behavior
  (constraints, triggers, `SELECT ... FOR UPDATE SKIP LOCKED`), and the ledger depends on exactly that.
- **Docker 29 compatibility.** Testcontainers' bundled docker-java defaults to Docker API 1.32.
  Docker Engine 29 requires at least 1.40 and answers with an empty `400`, which Testcontainers reports as
  "Could not find a valid Docker environment". The parent pom sets `api.version=1.44` as a Failsafe
  system property, so the fix lives in the repo, not in each developer's `~/.docker-java.properties`.
  Testcontainers is also raised to 1.21.x (Boot 3.3 ships 1.20.x).
- **ITs use `@AutoConfigureObservability`.** `@SpringBootTest` disables metrics exporters by default,
  so `/actuator/prometheus` returns 404 in tests unless the annotation re-enables them.

### Local infrastructure (`infra/docker-compose.yml`)
- **Kafka runs single-node KRaft** (`apache/kafka`): one process is both broker and controller, with no ZooKeeper.
- **Two client listeners.** A Kafka client uses the bootstrap address only for its first
  connection. After that it connects to whatever address the broker *advertises*, so a single
  listener can't serve both the host and other containers:
  - `EXTERNAL` → advertised as `localhost:9092`, for apps on the host (IDE, `make run-all`).
  - `INTERNAL` → advertised as `kafka:29092`, for containers on the compose network (the
    inter-broker listener too).
  - `CONTROLLER` → `:9093`, used only for KRaft quorum traffic and never published.
- **Postgres is published on host port 5433**, not 5432, so it doesn't clash with a natively
  installed Postgres (common on dev laptops). Containers still use `postgres:5432`. gateway-api
  defaults to `localhost:5433`, overridable with `DB_URL` / `DB_USERNAME` / `DB_PASSWORD`.
- All services have healthchecks, and `make up` uses `docker compose up --wait`, so it returns only
  once Postgres, Redis and Kafka are ready.

### Security
- Spring Security is on the classpath but **temporarily permits all requests** (CSRF disabled, since
  this is a stateless JSON API). Merchant API-key auth replaces this once payments exist.
  `/admin/**` (currently only `/admin/ledger/verify`) is also open for now. It must get its own
  operator role before anything is deployed.

## Ledger

Code: `gateway-api/.../ledger/`, schema: `V2__ledger.sql`.

### Model
- **Accounts** hold a cached `balance`. **Journal entries** group **postings**. Each posting
  debits or credits one account by a positive amount (`BIGINT` paise).
- **Sign convention: `balance = credits − debits` for every account.** One rule, with no
  per-account "normal side". Because every entry balances, **the sum of all balances is always 0**,
  and `/verify` reports that sum as the global net.
- **Chart of accounts**

  | Account | Owner | `allow_negative` | Meaning |
  |---|---|---|---|
  | `CUSTOMER_FUNDS` | platform | yes | Clearing account money arrives through. It is debited on capture, so it goes negative by design. |
  | `MERCHANT_PAYABLE` | one per merchant | no | What we owe the merchant. It can never go below 0, so a refund larger than the merchant's balance fails in the DB. |
  | `PLATFORM_FEES` | platform | no | Our revenue. |
  | `REFUNDS` | platform | yes | Seeded for the refund flow; unused so far. |

- **Capture of A with fee F:** debit `CUSTOMER_FUNDS` A, credit `MERCHANT_PAYABLE` A−F,
  credit `PLATFORM_FEES` F. When F rounds down to 0 (A < 50 paise), the fee posting is left out,
  because postings must be positive. The entry then has two postings instead of three.
- **Platform accounts have `owner_id NULL`** and are seeded by the migration. The unique index
  uses `NULLS NOT DISTINCT` (Postgres 15+); otherwise NULLs never collide and several
  `PLATFORM_FEES` accounts could be created. A CHECK ties the two together:
  `owner_type = 'PLATFORM'` ⇔ `owner_id IS NULL`.
- **Enum-like columns are `TEXT` + `CHECK`**, not Postgres `ENUM` types. Adding a value is a
  one-line migration, and Hibernate maps them with plain `@Enumerated(STRING)`.
- **Single currency (INR) for now.** Accounts carry `currency`, and the commit trigger rejects an
  entry whose postings span currencies. Multi-currency would need one account set per currency.

### Fees: 2% rounded down, without overflow
`Fees.captureFee` uses basis points (200 bp). `amount * 200 / 10_000` overflows `long` above
~4.6·10¹⁶, so the amount is split as `amount = q·10,000 + r` and the fee computed as
`q·200 + r·200/10,000`. This is exact floor division, and neither term can overflow. Unit tests
cover 1 and 49 (fee 0), 50 (fee 1), 99 (rounds down, not up), and `Long.MAX_VALUE`, checked against
`BigInteger`.

### Invariants enforced by Postgres
Java validates the same rules first (`JournalEntryRequest` rejects unbalanced input), but that only
gives nicer errors. The database is the authority:

| Invariant | Mechanism |
|---|---|
| Debits = credits per entry; an entry has ≥ 1 posting; one currency | `DEFERRABLE INITIALLY DEFERRED` constraint triggers on `journal_entries` and `postings` insert, calling `ledger_assert_entry_balanced(entry_id)` |
| Ledger is append-only | `BEFORE UPDATE OR DELETE` row triggers and `BEFORE TRUNCATE` statement triggers on both tables |
| Posting amount > 0 | `CHECK (amount > 0)` |
| No overdraft | `CHECK (allow_negative OR balance >= 0)` on `accounts` |
| A payment is captured at most once | partial unique index `journal_entries(payment_id) WHERE type = 'CAPTURE'` |

- **Why deferred:** the entry row and its postings are separate `INSERT`s, so the entry is
  unbalanced between statements. A deferred trigger checks at `COMMIT`, when the entry is
  complete. The integration test proves this: both raw-SQL inserts succeed, and only `commit()` fails.
- **Why a trigger on `journal_entries` too:** the `postings` trigger only fires if a posting
  exists. Without the second trigger, an entry with no postings at all would commit.
- **Trigger cost:** the check runs once per inserted row, so 4 times per 3-posting capture. Each run
  is an index lookup (~0.25 ms, see below). Bulk-loading 200k entries took 88 s at commit (~110 µs
  per trigger call), which is fine for OLTP but worth knowing for backfills.
- **Error codes:** the triggers raise `check_violation` / `integrity_constraint_violation`, so
  Spring translates them to `DataIntegrityViolationException` like any other constraint.
- **Limitation:** the table owner can still `ALTER TABLE ... DISABLE TRIGGER`. In production the
  app would connect as a role with only `SELECT, INSERT` on the ledger tables, and migrations would run
  as a separate owner role.

### Posting: one transaction, ordered row locks
`LedgerService.post` is `@Transactional` and does, in order:
1. Nets the postings per account in a `TreeMap`, which gives the account ids sorted ascending.
2. `SELECT id FROM accounts WHERE id IN (...) ORDER BY id FOR UPDATE`, which locks every affected
   account. It fails early if an id is unknown.
3. Inserts the entry and its postings.
4. `UPDATE accounts SET balance = balance + :delta, version = version + 1` per account.

- **Why a consistent lock order:** if T1 locks A and waits for B while T2 holds B and waits for A,
  Postgres detects a deadlock and aborts one of them. When every transaction locks in ascending id
  order, that cycle can't form. The plan below shows the `ORDER BY` is satisfied by the primary-key
  index scan under `LockRows`, so rows are locked in id order as they come off the index.
- **Hot rows:** every capture locks `CUSTOMER_FUNDS` and `PLATFORM_FEES`, so all captures across all
  merchants serialize on those two rows for the length of the transaction. This is fine at this
  project's scale. The usual fix is to split each platform account into N sub-accounts and pick one
  at random (sum them for reporting).
- **JPA vs SQL:** `Account` and `Merchant` are JPA entities (lookups, onboarding). The posting path
  is `JdbcClient` SQL, following the rule to use plain SQL where locking matters. `Account.balance`
  is mapped `insertable = false, updatable = false`, so a stale entity can never overwrite a balance.
  `@Version` makes any other JPA update optimistic-locked. `JpaTransactionManager` shares its JDBC
  connection with `JdbcClient`, so both run in the same transaction.
- **The concurrency test** fires 50 captures for one merchant at once on virtual threads, released
  together by a latch through a 10-connection pool. It asserts exact final balances on all three
  accounts, and that `/verify` is clean.
- **What the concurrency test doesn't show:** the `balance = balance + delta` update is atomic by
  itself, so it would not lose updates even without `FOR UPDATE`. The explicit lock matters for code
  that *reads* a balance and then decides, for example a refund checking funds or a payout. It also
  makes the lock order a visible, deliberate step instead of a side effect of update order.

### Cached balances and `/admin/ledger/verify`
The balance is cached on `accounts` so reads are O(1). The postings are the source of truth.
`GET /admin/ledger/verify` returns:
- `allEntriesBalanced` + `unbalancedEntryIds`: groups postings by entry.
- `balancesMatchPostings` + `mismatchedAccountIds`: compares each cached balance with
  credits − debits of its postings.
- `globalNet`: `SUM(accounts.balance)`, which must be 0. This checks the *cache*. The same sum over
  postings would be 0 whenever entries balance, so it would tell us nothing new.
- `consistent`: all of the above.

It runs `@Transactional(readOnly, REPEATABLE_READ)`, so all three queries read one snapshot. A
capture committing between them can't cause a false alarm. It always returns 200; callers
(monitoring, a scheduled job) check `consistent`.

**Trade-off:** cached-balance correctness is *detected* by `/verify`, not *enforced* by Postgres. A
posting-insert trigger could maintain balances itself, but that moves the business logic into
PL/pgSQL and hides it from the service layer. The integration test proves that drift is detected.

### Indexes

Measured on Postgres 16 with 1,003 accounts, 200,000 journal entries, and 600,000 postings (200 per
merchant account, 200,000 each on the platform accounts), after `VACUUM ANALYZE`.

| Index | Used by | With index | Without |
|---|---|---|---|
| `accounts(owner_type, owner_id, type)` UNIQUE `NULLS NOT DISTINCT` | Account lookup in `capture`; uniqueness | 0.11 ms index scan | — (uniqueness needs it anyway) |
| `postings(journal_entry_id)` | Commit-time balance trigger, run on every insert | 0.25 ms | 8.5 ms parallel seq scan, **growing with ledger size, on every write** |
| `journal_entries(payment_id)` | Finding a payment's entries (refunds, support) | 0.14 ms | 17 ms seq scan |
| `postings(account_id)` | Account statements, recomputing one account's balance, FK checks if an account is ever deleted | 1.9 ms (200 rows) | 8.3 ms seq scan |
| `accounts` PK | `SELECT ... FOR UPDATE` lock step | 0.13 ms | — |

**`accounts(owner_type, owner_id, type)`**: both lookups (merchant by id, platform by `IS NULL`) use
all three columns as index conditions:
```
Index Scan using accounts_owner_type_uq on accounts (actual time=0.070..0.071 rows=1 loops=1)
  Index Cond: ((owner_type = 'MERCHANT') AND (owner_id = 500) AND (type = 'MERCHANT_PAYABLE'))
Execution Time: 0.113 ms

Index Scan using accounts_owner_type_uq on accounts (actual time=0.024..0.025 rows=1 loops=1)
  Index Cond: ((owner_type = 'PLATFORM') AND (owner_id IS NULL) AND (type = 'PLATFORM_FEES'))
Execution Time: 0.032 ms
```

**Lock step** (primary key; ordered index scan feeding `LockRows`, no separate sort):
```
LockRows (actual time=0.106..0.116 rows=3 loops=1)
  ->  Index Scan using accounts_pkey on accounts (actual time=0.019..0.025 rows=3 loops=1)
        Index Cond: (id = ANY ('{1,503,2}'::bigint[]))
Execution Time: 0.131 ms
```

**`postings(journal_entry_id)`**: the most important index. The deferred trigger queries postings
by entry for every inserted row. Without the index, each capture would pay 4 sequential scans of
the whole postings table at commit, and that gets slower as the ledger grows. Postgres does not
create indexes on FK columns automatically.
```
Aggregate (actual time=0.167..0.168 rows=1 loops=1)
  ->  Sort (actual time=0.151..0.151 rows=3 loops=1)
        ->  Nested Loop (actual time=0.085..0.115 rows=3 loops=1)
              ->  Bitmap Heap Scan on postings p (actual time=0.076..0.102 rows=3 loops=1)
                    ->  Bitmap Index Scan on postings_journal_entry_id_idx (actual time=0.069..0.069 rows=3 loops=1)
                          Index Cond: (journal_entry_id = 123456)
              ->  Index Scan using accounts_pkey on accounts a (actual time=0.003..0.003 rows=1 loops=3)
Execution Time: 0.245 ms

-- same query without the index:
Parallel Seq Scan on postings p (actual time=1.479..5.655 rows=1 loops=3)
  Filter: (journal_entry_id = 123456)
  Rows Removed by Filter: 199999
Execution Time: 8.463 ms
```

**`journal_entries(payment_id)`**: the refund flow will look up a payment's capture and earlier
refunds by `payment_id`. The partial unique index (`WHERE type = 'CAPTURE'`) can't serve that query
because it only contains captures, so this general index is separate.
```
Index Scan using journal_entries_payment_id_idx on journal_entries (actual time=0.129..0.130 rows=1 loops=1)
  Index Cond: (payment_id = '01b4d174-...'::uuid)
Execution Time: 0.141 ms

-- without: Seq Scan on journal_entries, Rows Removed by Filter: 199999, Execution Time: 17.382 ms
```

**`postings(account_id)`**: merchant account statements and single-account reconciliation. It
also keeps FK checks on `accounts` from scanning `postings`. It pays off for selective accounts
(merchants). For the hot platform accounts, which hold a third of all postings each, the planner
correctly ignores it and seq-scans (12 ms), so platform-level reporting should read the cached
balance, not sum postings.
```
Aggregate (actual time=1.892..1.892 rows=1 loops=1)
  ->  Index Scan using postings_account_id_idx on postings (actual time=0.030..1.853 rows=200 loops=1)
        Index Cond: (account_id = 503)
Execution Time: 1.900 ms

-- without: Parallel Seq Scan on postings, Rows Removed by Filter: 199933, Execution Time: 8.255 ms
```

**`/verify` queries are full scans on purpose.** An audit has to read every posting, so no index
can make it cheaper. Measured: unbalanced-entry check 274 ms (the planner walks
`postings_journal_entry_id_idx` to feed a streaming `GroupAggregate`); balance-mismatch check 31 ms
(parallel seq scan + hash aggregate, merge-joined to the accounts PK); global net 0.1 ms. That is
acceptable for an operator endpoint or nightly job. At a much larger scale, verification would run
incrementally (only entries since the last checkpoint) or on a read replica.

### Why it's built this way (interview notes)

#### Why double-entry?
Single-entry bookkeeping is `merchant.balance += 9800`. If a bug skips the fee credit, or credits
twice, nothing notices: the number is just wrong, and there is no history to explain it.

Double-entry records every movement as a journal entry whose debits equal its credits. Money is
never created or destroyed, only moved between accounts. That gives three things:
1. **A built-in checksum.** All balances must sum to 0. Any code path that "forgets the other side"
   breaks this and `/verify` catches it.
2. **An audit trail.** The postings *are* the history. Every balance can be recomputed and
   explained line by line ("why is this merchant owed ₹98?" → these postings).
3. **Immutability.** Mistakes are fixed with a reversing entry, never by editing. The past stays
   true, which is what auditors, reconciliation with the bank, and disputes need.

The cached balance is an optimisation on top. The postings are the source of truth.

#### Why a deferred constraint trigger instead of (only) a Java check?
- **A Java check protects one code path; the database protects all of them.** Raw SQL fixes in
  `psql`, a future second service, a data migration, a refactor that bypasses `LedgerService`: none
  of them go through the Java check. The trigger applies to every writer. The Java check is still
  there, for a fast, clear error and a unit-testable rule.
- **Why a trigger, not a `CHECK`:** a `CHECK` constraint only sees the row being written.
  "Debits = credits" is a rule across many rows. The SQL standard's `CREATE ASSERTION` would express
  it, but Postgres doesn't implement it. A constraint trigger is the Postgres way to get a
  cross-row constraint.
- **Why deferred:** an entry is written with several `INSERT`s (entry, then each posting). After the
  first posting it is unbalanced *by construction*, so an immediate check would reject every valid
  entry. `DEFERRABLE INITIALLY DEFERRED` runs the check at `COMMIT`, when the entry is complete. If
  it fails, the whole transaction rolls back, including the balance updates.

#### Why lock rows in id order?
A deadlock needs a cycle: T1 holds A and waits for B, while T2 holds B and waits for A. Postgres
detects it after `deadlock_timeout` (1 s by default) and aborts one transaction with `40P01`. That
means a failed payment and a one-second latency spike.

If every transaction acquires locks in the same global order (ascending account id), a cycle is
impossible. A transaction only ever waits for a lock with a *higher* id than every lock it holds, so
the wait-for chain can't loop back. Reproduced on Postgres 16, with two transactions updating accounts
1 and 2:

| Lock order | Result |
|---|---|
| T1: 1 then 2, T2: 2 then 1 | T1 aborted: `ERROR: deadlock detected` |
| Both: 1 then 2 | T2 waits for T1, then both commit |

Details that come up in interviews:
- The order only protects you if **every** code path follows it, including refunds and payouts.
  That's why locking is one explicit step in `LedgerService.post`, not scattered updates.
- `ORDER BY id ... FOR UPDATE` locks rows in output order. Postgres warns that rows can come back
  out of order if the sort key changes while waiting. `id` never changes, so that can't happen here.
- Taking all locks **up front, before any writes** also means no work is thrown away halfway if a
  lock can't be taken.
- `FOR NO KEY UPDATE` would be a slightly weaker, sufficient lock, because we never change the key.
  It doesn't block other transactions' FK checks (`FOR KEY SHARE`) from inserting postings that
  reference the account. Since every writer here locks first anyway, `FOR UPDATE` is kept for clarity.

#### What would break with READ COMMITTED and no `FOR UPDATE`?
In READ COMMITTED (the Postgres default, and what `post()` runs in), **each statement** sees a new
snapshot of committed data. Measured on Postgres 16, with two concurrent transactions each adding
9,800 to a balance of 0:

| Pattern | Final balance | Why |
|---|---|---|
| App reads balance, computes, writes `balance = :value` | **9,800** (lost update) | Both read 0, both write 9,800. The second write overwrites the first. |
| `UPDATE SET balance = balance + 9800` (what `post()` does) | 19,600 ✓ | The second `UPDATE` waits for the row lock, then **re-evaluates against the newly committed row** before applying `+ 9800`. |
| Read with `FOR UPDATE`, compute, write | 19,600 ✓ | The second reader waits for the lock and then reads the committed 9,800. |

So in *today's* code, removing `FOR UPDATE` would **not** lose balance updates, because the atomic
increment is safe on its own. What breaks is anything that **reads, decides, then writes**:
- **Refund limits** ("total refunds ≤ captured amount"). Two refunds of ₹60 on a ₹100 capture each
  read "refunded so far = 0", each pass the check, and both insert. The measured result is 120
  refunded. Neither transaction updated a row the other read, so nothing conflicts.
- **Funds checks** ("merchant has enough for this payout"). The `balance >= 0` CHECK still stops a
  negative balance, but the user gets a constraint-violation error instead of a clean
  "insufficient funds", and rules that aren't single-row CHECKs aren't protected at all.
- **Deadlock safety becomes accidental.** It depends on the update loop happening to iterate in id
  order, instead of being a deliberate step.
- A read-modify-write in Java (loading the `Account` entity, `setBalance`, save) would lose
  updates outright. `/verify` would report the account as mismatched, because the postings would
  still sum correctly.

The fix for the refund race under READ COMMITTED is to lock a common parent row (the payment, or the
merchant's account) `FOR UPDATE` **before** reading the refund total. Measured result: 60 refunded,
and the second refund is rejected. It works because after waiting for the lock, READ COMMITTED's
*next statement* takes a fresh snapshot that includes the first refund.

#### READ COMMITTED vs REPEATABLE READ vs SERIALIZABLE in Postgres
All three results below were reproduced with two concurrent `psql` sessions on Postgres 16.

| Level | Snapshot | Lost update (read-modify-write) | Write skew (refund race) | Must retry on `40001`? |
|---|---|---|---|---|
| READ COMMITTED | New one per **statement** | **Happens** (9,800 instead of 19,600) | **Happens** (120 refunded) | No |
| REPEATABLE READ | One per **transaction** (snapshot isolation) | Prevented: second writer gets `could not serialize access due to concurrent update` | **Happens** (120 refunded) | Yes |
| SERIALIZABLE | One per transaction + conflict detection (SSI) | Prevented | Prevented: second gets `could not serialize access due to read/write dependencies among transactions` (60 refunded) | Yes |

- **READ COMMITTED.** No transaction-wide snapshot, so two reads in one transaction can disagree
  (non-repeatable reads, phantoms). Concurrent writers to the same row are serialized by the row lock
  and re-evaluate their `WHERE`/`SET` against the latest version. Correctness for read-then-write
  logic needs explicit locks.
- **REPEATABLE READ.** Every statement sees the snapshot taken at the transaction's first
  statement. In Postgres this also prevents phantoms, which is stronger than the SQL standard
  requires. If you try to update or lock a row that someone else changed and committed after your
  snapshot, you get `40001` instead of silently overwriting. So lost updates become errors. **Write
  skew still gets through:** each transaction reads the same data, then writes *different* rows (two
  new refund rows), so there is no write-write conflict to detect.
- **SERIALIZABLE.** Adds predicate locks ("SIRead" locks) that track what each transaction *read*.
  If transaction A read something that B then wrote, and B read something that A then wrote, that's a
  dangerous cycle, and Postgres aborts one of them. It's the only level where "each transaction is
  correct alone ⇒ correct together" holds with no manual locking. The costs: you must retry
  `40001`; there are false positives (sequential scans take relation-level predicate locks and
  conflict more, so good indexes matter); and there's memory overhead for predicate locks.

**Surprise found while measuring:** under REPEATABLE READ, locking the parent row first did **not**
fix the refund race. The result was still 120. T2's snapshot was taken by its first statement
(the `FOR UPDATE`) *before* T1 committed. When T1 committed, T2 got the lock, but T1 had only locked
the row, not updated it, so there was no conflict to raise, and T2's `SUM` still read its old
snapshot. The "lock the parent row" pattern relies on READ COMMITTED's per-statement snapshot. Under
REPEATABLE READ, the parent row must actually be *updated* (then the second transaction gets
`40001`), or you use SERIALIZABLE. In this ledger, every refund would `UPDATE` the merchant account's
balance, so REPEATABLE READ would raise `40001` there. But that protection would be a side effect
of the balance update, and it's better not to rely on it.

**What Ledgerline uses:** READ COMMITTED + explicit `FOR UPDATE` in a fixed order for money
movement. It needs no retry loop, the behaviour is easy to reason about, and on hot rows blocking is
cheaper than repeated aborts. `/verify` uses REPEATABLE READ, because it's read-only and only needs
one consistent snapshot across its three queries.

#### Optimistic (`@Version`) vs pessimistic (`FOR UPDATE`) locking
- **Optimistic:** no lock is held while working. The write is
  `UPDATE ... SET ..., version = version + 1 WHERE id = ? AND version = ?`. If 0 rows are affected,
  someone else got there first, and JPA throws `OptimisticLockException`. You then retry or return
  `409 Conflict`.
- **Pessimistic:** take the row lock before reading. Others wait instead of failing.

| Pick optimistic when | Pick pessimistic when |
|---|---|
| Conflicts are **rare** (different users editing different things) | Conflicts are **common**: hot rows like `PLATFORM_FEES`, which every capture touches |
| The "transaction" spans **user think time** or several HTTP requests. You can't hold a DB lock while someone edits a form | The critical section is **short** and entirely inside one DB transaction |
| Failing with "someone else changed this, reload" is an acceptable answer | The operation **must succeed** and retries are costly or have side effects |
| Reads vastly outnumber writes | You need to read-then-decide on current data (funds and limit checks) |

Why not optimistic for the ledger: with 50 concurrent captures on the same accounts, only one
wins each round and the other 49 retry. That's roughly n² attempts, the retry storm gets worse
exactly when load is highest, and each retry re-runs the whole transaction. Pessimistic locking
queues them instead: each waits once, and all 50 succeed.

The costs of pessimistic locking: waiting holds a pooled connection, and a slow lock holder
stalls everyone behind it. So **never hold a row lock across a network call**. The call to
mock-bank happens *before* the ledger transaction, never inside it. Keep the locked section to a
few milliseconds of SQL.

In this project: the ledger uses pessimistic locking. `Account` still carries `@Version`, so any
future JPA edit of an account (such as toggling `allow_negative`) fails loudly instead of
overwriting a concurrent change. Merchant profile edits (webhook URL, tier) are the textbook
optimistic case: rare conflicts, and a human in the loop.

### Testing
- **Unit (`FeesTest`, `LedgerServiceTest`, Mockito):** fee rounding edge cases; exact postings
  built for a capture (including the dropped zero-fee posting and `Long.MAX_VALUE`); lock-then-write
  ordering (`InOrder`) with sorted ids; netting of repeated accounts; unknown accounts fail before
  any write.
- **Integration (`LedgerIT`, Testcontainers Postgres 16):** balanced capture; unbalanced raw-SQL
  entry fails at `commit()`; entry without postings fails; `UPDATE`/`DELETE`/`TRUNCATE` on
  `postings` and `journal_entries` fail; double capture and overdraft are rejected and roll back;
  50 concurrent captures; `/verify` clean, and detects deliberately corrupted balances.
- Ledger rows can't be deleted, so ITs don't clean up. Each test creates its own merchant and
  asserts on *changes* to the shared platform accounts.
