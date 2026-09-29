# Ledgerline runbook: debugging on Kubernetes

Commands for a live cluster: EKS (`ledgerline-eks` context), or kind (`kind-ledgerline`), where every
command below was run and checked. RDS-specific steps apply only on EKS.

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
[below](#debug-containers-jcmd-and-network-tools)) for the second. Nothing can be installed in the app
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

Two ways to use one:
- **Interactive:** `kubectl debug -it "$POD" ... -- bash` (below). The container stops when you exit the shell.
- **One-off commands** (scripts, or when attaching is flaky): start it with a long `sleep`, then `exec` into it:
  ```bash
  kubectl debug "$POD" --image=eclipse-temurin:21-jdk --target=gateway-api --profile=restricted \
    --container=jdk-debug --quiet -- sleep 1800
  kubectl exec "$POD" -c jdk-debug -- jcmd 1 GC.heap_info
  ```

### JDK tools (`eclipse-temurin:21-jdk`: same JVM version, plus jcmd)
```bash
kubectl debug -it "$POD" --image=eclipse-temurin:21-jdk --target=gateway-api --profile=restricted -- bash
```
Inside:
```bash
jcmd 1 VM.version
jcmd 1 Thread.print > /tmp/threads.txt            # platform threads, with locks
jcmd 1 Thread.dump_to_file -overwrite -format=json /tmp/vthreads.json    # incl. virtual threads
ls -l /proc/1/root/tmp/vthreads.json               # file paths are resolved by the APP's JVM: its /tmp
jcmd 1 GC.heap_info                                # heap regions, used/committed
jcmd 1 VM.flags                                    # the effective -XX flags (MaxHeapSize from MaxRAMPercentage)
jcmd 1 GC.class_histogram | head -25               # what fills the heap
jcmd 1 VM.native_memory summary                    # only if started with -XX:NativeMemoryTracking=summary (not by default)
```
A heap dump also goes to the **app's** `/tmp`. Copy it out through the app container, then delete
it, because the emptyDir lives on the node's disk (on kind a dump of the gateway was ~85 MB):
```bash
jcmd 1 GC.heap_dump /tmp/heap.hprof                                   # in the debug container
kubectl cp -c gateway-api "$POD":/tmp/heap.hprof ./heap.hprof          # from your laptop
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
