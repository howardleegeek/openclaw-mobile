#!/bin/bash
# ClawPhones API 自动化测试
# Usage: bash test_api.sh [BASE_URL]

BASE=${1:-"http://3.142.69.6:8080"}
ADMIN_KEY="${CLAWPHONES_ADMIN_KEY:?missing CLAWPHONES_ADMIN_KEY}"
PASS=0
FAIL=0

green() { echo -e "\033[32m✅ $1\033[0m"; PASS=$((PASS+1)); }
red()   { echo -e "\033[31m❌ $1\033[0m"; FAIL=$((FAIL+1)); }

echo "🔧 Testing ClawPhones API at $BASE"
echo "================================================"

# 1. Health check
echo -n "1. Health check... "
HEALTH=$(curl -sf "$BASE/health")
if echo "$HEALTH" | grep -q '"ok":true'; then green "Health OK"; else red "Health failed: $HEALTH"; fi

# 2. Generate free token
echo -n "2. Generate free token... "
TOKEN_RESP=$(curl -sf -X POST "$BASE/admin/tokens/generate" \
  -H "X-Admin-Key: $ADMIN_KEY" \
  -H "Content-Type: application/json" \
  -d '{"tier":"free","note":"autotest"}')
FREE_TOKEN=$(echo "$TOKEN_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['tokens'][0])" 2>/dev/null)
if [ -n "$FREE_TOKEN" ]; then green "Token: $FREE_TOKEN"; else red "Token generation failed: $TOKEN_RESP"; fi

# 3. Generate pro token
echo -n "3. Generate pro token... "
PRO_RESP=$(curl -sf -X POST "$BASE/admin/tokens/generate" \
  -H "X-Admin-Key: $ADMIN_KEY" \
  -H "Content-Type: application/json" \
  -d '{"tier":"pro","note":"autotest"}')
PRO_TOKEN=$(echo "$PRO_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['tokens'][0])" 2>/dev/null)
if [ -n "$PRO_TOKEN" ]; then green "Pro token OK"; else red "Pro token failed"; fi

# 4. Generate max token
echo -n "4. Generate max token... "
MAX_RESP=$(curl -sf -X POST "$BASE/admin/tokens/generate" \
  -H "X-Admin-Key: $ADMIN_KEY" \
  -H "Content-Type: application/json" \
  -d '{"tier":"max","note":"autotest"}')
MAX_TOKEN=$(echo "$MAX_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['tokens'][0])" 2>/dev/null)
if [ -n "$MAX_TOKEN" ]; then green "Max token OK"; else red "Max token failed"; fi

# 5. Auth rejection (no token)
echo -n "5. Reject unauthenticated request... "
UNAUTH=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/v1/chat/completions" \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"test"}],"max_tokens":10}')
if [ "$UNAUTH" = "401" ] || [ "$UNAUTH" = "403" ]; then green "Rejected ($UNAUTH)"; else red "Expected 401/403, got $UNAUTH"; fi

# 6. Create conversation
echo -n "6. Create conversation... "
CONV_RESP=$(curl -sf -X POST "$BASE/v1/conversations" \
  -H "Authorization: Bearer $FREE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"autotest"}')
CONV_ID=$(echo "$CONV_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])" 2>/dev/null)
if [ -n "$CONV_ID" ]; then green "Conversation: $CONV_ID"; else red "Create conv failed: $CONV_RESP"; fi

# 7. List conversations
echo -n "7. List conversations... "
LIST=$(curl -sf "$BASE/v1/conversations" -H "Authorization: Bearer $FREE_TOKEN")
if echo "$LIST" | python3 -c "import sys,json; d=json.load(sys.stdin); assert len(d)>0" 2>/dev/null; then
  green "Listed OK"
else
  red "List failed: $LIST"
fi

# 8. Chat (free tier = DeepSeek)
echo -n "8. Chat free tier (DeepSeek)... "
CHAT_FREE=$(curl -sf --max-time 30 -X POST "$BASE/v1/conversations/$CONV_ID/chat" \
  -H "Authorization: Bearer $FREE_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"say OK"}')
if echo "$CHAT_FREE" | grep -q '"role":"assistant"'; then
  green "DeepSeek responded"
else
  red "DeepSeek failed: $CHAT_FREE"
fi

# 9. Chat (pro tier = Kimi)
echo -n "9. Chat pro tier (Kimi)... "
CHAT_PRO=$(curl -sf --max-time 30 -X POST "$BASE/v1/chat/completions" \
  -H "Authorization: Bearer $PRO_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"say OK"}],"max_tokens":20}')
if echo "$CHAT_PRO" | grep -q '"choices"'; then
  green "Kimi responded"
else
  red "Kimi failed: $CHAT_PRO"
fi

# 10. Chat (max tier = Claude)
echo -n "10. Chat max tier (Claude)... "
CHAT_MAX=$(curl -sf --max-time 30 -X POST "$BASE/v1/chat/completions" \
  -H "Authorization: Bearer $MAX_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"messages":[{"role":"user","content":"say OK"}],"max_tokens":20}')
if echo "$CHAT_MAX" | grep -q '"choices"'; then
  green "Claude responded"
else
  red "Claude failed: $CHAT_MAX"
fi

# 11. Get conversation detail
echo -n "11. Get conversation detail... "
DETAIL=$(curl -sf "$BASE/v1/conversations/$CONV_ID" -H "Authorization: Bearer $FREE_TOKEN")
if echo "$DETAIL" | grep -q '"messages"'; then
  green "Detail has messages"
else
  red "Detail failed: $DETAIL"
fi

# 12. Delete conversation
echo -n "12. Delete conversation... "
DEL=$(curl -sf -X DELETE "$BASE/v1/conversations/$CONV_ID" -H "Authorization: Bearer $FREE_TOKEN")
if echo "$DEL" | grep -qi 'ok\|delete\|success' || [ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/v1/conversations/$CONV_ID" -H "Authorization: Bearer $FREE_TOKEN")" = "404" ]; then
  green "Deleted OK"
else
  red "Delete may have failed: $DEL"
fi

# 13. Message too long rejection
echo -n "13. Reject oversized message... "
LONG_MSG=$(python3 -c "print('x'*60000)")
LONG_RESP=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/v1/chat/completions" \
  -H "Authorization: Bearer $FREE_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"messages\":[{\"role\":\"user\",\"content\":\"$LONG_MSG\"}],\"max_tokens\":10}")
if [ "$LONG_RESP" = "400" ]; then green "Rejected oversized ($LONG_RESP)"; else red "Expected 400, got $LONG_RESP"; fi

# 14. Admin key rejection (wrong key)
echo -n "14. Reject wrong admin key... "
BAD_ADMIN=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/admin/tokens/generate" \
  -H "X-Admin-Key: wrongkey" \
  -H "Content-Type: application/json" \
  -d '{"tier":"free"}')
if [ "$BAD_ADMIN" = "401" ] || [ "$BAD_ADMIN" = "403" ]; then green "Rejected ($BAD_ADMIN)"; else red "Expected 401/403, got $BAD_ADMIN"; fi

echo ""
echo "================================================"
echo "Results: $PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ] && echo "🎉 ALL TESTS PASSED" || echo "⚠️  SOME TESTS FAILED"
