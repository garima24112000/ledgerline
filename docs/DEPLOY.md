# Deploying Ledgerline to AWS (Phase 9)

Everything runs in **us-east-1 (N. Virginia)**, in AZs us-east-1a and us-east-1b:

- **EKS**: 3 × c7i-flex.large nodes (AL2023, x86_64), running the four apps plus in-cluster Redis and Kafka
- **RDS for PostgreSQL 16**: the ledger
- **ECR**: images
- **S3 + Lambda**: nightly ledger audit
- **GitHub Actions**: CI/CD, authenticated with OIDC

Who does what:

| Actor | Does | Never does |
|---|---|---|
| You, locally | `scripts/aws-up.sh` (Terraform + cluster add-ons + secrets), `scripts/aws-down.sh` | build or push application images |
| `.github/workflows/ci.yml` | `mvn verify` (Testcontainers), Helm and Terraform checks, shellcheck | touch AWS |
| `.github/workflows/deploy.yml` | build 4 linux/amd64 images → ECR, update the Lambda code, `helm upgrade` | change infrastructure (no Terraform, no IAM/EC2/RDS permissions) |

> **Cost.** The stack costs about **$0.21 per hour (around $5/day) plus the EC2 cost of the 3
> nodes**, whether or not anyone
> uses it: EKS control plane, 3 nodes, NAT gateway, ALB, RDS and public IPv4 addresses. Bring it up
> for a session and run `scripts/aws-down.sh` afterwards. See [Costs](#costs).

---

## 1. Prerequisites

| Tool | Version | Check |
|---|---|---|
| AWS CLI | v2 | `aws --version` |
| Terraform | ≥ 1.11 (ephemeral values, write-only arguments) | `terraform version` |
| kubectl | within one minor of EKS 1.36 | `kubectl version --client` |
| Helm | 3.x or 4.x | `helm version` |
| jq | any | `jq --version` |
| JDK 21 + Maven 3.9 | builds the Lambda jar for the first `terraform apply` | `java -version`, `mvn -v` |
| openssl | any (Grafana password) | `openssl version` |
| gh (optional) | for `--set-gh-vars` / `--deploy` | `gh auth status` |

Docker is **not** needed for AWS: images are built on GitHub's amd64 runners.

## 2. One-time AWS and GitHub setup

1. **Credentials.** Sign in with a principal that can create IAM, VPC, EKS, RDS, ECR, S3, Lambda, SSM,
   CloudWatch, SNS and EventBridge Scheduler resources, e.g. `AdministratorAccess` through IAM
   Identity Center:
   ```bash
   aws sso login --profile <profile>   # or: aws configure
   export AWS_PROFILE=<profile>
   aws sts get-caller-identity
   ```
   This identity becomes the EKS cluster admin (`enable_cluster_creator_admin_permissions`).
2. **GitHub OIDC provider.** Only one can exist per account:
   ```bash
   aws iam list-open-id-connect-providers | grep token.actions.githubusercontent.com
   ```
   If it prints a line, set `create_github_oidc_provider = false` in `terraform.tfvars`.
3. **Quotas** in us-east-1. You need 6 vCPUs for the nodes, plus room for a rolling node replacement, and 1 Elastic IP (the NAT gateway):
   ```bash
   aws service-quotas get-service-quota --region us-east-1 --service-code ec2 --quota-code L-1216C47A \
     --query 'Quota.Value'    # Running On-Demand Standard instances (vCPUs): need >= 8
   aws ec2 describe-addresses --region us-east-1 --query 'length(Addresses)'   # default limit is 5
   ```
4. **A budget alert** (strongly recommended). Billing → Budgets → Create budget, e.g. $20/month with
   an email at 50%. It is deliberately not in Terraform, so it outlives `aws-down.sh`.
5. **`terraform.tfvars`:**
   ```bash
   cp infra/terraform/terraform.tfvars.example infra/terraform/terraform.tfvars
   curl -s https://checkip.amazonaws.com        # your public IP
   ```
   Set `ingress_allowed_cidrs = ["<that IP>/32"]`. This is **required**: the ALB is plain HTTP, and
   Terraform, deploy.yml and the Helm chart all refuse an empty list or `0.0.0.0/0`.
   `terraform.tfvars` is gitignored.

## 3. GitHub repository variables

`deploy.yml` reads these **variables** (not secrets; none of them is sensitive). Set them with
`scripts/aws-up.sh --set-gh-vars`, or under Settings → Secrets and variables → Actions → Variables:

| Variable | Value | From |
|---|---|---|
| `AWS_REGION` | `us-east-1` | — |
| `AWS_ROLE_ARN` | `arn:aws:iam::<account>:role/ledgerline-github-deploy` | `terraform output -raw github_deploy_role_arn` |
| `ECR_REGISTRY` | `<account>.dkr.ecr.us-east-1.amazonaws.com` | `terraform output -raw ecr_registry` |
| `EKS_CLUSTER_NAME` | `ledgerline` | `terraform output -raw cluster_name` |
| `LAMBDA_FUNCTION_NAME` | `ledgerline-ledger-audit` | `terraform output -raw audit_lambda_name` |
| `INGRESS_ALLOWED_CIDRS` | `203.0.113.7/32` (comma-separated) | `terraform output -raw ingress_allowed_cidrs` |
| `DEPLOY_ENABLED` | `true` while the stack exists; `aws-down.sh` sets `false` | — |

There are no AWS secrets in GitHub. The deploy role trusts only
`repo:garima24112000@76704188/ledgerline@1391413618:ref:refs/heads/main`: GitHub's immutable subject
format, with the numeric owner and repository ids (`github_owner_id` / `github_repository_id` in
Terraform). Pull requests, other branches and GitHub
"environments" cannot assume it.

## 4. First deploy

```bash
scripts/aws-up.sh --set-gh-vars          # 20-30 min; asks before terraform apply
gh workflow run deploy.yml --ref main    # or push to main
gh run watch
```

`aws-up.sh`, in order:
1. Builds `ledger-audit-lambda/target/ledger-audit-lambda.jar` (Terraform uploads it when it first creates the function).
2. `terraform init` + `apply`: VPC, EKS, RDS, ECR, S3, SSM parameters, Lambda + schedule + alarms, GitHub OIDC role.
3. `aws eks update-kubeconfig --alias ledgerline-eks`, then waits for 3 Ready nodes.
4. Creates the **non-default** `gp3` StorageClass if missing. The existing default is never changed.
5. Helm installs the AWS Load Balancer Controller, metrics-server and kube-prometheus-stack.
6. Creates namespace `ledgerline` and the deployer RoleBinding.
7. Creates Secret `ledgerline-secrets` from the SSM SecureStrings. Values are never printed or written to disk.
8. With `--set-gh-vars`, sets the variables above.

Then `deploy.yml` runs:
1. CI
2. four `linux/amd64` images tagged with the commit SHA, pushed to ECR
3. `update-function-code` for the Lambda
4. `helm upgrade --install`

Once it's green:
```bash
ALB=$(kubectl --context ledgerline-eks -n ledgerline get ingress ledgerline-gateway-api \
        -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
curl -s "http://$ALB/actuator/health"                    # {"status":"UP",...}
open "http://$ALB/swagger-ui.html"
```
The ALB's DNS name can take 1–3 minutes to resolve after the Ingress gets its address.

### Manual deploy (the same commands deploy.yml runs)

For debugging the pipeline only. Build **on an amd64 machine**, or with `--platform linux/amd64`.
The nodes are amd64, and an arm64 image built natively on an Apple Silicon laptop fails with
`exec format error`.
```bash
REGION=us-east-1
REGISTRY=$(terraform -chdir=infra/terraform output -raw ecr_registry)
TAG=$(git rev-parse HEAD)
aws ecr get-login-password --region $REGION | docker login --username AWS --password-stdin $REGISTRY
for app in gateway-api webhook-dispatcher mock-bank demo-merchant; do
  docker buildx build --platform linux/amd64 --provenance=false -f $app/Dockerfile \
    -t $REGISTRY/ledgerline/$app:$TAG --push .
done

mvn -B -pl ledger-audit-lambda -am package -DskipTests
aws lambda update-function-code --region $REGION --function-name ledgerline-ledger-audit \
  --zip-file fileb://ledger-audit-lambda/target/ledger-audit-lambda.jar
aws lambda wait function-updated-v2 --region $REGION --function-name ledgerline-ledger-audit

DB_URL=$(aws ssm get-parameter --region $REGION --name /ledgerline/deploy/database-url \
           --query Parameter.Value --output text)
helm upgrade --install ledgerline infra/helm/ledgerline --kube-context ledgerline-eks \
  --namespace ledgerline -f infra/helm/ledgerline/values-eks.yaml \
  --set image.repository=$REGISTRY/ledgerline --set image.tag=$TAG \
  --set-string external.databaseUrl="$DB_URL" \
  --set "ingress.allowedCidrs={$(terraform -chdir=infra/terraform output -raw ingress_allowed_cidrs)}" \
  --atomic --wait --timeout 15m          # Helm 4: --rollback-on-failure instead of --atomic
```

## 5. Redeploy

- **Code change:** merge to `main`. deploy.yml builds only images whose SHA tag isn't in ECR yet
  (tags are immutable), updates the Lambda and rolls the pods.
- **Re-run the same commit:** `gh workflow run deploy.yml --ref main`.
- **Infrastructure change:** edit `infra/terraform`, then `scripts/aws-up.sh`. Terraform shows the
  diff and asks. The Lambda's code is never reverted (`ignore_changes`).
- **Your IP changed** (the ALB times out):
  1. Update `ingress_allowed_cidrs` in `terraform.tfvars`.
  2. Run `scripts/aws-up.sh --set-gh-vars`.
  3. Run `gh workflow run deploy.yml --ref main`.
- **Rotate the generated secrets:** bump `secrets_version` in `terraform.tfvars`, then run
  `scripts/aws-up.sh`. It updates RDS and SSM, rewrites the Secret and restarts the deployments.

### The audit Lambda
```bash
aws lambda invoke --region us-east-1 --function-name ledgerline-ledger-audit out.json && cat out.json
aws s3 ls s3://ledgerline-audit-<account>/audits/
aws logs tail /aws/lambda/ledgerline-ledger-audit --region us-east-1 --since 1h
```
It also runs nightly at 02:00 America/New_York (EventBridge Scheduler `ledgerline-ledger-audit-nightly`).
Alarms: `ledgerline-ledger-audit-failures` (a check failed) and `ledgerline-ledger-audit-errors` (the
audit could not run). Both go to SNS topic `ledgerline-alerts`; set `alert_email` to get emails.

## 6. Teardown

```bash
scripts/aws-down.sh        # type "ledgerline" to confirm; 15-30 min
```
1. Uninstalls the chart. The LB controller deletes the ALB, target groups and its security groups.
2. Deletes the Kafka PVC. The EBS CSI driver deletes the volume.
3. **Waits until AWS confirms** those are gone.
4. Uninstalls the add-ons.
5. Empties ECR and the audit bucket.
6. Runs `terraform destroy`, retrying once for slow Lambda network interfaces.
7. Sets `DEPLOY_ENABLED=false`.
8. Prints a PASS/FAIL table: EKS, EC2, NAT, EIPs, load balancers, EBS, RDS (+ snapshots and
   backups), VPC, ECR, S3, Lambda, schedule, alarms, log groups, SSM. **It exits non-zero if
   anything is left.** A lookup that fails for any reason other than "not found" also counts as FAIL.

It is safe to re-run, including after a partial failure or on a stack that's already gone. Check Cost
Explorer the next day as a final confirmation.

## 7. Common failures

| Symptom | Cause | Fix |
|---|---|---|
| `Not authorized to perform sts:AssumeRoleWithWebIdentity` | Workflow ran from a branch other than `main`, used an `environment:`, or `AWS_ROLE_ARN` is stale | Run from `main`; re-run `aws-up.sh --set-gh-vars` |
| deploy.yml skipped | `DEPLOY_ENABLED` isn't `true` | `gh variable set DEPLOY_ENABLED --body true` |
| `INGRESS_ALLOWED_CIDRS is not set` / `ingress.allowedCidrs is required` | Deliberate safety check | Set it to your IP/32 |
| `external.databaseUrl is required` | Helm run without the SSM value | Use the deploy.yml command (reads `/ledgerline/deploy/database-url`) |
| Pod `CrashLoopBackOff`, logs say `exec format error` | arm64 image on amd64 nodes | Build with `--platform linux/amd64` (deploy.yml always does) |
| `ImagePullBackOff` | Tag not in ECR, or wrong `image.repository` | `aws ecr describe-images --repository-name ledgerline/gateway-api` |
| gateway-api never Ready, logs: `Connection to ...rds.amazonaws.com:5432 refused` / timeout | RDS security group, or RDS still creating | `aws rds describe-db-instances --db-instance-identifier ledgerline`; RUNBOOK "RDS connectivity" |
| `password authentication failed for user "ledgerline"` | Secret out of sync with RDS | Re-run `scripts/aws-up.sh` (rewrites the Secret from SSM, restarts pods) |
| Kafka pod Pending, PVC `Pending` | No `gp3` StorageClass or EBS CSI add-on not ready | `kubectl get sc`; `kubectl -n kube-system get pods -l app=ebs-csi-controller` |
| Ingress has no ADDRESS | LB controller error (subnet tags, IAM) | `kubectl -n kube-system logs deploy/aws-load-balancer-controller` |
| ALB address exists but `curl` times out | Your IP isn't in `INGRESS_ALLOWED_CIDRS` | See "Your IP changed" |
| ALB returns 503 | No Ready gateway-api pods (readiness includes the DB) | `kubectl -n ledgerline get pods`; RUNBOOK |
| gateway-api pods Pending during HPA scale-up | 3 × c7i-flex.large are full (≈9.2 GiB allocatable in total) | Expected above 4 replicas; maxReplicas is 4 on EKS |
| `helm upgrade` failed and rolled back (`--atomic`) | A pod never became Ready within 15 min | `kubectl -n ledgerline describe pod` / logs; fix and redeploy |
| `forbidden: User "…github-deploy…" cannot …` | Deployer RBAC missing (namespace recreated by hand) | Re-run `scripts/aws-up.sh` |
| Lambda `Task timed out` / `connection attempt timed out` | Lambda can't reach RDS (SG) or SSM (NAT) | `aws logs tail /aws/lambda/ledgerline-ledger-audit` |
| `terraform apply`: `VcpuLimitExceeded` | EC2 vCPU quota | Request an increase (step 2.3) |
| `terraform apply`: EKS node group `CREATE_FAILED`, instance type not eligible for the Free Tier | Account on the AWS Free plan only launches Free Tier-eligible types (t3.medium was rejected; c7i-flex.large was accepted) | Pick an eligible x86_64 type in `eks.tf`; the next plan replaces the tainted node group only |
| `terraform apply`: `EntityAlreadyExists … oidc-provider` | Provider already in the account | `create_github_oidc_provider = false` |
| `terraform destroy`: `DependencyViolation` on a subnet or security group | Lambda ENIs still releasing, or an ALB left behind | `aws-down.sh` retries once; if it still fails, re-run it after 20 min |

## Costs

Approximate on-demand prices in us-east-1. Check current prices before relying on them.

| Resource | While it exists |
|---|---|
| EKS control plane (standard support) | ~$0.10/h |
| 3 × c7i-flex.large (EC2) | Not quoted: the on-demand c7i-flex.large price is not quoted here: this AWS account reported the type as Free Tier eligible during deployment, so free-tier usage or account credits may cover part or all of it |
| 3 × 20 GB gp3 root volumes | <$0.01/h |
| NAT gateway (+ per GB processed) | ~$0.045/h |
| ALB (+ LCU) | ~$0.023/h |
| Public IPv4 (NAT + ALB, ~3) | ~$0.015/h |
| RDS db.t4g.micro + 20 GB gp3 | ~$0.02/h (free tier/credits may apply) |
| Kafka EBS 10 GB, ECR, S3, CloudWatch alarms and metric, SSM, Lambda, Scheduler | cents per month |
| **Total** | **~$0.21/h ≈ $5/day, plus the nodes' EC2 cost** |

Choices made to keep it down:
- one NAT gateway, not one per AZ
- no EKS control-plane logs
- no customer-managed KMS key
- SSM Parameter Store instead of Secrets Manager
- a free S3 gateway endpoint
- Grafana through port-forward (no second load balancer)
- EKS pinned to a standard-support version, with `support_type = STANDARD`
