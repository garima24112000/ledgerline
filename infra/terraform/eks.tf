module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "21.26.0"

  name               = local.name
  kubernetes_version = var.kubernetes_version

  # At the end of standard support EKS upgrades the cluster instead of silently billing
  # extended support ($0.60/h instead of $0.10/h).
  upgrade_policy = {
    support_type = "STANDARD"
  }

  # Public endpoint: this laptop and GitHub-hosted runners (no fixed IPs) run kubectl/helm.
  # Access is still IAM-authenticated (access entries below). Nodes use the private endpoint.
  endpoint_public_access  = true
  endpoint_private_access = true

  enable_irsa = true # OIDC provider for IAM Roles for Service Accounts (EBS CSI, LB controller)

  # Cost: control-plane logs are CloudWatch ingestion ($/GB); no customer-managed KMS key
  # ($1/month, and it lingers "pending deletion" after destroy). EKS already envelope-encrypts
  # Kubernetes Secrets with an AWS-owned key by default.
  enabled_log_types           = []
  create_cloudwatch_log_group = false
  create_kms_key              = false
  encryption_config           = null

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.private_subnets

  # The module creates the cluster without the default self-managed add-ons, so all of them are
  # listed here. vpc-cni and kube-proxy must exist before nodes join.
  addons = {
    vpc-cni = {
      before_compute = true
    }
    kube-proxy = {
      before_compute = true
    }
    coredns = {}
    # Dynamic EBS volumes for the gp3 StorageClass (Kafka's PVC).
    aws-ebs-csi-driver = {
      service_account_role_arn = module.ebs_csi_irsa.arn
    }
  }

  eks_managed_node_groups = {
    default = {
      ami_type       = "AL2023_x86_64_STANDARD" # amd64: images are built for linux/amd64
      instance_types = ["c7i-flex.large"]       # 2 vCPU, 4 GiB (Free Tier eligible), max 29 pods per node with the VPC CNI

      min_size     = 3
      max_size     = 3
      desired_size = 3

      subnet_ids = module.vpc.private_subnets
      tags       = local.tags
    }
  }

  # Whoever runs `terraform apply` becomes cluster admin (for aws-up.sh / aws-down.sh).
  enable_cluster_creator_admin_permissions = true

  # GitHub Actions: no EKS access policy, only a Kubernetes group. EKS access policies (e.g.
  # AmazonEKSAdminPolicy) don't cover custom resources, and the chart creates ServiceMonitors.
  # infra/k8s/eks/deployer-rbac.yaml (applied by aws-up.sh) binds this group to the "admin"
  # ClusterRole in the ledgerline namespace only; kube-prometheus-stack aggregates the
  # monitoring.coreos.com resources into "admin".
  access_entries = {
    github_deploy = {
      principal_arn     = aws_iam_role.github_deploy.arn
      kubernetes_groups = ["ledgerline-deployers"]
    }
  }

  tags = local.tags
}

module "ebs_csi_irsa" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts"
  version = "6.8.2"

  name                  = "${local.name}-ebs-csi"
  attach_ebs_csi_policy = true

  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["kube-system:ebs-csi-controller-sa"]
    }
  }

  tags = local.tags
}

# Installed by scripts/aws-up.sh (Helm); it turns the gateway-api Ingress into an ALB.
module "lb_controller_irsa" {
  source  = "terraform-aws-modules/iam/aws//modules/iam-role-for-service-accounts"
  version = "6.8.2"

  # The module uses name as a name_prefix, which IAM limits to 38 characters:
  # "ledgerline-aws-load-balancer-controller-" (40) was too long.
  name                                   = "${local.name}-lbc"
  attach_load_balancer_controller_policy = true

  oidc_providers = {
    main = {
      provider_arn               = module.eks.oidc_provider_arn
      namespace_service_accounts = ["kube-system:aws-load-balancer-controller"]
    }
  }

  tags = local.tags
}
