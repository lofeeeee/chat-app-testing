$ErrorActionPreference = "Stop"
$endpoint = "http://localhost:8080/graphql"
$pass = 0; $fail = 0

function Check($name, $cond, $detail) {
  if ($cond) { $script:pass++; "  PASS  $name" }
  else { $script:fail++; "  FAIL  $name  -> $detail" }
}

function Gql($query, $vars, $token) {
  $headers = @{ "content-type" = "application/json" }
  if ($token) { $headers["Authorization"] = "Bearer $token" }
  $body = @{ query = $query; variables = $vars } | ConvertTo-Json -Depth 12 -Compress
  $r = Invoke-RestMethod -Uri $endpoint -Method Post -Headers $headers -Body $body
  if ($r.errors) { throw ($r.errors | ConvertTo-Json -Depth 8 -Compress) }
  return $r.data
}

$suffix = (Get-Random -Maximum 99999)
$alexName = "alex$suffix"
$samName  = "sam$suffix"

"== auth =="
$reg = 'mutation($i:RegisterInput!){register(input:$i){accessToken refreshToken expiresInSeconds user{id handle username discriminator}}}'
$alex = (Gql $reg @{ i = @{ username=$alexName; email="$alexName@example.com"; password="correct-horse-battery" } } $null).register
$sam  = (Gql $reg @{ i = @{ username=$samName;  email="$samName@example.com";  password="correct-horse-battery" } } $null).register

Check "register allocates a 4-digit discriminator" ($alex.user.handle -match "^$alexName#\d{4}$") $alex.user.handle
Check "discriminator is in 1..9999" ($alex.user.discriminator -ge 1 -and $alex.user.discriminator -le 9999) $alex.user.discriminator
Check "snowflake id serialised as a string" ($alex.user.id -is [string]) ($alex.user.id.GetType().Name)
Check "id exceeds JS safe-integer range" ([double]$alex.user.id -gt 9007199254740991) $alex.user.id

# Same username, second account -> different number, both live
$alex2 = (Gql $reg @{ i = @{ username=$alexName; email="$alexName-2@example.com"; password="correct-horse-battery" } } $null).register
Check "same username, different discriminator" ($alex2.user.discriminator -ne $alex.user.discriminator) "$($alex.user.handle) vs $($alex2.user.handle)"

# Duplicate email must be refused
$dupe = $false
try { Gql $reg @{ i = @{ username="other$suffix"; email="$alexName@example.com"; password="correct-horse-battery" } } $null }
catch { $dupe = $true }
Check "duplicate email refused" $dupe "no error raised"

# Wrong password
$badPw = $false
$login = 'mutation($i:LoginInput!){login(input:$i){accessToken refreshToken user{handle}}}'
try { Gql $login @{ i = @{ email="$alexName@example.com"; password="wrong-password-here" } } $null }
catch { $badPw = $true }
Check "wrong password rejected" $badPw "login succeeded"

# Login by email
$byEmail = (Gql $login @{ i = @{ email="$alexName@example.com"; password="correct-horse-battery" } } $null).login
Check "login by email works" ($byEmail.user.handle -eq $alex.user.handle) $byEmail.user.handle

# A handle is an address, not a credential — it must not sign anyone in
$handleLogin = $false
try { Gql $login @{ i = @{ email=$alex.user.handle; password="correct-horse-battery" } } $null }
catch { $handleLogin = $true }
Check "handle cannot be used to sign in" $handleLogin "signed in with a handle"

# Unauthenticated access
$unauth = $false
try { Gql 'query{channels{id}}' @{} $null } catch { $unauth = $true }
Check "channels requires auth" $unauth "unauthenticated call succeeded"

"== refresh rotation =="
$refreshOp = 'mutation($t:String!){refresh(refreshToken:$t){accessToken refreshToken user{handle}}}'
$r1 = (Gql $refreshOp @{ t = $alex.refreshToken } $null).refresh
Check "refresh returns a NEW refresh token" ($r1.refreshToken -ne $alex.refreshToken) "token unchanged"

# Replaying the superseded token must revoke the whole family
$reuseBlocked = $false
try { Gql $refreshOp @{ t = $alex.refreshToken } $null } catch { $reuseBlocked = $true }
Check "replayed refresh token rejected" $reuseBlocked "reuse accepted"

$familyDead = $false
try { Gql $refreshOp @{ t = $r1.refreshToken } $null } catch { $familyDead = $true }
Check "reuse revokes the whole family" $familyDead "descendant token still live"

# Re-login for a clean session
$alexTok = (Gql $login @{ i = @{ email="$alexName@example.com"; password="correct-horse-battery" } } $null).login
$samTok  = (Gql $login @{ i = @{ email="$samName@example.com";  password="correct-horse-battery" } } $null).login

"== dm + messages =="
$openDm = 'mutation($u:Snowflake!){openDirectMessage(userId:$u){id type members{handle}}}'
$dm1 = (Gql $openDm @{ u = $sam.user.id } $alexTok.accessToken).openDirectMessage
$dm2 = (Gql $openDm @{ u = $sam.user.id } $alexTok.accessToken).openDirectMessage
Check "openDirectMessage is idempotent" ($dm1.id -eq $dm2.id) "$($dm1.id) vs $($dm2.id)"
Check "DM has both members" (@($dm1.members).Count -eq 2) @($dm1.members).Count

$send = 'mutation($i:SendMessageInput!){sendMessage(input:$i){id content author{handle} authorBlocked}}'
$nonce = "nonce-$suffix"
$m1 = (Gql $send @{ i = @{ channelId=$dm1.id; content="first message"; nonce=$nonce } } $alexTok.accessToken).sendMessage
$m2 = (Gql $send @{ i = @{ channelId=$dm1.id; content="first message"; nonce=$nonce } } $alexTok.accessToken).sendMessage
Check "idempotent send returns the original" ($m1.id -eq $m2.id) "$($m1.id) vs $($m2.id)"
Check "author resolved via batch mapping" ($m1.author.handle -eq $alex.user.handle) $m1.author.handle

Gql $send @{ i = @{ channelId=$dm1.id; content="second message"; nonce="$nonce-b" } } $samTok.accessToken | Out-Null

$page = (Gql 'query($c:Snowflake!){messages(channelId:$c,limit:10){nodes{id content author{handle}} hasMore nextCursor}}' @{ c = $dm1.id } $alexTok.accessToken).messages
Check "both messages returned" (@($page.nodes).Count -eq 2) @($page.nodes).Count
Check "newest first" ($page.nodes[0].content -eq "second message") $page.nodes[0].content

# A third party must not see the DM
$outsider = (Gql $reg @{ i = @{ username="nosy$suffix"; email="nosy$suffix@example.com"; password="correct-horse-battery" } } $null).register
$blocked = $false
try { Gql 'query($c:Snowflake!){messages(channelId:$c,limit:5){nodes{id}}}' @{ c = $dm1.id } $outsider.accessToken }
catch { $blocked = $true }
Check "non-member cannot read the DM" $blocked "outsider read the channel"

# Empty message
$empty = $false
try { Gql $send @{ i = @{ channelId=$dm1.id; content="   " } } $alexTok.accessToken } catch { $empty = $true }
Check "empty message rejected" $empty "blank message accepted"

"== sessions =="
$sessionsOp = 'query{sessions{id platform ipAddress origin current lastSeenAt}}'
$sessions = (Gql $sessionsOp @{} $alexTok.accessToken).sessions
Check "session list is non-empty" (@($sessions).Count -ge 1) @($sessions).Count
Check "exactly one session marked current" (@($sessions | Where-Object { $_.current }).Count -eq 1) (@($sessions | Where-Object { $_.current }).Count)
Check "password logins tagged PASSWORD" (@($sessions | Where-Object { $_.origin -eq 'PASSWORD' }).Count -ge 1) ($sessions.origin -join ',')

"== qr sign-in =="
$createQr = 'mutation($p:String){createLoginRequest(platform:$p){pollSecret request{id qrPayload status rotateAfterSeconds expiresAt}}}'
$qr = (Gql $createQr @{ p = "Windows desktop" } $null).createLoginRequest
Check "QR request works unauthenticated" ($null -ne $qr.request.id) "no request returned"
Check "rotate interval is 20s" ($qr.request.rotateAfterSeconds -eq 20) $qr.request.rotateAfterSeconds
Check "payload is a singular:// deep link" ($qr.request.qrPayload -match '^singular://login\?id=\d+&t=.+') $qr.request.qrPayload
Check "poll secret is not in the QR" (-not $qr.request.qrPayload.Contains($qr.pollSecret)) "poll secret leaked into the QR"

$token1 = ($qr.request.qrPayload -split 't=')[1]

$rotateOp = 'mutation($i:Snowflake!,$s:String!){rotateLoginToken(id:$i,pollSecret:$s){id qrPayload status}}'
$rot = (Gql $rotateOp @{ i = $qr.request.id; s = $qr.pollSecret } $null).rotateLoginToken
$token2 = ($rot.qrPayload -split 't=')[1]
Check "rotation issues a different token" ($token2 -ne $token1) "token unchanged"
Check "request id survives rotation" ($rot.id -eq $qr.request.id) "$($rot.id) vs $($qr.request.id)"

$wrongSecret = $false
try { Gql $rotateOp @{ i = $qr.request.id; s = "not-the-poll-secret" } $null } catch { $wrongSecret = $true }
Check "rotation requires the poll secret" $wrongSecret "rotated with a bogus secret"

# The old token must be dead after rotation
$claimOp = 'mutation($t:String!){claimLoginRequest(qrToken:$t){id ipAddress platform userAgent requestedAt}}'
$staleDead = $false
try { Gql $claimOp @{ t = $token1 } $samTok.accessToken } catch { $staleDead = $true }
Check "rotated-out token no longer scans" $staleDead "stale QR token still worked"

$anonClaim = $false
try { Gql $claimOp @{ t = $token2 } $null } catch { $anonClaim = $true }
Check "claiming requires an authenticated user" $anonClaim "anonymous claim succeeded"

$scanned = (Gql $claimOp @{ t = $token2 } $samTok.accessToken).claimLoginRequest
Check "claim reveals the requesting platform" ($scanned.platform -eq "Windows desktop") $scanned.platform
Check "claim reveals the requesting IP" ($null -ne $scanned.ipAddress) "no IP shown on the approval screen"

$reclaim = $false
try { Gql $claimOp @{ t = $token2 } $alexTok.accessToken } catch { $reclaim = $true }
Check "a scanned code cannot be claimed twice" $reclaim "second claim succeeded"

# Rotation must freeze once scanned, so the phone's token stays valid
$frozen = (Gql $rotateOp @{ i = $qr.request.id; s = $qr.pollSecret } $null).rotateLoginToken
Check "rotation frozen after scan" ($frozen.status -eq 'SCANNED') $frozen.status

$approveOp = 'mutation($i:Snowflake!){approveLoginRequest(id:$i)}'
$wrongApprover = $false
try { Gql $approveOp @{ i = $qr.request.id } $alexTok.accessToken } catch { $wrongApprover = $true }
Check "only the claimer can approve" $wrongApprover "a different user approved it"

$approved = (Gql $approveOp @{ i = $qr.request.id } $samTok.accessToken).approveLoginRequest
Check "claimer can approve" ($approved -eq $true) $approved

$twice = $false
try { Gql $approveOp @{ i = $qr.request.id } $samTok.accessToken } catch { $twice = $true }
Check "approval is single-use" $twice "approved twice"

# The minted session belongs to the REQUESTING device, tagged QR_CODE
$samSessions = (Gql $sessionsOp @{} $samTok.accessToken).sessions
$qrSession = $samSessions | Where-Object { $_.origin -eq 'QR_CODE' }
Check "QR approval minted a session for the requester" ($null -ne $qrSession) ($samSessions.origin -join ',')
Check "QR session carries the requester's platform" ($qrSession.platform -eq "Windows desktop") $qrSession.platform
Check "QR session is not the approving phone" ($qrSession.current -eq $false) "QR session marked as current device"

"== revoking =="
$revokeOp = 'mutation($i:Snowflake!){revokeSession(id:$i)}'
Gql $revokeOp @{ i = $qrSession.id } $samTok.accessToken | Out-Null
$after = (Gql $sessionsOp @{} $samTok.accessToken).sessions
Check "revoked session disappears" (@($after | Where-Object { $_.id -eq $qrSession.id }).Count -eq 0) "still listed"

$forged = $false
try { Gql $revokeOp @{ i = $sessions[0].id } $outsider.accessToken } catch { $forged = $true }
Check "cannot revoke another user's session" $forged "revoked someone else's device"

""
"================================"
"  PASSED: $pass    FAILED: $fail"
"================================"
if ($fail -gt 0) { exit 1 }
