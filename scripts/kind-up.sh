#!/usr/bin/env bash
# Brings up Ledgerline on a local kind cluster: cluster, images, ingress-nginx, metrics-server,
# kube-prometheus-stack, then the ledgerline chart. Safe to re-run: existing pieces are upgraded.
#
#   scripts/kind-up.sh          (or: make kind-up)
#
# Needs: docker, kind, kubectl, helm.
set -euo pipefail

cd "$(dirname "$0")/.."

CLUSTER=ledgerline
NAMESPACE=ledgerline
APPS=(gateway-api webhook-dispatcher mock-bank demo-merchant)

# Pinned chart versions, so a re-run months from now installs the same thing.
INGRESS_NGINX_VERSION=4.15.1
METRICS_SERVER_VERSION=3.14.0
KUBE_PROMETHEUS_STACK_VERSION=91.8.1

# A new tag whenever the code changes, so `helm upgrade` rolls the pods (with pullPolicy Never,
# re-using the tag "dev" would leave the old image running).
IMAGE_TAG="$(git rev-parse --short HEAD)"
if [[ -n "$(git status --porcelain)" ]]; then
  IMAGE_TAG="${IMAGE_TAG}-dirty-$(date +%s)"
fi

step() { printf '\n==> %s\n' "$*"; }

for tool in docker kind kubectl helm; do
  command -v "$tool" >/dev/null || { echo "missing required tool: $tool" >&2; exit 1; }
done

step "Cluster"
if kind get clusters | grep -qx "$CLUSTER"; then
  echo "kind cluster '$CLUSTER' already exists"
else
  # The cluster plus kube-prometheus-stack needs ~6 GB. With less, the node containers swap, the API
  # server stops answering, and helm fails with confusing connection errors.
  available_mb="$(docker run --rm busybox:1.36 free -m | awk '/^Mem:/ {print $7}')"
  if (( available_mb < 6000 )); then
    echo "WARNING: Docker has only ${available_mb} MB of memory available; ~6000 MB is needed." >&2
    echo "Stop other containers (e.g. make down) or give Docker Desktop more memory." >&2
  fi
  kind create cluster --config infra/k8s/kind-config.yaml
fi
kubectl config use-context "kind-$CLUSTER" >/dev/null

step "Images (tag $IMAGE_TAG)"
for app in "${APPS[@]}"; do
  echo "building ledgerline/$app:$IMAGE_TAG"
  docker build -q -f "$app/Dockerfile" -t "ledgerline/$app:$IMAGE_TAG" . >/dev/null
  kind load docker-image --name "$CLUSTER" "ledgerline/$app:$IMAGE_TAG"
done

step "Helm repositories"
helm repo add ingress-nginx https://kubernetes.github.io/ingress-nginx --force-update >/dev/null
helm repo add metrics-server https://kubernetes-sigs.github.io/metrics-server/ --force-update >/dev/null
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts --force-update >/dev/null
helm repo update >/dev/null

step "ingress-nginx"
helm upgrade --install ingress-nginx ingress-nginx/ingress-nginx \
  --version "$INGRESS_NGINX_VERSION" \
  --namespace ingress-nginx --create-namespace \
  -f infra/k8s/ingress-nginx-values.yaml \
  --wait --timeout 5m

step "metrics-server (CPU metrics for the HPA)"
# kind's kubelets use self-signed certificates, hence --kubelet-insecure-tls. Local clusters only.
helm upgrade --install metrics-server metrics-server/metrics-server \
  --version "$METRICS_SERVER_VERSION" \
  --namespace kube-system \
  --set 'args={--kubelet-insecure-tls}' \
  --wait --timeout 5m

step "kube-prometheus-stack"
helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  --version "$KUBE_PROMETHEUS_STACK_VERSION" \
  --namespace monitoring --create-namespace \
  -f infra/k8s/kube-prometheus-stack-values.yaml \
  --wait --timeout 10m

step "ledgerline chart"
helm upgrade --install ledgerline infra/helm/ledgerline \
  --namespace "$NAMESPACE" --create-namespace \
  -f infra/helm/ledgerline/values-kind.yaml \
  --set image.tag="$IMAGE_TAG" \
  --wait --timeout 10m

step "Checking Grafana's Prometheus datasource uid"
# The Ledgerline dashboard refers to its datasource by uid "prometheus". Fail loudly if Grafana
# doesn't have exactly that uid, instead of showing a dashboard full of "datasource not found".
for attempt in $(seq 1 30); do
  if body="$(curl -fsS -u admin:admin http://grafana.localtest.me/api/datasources/uid/prometheus 2>/dev/null)"; then
    break
  fi
  body=""
  sleep 2
done
if [[ "$body" != *'"uid":"prometheus"'* ]]; then
  echo "Grafana has no datasource with uid 'prometheus' (response: ${body:-none})." >&2
  echo "The Ledgerline dashboard would show no data. Check grafana.sidecar.datasources.uid." >&2
  exit 1
fi
echo "ok: datasource uid is 'prometheus'"

step "Ready"
kubectl -n "$NAMESPACE" get pods,hpa
cat <<EOF

Gateway API:  http://api.localtest.me/swagger-ui.html
Grafana:      http://grafana.localtest.me   (anonymous viewer; admin/admin to edit)
Tear down:    scripts/kind-down.sh
EOF
