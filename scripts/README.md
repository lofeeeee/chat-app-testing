# Scripts

End-to-end checks that run against a **live server and a real database**. Nothing here is
mocked — that is the point. They exist because the bugs worth catching in this codebase are the
ones unit tests structurally cannot see: transaction rollback semantics, Postgres partition
pruning, WebSocket handshakes, and permission resolution order.

Every bug listed at the bottom of this file was found by running these, and would otherwise
have shipped.

## Running them

Start the stack first (`..\start.bat`), then:

```powershell
powershell -File scripts\e2e-api.ps1
powershell -File scripts\e2e-qr-ws.ps1
```

Both create their own throwaway accounts with a random suffix, so they are safe to re-run and
won't collide with your seeded data.

---

## `e2e-api.ps1` — 45 checks over HTTP

Registration and discriminator allocation, refresh-token rotation and reuse detection, DM
idempotency, message paging, authorisation boundaries, the session list, and the whole QR state
machine.

The checks worth knowing about:

- **A handle cannot sign anyone in.** `name#0971` is an address, not a credential — it's meant
  to be shared publicly and its number is reallocated on rename.
- **Reuse of a rotated refresh token revokes the entire family.** Not just the replayed token.
- **A non-member cannot read a DM**, and an outsider cannot revoke someone else's session.
- **`openDirectMessage` is idempotent** — calling it twice returns the same channel.
- **Resending a nonce returns the original message**, not a duplicate.

---

## `e2e-qr-ws.ps1` — 9 checks over a real WebSocket

Covers what HTTP cannot: the `graphql-transport-ws` handshake on an **anonymous** socket,
`SCANNED` and `APPROVED` push, and token delivery on the poll-secret channel.

Two negative cases matter as much as the positive ones:

- A socket presenting the **wrong poll secret** is refused.
- An **anonymous socket cannot subscribe to messages** — the anonymous path exists only because
  a device waiting on a QR sign-in has no bearer token yet.

---

## A PowerShell trap worth knowing

`.Count` on a single `PSCustomObject` returned by `Where-Object` is `$null`, not `1`. Always
wrap: `@($items | Where-Object { ... }).Count`.

This cost a false failure once — the server was correct and the *test* was wrong. Worth checking
before assuming a red result is real.

---

## Bugs these caught

| Bug | Why it mattered |
|---|---|
| `revokeFamily` ran inside the `@Transactional` method that then threw | The throw rolled the revocation back, so **detecting a stolen refresh token left every stolen session live** — precisely inverted. Fixed with `noRollbackFor`. |
| `AuditLog.record` joined the caller's transaction | Every `LOGIN_FAILED` and `TOKEN_REUSE_DETECTED` row was rolled back by the throw that followed it. The security events most worth having were the ones vanishing. Fixed with `REQUIRES_NEW`. |
| Bare `? IS NULL` on the message cursor | `could not determine data type of parameter` — would have hit the first page load of every channel. Fixed with an explicit `CAST`. |
| One-sided partition predicate | 17 index scans where 4 suffice. Found with `EXPLAIN`, fixed with a two-sided `created_at` window. |
| `Location` resolver returned a `Map` | Spring resolves GraphQL fields from properties, so every field was unmapped. Caught by the boot-time schema inspector. |
| `ensureBucket` only created on `NoSuchBucketException` | MinIO answers **403** for HEAD on a missing bucket where S3 answers 404, so the bucket was never created — it logged a permissions error that wasn't one. |

---

## Adding a check

Keep them **assertive and self-describing**: one line per invariant, naming the behaviour rather
than the mechanism. `"handle cannot be used to sign in"` survives a refactor; `"login mutation
returns 401"` does not.
