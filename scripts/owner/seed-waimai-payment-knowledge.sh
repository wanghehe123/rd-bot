#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${RD_BOT_BASE_URL:-http://127.0.0.1:18080}"
KB_NAME="${WAIMAI_KB_NAME:-waimai}"
TMP_DIR="${TMPDIR:-/tmp}/rd-bot-waimai-payment-seed.$$"

mkdir -p "$TMP_DIR"
trap 'rm -rf "$TMP_DIR"' EXIT

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "missing required command: $1" >&2
    exit 1
  fi
}

json_payload() {
  local output="$1"
  shift
  python3 "$@" > "$output"
}

require_cmd curl
require_cmd python3

echo "Checking RD-Bot at ${BASE_URL}..."
curl -fsS "${BASE_URL}/rag/settings" >/dev/null

KB_QUERY="$(
  python3 - "$KB_NAME" <<'PY'
import sys
import urllib.parse

print(urllib.parse.urlencode({
    "current": "1",
    "size": "100",
    "name": sys.argv[1],
}))
PY
)"

curl -fsS "${BASE_URL}/knowledge-base?${KB_QUERY}" > "${TMP_DIR}/knowledge-bases.json"
KB_ID="$(
  python3 - "$KB_NAME" "${TMP_DIR}/knowledge-bases.json" <<'PY'
import json
import sys

name = sys.argv[1]
with open(sys.argv[2], encoding="utf-8") as handle:
    payload = json.load(handle)
for item in payload.get("records", []):
    if item.get("name") == name:
        print(item.get("id", ""))
        break
PY
)"

if [ -z "$KB_ID" ]; then
  json_payload "${TMP_DIR}/create-base.json" - "$KB_NAME" <<'PY'
import json
import sys

json.dump({
    "name": sys.argv[1],
    "description": "外卖系统支付回调、订单状态流转与商家接单知识库",
}, sys.stdout, ensure_ascii=False)
PY
  curl -fsS -X POST "${BASE_URL}/knowledge-base" \
    -H "Content-Type: application/json" \
    --data-binary @"${TMP_DIR}/create-base.json" > "${TMP_DIR}/created-base.json"
  KB_ID="$(
    python3 - "${TMP_DIR}/created-base.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    print(json.load(handle)["id"])
PY
  )"
  echo "Created knowledge base: ${KB_NAME} (${KB_ID})"
else
  echo "Using existing knowledge base: ${KB_NAME} (${KB_ID})"
fi

cat > "${TMP_DIR}/waimai-payment-callback-runbook.md" <<'DOC'
# waimai 支付回调状态修复手册

适用系统: waimai
故障标题: 外卖订单支付成功后状态仍为待支付
关键日志: java.lang.IllegalStateException: Payment callback handled but order status remains PENDING_PAYMENT at com.waimai.payment.PaymentCallbackService.handleSuccess(PaymentCallbackService.java:67)

排查结论:
- PaymentCallbackService.handleSuccess 在确认第三方支付成功后必须调用 OrderService.markPaid 或 OrderRepository.updateStatus(orderId, PAID)。
- 如果回调只记录 payment transaction 但没有更新 orders.status，前端订单详情会继续显示 PENDING_PAYMENT，商家无法接单。
- 修复应放在支付回调事务内: 校验签名 -> 幂等查找 payment_no -> 锁定订单 -> PENDING_PAYMENT 更新为 PAID -> 记录 paid_at/payment_transaction_id -> 发布 MerchantOrderPaidEvent。
- 已经是 PAID 的订单应幂等返回 success，不应重复通知商家。
DOC

cat > "${TMP_DIR}/waimai-payment-callback-code.md" <<'DOC'
# server/src/services/PaymentCallbackService.java

PaymentCallbackService.handleSuccess 是支付成功回调入口。

推荐修复形态:
1. 根据 paymentNo / orderNo 查询支付流水和订单。
2. 校验回调签名和支付金额。
3. 在事务内调用 OrderService.markPaid(orderId, paymentTransactionId)。
4. markPaid 只允许 PENDING_PAYMENT -> PAID；若订单已是 PAID，直接返回幂等成功。
5. 状态更新后再通知商家接单。

反例:
- handleSuccess 只保存 payment_callback_log。
- handleSuccess 只把 payment_transaction.status 标记为 SUCCESS。
- handleSuccess 没有持久化 orders.status = PAID。
DOC

cat > "${TMP_DIR}/waimai-payment-order-status-schema.md" <<'DOC'
# waimai 订单状态与支付字段

orders 表关键字段:
- order_no: 外部展示订单号。
- status: 订单状态，支付前为 PENDING_PAYMENT，支付成功后必须更新为 PAID，商家接单后进入 ACCEPTED。
- paid_at: 支付成功时间。
- payment_transaction_id: 第三方支付流水号。

业务约束:
- 商家端只能接单 status = PAID 的订单。
- status 仍为 PENDING_PAYMENT 时，订单详情显示待支付，商家无法接单。
- 支付回调成功后若订单状态仍为 PENDING_PAYMENT，应视为回调处理缺陷。
DOC

curl -fsS "${BASE_URL}/knowledge-base/${KB_ID}/docs" > "${TMP_DIR}/documents.json"
python3 - "${TMP_DIR}/documents.json" <<'PY' > "${TMP_DIR}/delete-doc-ids.txt"
import json
import sys

targets = {
    "waimai-payment-callback-runbook.md",
    "waimai-payment-callback-code.md",
    "waimai-payment-order-status-schema.md",
}
with open(sys.argv[1], encoding="utf-8") as handle:
    for item in json.load(handle):
        if item.get("sourceName") in targets:
            print(item.get("id", ""))
PY

while IFS= read -r doc_id; do
  if [ -n "$doc_id" ]; then
    curl -fsS -X DELETE "${BASE_URL}/knowledge-base/docs/${doc_id}" >/dev/null
    echo "Deleted existing seed document: ${doc_id}"
  fi
done < "${TMP_DIR}/delete-doc-ids.txt"

write_document() {
  local source_name="$1"
  local knowledge_type="$2"
  local content_file="$3"
  local body_file="${TMP_DIR}/${source_name}.json"
  json_payload "$body_file" - "$source_name" "$knowledge_type" "$content_file" <<'PY'
import json
import sys

source_name, knowledge_type, content_file = sys.argv[1:4]
with open(content_file, encoding="utf-8") as handle:
    content = handle.read()
json.dump({
    "sourceName": source_name,
    "knowledgeType": knowledge_type,
    "mimeType": "text/markdown",
    "content": content,
    "chunkingMode": "STRUCTURE_AWARE",
    "chunkSize": 180,
    "overlapSize": 24,
}, sys.stdout, ensure_ascii=False)
PY
  curl -fsS -X POST "${BASE_URL}/knowledge-base/${KB_ID}/docs/write" \
    -H "Content-Type: application/json" \
    --data-binary @"$body_file" > "${TMP_DIR}/${source_name}.response.json"
  echo "Seeded document: ${source_name}"
}

write_document "waimai-payment-callback-runbook.md" "runbook" "${TMP_DIR}/waimai-payment-callback-runbook.md"
write_document "waimai-payment-callback-code.md" "code-snippet" "${TMP_DIR}/waimai-payment-callback-code.md"
write_document "waimai-payment-order-status-schema.md" "database" "${TMP_DIR}/waimai-payment-order-status-schema.md"

curl -fsS "${BASE_URL}/intent-tree/trees" > "${TMP_DIR}/intent-tree.json"
python3 - "$KB_ID" "${TMP_DIR}/intent-tree.json" <<'PY' > "${TMP_DIR}/update-waimai-intent.json"
import json
import sys

kb_id = sys.argv[1]
with open(sys.argv[2], encoding="utf-8") as handle:
    roots = json.load(handle)

def walk(nodes):
    for node in nodes:
        if node.get("intentCode") == "waimai":
            return node
        found = walk(node.get("children", []))
        if found:
            return found
    return None

node = walk(roots)
if not node:
    raise SystemExit("waimai intent node not found")

payload = {
    "intentCode": node.get("intentCode"),
    "name": node.get("name"),
    "level": node.get("level"),
    "parentCode": node.get("parentCode"),
    "description": node.get("description"),
    "kbId": kb_id,
    "examples": node.get("examples", []),
    "codeRepositoryIds": node.get("codeRepositoryIds", []),
    "enabled": node.get("enabled", 1),
    "sortOrder": node.get("sortOrder", 0),
}
json.dump(payload, sys.stdout, ensure_ascii=False)
PY
INTENT_ID="$(python3 - "${TMP_DIR}/intent-tree.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    roots = json.load(handle)

def walk(nodes):
    for node in nodes:
        if node.get("intentCode") == "waimai":
            print(node.get("id", ""))
            return True
        if walk(node.get("children", [])):
            return True
    return False

walk(roots)
PY
)"
curl -fsS -X PUT "${BASE_URL}/intent-tree/${INTENT_ID}" \
  -H "Content-Type: application/json" \
  --data-binary @"${TMP_DIR}/update-waimai-intent.json" >/dev/null
echo "Bound waimai intent to PostgreSQL knowledge base id: ${KB_ID}"

python3 - <<'PY' > "${TMP_DIR}/rag-request.json"
import json
import sys

description = """问题: 外卖订单支付成功后状态仍为待支付
系统: waimai
优先级: P1
日志: java.lang.IllegalStateException: Payment callback handled but order status remains PENDING_PAYMENT at com.waimai.payment.PaymentCallbackService.handleSuccess(PaymentCallbackService.java:67)
实际: 用户支付成功后，订单详情仍显示待支付，商家无法接单
期望: 支付成功后订单状态更新为 PAID，并通知商家接单"""

json.dump({
    "ticketId": "FI-waimai-payment-status-seed",
    "title": "外卖订单支付成功后状态仍为待支付",
    "description": description,
    "labels": ["waimai", "P1", "payment-callback"],
    "logs": [
        "java.lang.IllegalStateException: Payment callback handled but order status remains PENDING_PAYMENT at com.waimai.payment.PaymentCallbackService.handleSuccess(PaymentCallbackService.java:67)"
    ],
    "deepThinking": False,
}, sys.stdout, ensure_ascii=False)
PY

curl -fsS -X POST "${BASE_URL}/rag/bugfix/messages" \
  -H "Content-Type: application/json" \
  --data-binary @"${TMP_DIR}/rag-request.json" > "${TMP_DIR}/rag-response.json"

python3 - "${TMP_DIR}/rag-response.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    payload = json.load(handle)

haystack = json.dumps(payload, ensure_ascii=False)
required = ["PaymentCallbackService", "PENDING_PAYMENT", "PAID"]
missing = [token for token in required if token not in haystack]
if payload.get("primaryIntentSystemId") != "waimai":
    raise SystemExit(f"RAG verification failed: primaryIntentSystemId={payload.get('primaryIntentSystemId')!r}")
if not payload.get("retrievedChunks"):
    raise SystemExit("RAG verification failed: retrievedChunks is empty")
if not payload.get("evidenceChunkIds"):
    raise SystemExit("RAG verification failed: evidenceChunkIds is empty")
if missing:
    raise SystemExit("RAG verification failed: missing " + ", ".join(missing))
if "未检索到足够证据" in payload.get("answer", ""):
    raise SystemExit("RAG verification failed: answer says evidence is insufficient")

print("RAG verification passed")
print("taskId=" + payload.get("taskId", ""))
print("primaryIntentSystemId=" + payload.get("primaryIntentSystemId", ""))
print("retrievedChunkCount=" + str(len(payload.get("retrievedChunks", []))))
print("answer=" + payload.get("answer", ""))
PY

echo
echo "Feishu test message:"
cat <<'MSG'
问题: 外卖订单支付成功后状态仍为待支付
系统: waimai
优先级: P1
日志: java.lang.IllegalStateException: Payment callback handled but order status remains PENDING_PAYMENT at com.waimai.payment.PaymentCallbackService.handleSuccess(PaymentCallbackService.java:67)
实际: 用户支付成功后，订单详情仍显示待支付，商家无法接单
期望: 支付成功后订单状态更新为 PAID，并通知商家接单
MSG
