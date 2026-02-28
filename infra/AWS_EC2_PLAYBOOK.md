# AWS EC2 Gateway: Deploy, Update & Benchmark Playbook

Пошаговый плейбук для работы с gateway на AWS EC2 через SSM.
Все команды выполняются с локальной машины (macOS), SSH не нужен.

---

## 1. Предварительные требования

```bash
# AWS CLI + профиль bench
aws sts get-caller-identity --profile bench

# Проверить что SSM agent работает на инстансах
AWS_PROFILE=bench aws ssm describe-instance-information \
  --query 'InstanceInformationList[*].[InstanceId,PingStatus]' --output table
```

## 2. Узнать IP и Instance ID

### Из Terraform
```bash
cd infra/terraform
terraform output -json instance_ids
terraform output -json private_ips
```

### Вручную (если terraform state недоступен)
```bash
AWS_PROFILE=bench aws ec2 describe-instances \
  --filters "Name=tag:Project,Values=finops-benchmark" "Name=instance-state-name,Values=running" \
  --query 'Reservations[].Instances[].[Tags[?Key==`Role`].Value|[0],InstanceId,PrivateIpAddress]' \
  --output table
```

### Текущие значения (обновлять при пересоздании инфры)

| Role | Instance ID | Private IP | Type |
|------|-------------|------------|------|
| gateway | `i-0a472b35f13b18cf7` | `10.42.1.160` | c7i.2xlarge |
| telemetrygen | `i-01aeb4ef07aa20daf` | `10.42.1.129` | c7i.2xlarge |
| upstream | `i-030a068c617bbe834` | `10.42.1.127` | c7i.xlarge |

---

## 3. Обновление и деплой Gateway

### 3.1 Запушить код

```bash
git push origin main
```

### 3.2 Собрать и запустить на EC2

Через SSM отправляем shell-скрипт на gateway-ноду. Скрипт клонирует/обновляет репо,
собирает Docker-образ и запускает контейнер.

```bash
GATEWAY_ID="i-0a472b35f13b18cf7"
UPSTREAM_IP="10.42.1.127"
GIT_REF="main"   # или конкретный коммит: 72a706f

AWS_PROFILE=bench aws ssm send-command \
  --instance-ids "${GATEWAY_ID}" \
  --document-name "AWS-RunShellScript" \
  --timeout-seconds 600 \
  --parameters "$(jq -cn \
    --arg cmd "$(cat <<'SCRIPT'
set -euo pipefail
UPSTREAM_IP="__UPSTREAM_IP__"
GIT_REF="__GIT_REF__"

if [ ! -d /opt/finops/otel-gateway/.git ]; then
  git clone https://github.com/Aberkingaliev/otel-gateway.git /opt/finops/otel-gateway
fi

cd /opt/finops/otel-gateway
git fetch --all --prune
git checkout ${GIT_REF}

docker build -t finops-gateway:bench -f infra/docker/gateway.Dockerfile .

docker rm -f finops-gateway >/dev/null 2>&1 || true
docker run -d --name finops-gateway --restart unless-stopped \
  --log-opt max-size=50m --log-opt max-file=2 \
  -p 4317:4317 -p 4318:4318 -p 9464:9464 \
  -e OTLP_UPSTREAM_TRACES_URL="http://${UPSTREAM_IP}:14328/v1/traces" \
  -e OTLP_UPSTREAM_METRICS_URL="http://${UPSTREAM_IP}:14328/v1/metrics" \
  -e OTLP_UPSTREAM_LOGS_URL="http://${UPSTREAM_IP}:14328/v1/logs" \
  -e GATEWAY_QUEUE_ENABLED=true \
  -e GATEWAY_QUEUE_CAPACITY=65536 \
  -e GATEWAY_QUEUE_SHARDS=16 \
  -e GATEWAY_QUEUE_WORKERS=16 \
  -e GATEWAY_BACKPRESSURE_LOW=32768 \
  -e GATEWAY_BACKPRESSURE_HIGH=49152 \
  -e GATEWAY_BACKPRESSURE_CRITICAL=58982 \
  -e GATEWAY_BACKPRESSURE_MAX_QUEUE_WAIT_MS=500 \
  -e GATEWAY_ENABLE_REFRAME=true \
  -e GATEWAY_MASKING_ENABLED=true \
  -e GATEWAY_MASKING_SIMD=on \
  -e GATEWAY_MASKING_MAX_OPS_PER_PACKET=128 \
  -e "GATEWAY_MASKING_RULES=mask-tenant-trace-resource|TRACES|REDACT_MASK|resource.attributes.tenant_id|##########|10|skip|true;mask-tenant-trace-span|TRACES|REDACT_MASK|scopeSpans[*].spans[*].attributes.tenant_id|##########|11|skip|true;mask-tenant-metrics-resource|METRICS|REDACT_MASK|resourcemetrics.resource.attributes.tenant_id|##########|10|skip|true;drop-tenant-logs|LOGS|DROP|resourcelogs.resource.attributes.tenant_id||1|skip|true" \
  -e GATEWAY_MAX_INFLIGHT=8192 \
  -e GATEWAY_EXPORTER_POOL_SIZE=64 \
  -e GATEWAY_SLAB_SIZE_BYTES=268435456 \
  -e GATEWAY_METRICS_ENABLED=true \
  -e GATEWAY_METRICS_HTTP_ENABLED=true \
  -e GATEWAY_METRICS_HTTP_PORT=9464 \
  -e GATEWAY_METRICS_HTTP_PATH=/metrics \
  -e "JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75 -Dio.netty.leakDetection.level=simple" \
  finops-gateway:bench

for i in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:9464/metrics >/dev/null 2>&1; then
    echo "gateway_ready=1"
    exit 0
  fi
  sleep 2
done
echo "gateway not ready" >&2
exit 1
SCRIPT
)" \
    '{commands: [$cmd]}' \
    | sed "s|__UPSTREAM_IP__|${UPSTREAM_IP}|g; s|__GIT_REF__|${GIT_REF}|g"
  )" \
  --output json | jq -r '.Command.CommandId'
```

### 3.3 Проверить результат

```bash
COMMAND_ID="<command-id-из-предыдущего-шага>"

AWS_PROFILE=bench aws ssm get-command-invocation \
  --command-id "${COMMAND_ID}" \
  --instance-id "${GATEWAY_ID}" \
  --output json | jq -r '.Status, .StandardOutputContent, .StandardErrorContent'
```

Ожидаемый результат: `Status: Success`, `gateway_ready=1`.

---

## 4. Съём метрик

### 4.1 Снять метрики (через SSM с telemetrygen-ноды)

Gateway не имеет публичного доступа к порту 9464. Метрики снимаем
через curl на любой ноде внутри VPC (telemetrygen удобнее всего).

```bash
TELEMETRYGEN_ID="i-01aeb4ef07aa20daf"
GATEWAY_IP="10.42.1.160"

AWS_PROFILE=bench aws ssm send-command \
  --instance-ids "${TELEMETRYGEN_ID}" \
  --document-name "AWS-RunShellScript" \
  --parameters "$(jq -cn --arg cmd "curl -fsS http://${GATEWAY_IP}:9464/metrics" '{commands: [$cmd]}')" \
  --timeout-seconds 30 \
  --output json | jq -r '.Command.CommandId'
```

Забрать результат:
```bash
AWS_PROFILE=bench aws ssm get-command-invocation \
  --command-id "<command-id>" \
  --instance-id "${TELEMETRYGEN_ID}" \
  --output json | jq -r '.StandardOutputContent'
```

### 4.2 Ключевые метрики для анализа

| Метрика | Что показывает |
|---------|---------------|
| `gateway_packets_processed_total{status="received"}` | Всего получено пакетов |
| `gateway_packets_processed_total{status="accepted"}` | Успешно обработано |
| `gateway_packets_processed_total{status="dropped"}` | Общее число дропов |
| `gateway_dropped_total{reason_code="507"}` | Slab exhaustion (должен быть 0) |
| `gateway_dropped_total{reason_code="500"}` | Export failures |
| `gateway_dropped_total{reason_code="429"}` | Backpressure drops |
| `gateway_parse_errors_total{error_code="500"}` | Parse errors |
| `gateway_queue_depth` | Текущая глубина очереди |
| `gateway_end_to_end_p99_nanos` | p99 latency в наносекундах |
| `gateway_mask_writer_active` | SIMD или scalar masking |

---

## 5. Запуск нагрузочного теста (hey)

### 5.1 Быстрый тест через SSM

На telemetrygen-ноде уже установлен `hey` (`/usr/local/bin/hey`) и лежат
заранее сгенерированные payload-ы:

| Файл | Размер | Описание |
|------|--------|----------|
| `/tmp/payload-100.bin` | ~33 KB | 100 spans, protobuf |
| `/tmp/real-payload.bin` | ~19 KB | Реальный payload |

```bash
TELEMETRYGEN_ID="i-01aeb4ef07aa20daf"
GATEWAY_IP="10.42.1.160"
DURATION="120s"
CONCURRENCY=1000

AWS_PROFILE=bench aws ssm send-command \
  --instance-ids "${TELEMETRYGEN_ID}" \
  --document-name "AWS-RunShellScript" \
  --parameters "$(jq -cn --arg cmd "hey -z ${DURATION} -c ${CONCURRENCY} -m POST -D /tmp/payload-100.bin -T application/x-protobuf -disable-keepalive=false http://${GATEWAY_IP}:4318/v1/traces 2>&1" '{commands: [$cmd]}')" \
  --timeout-seconds 300 \
  --output json | jq -r '.Command.CommandId'
```

### 5.2 Полноценный бенчмарк (hey-benchmark.sh)

Скрипт `infra/scripts/benchmark/hey-benchmark.sh` автоматизирует:
генерацию payload → снятие metrics-before → hey → metrics-after → summary.

```bash
# Скопировать скрипт на ноду через SSM и запустить:
AWS_PROFILE=bench aws ssm send-command \
  --instance-ids "${TELEMETRYGEN_ID}" \
  --document-name "AWS-RunShellScript" \
  --parameters "$(jq -cn --arg cmd 'cd /opt/finops/otel-gateway && bash infra/scripts/benchmark/hey-benchmark.sh --gateway-ip 10.42.1.160 --duration 2m --concurrency 1000 --spans-per-request 100' '{commands: [$cmd]}')" \
  --timeout-seconds 600 \
  --output json | jq -r '.Command.CommandId'
```

### 5.3 Генерация нового payload (если нужен)

```bash
# На telemetrygen-ноде (через SSM):
python3 /opt/finops/otel-gateway/infra/scripts/benchmark/gen-otlp-payload.py 100 > /tmp/payload-100.bin
```

---

## 6. Просмотр логов Gateway

```bash
GATEWAY_ID="i-0a472b35f13b18cf7"

AWS_PROFILE=bench aws ssm send-command \
  --instance-ids "${GATEWAY_ID}" \
  --document-name "AWS-RunShellScript" \
  --parameters "$(jq -cn --arg cmd 'docker logs finops-gateway --tail 200 2>&1' '{commands: [$cmd]}')" \
  --timeout-seconds 30 \
  --output json | jq -r '.Command.CommandId'
```

---

## 7. Полный цикл: обновление + бенчмарк

```bash
# Переменные
export AWS_PROFILE=bench
GATEWAY_ID="i-0a472b35f13b18cf7"
TELEMETRYGEN_ID="i-01aeb4ef07aa20daf"
UPSTREAM_IP="10.42.1.127"
GATEWAY_IP="10.42.1.160"

# 1. Запушить код
git push origin main

# 2. Деплой (см. раздел 3.2, подождать gateway_ready=1)

# 3. Снять metrics-before
#    (см. раздел 4.1)

# 4. Запустить hey -c 1000 -z 120s
#    (см. раздел 5.1, подождать ~140 секунд)

# 5. Снять metrics-after
#    (см. раздел 4.1)

# 6. Сравнить:
#    - dropped_507 должен быть 0 (slab exhaustion)
#    - p99 < 5ms
#    - allocCount == releaseCount (нет утечек)

# 7. Логи при необходимости
#    (см. раздел 6)
```

---

## 8. Перезапуск контейнера (без пересборки)

```bash
AWS_PROFILE=bench aws ssm send-command \
  --instance-ids "${GATEWAY_ID}" \
  --document-name "AWS-RunShellScript" \
  --parameters "$(jq -cn --arg cmd 'docker restart finops-gateway && sleep 10 && curl -fsS http://127.0.0.1:9464/metrics >/dev/null && echo ok' '{commands: [$cmd]}')" \
  --timeout-seconds 60 \
  --output json | jq -r '.Command.CommandId'
```

---

## 9. Управление инфраструктурой

### Создать/уничтожить

```bash
cd infra/terraform
terraform apply  -var-file=profile_c7i.tfvars   # создать
terraform destroy -var-file=profile_c7i.tfvars   # уничтожить (ОСТОРОЖНО)
```

### Проверить состояние инстансов

```bash
AWS_PROFILE=bench aws ec2 describe-instance-status \
  --instance-ids i-0a472b35f13b18cf7 i-01aeb4ef07aa20daf i-030a068c617bbe834 \
  --query 'InstanceStatuses[*].[InstanceId,InstanceState.Name,SystemStatus.Status]' \
  --output table
```

---

## 10. Troubleshooting

### SSM command зависает / таймаут
- Проверить SSM agent: `aws ssm describe-instance-information`
- Увеличить `--timeout-seconds` (для hey бенчмарка нужно duration + 60s запаса)

### gateway_ready=0
- Посмотреть docker logs (раздел 6)
- Проверить что upstream (10.42.1.127:14328) доступен
- Проверить что порты 4317/4318/9464 не заняты: `ss -tlnp | grep -E '4317|4318|9464'`

### dropped_507 (slab exhaustion)
- Увеличить `GATEWAY_SLAB_SIZE_BYTES` (по умолчанию 256 MB)
- Проверить `GATEWAY_SLAB_REGIONS` (по умолчанию 8, power of two)
- Проверить нет ли утечки PacketRef (allocCount >> releaseCount)

### dropped_500 (export failures)
- Это Netty `ClosedChannelException` — upstream закрывает соединения
- Не связано с slab allocator
- При -c 1000 ожидаемо ~5-15% ошибок при перегрузке upstream

### parse_errors_500
- Payload не является валидным protobuf
- Проверить что используется правильный payload файл
- Проверить Content-Type: `application/x-protobuf`
