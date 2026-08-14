param (
    [string]$ApiUrl = "http://localhost:8080",
    [string]$DbUrl = "postgres://postgres:postgres@localhost:5433/wallet"
)

Write-Host "========================================================="
Write-Host " Paytm Wallet - Concurrency Burst & Idempotency Test Gate"
Write-Host "========================================================="
Write-Host "Target API: $ApiUrl"
Write-Host "Target DB:  $DbUrl`n"

$USER_A = "test_sender_1"
$USER_B = "test_receiver_1"
$TOKEN_A = "eyJhbGciOiAiSFMyNTYiLCAidHlwIjogIkpXVCJ9.eyJzdWIiOiAidGVzdF9zZW5kZXJfMSJ9.JNYabE68UlOGXdiIflLskb3sI98Kkz5GiD1laOd8yKI"
$TOKEN_B = "eyJhbGciOiAiSFMyNTYiLCAidHlwIjogIkpXVCJ9.eyJzdWIiOiAidGVzdF9yZWNlaXZlcl8xIn0.VU2nbs7Sv10k0V6Q2cf_dKFCiXW4pCbK8EMZuhaxZ2A"

# 1. Bootstrap Database
Write-Host "[1] Bootstrapping DB: Funding Sender ($USER_A) with 100,000 paise..."
$psqlQuery = "INSERT INTO wallets (user_id, balance_paise) VALUES ('$USER_A', 100000) ON CONFLICT (user_id) DO UPDATE SET balance_paise = 100000; DELETE FROM wallets WHERE user_id = '$USER_B'; DELETE FROM transfers WHERE from_user_id = '$USER_A' OR to_user_id = '$USER_B';"

try {
    psql $DbUrl -c $psqlQuery *>$null
} catch {
    Write-Host "Warning: psql command failed. Skipping bootstrap if run locally or if psql is not installed."
}

Write-Host "`n[2] Waiting for API readiness..." -NoNewline
while ($true) {
    try {
        $response = Invoke-RestMethod -Uri "$ApiUrl/readyz" -ErrorAction Stop
        if ($response.status -eq "UP") { break }
    } catch {}
    Write-Host "." -NoNewline
    Start-Sleep -Seconds 1
}
Write-Host " API is ready!"

# Function to run requests concurrently
function Run-ConcurrentRequests {
    param ($Requests)
    $jobs = foreach ($req in $Requests) {
        Start-ThreadJob -ScriptBlock {
            param($url, $token, $body)
            Invoke-RestMethod -Uri $url -Method Post -Headers @{ "Authorization" = "Bearer $token"; "Content-Type" = "application/json" } -Body $body -ErrorAction SilentlyContinue | Out-Null
        } -ArgumentList $req.Url, $req.Token, $req.Body
    }
    Wait-Job $jobs | Out-Null
    Remove-Job $jobs
}

if (-not (Get-Module -ListAvailable -Name ThreadJob)) {
    Write-Host "Installing ThreadJob module for fast concurrency..."
    Install-Module -Name ThreadJob -Scope CurrentUser -Force -AllowClobber
}

# 2. Concurrency Race Test
Write-Host "`n[3] Firing 50 Concurrent First-Transfers (Testing Get-or-Create Race)..."
$raceRequests = @()
for ($i = 1; $i -le 50; $i++) {
    $randomStr = -join ((48..57) + (97..122) | Get-Random -Count 8 | % {[char]$_})
    $key = "race_key_${i}_${randomStr}"
    $body = "{`"to_user`":`"$USER_B`",`"amount_paise`":100,`"idempotency_key`":`"$key`"}"
    $raceRequests += [PSCustomObject]@{ Url = "$ApiUrl/transfers"; Token = $TOKEN_A; Body = $body }
}
Run-ConcurrentRequests -Requests $raceRequests
Write-Host " -> Burst complete."

# 3. Idempotency Test
Write-Host "`n[4] Firing 50 Concurrent Retries of the SAME transfer (Testing Idempotency)..."
$randomStr = -join ((48..57) + (97..122) | Get-Random -Count 8 | % {[char]$_})
$sharedKey = "idempotency_key_$randomStr"
$retryRequests = @()
for ($i = 1; $i -le 50; $i++) {
    $body = "{`"to_user`":`"$USER_B`",`"amount_paise`":500,`"idempotency_key`":`"$sharedKey`"}"
    $retryRequests += [PSCustomObject]@{ Url = "$ApiUrl/transfers"; Token = $TOKEN_A; Body = $body }
}
Run-ConcurrentRequests -Requests $retryRequests
Write-Host " -> Burst complete."

# 4. Verification
Write-Host "`n[5] Verification..."
$senderResp = Invoke-RestMethod -Uri "$ApiUrl/accounts/me" -Method Get -Headers @{ "Authorization" = "Bearer $TOKEN_A" }
$receiverResp = Invoke-RestMethod -Uri "$ApiUrl/accounts/me" -Method Get -Headers @{ "Authorization" = "Bearer $TOKEN_B" }

$SENDER_BAL = $senderResp.balance
$RECEIVER_BAL = $receiverResp.balance

Write-Host "Final Sender Balance: $SENDER_BAL paise (Expected: 94500)"
Write-Host "Final Receiver Balance: $RECEIVER_BAL paise (Expected: 5500)"

if ($SENDER_BAL -eq 94500 -and $RECEIVER_BAL -eq 5500) {
    Write-Host "✅ TEST PASSED: No money lost, exactly correct amount transferred under heavy concurrency." -ForegroundColor Green
} else {
    Write-Host "❌ TEST FAILED: Balances do not match expected values!" -ForegroundColor Red
}
