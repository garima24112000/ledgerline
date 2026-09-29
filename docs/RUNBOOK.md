# Ledgerline runbook: debugging on Kubernetes

Commands for a live cluster: EKS (`ledgerline-eks` context, us-east-1, 3 × c7i-flex.large, ALB) or
kind (`kind-ledgerline`). Every command below was run and checked on kind. On EKS, the Phase 9
deployment used the JVM debugging flow in [§4](#4-debug-containers-jcmd-and-network-tools), the
audit Lambda invoke, `kubectl get ingress` and a `pg_stat_activity` lock query. RDS-specific steps
apply only on EKS.

```bash
kubectl config use-context ledgerline-eks     # kind: kind-ledgerline
kubectl config set-context --current --namespace ledgerline
```
The examples use gateway-api; the other apps work the same way. Deployments are
`ledgerline-<app>`, pods carry `app.kubernetes.io/name=<app>`, and the container is named `<app>`
(pass `-c gateway-api` to skip the "Defaulted container" notice caused by the init container).
`deploy/<name>` picks *one* pod of the deployment. When two commands must hit the same pod, use its name:
```bash
POD=$(kubectl get pod -l app.kubernetes.io/name=gateway-api -o jsonpath='{.items[0].metadata.name}')
```

## What is (and isn't) inside the app image

The app images are `eclipse-temurin:21-jre` (Ubuntu) plus one jar. They run as uid 10001, with a
**read-only root filesystem** (only `/tmp` is writable, an `emptyDir`) and no capabilities. The tools
were checked by running the image:

| Available in the app container | **Missing**: use a debug container |
|---|---|
| `bash`, `ps`, `top`, `free`, `df`, `env`, `curl`, `wget`, `getent`, `kill`, `timeout`, `tar` | `ss`, `netstat`, `ip`, `nslookup`, `dig`, `nc`, `tcpdump` |
| `java`, `jfr` | **`jcmd`, `jstack`, `jmap`** (JDK tools; a JRE doesn't have them) |

So: `kubectl exec` for the first column, and an **ephemeral debug container** (`kubectl debug`,
[below](#4-debug-containers-jcmd-and-network-tools)) for the second. Nothing can be installed in the app
container: the filesystem is read-only and the user isn't root.

## 1. First look

```bash
kubectl get pods -o wide                         # restarts, node, pod IP
kubectl get hpa,ingress
kubectl get events --sort-by=.lastTimestamp | tail -20
kubectl describe pod -l app.kubernetes.io/name=gateway-api | sed -n '/Conditions:/,$p'
kubectl top pods                                  # needs metrics-server (installed by kind-up/aws-up)
kubectl top nodes
```
In `describe`, look at `Last State: Terminated` + `Reason: OOMKilled` (memory limit) and
`Readiness probe failed` (on gateway-api, readiness includes the database).

## 2. Container logs

The apps log one JSON object per line (logstash encoder), with `traceId` and `paymentId` where known.
```bash
kubectl logs deploy/ledgerline-gateway-api --since=15m                  # one pod of the deployment
kubectl logs -l app.kubernetes.io/name=gateway-api --prefix --tail=200  # every replica
kubectl logs <pod> --previous                                           # the crashed container before the restart
kubectl logs -f deploy/ledgerline-webhook-dispatcher                    # follow

# Errors only, as timestamp / logger / message. fromjson? skips non-JSON lines (e.g. thread dumps).
kubectl logs deploy/ledgerline-gateway-api --since=1h |
  jq -rR 'fromjson? | select(.level == "ERROR") | [."@timestamp", .logger_name, .message] | @tsv'

# Everything about one payment, across all gateway replicas.
kubectl logs -l app.kubernetes.io/name=gateway-api --tail=-1 | grep '<payment-id>'
```

## 3. Inside the app container (`kubectl exec`)

```bash
kubectl exec -it deploy/ledgerline-gateway-api -- bash
```
Then, inside:
```bash
ps -ef                                   # java is PID 1 (exec-form ENTRYPOINT)
top -b -n 1 | head -15                   # CPU/memory of the JVM
df -h / /tmp                             # / is read-only; /tmp is the emptyDir Tomcat writes to
free -m                                  # NOTE: shows the NODE's memory, not this container's limit
cat /sys/fs/cgroup/memory.max /sys/fs/cgroup/memory.current   # the container's limit and usage (bytes)
env | grep -vE 'PASSWORD|TOKEN' | sort   # config from the ConfigMap; never print the secrets
echo "$JAVA_TOOL_OPTIONS"                # -XX:MaxRAMPercentage=60 ...

# The app's own view of its health and JVM:
curl -s localhost:8080/actuator/health/readiness; echo
curl -s localhost:8080/actuator/prometheus | grep -E '^jvm_memory_used_bytes|^jvm_threads_live|^hikaricp_connections_active'

# DNS and TCP without nslookup/nc: getent and bash's /dev/tcp.
getent hosts ledgerline-kafka ledgerline-redis
timeout 3 bash -c '</dev/tcp/ledgerline-redis/6379' && echo "redis: port open"
DB_HOST=$(sed -E 's#jdbc:postgresql://([^:/]+).*#\1#' <<<"$DB_URL"); echo "$DB_HOST"
timeout 3 bash -c "</dev/tcp/$DB_HOST/5432" && echo "postgres: port open"
```
Ports: gateway-api 8080, webhook-dispatcher 8081, mock-bank 8082, demo-merchant 8083.

### Thread dump without jcmd

`SIGQUIT` makes the JVM print every thread's stack to stdout without stopping. `kill` is a bash
builtin, so this works in the JRE image:
```bash
kubectl exec "$POD" -c gateway-api -- bash -c 'kill -3 1'
kubectl logs "$POD" -c gateway-api --since=1m | sed -n '/^Full thread dump/,/^JNI global refs/p' > threads.txt
```
Virtual threads (used for requests and Kafka listeners) are not in this dump. Use
`jcmd Thread.dump_to_file` below for those.

## 4. Debug containers: jcmd and network tools

`kubectl debug --target=<container>` adds an **ephemeral container** to the running pod. It shares the
app's process and network namespaces, so it sees the JVM as PID 1 and the app's sockets, without a
restart. It inherits the pod's `runAsUser: 10001`, which is what jcmd needs: it can only attach to a
JVM running as the same user. It can't be removed afterwards. It stops when you exit, and it goes
away with the pod.

### JDK tools with an ephemeral `eclipse-temurin:21-jdk` container (used on EKS)
Same JVM version as the app, plus `jcmd`. Start the debug container with a long `sleep`, then run
each tool with `kubectl exec`. This is the flow used on EKS: it doesn't depend on an interactive
attach, and every command is one line you can script or paste.
```bash
POD=$(kubectl get pod -l app.kubernetes.io/name=gateway-api -o jsonpath='{.items[0].metadata.name}')
kubectl debug "$POD" --image=eclipse-temurin:21-jdk --target=gateway-api --profile=restricted \
  --container=jdk-debug --quiet -- sleep 1800
kubectl get pod "$POD" -o jsonpath='{.status.ephemeralContainerStatuses[*].state}'; echo   # "running" once the image is pulled

kubectl exec "$POD" -c jdk-debug -- jcmd 1 VM.version
kubectl exec "$POD" -c jdk-debug -- jcmd 1 Thread.print > threads.txt            # platform threads, with locks
kubectl exec "$POD" -c jdk-debug -- jcmd 1 Thread.dump_to_file -overwrite -format=json /tmp/vthreads.json   # incl. virtual threads
kubectl exec "$POD" -c jdk-debug -- jcmd 1 GC.heap_info                          # heap regions, used/committed
kubectl exec "$POD" -c jdk-debug -- jcmd 1 VM.flags                              # effective -XX flags (MaxHeapSize from MaxRAMPercentage)
kubectl exec "$POD" -c jdk-debug -- jcmd 1 GC.class_histogram | head -25       # what fills the heap
```
- `jcmd 1` works because the debug container shares the app's process namespace (`--target`) and
  inherits `runAsUser: 10001`. jcmd can only attach to a JVM running as the same user.
- File paths such as `/tmp/vthreads.json` are resolved by the **app's** JVM, so the file lands in
  the app container's `/tmp`. Copy it out through the app container:
  `kubectl cp -c gateway-api "$POD":/tmp/vthreads.json ./vthreads.json`.
- `jcmd 1 VM.native_memory summary` works only if the JVM was started with
  `-XX:NativeMemoryTracking=summary`, which it isn't by default.
- An ephemeral container can't be removed or restarted. After `sleep` ends, `kubectl debug` again
  with a **new** `--container` name (e.g. `jdk-debug-2`). They all go away when the pod is replaced.

**Interactive alternative** (verified on kind): a shell in the same kind of container. It stops
when you exit.
```bash
kubectl debug -it "$POD" --image=eclipse-temurin:21-jdk --target=gateway-api --profile=restricted -- bash
# inside: jcmd 1 VM.version, jcmd 1 GC.heap_info, ... (same commands as above, without kubectl exec)
```

A heap dump also goes to the **app's** `/tmp`. Copy it out through the app container, then delete
it, because the emptyDir lives on the node's disk (on kind a dump of the gateway was ~85 MB):
```bash
kubectl exec "$POD" -c jdk-debug -- jcmd 1 GC.heap_dump /tmp/heap.hprof
kubectl cp -c gateway-api "$POD":/tmp/heap.hprof ./heap.hprof
kubectl exec "$POD" -c gateway-api -- rm /tmp/heap.hprof
```

### Network tools (`nicolaka/netshoot`: ss, dig, nslookup, curl, tcpdump, …)
```bash
kubectl debug -it "$POD" --image=nicolaka/netshoot --target=gateway-api --profile=restricted -- bash
```
Inside (same network namespace as the app):
```bash
ss -tnp                                   # the JVM's TCP connections: DB pool (5432), Redis (6379), Kafka (9092)
ss -tlnp                                  # listening: 8080
nslookup ledgerline-kafka                 # cluster DNS
dig +short ledgerline-redis.ledgerline.svc.cluster.local
curl -s localhost:8080/actuator/health
```
`tcpdump` needs root and `NET_RAW`, which a debug container in this pod doesn't get (`runAsNonRoot`):
it fails with "You don't have permission to perform this capture". Capture from a separate root pod
instead, or not at all.

## 5. RDS connectivity (EKS)

RDS is private. Its security group accepts 5432 only from the EKS nodes and the audit Lambda, so
test **from inside the cluster**. From a laptop it always times out, by design.

Quick check from the app container (section 3): `timeout 3 bash -c "</dev/tcp/$DB_HOST/5432"`.

Full check with `psql` in a throwaway pod that gets the same ConfigMap and Secret as the apps:
```bash
kubectl run psql-debug --rm -it --restart=Never --image=postgres:16 --overrides='{
  "spec": {"containers": [{
    "name": "psql-debug", "image": "postgres:16", "stdin": true, "tty": true, "command": ["bash"],
    "envFrom": [{"configMapRef": {"name": "ledgerline-config"}}, {"secretRef": {"name": "ledgerline-secrets"}}]
  }]}}'
```
Inside:
```bash
DB_HOST=$(sed -E 's#jdbc:postgresql://([^:/]+).*#\1#' <<<"$DB_URL")
pg_isready -h "$DB_HOST" -p 5432                                  # "accepting connections"?
PGPASSWORD="$DB_PASSWORD" psql "host=$DB_HOST dbname=ledgerline user=$DB_USERNAME sslmode=require" \
  -c 'select version()' \
  -c 'select ssl, version from pg_stat_ssl where pid = pg_backend_pid()' \
  -c 'select status, count(*) from payments group by status'
```
(On kind the in-cluster Postgres has no TLS: use `sslmode=disable` there. For a non-interactive run,
drop `-it`/`stdin`/`tty`, put the commands in `"command": ["bash", "-c", "..."]`, then
`kubectl wait --for=jsonpath='{.status.phase}'=Succeeded pod/psql-debug`, `kubectl logs psql-debug`,
and `kubectl delete pod psql-debug`.)

How to read the failure:

| Symptom | Meaning |
|---|---|
| `pg_isready`: `no response`, psql hangs then `timeout expired` | Network: security group, subnet or route. Check the RDS SG allows the node SG, and that RDS is `available` |
| `could not translate host name` | Wrong endpoint in `DB_URL` / SSM `/ledgerline/deploy/database-url` |
| `password authentication failed` | Secret out of sync with RDS: re-run `scripts/aws-up.sh` |
| `no pg_hba.conf entry … no encryption` | Connected without TLS; RDS 16 forces it: use `sslmode=require` |

## 6. Ledger audit Lambda

```bash
aws lambda invoke --region us-east-1 --function-name ledgerline-ledger-audit out.json && jq . out.json
aws logs tail /aws/lambda/ledgerline-ledger-audit --region us-east-1 --since 1h --follow
aws s3 cp "s3://ledgerline-audit-$(aws sts get-caller-identity --query Account --output text)/audits/$(TZ=America/New_York date +%F).json" - | jq '.checks[] | {name, passed, violations, examples}'
aws cloudwatch describe-alarms --region us-east-1 --alarm-name-prefix ledgerline- \
  --query 'MetricAlarms[].[AlarmName,StateValue]' --output table
```
- `failures > 0`: the ledger is inconsistent. The `examples` in the report are the offending
  journal-entry, account or payment ids.
- A timeout or `connection attempt timed out` in the logs: the Lambda can't reach RDS (SG) or SSM
  (NAT). Same diagnosis as section 5.

## 7. The ALB (EKS)

```bash
kubectl get ingress ledgerline-gateway-api                         # ADDRESS = the ALB DNS name
kubectl -n kube-system logs deploy/aws-load-balancer-controller --since=30m | grep -iE 'error|denied'
TG=$(aws elbv2 describe-target-groups --region us-east-1 \
       --query "TargetGroups[?contains(TargetGroupName, 'ledgerli')].TargetGroupArn | [0]" --output text)
aws elbv2 describe-target-health --region us-east-1 --target-group-arn "$TG" \
  --query 'TargetHealthDescriptions[].[Target.Id,TargetHealth.State,TargetHealth.Reason]' --output table
```
Targets are pod IPs (`target-type: ip`), health-checked on `/actuator/health/readiness`. `unhealthy`
targets usually mean the pod isn't Ready (e.g. the database is down). A `curl` that times out while the
targets are healthy means your IP isn't in `INGRESS_ALLOWED_CIDRS`.

## 8. Lock contention on the ledger

Symptoms: p99 of `POST /v1/payments` and of `ledger_post_seconds` climbs to seconds,
`hikaricp_connections_pending` grows into the hundreds, and the median barely moves. This is
the known hot-row limit ([DESIGN.md: Known scaling limits](DESIGN.md#known-scaling-limits)): every
capture locks the shared `CUSTOMER_FUNDS` and `PLATFORM_FEES` rows.

From the app's metrics (section 3, inside the gateway container):
```bash
curl -s localhost:8080/actuator/prometheus | grep -E '^hikaricp_connections_(pending|active|max)'
curl -s localhost:8080/actuator/prometheus | grep -E '^ledger_post_seconds_(count|sum)'
```

From Postgres: on EKS use the psql pod from [section 5](#5-rds-connectivity-eks). Locally, use
`docker compose -f infra/docker-compose.yml exec postgres psql -U ledgerline ledgerline`.
```sql
-- Who is waiting on a lock, and for how long.
SELECT pid, wait_event_type, wait_event, now() - query_start AS running_for, left(query, 90) AS query
FROM pg_stat_activity
WHERE datname = current_database() AND wait_event_type = 'Lock'
ORDER BY query_start;

-- Who blocks whom.
SELECT waiting.pid AS waiting_pid, blocking.pid AS blocking_pid,
       left(waiting.query, 60) AS waiting_query, left(blocking.query, 60) AS blocking_query
FROM pg_stat_activity waiting
JOIN LATERAL unnest(pg_blocking_pids(waiting.pid)) AS b(pid) ON true
JOIN pg_stat_activity blocking ON blocking.pid = b.pid;
```
How to read it:
- Many sessions in `Lock:tuple` or `Lock:transactionid` on
  `SELECT id FROM accounts WHERE id IN (...) ORDER BY id FOR UPDATE`, plus `COMMIT`, is the hot-row
  queue ([example snapshot from EKS](images/postgres-lock-contention.png)). The blocking pids are
  captures holding the platform account rows until COMMIT.
- Scaling out gateway pods won't help: it only adds waiters. The options are in DESIGN.
- Afterwards, confirm the ledger is intact:
  `curl -u "$ADMIN_USERNAME:$ADMIN_PASSWORD" http://<gateway>/admin/ledger/verify` should report every
  entry balanced and a global net of 0.
- Requests that waited more than 10 s can lose their idempotency lock to the reconciler, and then
  fail even though their payment was resolved. That is the secondary race in DESIGN.

## 9. Grafana and Prometheus on EKS

No Ingress: port-forward. The Ledgerline dashboard is provisioned by the chart.
```bash
kubectl --context ledgerline-eks -n monitoring port-forward svc/kube-prometheus-stack-grafana 3000:80
kubectl --context ledgerline-eks -n monitoring get secret kube-prometheus-stack-grafana \
  -o jsonpath='{.data.admin-password}' | base64 --decode; echo            # user: admin
kubectl --context ledgerline-eks -n monitoring get svc | grep prometheus   # then port-forward the -prometheus service on 9090
```
The panels to watch under load are "Ledger post latency" and "Hikari pool (gateway-api)".
