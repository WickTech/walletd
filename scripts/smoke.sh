#!/usr/bin/env bash
# End-to-end check of a running walletd: auth, wallets, top-up, idempotent retries, transfers, the signed
# payment webhook, the audit and the metrics. CI runs it against the Docker image.
#
#   BASE=http://localhost:8080 WALLETD_WEBHOOK_SECRET=<secret of the server> scripts/smoke.sh
#
# Needs the server started with WALLETD_DEV_TOKENS=true (it mints the test tokens). Needs curl, jq, openssl.
set -euo pipefail

BASE=${BASE:-http://localhost:8080}
: "${WALLETD_WEBHOOK_SECRET:?set WALLETD_WEBHOOK_SECRET to the webhook secret of the server}"

fail() { echo "FAIL: $*" >&2; exit 1; }
expect() { [ "$2" = "$3" ] || fail "$1: expected '$3' but got '$2'"; }
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen; }
status() { curl -s -o /dev/null -w '%{http_code}' "$@"; }

token() { curl -fsS "$BASE/dev/token" -H 'content-type: application/json' -d "$1" | jq -r .token; }
open_wallet() { curl -fsS "$BASE/accounts" -H "Authorization: Bearer $1" -H 'content-type: application/json' \
  -d '{"currency":"INR"}' | jq -r .id; }
balance() { curl -fsS "$BASE/accounts/$1" -H "Authorization: Bearer $2" | jq -r .balanceMinor; }

RUN=$$-$(date +%s)
OPS=$(token '{"subject":"smoke-ops","admin":true}')
ASHA=$(token "{\"subject\":\"smoke-asha-$RUN\"}")
RAVI=$(token "{\"subject\":\"smoke-ravi-$RUN\"}")

echo "1. auth"
expect "no token" "$(status "$BASE/accounts/$(uuid)")" 401
A=$(open_wallet "$ASHA"); B=$(open_wallet "$RAVI")
expect "other user's wallet is invisible" "$(status "$BASE/accounts/$A" -H "Authorization: Bearer $RAVI")" 404

echo "2. top-up (admin only) and its idempotent retry"
KEY=$(uuid)
topup() { curl -sS -D - -o /dev/null "$BASE/accounts/$A/top-ups" -H "Authorization: Bearer $OPS" \
  -H 'content-type: application/json' -H "Idempotency-Key: $KEY" -d '{"amountMinor":10000}'; }
topup | grep -qi '^HTTP/.* 201' || fail "first top-up should be 201"
topup | grep -qi '^idempotent-replayed: true' || fail "retry should be flagged as a replay"
expect "balance after top-up + retry" "$(balance "$A" "$ASHA")" 10000
expect "a user cannot mint money" "$(status "$BASE/accounts/$A/top-ups" -H "Authorization: Bearer $ASHA" \
  -H 'content-type: application/json' -H "Idempotency-Key: $(uuid)" -d '{"amountMinor":1}')" 403

echo "3. transfers"
TKEY=$(uuid)
transfer() { curl -fsS "$BASE/transfers" -H "Authorization: Bearer $ASHA" -H 'content-type: application/json' \
  -H "Idempotency-Key: $1" -d "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amountMinor\":$2}"; }
transfer "$TKEY" 2500 >/dev/null; transfer "$TKEY" 2500 >/dev/null     # second call is a retry
expect "sender" "$(balance "$A" "$ASHA")" 7500
expect "receiver" "$(balance "$B" "$RAVI")" 2500
expect "cannot spend someone else's wallet" "$(status "$BASE/transfers" -H "Authorization: Bearer $RAVI" \
  -H 'content-type: application/json' -H "Idempotency-Key: $(uuid)" \
  -d "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amountMinor\":1}")" 404
expect "overdraft" "$(status "$BASE/transfers" -H "Authorization: Bearer $ASHA" \
  -H 'content-type: application/json' -H "Idempotency-Key: $(uuid)" \
  -d "{\"fromAccountId\":\"$A\",\"toAccountId\":\"$B\",\"amountMinor\":999999}")" 422

echo "4. signed payment webhook"
PAY="pay_smoke_$RUN"
BODY="{\"eventId\":\"evt_1\",\"type\":\"payment.captured\",\"paymentId\":\"$PAY\",\"accountId\":\"$B\",\"amountMinor\":5000,\"currency\":\"INR\"}"
deliver() { TS=$(date +%s)
  SIG=$(printf '%s.%s' "$TS" "$BODY" | openssl dgst -sha256 -hmac "$WALLETD_WEBHOOK_SECRET" -hex | sed 's/^.* //')
  curl -sS "$BASE/webhooks/payments" -H 'content-type: application/json' -H "X-Walletd-Timestamp: $TS" \
    -H "X-Walletd-Signature: sha256=${1:-$SIG}" -d "$BODY"; }
expect "first delivery" "$(deliver | jq -r .status)" credited
expect "redelivery" "$(deliver | jq -r .status)" duplicate
expect "forged signature" "$(TS=$(date +%s); status "$BASE/webhooks/payments" -H 'content-type: application/json' \
  -H "X-Walletd-Timestamp: $TS" -H 'X-Walletd-Signature: sha256=00' -d "$BODY")" 401
expect "receiver after webhook" "$(balance "$B" "$RAVI")" 7500

echo "5. audit and metrics"
expect "ledger audit" "$(curl -fsS "$BASE/admin/reconciliation" -H "Authorization: Bearer $OPS" | jq -r .ok)" true
expect "audit is admin-only" "$(status "$BASE/admin/reconciliation" -H "Authorization: Bearer $ASHA")" 403
curl -fsS "$BASE/actuator/prometheus" -H "Authorization: Bearer $OPS" | grep -q '^walletd_ledger_entries_total' \
  || fail "metrics missing walletd_ledger_entries_total"
curl -fsS "$BASE/v3/api-docs" | jq -e '.paths["/transfers"]' >/dev/null || fail "OpenAPI spec missing /transfers"

echo "smoke test passed"
