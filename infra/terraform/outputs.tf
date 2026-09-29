output "region" {
  value = var.region
}

output "cluster_name" {
  value = module.eks.cluster_name
}

output "vpc_id" {
  value = module.vpc.vpc_id
}

output "rds_endpoint" {
  description = "host:port of the RDS instance (private)"
  value       = aws_db_instance.ledgerline.endpoint
}

output "database_url" {
  description = "JDBC URL (also in SSM /ledgerline/deploy/database-url). Read with: terraform output -raw database_url"
  # The AWS provider always marks an SSM parameter's value as sensitive, even for a String. The URL
  # holds no credentials, but it stays hidden in plan/apply output ("<sensitive>").
  value     = aws_ssm_parameter.database_url.value
  sensitive = true
}

output "ecr_registry" {
  description = "GitHub variable ECR_REGISTRY"
  value       = "${local.account_id}.dkr.ecr.${var.region}.amazonaws.com"
}

output "ecr_repository_urls" {
  value = { for app, repo in aws_ecr_repository.app : app => repo.repository_url }
}

output "audit_bucket" {
  value = aws_s3_bucket.audit.bucket
}

output "audit_lambda_name" {
  description = "GitHub variable LAMBDA_FUNCTION_NAME"
  value       = aws_lambda_function.audit.function_name
}

output "github_deploy_role_arn" {
  description = "GitHub variable AWS_ROLE_ARN"
  value       = aws_iam_role.github_deploy.arn
}

output "lb_controller_role_arn" {
  description = "IRSA role for the AWS Load Balancer Controller (used by scripts/aws-up.sh)"
  value       = module.lb_controller_irsa.arn
}

output "ingress_allowed_cidrs" {
  description = "GitHub variable INGRESS_ALLOWED_CIDRS (comma-separated)"
  value       = join(",", var.ingress_allowed_cidrs)
}

output "alerts_topic_arn" {
  value = aws_sns_topic.alerts.arn
}
