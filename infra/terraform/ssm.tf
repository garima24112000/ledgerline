# Secrets live in SSM Parameter Store (SecureString, AWS-managed aws/ssm key: no extra cost).
#   /ledgerline/db/password              RDS master password    -> K8s Secret (aws-up.sh), audit Lambda
#   /ledgerline/app/admin-password       ADMIN_PASSWORD         -> K8s Secret (aws-up.sh)
#   /ledgerline/app/internal-service-token INTERNAL_SERVICE_TOKEN -> K8s Secret (aws-up.sh)
#   /ledgerline/deploy/database-url      JDBC URL, not secret   -> deploy.yml (the only one it can read)
#
# The values are ephemeral and write-only (value_wo): they are never in terraform.tfstate. Both the
# DB password parameter and the RDS instance get the same ephemeral value in the apply that creates
# them; later applies write nothing until var.secrets_version changes.

resource "aws_ssm_parameter" "db_password" {
  name             = "/ledgerline/db/password"
  description      = "RDS master password for user ledgerline"
  type             = "SecureString"
  value_wo         = ephemeral.random_password.db.result
  value_wo_version = var.secrets_version
}

ephemeral "random_password" "admin" {
  length  = 32
  special = false
}

resource "aws_ssm_parameter" "admin_password" {
  name             = "/ledgerline/app/admin-password"
  description      = "Basic-auth password for /admin endpoints (user admin)"
  type             = "SecureString"
  value_wo         = ephemeral.random_password.admin.result
  value_wo_version = var.secrets_version
}

ephemeral "random_password" "internal_service_token" {
  length  = 48
  special = false
}

resource "aws_ssm_parameter" "internal_service_token" {
  name             = "/ledgerline/app/internal-service-token"
  description      = "Token webhook-dispatcher uses to call gateway-api's internal API"
  type             = "SecureString"
  value_wo         = ephemeral.random_password.internal_service_token.result
  value_wo_version = var.secrets_version
}

resource "aws_ssm_parameter" "database_url" {
  name        = "/ledgerline/deploy/database-url"
  description = "JDBC URL of the RDS instance, read by deploy.yml (not a secret)"
  type        = "String"
  # sslmode=require: RDS for PostgreSQL 16 forces TLS. It encrypts but doesn't verify the server
  # certificate (verify-full would need the RDS CA bundle in the images).
  value = "jdbc:postgresql://${aws_db_instance.ledgerline.address}:${aws_db_instance.ledgerline.port}/${aws_db_instance.ledgerline.db_name}?sslmode=require"
}
