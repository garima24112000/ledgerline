#!/usr/bin/env bash
# Tears down everything scripts/aws-up.sh and Terraform created, in an order that leaves nothing
# chargeable behind, then PROVES it with a verification table. Exits non-zero if anything remains.
#
#   scripts/aws-down.sh         asks you to type the cluster name first
#   scripts/aws-down.sh --yes   no prompts (terraform destroy -auto-approve)
#
# Order matters:
#   1. helm uninstall ledgerline  -> its Ingress is deleted, and the AWS Load Balancer Controller
#      (still running!) deletes the ALB, its target groups and security groups;
#   2. delete the Kafka PVC       -> the EBS CSI driver deletes the EBS volume;
#   3. wait until AWS confirms the load balancers and volumes are gone (they are not in Terraform
#      state, and a leftover ALB or its security group blocks VPC deletion);
#   4. only then uninstall the controllers (uninstalling the LB controller first would orphan the ALB);
#   5. empty ECR and S3, terraform destroy, verify.
#
# Safe to re-run, including on an already-destroyed stack (it then only verifies).
# Needs: aws, terraform, kubectl, helm, jq.
set -Eeuo pipefail

cd "$(dirname "$0")/.."

REGION=us-east-1
TF_DIR=infra/terraform
CLUSTER=ledgerline
NAMESPACE=ledgerline
KUBE_CONTEXT=ledgerline-eks
PROJECT=ledgerline
APPS=(gateway-api webhook-dispatcher mock-bank demo-merchant)

ASSUME_YES=false
for arg in "$@"; do
  case "$arg" in
    --yes) ASSUME_YES=true ;;
    -h | --help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

step() { printf '\n==> %s\n' "$*"; }
die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
incomplete() {
  cat >&2 <<EOF

!!! TEARDOWN INCOMPLETE - chargeable resources may still exist in $REGION. !!!
Fix the error above and re-run scripts/aws-down.sh (it is safe to re-run), or check by hand:
  aws resourcegroupstaggingapi get-resources --region $REGION --tag-filters Key=project,Values=$PROJECT
  aws eks list-clusters --region $REGION
  aws ec2 describe-nat-gateways --region $REGION --filter Name=state,Values=pending,available
  aws elbv2 describe-load-balancers --region $REGION
  aws rds describe-db-instances --region $REGION
EOF
}
# Any non-zero exit (a failed command, die, a FAIL in the verification) prints the warning.
on_exit() { local status=$?; if ((status != 0)); then incomplete; fi; }
trap on_exit EXIT

export AWS_REGION="$REGION" AWS_DEFAULT_REGION="$REGION"
K=(kubectl --context "$KUBE_CONTEXT")
H=(helm --kube-context "$KUBE_CONTEXT")

for tool in aws terraform kubectl helm jq; do
  command -v "$tool" >/dev/null || die "missing required tool: $tool"
done

# Polls "$@" (a command printing leftover resource ids) until it prints nothing.
wait_until_empty() {
  local what="$1" timeout="$2"; shift 2
  local deadline=$((SECONDS + timeout)) leftover
  while true; do
    leftover="$("$@")"
    if [[ -z "$leftover" ]]; then
      echo "ok: no $what left"
      return 0
    fi
    if ((SECONDS >= deadline)); then
      printf 'still present after %ss:\n%s\n' "$timeout" "$leftover" >&2
      die "$what did not disappear. Delete them by hand (ids above), then re-run."
    fi
    echo "waiting for $what to be deleted: $(tr '\n' ' ' <<<"$leftover")"
    sleep 15
  done
}

step "AWS identity"
ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)" || die "not logged in to AWS"
echo "account: $ACCOUNT_ID   region: $REGION"
if ! $ASSUME_YES; then
  read -r -p "DESTROY Ledgerline (cluster, RDS database and all its data, images, audit reports)? Type the cluster name '$CLUSTER': " answer
  [[ "$answer" == "$CLUSTER" ]] || die "aborted"
fi

VPC_ID="$(aws ec2 describe-vpcs --filters "Name=tag:project,Values=$PROJECT" --query 'Vpcs[0].VpcId' --output text)"
[[ "$VPC_ID" == "None" ]] && VPC_ID=""
echo "vpc: ${VPC_ID:-none}"

# --- Leftover finders (print ids, one per line; nothing = none left) ---------------------------------
load_balancers_in_vpc() {
  [[ -n "$VPC_ID" ]] || return 0
  aws elbv2 describe-load-balancers --query "LoadBalancers[?VpcId=='$VPC_ID'].LoadBalancerArn" --output text | tr '\t' '\n'
  aws elb describe-load-balancers --query "LoadBalancerDescriptions[?VPCId=='$VPC_ID'].LoadBalancerName" --output text | tr '\t' '\n'
}
target_groups_in_vpc() {
  [[ -n "$VPC_ID" ]] || return 0
  aws elbv2 describe-target-groups --query "TargetGroups[?VpcId=='$VPC_ID'].TargetGroupArn" --output text | tr '\t' '\n'
}
controller_security_groups() {
  [[ -n "$VPC_ID" ]] || return 0
  aws ec2 describe-security-groups \
    --filters "Name=vpc-id,Values=$VPC_ID" "Name=tag-key,Values=elbv2.k8s.aws/cluster" \
    --query 'SecurityGroups[].GroupId' --output text | tr '\t' '\n'
}
lb_leftovers() { load_balancers_in_vpc; target_groups_in_vpc; controller_security_groups; }
pvc_volumes() {
  aws ec2 describe-volumes \
    --filters "Name=tag:project,Values=$PROJECT" "Name=tag-key,Values=CSIVolumeName" \
    --query 'Volumes[].VolumeId' --output text | tr '\t' '\n'
}

# --- 1-4. Kubernetes side, while the cluster and its controllers still exist ------------------------
if aws eks describe-cluster --name "$CLUSTER" >/dev/null 2>&1; then
  step "kubeconfig"
  aws eks update-kubeconfig --name "$CLUSTER" --region "$REGION" --alias "$KUBE_CONTEXT" >/dev/null

  step "Uninstall the ledgerline chart (deletes the Ingress -> the controller deletes the ALB)"
  if "${H[@]}" status ledgerline -n "$NAMESPACE" >/dev/null 2>&1; then
    "${H[@]}" uninstall ledgerline -n "$NAMESPACE" --wait --timeout 10m
  else
    echo "not installed"
  fi

  step "Delete PersistentVolumeClaims (StatefulSet volumes survive helm uninstall)"
  "${K[@]}" delete pvc --all -n "$NAMESPACE" --wait=true --timeout=5m --ignore-not-found

  step "Delete any other Ingress / LoadBalancer Service in the cluster"
  "${K[@]}" delete ingress --all --all-namespaces --wait=true --timeout=5m
  "${K[@]}" get svc --all-namespaces -o json |
    jq -r '.items[] | select(.spec.type == "LoadBalancer") | "\(.metadata.namespace) \(.metadata.name)"' |
    while read -r ns name; do
      "${K[@]}" delete svc -n "$ns" "$name" --wait=true --timeout=5m
    done
  remaining="$("${K[@]}" get ingress,svc --all-namespaces -o json |
    jq -r '.items[] | select(.kind == "Ingress" or .spec.type == "LoadBalancer") | "\(.kind) \(.metadata.namespace)/\(.metadata.name)"')"
  [[ -z "$remaining" ]] || die "still present in the cluster: $remaining"
  echo "ok: no Ingress or LoadBalancer Service left"

  step "Wait for AWS to delete the ALB, target groups and controller security groups"
  wait_until_empty "load balancers / target groups / controller security groups" 600 lb_leftovers

  step "Wait for the EBS CSI driver to delete the PVC volumes"
  wait_until_empty "PVC EBS volumes" 300 pvc_volumes

  step "Uninstall the add-ons (only now: the LB controller had to delete the ALB first)"
  for release in "kube-prometheus-stack monitoring" "metrics-server kube-system" "aws-load-balancer-controller kube-system"; do
    read -r name ns <<<"$release"
    if "${H[@]}" status "$name" -n "$ns" >/dev/null 2>&1; then
      "${H[@]}" uninstall "$name" -n "$ns" --wait --timeout 5m
    fi
  done
else
  step "EKS cluster $CLUSTER not found: skipping the Kubernetes steps"
  # Without the controller, nothing will delete a leftover ALB for us, and it would block VPC deletion.
  [[ -z "$(lb_leftovers)" ]] || die "load balancers are left in $VPC_ID but the cluster is gone: $(lb_leftovers | tr '\n' ' ')"
fi

# --- 5. Empty ECR and S3 (Terraform's force_delete/force_destroy is only a backstop) ----------------
step "Empty the ECR repositories"
for app in "${APPS[@]}"; do
  repo="$PROJECT/$app"
  if ! aws ecr describe-repositories --repository-names "$repo" >/dev/null 2>&1; then
    echo "$repo: not found"
    continue
  fi
  while true; do
    ids="$(aws ecr list-images --repository-name "$repo" --max-items 100 --query 'imageIds' --output json)"
    [[ "$(jq length <<<"$ids")" -eq 0 ]] && break
    aws ecr batch-delete-image --repository-name "$repo" --image-ids "$ids" >/dev/null
  done
  echo "$repo: empty"
done

step "Empty the audit bucket"
BUCKET="$PROJECT-audit-$ACCOUNT_ID"
if aws s3api head-bucket --bucket "$BUCKET" >/dev/null 2>&1; then
  versioning="$(aws s3api get-bucket-versioning --bucket "$BUCKET" --query Status --output text)"
  [[ "$versioning" == "None" ]] ||
    die "versioning is '$versioning' on $BUCKET: 's3 rm' would leave old versions behind. Delete them first."
  aws s3 rm "s3://$BUCKET" --recursive
  echo "$BUCKET: empty"
else
  echo "$BUCKET: not found"
fi

# --- terraform destroy -------------------------------------------------------------------------------
step "terraform destroy"
terraform -chdir="$TF_DIR" init -input=false >/dev/null
destroy_args=(-input=false)
if $ASSUME_YES; then destroy_args+=(-auto-approve); fi
# ingress_allowed_cidrs is required, but its value doesn't matter for destroy.
[[ -f "$TF_DIR/terraform.tfvars" ]] || destroy_args+=(-var 'ingress_allowed_cidrs=["127.0.0.1/32"]')
if [[ -z "$(terraform -chdir="$TF_DIR" state list 2>/dev/null)" ]]; then
  echo "Terraform state is empty: nothing to destroy"
elif ! terraform -chdir="$TF_DIR" destroy "${destroy_args[@]}"; then
  # Usually Lambda's VPC network interfaces, which AWS releases up to ~20-40 minutes after the
  # function is deleted and which block the subnet/security group deletion meanwhile.
  echo "destroy failed; retrying in 5 minutes (Lambda VPC network interfaces release slowly)..."
  sleep 300
  terraform -chdir="$TF_DIR" destroy "${destroy_args[@]}"
fi

if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
  # Pushes to main no longer try (and fail) to deploy to a stack that doesn't exist.
  # Best effort: a failure here must not fail the teardown.
  if gh variable set DEPLOY_ENABLED --body false >/dev/null 2>&1; then
    echo "GitHub variable DEPLOY_ENABLED=false"
  fi
fi

# --- Verification: prove nothing chargeable is left ---------------------------------------------------
step "Verification ($REGION)"
aws sts get-caller-identity >/dev/null || die "AWS credentials stopped working: cannot verify"
failures=0
# PASS only when the lookup succeeds and finds nothing, or fails with "not found". Any other error
# (expired credentials, throttling, a typo) is a FAIL: "couldn't check" must never read as "gone".
check() {
  local name="$1" found err status=0
  shift
  err="$(mktemp)"
  found="$("$@" 2>"$err")" || status=$?
  found="$(tr '\t' '\n' <<<"$found" | grep -v -e '^None$' -e '^$' || true)"
  if ((status != 0)) && ! grep -qiE 'not ?found|does not exist|NoSuch' "$err"; then
    printf '  FAIL  %s: could not check (%s)\n' "$name" "$(head -c 200 "$err" | tr '\n' ' ')"
    failures=$((failures + 1))
  elif [[ -n "$found" ]]; then
    printf '  FAIL  %s: %s\n' "$name" "$(tr '\n' ' ' <<<"$found")"
    failures=$((failures + 1))
  else
    printf '  PASS  %s\n' "$name"
  fi
  rm -f "$err"
}
tagged_load_balancers() {
  local arns
  arns="$(aws elbv2 describe-load-balancers --query 'LoadBalancers[].LoadBalancerArn' --output text)"
  [[ -z "$arns" || "$arns" == "None" ]] && return 0
  # describe-tags takes at most 20 ARNs per call.
  xargs -n 20 <<<"$arns" | while read -r batch; do
    # shellcheck disable=SC2086 # one ARN per word on purpose
    aws elbv2 describe-tags --resource-arns $batch --output json |
      jq -r --arg p "$PROJECT" --arg c "$CLUSTER" '.TagDescriptions[]
        | select(any(.Tags[]; (.Key == "project" and .Value == $p) or (.Key == "elbv2.k8s.aws/cluster" and .Value == $c)))
        | .ResourceArn'
  done
}
eks_cluster() { aws eks describe-cluster --name "$CLUSTER" --query cluster.status --output text; }

check "EKS cluster"                eks_cluster
check "EC2 instances (nodes)"      aws ec2 describe-instances --filters "Name=tag:eks:cluster-name,Values=$CLUSTER" "Name=instance-state-name,Values=pending,running,stopping,stopped" --query 'Reservations[].Instances[].InstanceId' --output text
check "EC2 instances (tagged)"     aws ec2 describe-instances --filters "Name=tag:project,Values=$PROJECT" "Name=instance-state-name,Values=pending,running,stopping,stopped" --query 'Reservations[].Instances[].InstanceId' --output text
check "NAT gateways"               aws ec2 describe-nat-gateways --filter "Name=tag:project,Values=$PROJECT" "Name=state,Values=pending,available,deleting" --query 'NatGateways[].NatGatewayId' --output text
check "Elastic IPs"                aws ec2 describe-addresses --filters "Name=tag:project,Values=$PROJECT" --query 'Addresses[].AllocationId' --output text
check "Load balancers (tagged)"    tagged_load_balancers
check "EBS volumes (tagged)"       aws ec2 describe-volumes --filters "Name=tag:project,Values=$PROJECT" --query 'Volumes[].VolumeId' --output text
check "EBS volumes (cluster)"      aws ec2 describe-volumes --filters "Name=tag-key,Values=kubernetes.io/cluster/$CLUSTER" --query 'Volumes[].VolumeId' --output text
check "RDS instance"               aws rds describe-db-instances --db-instance-identifier "$CLUSTER" --query 'DBInstances[].DBInstanceStatus' --output text
check "RDS manual snapshots"       aws rds describe-db-snapshots --db-instance-identifier "$CLUSTER" --snapshot-type manual --query 'DBSnapshots[].DBSnapshotIdentifier' --output text
check "RDS retained backups"       aws rds describe-db-instance-automated-backups --db-instance-identifier "$CLUSTER" --query 'DBInstanceAutomatedBackups[].DBInstanceAutomatedBackupsArn' --output text
check "VPC"                        aws ec2 describe-vpcs --filters "Name=tag:project,Values=$PROJECT" --query 'Vpcs[].VpcId' --output text
check "ECR repositories"           aws ecr describe-repositories --query "repositories[?starts_with(repositoryName, '$PROJECT/')].repositoryName" --output text
check "S3 audit bucket"            aws s3api list-buckets --query "Buckets[?Name=='$PROJECT-audit-$ACCOUNT_ID'].Name" --output text
check "Lambda function"            aws lambda get-function --function-name "$PROJECT-ledger-audit" --query Configuration.FunctionName --output text
check "Scheduler schedule"         aws scheduler get-schedule --name "$PROJECT-ledger-audit-nightly" --query Name --output text
check "CloudWatch alarms"          aws cloudwatch describe-alarms --alarm-name-prefix "$PROJECT-" --query 'MetricAlarms[].AlarmName' --output text
check "Log groups (Lambda)"        aws logs describe-log-groups --log-group-name-prefix "/aws/lambda/$PROJECT" --query 'logGroups[].logGroupName' --output text
check "Log groups (EKS)"           aws logs describe-log-groups --log-group-name-prefix "/aws/eks/$CLUSTER" --query 'logGroups[].logGroupName' --output text
check "SSM parameters"             aws ssm get-parameters-by-path --path "/$PROJECT" --recursive --query 'Parameters[].Name' --output text

# The tagging API lags deletions by a few minutes, so it is reported but not counted as a failure.
echo
echo "Anything still tagged project=$PROJECT (may lag by a few minutes after deletion):"
aws resourcegroupstaggingapi get-resources --tag-filters "Key=project,Values=$PROJECT" \
  --query 'ResourceTagMappingList[].ResourceARN' --output text | tr '\t' '\n' | sed 's/^/  /'

if ((failures > 0)); then
  printf '\n%s check(s) failed.\n' "$failures" >&2
  exit 1
fi
printf '\nAll clear: nothing chargeable from Ledgerline is left in %s.\n' "$REGION"
echo "Terraform state is kept locally in $TF_DIR (it is empty now). Check Cost Explorer tomorrow to be sure."
