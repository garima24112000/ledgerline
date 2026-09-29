#!/usr/bin/env bash
# Brings up Ledgerline's AWS infrastructure in us-east-1: Terraform (VPC, EKS, RDS, ECR, S3, audit
# Lambda, GitHub OIDC role), then the cluster add-ons (AWS Load Balancer Controller, metrics-server,
# kube-prometheus-stack), the gp3 StorageClass, the deployer RBAC and the ledgerline-secrets Secret.
#
# It does NOT build or deploy the application images: .github/workflows/deploy.yml does that on
# GitHub's amd64 runners (never from an Apple Silicon laptop).
#
#   scripts/aws-up.sh                 interactive (terraform asks before applying)
#   scripts/aws-up.sh --set-gh-vars   also set the GitHub repository variables deploy.yml reads
#   scripts/aws-up.sh --deploy        also trigger deploy.yml on main and watch it
#   scripts/aws-up.sh --yes           no confirmation prompts (terraform apply -auto-approve)
#
# Safe to re-run: terraform converges, helm uses upgrade --install, kubectl uses apply, and the gp3
# StorageClass is only created when missing. The Lambda's code is never reverted (see lambda.tf).
#
# Needs: aws (v2, logged in), terraform >= 1.11, kubectl, helm, jq, java 21 + mvn; gh for --set-gh-vars/--deploy.
# COSTS MONEY while it exists (~$0.21/h plus the 3 nodes' EC2 cost). Tear down with scripts/aws-down.sh.
set -euo pipefail

cd "$(dirname "$0")/.."

REGION=us-east-1
TF_DIR=infra/terraform
NAMESPACE=ledgerline
KUBE_CONTEXT=ledgerline-eks
SECRET_NAME=ledgerline-secrets

# Pinned chart versions, so a re-run months from now installs the same thing.
AWS_LOAD_BALANCER_CONTROLLER_VERSION=3.5.0
METRICS_SERVER_VERSION=3.14.0
KUBE_PROMETHEUS_STACK_VERSION=91.8.1

ASSUME_YES=false
SET_GH_VARS=false
DEPLOY=false
for arg in "$@"; do
  case "$arg" in
    --yes) ASSUME_YES=true ;;
    --set-gh-vars) SET_GH_VARS=true ;;
    --deploy) DEPLOY=true ;;
    -h | --help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

step() { printf '\n==> %s\n' "$*"; }
die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }

# Every aws/kubectl/helm call targets this region and this cluster explicitly, whatever the
# laptop's defaults are.
export AWS_REGION="$REGION" AWS_DEFAULT_REGION="$REGION"
K=(kubectl --context "$KUBE_CONTEXT")
H=(helm --kube-context "$KUBE_CONTEXT")

required_tools=(aws terraform kubectl helm jq mvn java openssl)
if $SET_GH_VARS || $DEPLOY; then required_tools+=(gh); fi
for tool in "${required_tools[@]}"; do
  command -v "$tool" >/dev/null || die "missing required tool: $tool"
done

[[ -f "$TF_DIR/terraform.tfvars" ]] ||
  die "$TF_DIR/terraform.tfvars not found. Copy $TF_DIR/terraform.tfvars.example and set ingress_allowed_cidrs to your IP/32 (curl -s https://checkip.amazonaws.com)."

step "AWS identity"
identity="$(aws sts get-caller-identity --output json)" || die "not logged in to AWS (aws sso login / aws configure)"
ACCOUNT_ID="$(jq -r .Account <<<"$identity")"
echo "account: $ACCOUNT_ID"
echo "caller:  $(jq -r .Arn <<<"$identity")"
echo "region:  $REGION"
if ! $ASSUME_YES; then
  read -r -p "Create/update CHARGEABLE resources (~\$0.21/hour plus EC2 for 3 nodes) in account $ACCOUNT_ID? Type 'yes': " answer
  [[ "$answer" == "yes" ]] || die "aborted"
fi

step "Audit Lambda jar (Terraform uploads it when it first creates the function)"
mvn -B -q -pl ledger-audit-lambda -am package -DskipTests
ls -lh ledger-audit-lambda/target/ledger-audit-lambda.jar

step "Terraform (15-25 minutes on the first run: EKS and RDS are slow to create)"
terraform -chdir="$TF_DIR" init -input=false
apply_args=(-input=false)
if $ASSUME_YES; then apply_args+=(-auto-approve); fi
terraform -chdir="$TF_DIR" apply "${apply_args[@]}"

outputs="$(terraform -chdir="$TF_DIR" output -json)"
out() { jq -r --arg k "$1" '.[$k].value' <<<"$outputs"; }
CLUSTER="$(out cluster_name)"
VPC_ID="$(out vpc_id)"
LBC_ROLE_ARN="$(out lb_controller_role_arn)"

step "kubeconfig (context $KUBE_CONTEXT)"
aws eks update-kubeconfig --name "$CLUSTER" --region "$REGION" --alias "$KUBE_CONTEXT" >/dev/null
"${K[@]}" wait --for=condition=Ready nodes --all --timeout=10m
"${K[@]}" get nodes -L topology.kubernetes.io/zone,node.kubernetes.io/instance-type

step "StorageClass gp3 (explicit, never the default)"
# Inspect first: never add a second default, never modify the existing default, and never blindly
# re-apply (StorageClass parameters are immutable, so a changed manifest would fail anyway).
sc_json="$("${K[@]}" get storageclass -o json)"
echo "existing StorageClasses (name, provisioner, default):"
jq -r '.items[] | "  \(.metadata.name)\t\(.provisioner)\t\(.metadata.annotations["storageclass.kubernetes.io/is-default-class"] // "false")"' <<<"$sc_json"
gp3="$(jq -c '.items[] | select(.metadata.name == "gp3")' <<<"$sc_json")"
if [[ -z "$gp3" ]]; then
  "${K[@]}" apply -f infra/k8s/eks/storageclass-gp3.yaml
elif [[ "$(jq -r '.provisioner' <<<"$gp3")" == "ebs.csi.aws.com" && "$(jq -r '.parameters.type // ""' <<<"$gp3")" == "gp3" ]]; then
  echo "gp3 already exists (ebs.csi.aws.com, type gp3): left as is"
else
  die "a StorageClass named gp3 exists but is not ebs.csi.aws.com/type=gp3 ($(jq -c '{provisioner, parameters}' <<<"$gp3")). Delete or rename it, then re-run."
fi

step "Helm repositories"
helm repo add eks https://aws.github.io/eks-charts --force-update >/dev/null
helm repo add metrics-server https://kubernetes-sigs.github.io/metrics-server/ --force-update >/dev/null
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts --force-update >/dev/null
helm repo update >/dev/null

step "AWS Load Balancer Controller (Ingress className alb -> ALB)"
"${H[@]}" upgrade --install aws-load-balancer-controller eks/aws-load-balancer-controller \
  --version "$AWS_LOAD_BALANCER_CONTROLLER_VERSION" \
  --namespace kube-system \
  -f infra/k8s/eks/aws-load-balancer-controller-values.yaml \
  --set clusterName="$CLUSTER" \
  --set region="$REGION" \
  --set vpcId="$VPC_ID" \
  --set "serviceAccount.annotations.eks\.amazonaws\.com/role-arn=$LBC_ROLE_ARN" \
  --wait --timeout 5m

step "metrics-server (CPU metrics for the gateway-api HPA)"
# EKS kubelets have proper certificates: unlike kind, no --kubelet-insecure-tls.
"${H[@]}" upgrade --install metrics-server metrics-server/metrics-server \
  --version "$METRICS_SERVER_VERSION" \
  --namespace kube-system \
  --wait --timeout 5m

step "kube-prometheus-stack (Grafana via port-forward only)"
# Keep Grafana's admin password across re-runs; generate one the first time.
grafana_password="$("${K[@]}" -n monitoring get secret kube-prometheus-stack-grafana \
  -o jsonpath='{.data.admin-password}' 2>/dev/null | base64 --decode || true)"
if [[ -z "$grafana_password" ]]; then
  grafana_password="$(openssl rand -hex 16)"
fi
# Passed as a values file on a file descriptor, not --set, so it never shows up in `ps`.
"${H[@]}" upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  --version "$KUBE_PROMETHEUS_STACK_VERSION" \
  --namespace monitoring --create-namespace \
  -f infra/k8s/eks/kube-prometheus-stack-values.yaml \
  -f <(printf 'grafana:\n  adminPassword: "%s"\n' "$grafana_password") \
  --wait --timeout 10m

step "Namespace $NAMESPACE + deployer RBAC (GitHub Actions: admin of this namespace only)"
"${K[@]}" apply -f infra/k8s/eks/deployer-rbac.yaml

step "Secret $SECRET_NAME (from SSM SecureStrings; values are never printed or written to disk)"
read_param() {
  aws ssm get-parameter --name "$1" --with-decryption --query Parameter.Value --output text
}
# Empty when the Secret doesn't exist yet.
secret_digest() {
  local data
  data="$("${K[@]}" -n "$NAMESPACE" get secret "$SECRET_NAME" -o jsonpath='{.data}' 2>/dev/null || true)"
  if [[ -n "$data" ]]; then shasum <<<"$data" | cut -d' ' -f1; fi
}
before="$(secret_digest)"
{
  printf 'DB_PASSWORD=%s\n' "$(read_param /ledgerline/db/password)"
  printf 'ADMIN_PASSWORD=%s\n' "$(read_param /ledgerline/app/admin-password)"
  printf 'INTERNAL_SERVICE_TOKEN=%s\n' "$(read_param /ledgerline/app/internal-service-token)"
} | "${K[@]}" -n "$NAMESPACE" create secret generic "$SECRET_NAME" \
      --from-env-file=/dev/stdin --dry-run=client -o yaml |
  "${K[@]}" apply -f -
after="$(secret_digest)"
# Pods read the Secret only at start, and the chart's checksum annotation doesn't cover a Secret it
# didn't create: restart the apps if the values changed (e.g. after bumping secrets_version).
if [[ -n "$before" && "$before" != "$after" ]] && "${K[@]}" -n "$NAMESPACE" get deploy -o name | grep -q .; then
  echo "secret changed: restarting deployments"
  "${K[@]}" -n "$NAMESPACE" rollout restart deploy
fi

if $SET_GH_VARS || $DEPLOY; then
  REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
fi

if $SET_GH_VARS; then
  step "GitHub repository variables on $REPO"
  gh variable set AWS_REGION --repo "$REPO" --body "$REGION"
  gh variable set AWS_ROLE_ARN --repo "$REPO" --body "$(out github_deploy_role_arn)"
  gh variable set ECR_REGISTRY --repo "$REPO" --body "$(out ecr_registry)"
  gh variable set EKS_CLUSTER_NAME --repo "$REPO" --body "$CLUSTER"
  gh variable set LAMBDA_FUNCTION_NAME --repo "$REPO" --body "$(out audit_lambda_name)"
  gh variable set INGRESS_ALLOWED_CIDRS --repo "$REPO" --body "$(out ingress_allowed_cidrs)"
  gh variable set DEPLOY_ENABLED --repo "$REPO" --body true
fi

if $DEPLOY; then
  step "Deploy: triggering deploy.yml on main"
  gh workflow run deploy.yml --repo "$REPO" --ref main
  sleep 5
  run_id="$(gh run list --repo "$REPO" --workflow deploy.yml --limit 1 --json databaseId -q '.[0].databaseId')"
  gh run watch "$run_id" --repo "$REPO" --exit-status
fi

step "Ready"
alb="$("${K[@]}" -n "$NAMESPACE" get ingress ledgerline-gateway-api \
  -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)"
cat <<EOF

Cluster:          $CLUSTER   (kubectl --context $KUBE_CONTEXT ...)
RDS:              $(out rds_endpoint)   (private; reachable only from the cluster and the audit Lambda)
ECR registry:     $(out ecr_registry)
Audit bucket:     s3://$(out audit_bucket)/audits/
Audit Lambda:     aws lambda invoke --region $REGION --function-name $(out audit_lambda_name) out.json && cat out.json
GitHub role:      $(out github_deploy_role_arn)
ALB allowed from: $(out ingress_allowed_cidrs)
EOF
if [[ -n "$alb" ]]; then
  echo "Gateway API:      http://$alb/swagger-ui.html"
else
  cat <<EOF
Gateway API:      not deployed yet. Set the GitHub variables (scripts/aws-up.sh --set-gh-vars),
                  then push to main or run: gh workflow run deploy.yml --ref main
EOF
fi
cat <<EOF
Grafana:          kubectl --context $KUBE_CONTEXT -n monitoring port-forward svc/kube-prometheus-stack-grafana 3000:80
                  then http://localhost:3000  (admin / password below)
                  kubectl --context $KUBE_CONTEXT -n monitoring get secret kube-prometheus-stack-grafana -o jsonpath='{.data.admin-password}' | base64 --decode

This stack costs money every hour it exists. Tear down: scripts/aws-down.sh
EOF
