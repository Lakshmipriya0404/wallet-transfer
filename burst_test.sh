#!/bin/bash
set -e

# Configuration
API_URL=${1:-"http://localhost:8080"}
DB_URL=${2:-"postgres://postgres:postgres@localhost:5432/wallet"}

echo "========================================================="
echo " Paytm Wallet - Concurrency Burst & Idempotency Test Gate"
echo "========================================================="
echo "Target API: $API_URL"
echo "Target DB:  $DB_URL"
echo ""

# Pre-signed JWTs using 'supersecretkeythatisatleast32byteslongforhmacsha256'
USER_A="test_sender_1"
USER_B="test_receiver_1"
TOKEN_A="eyJhbGciOiAiSFMyNTYiLCAidHlwIjogIkpXVCJ9.eyJzdWIiOiAidGVzdF9zZW5kZXJfMSJ9.JNYabE68UlOGXdiIflLskb3sI98Kkz5GiD1laOd8yKI"
TOKEN_B="eyJhbGciOiAiSFMyNTYiLCAidHlwIjogIkpXVCJ9.eyJzdWIiOiAidGVzdF9yZWNlaXZlcl8xIn0.VU2nbs7Sv10k0V6Q2cf_dKFCiXW4pCbK8EMZuhaxZ2A"

# 1. Bootstrap Database
echo "[1] Bootstrapping DB: Funding Sender ($USER_A) with 100,000 paise..."
# Ignore errors if table not found or PSQL not installed, as it might run in CI
psql "$DB_URL" -c "
INSERT INTO wallets (user_id, balance_paise) VALUES ('$USER_A', 100000) 
ON CONFLICT (user_id) DO UPDATE SET balance_paise = 100000;
DELETE FROM wallets WHERE user_id = '$USER_B';
DELETE FROM transfers WHERE from_user_id = '$USER_A' OR to_user_id = '$USER_B';
" > /dev/null 2>&1 || echo "Warning: psql command failed. Skipping bootstrap if run locally."

echo "[2] Waiting for API readiness..."
until curl -s -f -o /dev/null "$API_URL/readyz"; do
  printf '.'
  sleep 1
done
echo " API is ready!"

# 2. Concurrency Race Test
echo ""
echo "[3] Firing 50 Concurrent First-Transfers (Testing Get-or-Create Race)..."
# Generating 50 unique requests in parallel to transfer 100 paise each
> cmds_race.txt
for i in {1..50}; do
    KEY="race_key_${i}_${RANDOM}"
    echo "curl -s -X POST $API_URL/transfers -H 'Authorization: Bearer $TOKEN_A' -H 'Content-Type: application/json' -d '{\"to_user\":\"$USER_B\",\"amount_paise\":100,\"idempotency_key\":\"$KEY\"}' > /dev/null" >> cmds_race.txt
done
cat cmds_race.txt | xargs -I CMD -P 50 sh -c 'CMD'
echo " -> Burst complete."

# 3. Idempotency Test
echo ""
echo "[4] Firing 50 Concurrent Retries of the SAME transfer (Testing Idempotency)..."
SHARED_KEY="idempotency_key_${RANDOM}"
> cmds_retry.txt
for i in {1..50}; do
    echo "curl -s -X POST $API_URL/transfers -H 'Authorization: Bearer $TOKEN_A' -H 'Content-Type: application/json' -d '{\"to_user\":\"$USER_B\",\"amount_paise\":500,\"idempotency_key\":\"$SHARED_KEY\"}' > /dev/null" >> cmds_retry.txt
done
cat cmds_retry.txt | xargs -I CMD -P 50 sh -c 'CMD'
echo " -> Burst complete."

# 4. Verification
echo ""
echo "[5] Verification..."
SENDER_BAL=$(curl -s -X GET "$API_URL/accounts/me" -H "Authorization: Bearer $TOKEN_A" | grep -o '"balance":[^,}]*' | cut -d: -f2)
RECEIVER_BAL=$(curl -s -X GET "$API_URL/accounts/me" -H "Authorization: Bearer $TOKEN_B" | grep -o '"balance":[^,}]*' | cut -d: -f2)

echo "Final Sender Balance: $SENDER_BAL paise (Expected: 94500)"
echo "Final Receiver Balance: $RECEIVER_BAL paise (Expected: 5500)"

if [[ "$SENDER_BAL" == "94500" && "$RECEIVER_BAL" == "5500" ]]; then
    echo "✅ TEST PASSED: No money lost, exactly correct amount transferred under heavy concurrency."
else
    echo "❌ TEST FAILED: Balances do not match expected values!"
fi

rm cmds_race.txt cmds_retry.txt
