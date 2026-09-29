# PostgreSQL 16 for gateway-api. Private, single-AZ, smallest Graviton class: a demo, not production
# (production: Multi-AZ, longer backups, deletion protection, a final snapshot).

resource "aws_db_subnet_group" "ledgerline" {
  name       = local.name
  subnet_ids = module.vpc.private_subnets
}

# 5432 only from the EKS nodes (pods use the node's security group under the VPC CNI) and the audit
# Lambda. No CIDR rules: nothing else in the VPC, and nothing outside it, can connect.
resource "aws_security_group" "rds" {
  name        = "${local.name}-rds"
  description = "Postgres 5432 from EKS nodes and the ledger audit Lambda only"
  vpc_id      = module.vpc.vpc_id

  tags = { Name = "${local.name}-rds" }
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_eks_nodes" {
  security_group_id            = aws_security_group.rds.id
  referenced_security_group_id = module.eks.node_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "EKS nodes (gateway-api pods)"
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_audit_lambda" {
  security_group_id            = aws_security_group.rds.id
  referenced_security_group_id = aws_security_group.audit_lambda.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "Ledger audit Lambda"
}

# Ephemeral + write-only (password_wo): the password is generated during apply, sent to RDS and SSM,
# and never written to terraform.tfstate. Letters and digits only, so it needs no escaping in
# environment variables, psql connection strings or shells.
ephemeral "random_password" "db" {
  length  = 40
  special = false
}

resource "aws_db_instance" "ledgerline" {
  identifier     = local.name
  engine         = "postgres"
  engine_version = "16" # latest 16.x minor; the default parameter group has rds.force_ssl=1
  instance_class = "db.t4g.micro"

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = "ledgerline"
  username = "ledgerline" # = postgres.username in values-eks.yaml (DB_USERNAME)
  # Written only when password_wo_version changes (bump var.secrets_version to rotate).
  password_wo         = ephemeral.random_password.db.result
  password_wo_version = var.secrets_version

  db_subnet_group_name   = aws_db_subnet_group.ledgerline.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  multi_az               = false

  backup_retention_period  = 1
  delete_automated_backups = true
  skip_final_snapshot      = true # the stack is torn down after each session
  deletion_protection      = false
  apply_immediately        = true

  auto_minor_version_upgrade   = true
  performance_insights_enabled = false
  monitoring_interval          = 0
  copy_tags_to_snapshot        = true
}
