locals {
  name = "ledgerline"
  tags = {
    project = "ledgerline"
  }

  # Two AZs: the minimum for EKS and for an RDS subnet group (var.availability_zones).
  azs = var.availability_zones

  account_id = data.aws_caller_identity.current.account_id
  apps       = ["gateway-api", "webhook-dispatcher", "mock-bank", "demo-merchant"]
}

data "aws_caller_identity" "current" {}
