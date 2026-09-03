$ErrorActionPreference = "Stop"
$endpoint = "http://localhost:8080/graphql"
$pass = 0; $fail = 0
function Check($n, $c, $d) { if ($c) { $script:pass++; "  PASS  $n" } else { $script:fail++; "  FAIL  $n  -> $d" } }

function Gql($q, $v, $t) {
  $h = @{ "content-type" = "application/json" }; if ($t) { $h["Authorization"] = "Bearer $t" }
  $b = @{ query = $q; variables = $v } | ConvertTo-Json -Depth 12 -Compress
  $r = Invoke-RestMethod -Uri $endpoint -Method Post -Headers $h -Body $b
  if ($r.errors) { throw ($r.errors | ConvertTo-Json -Depth 8 -Compress) }
  $r.data
}

$ct = [Threading.CancellationToken]::None
function WsSend($ws, $obj) {
  $bytes = [Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Depth 12 -Compress))
  $seg = New-Object 'System.ArraySegment[byte]' -ArgumentList @(,$bytes)
  $ws.SendAsync($seg, [Net.WebSockets.WebSocketMessageType]::Text, $true, $ct).GetAwaiter().GetResult()
}
function WsRecv($ws, $timeoutMs) {
  $sb = New-Object Text.StringBuilder
  do {
    $buf = New-Object byte[] 65536
    $seg = New-Object 'System.ArraySegment[byte]' -ArgumentList @(,$buf)
    $task = $ws.ReceiveAsync($seg, $ct)
    if (-not $task.Wait($timeoutMs)) { return $null }
    $res = $task.Result
    [void]$sb.Append([Text.Encoding]::UTF8.GetString($buf, 0, $res.Count))
  } while (-not $res.EndOfMessage)
  $sb.ToString() | ConvertFrom-Json
}

# --- participants -----------------------------------------------------------
$sfx = Get-Random -Maximum 99999
$reg = 'mutation($i:RegisterInput!){register(input:$i){accessToken user{id handle}}}'
$phone = (Gql $reg @{ i = @{ username = "ws$sfx"; email = "ws$sfx@example.com"; password = "correct-horse-battery" } } $null).register
"phone signed in as $($phone.user.handle)"

# --- desktop asks for a QR --------------------------------------------------
$qr = (Gql 'mutation($p:String){createLoginRequest(platform:$p){pollSecret request{id qrPayload}}}' @{ p = "Windows desktop" } $null).createLoginRequest
$token = ($qr.request.qrPayload -split 't=')[1]

# --- desktop opens an ANONYMOUS socket and subscribes -----------------------
$ws = New-Object System.Net.WebSockets.ClientWebSocket
$ws.Options.AddSubProtocol("graphql-transport-ws")
$ws.ConnectAsync([Uri]"ws://localhost:8080/graphql", $ct).GetAwaiter().GetResult()
Check "anonymous WebSocket connects" ($ws.State -eq 'Open') $ws.State

WsSend $ws @{ type = "connection_init"; payload = @{} }
$ack = WsRecv $ws 10000
Check "connection_init acknowledged without a bearer token" ($ack.type -eq "connection_ack") $ack.type

$subQuery = 'subscription($i:Snowflake!,$s:String!){loginRequestUpdated(id:$i,pollSecret:$s){status approvedBy{handle} auth{accessToken refreshToken user{handle}}}}'
WsSend $ws @{ id = "1"; type = "subscribe"; payload = @{ query = $subQuery; variables = @{ i = $qr.request.id; s = $qr.pollSecret } } }
Start-Sleep -Milliseconds 400

# --- phone scans ------------------------------------------------------------
Gql 'mutation($t:String!){claimLoginRequest(qrToken:$t){id platform}}' @{ t = $token } $phone.accessToken | Out-Null
$evt1 = WsRecv $ws 10000
Check "SCANNED pushed to the waiting desktop" ($evt1.type -eq "next" -and $evt1.payload.data.loginRequestUpdated.status -eq "SCANNED") "$($evt1.type)/$($evt1.payload.data.loginRequestUpdated.status)"

# --- phone approves ---------------------------------------------------------
Gql 'mutation($i:Snowflake!){approveLoginRequest(id:$i)}' @{ i = $qr.request.id } $phone.accessToken | Out-Null
$evt2 = WsRecv $ws 10000
$payload = $evt2.payload.data.loginRequestUpdated
Check "APPROVED pushed to the desktop" ($payload.status -eq "APPROVED") $payload.status
Check "tokens delivered over the poll-secret channel" ($null -ne $payload.auth.accessToken) "no auth payload"
Check "event names the approver" ($payload.approvedBy.handle -eq $phone.user.handle) $payload.approvedBy.handle

# --- the delivered token must actually work ---------------------------------
$me = (Gql 'query{me{handle}}' @{} $payload.auth.accessToken).me
Check "delivered access token authenticates" ($me.handle -eq $phone.user.handle) $me.handle

# --- a socket with the WRONG poll secret must get nothing -------------------
$qr2 = (Gql 'mutation($p:String){createLoginRequest(platform:$p){pollSecret request{id qrPayload}}}' @{ p = "Windows desktop" } $null).createLoginRequest
$ws2 = New-Object System.Net.WebSockets.ClientWebSocket
$ws2.Options.AddSubProtocol("graphql-transport-ws")
$ws2.ConnectAsync([Uri]"ws://localhost:8080/graphql", $ct).GetAwaiter().GetResult()
WsSend $ws2 @{ type = "connection_init"; payload = @{} }
WsRecv $ws2 5000 | Out-Null
WsSend $ws2 @{ id = "1"; type = "subscribe"; payload = @{ query = $subQuery; variables = @{ i = $qr2.request.id; s = "wrong-poll-secret" } } }
$err = WsRecv $ws2 8000
Check "wrong poll secret is refused on the socket" ($err.type -eq "error") "got '$($err.type)'"

# --- an unauthenticated socket cannot subscribe to messages -----------------
$ws3 = New-Object System.Net.WebSockets.ClientWebSocket
$ws3.Options.AddSubProtocol("graphql-transport-ws")
$ws3.ConnectAsync([Uri]"ws://localhost:8080/graphql", $ct).GetAwaiter().GetResult()
WsSend $ws3 @{ type = "connection_init"; payload = @{} }
WsRecv $ws3 5000 | Out-Null
WsSend $ws3 @{ id = "1"; type = "subscribe"; payload = @{ query = 'subscription($c:Snowflake!){messageCreated(channelId:$c){id content}}'; variables = @{ c = "1" } } }
$err2 = WsRecv $ws3 8000
Check "anonymous socket cannot subscribe to messages" ($err2.type -eq "error") "got '$($err2.type)'"

foreach ($s in @($ws, $ws2, $ws3)) {
  try { $s.CloseAsync([Net.WebSockets.WebSocketCloseStatus]::NormalClosure, "done", $ct).Wait(2000) | Out-Null } catch {}
}

""
"================================"
"  PASSED: $pass    FAILED: $fail"
"================================"
if ($fail -gt 0) { exit 1 }
