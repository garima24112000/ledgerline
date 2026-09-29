# Nightly ledger audit (ledger-audit-lambda/). Plain Java 21, VPC-attached so it can reach RDS.
#
# Terraform owns the function's configuration; CI owns its code. The first create uploads the jar
# that scripts/aws-up.sh builds locally (Java bytecode, so the laptop's CPU doesn't matter).
# deploy.yml then runs `aws lambda update-function-code` on every push to main, and ignore_changes
# stops later applies from reverting that code to whatever jar happens to be on this laptop.

locals {
  audit_function_name = "${local.name}-ledger-audit"
  audit_jar           = "${path.module}/../../ledger-audit-lambda/target/ledger-audit-lambda.jar"
}

resource "aws_security_group" "audit_lambda" {
  name        = "${local.name}-audit-lambda"
  description = "Ledger audit Lambda: Postgres to RDS, HTTPS to SSM/S3/CloudWatch"
  vpc_id      = module.vpc.vpc_id

  tags = { Name = "${local.name}-audit-lambda" }
}

resource "aws_vpc_security_group_egress_rule" "audit_lambda_to_rds" {
  security_group_id            = aws_security_group.audit_lambda.id
  referenced_security_group_id = aws_security_group.rds.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "RDS"
}

resource "aws_vpc_security_group_egress_rule" "audit_lambda_https" {
  security_group_id = aws_security_group.audit_lambda.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "AWS APIs: SSM via the NAT gateway, S3 via the gateway endpoint"
}

# Created here rather than by Lambda on first run, so it has a retention period and is deleted by
# terraform destroy (a Lambda-created log group would outlive the stack).
resource "aws_cloudwatch_log_group" "audit_lambda" {
  name              = "/aws/lambda/${local.audit_function_name}"
  retention_in_days = 14
}

data "aws_iam_policy_document" "audit_lambda_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "audit_lambda" {
  name               = "${local.name}-ledger-audit-lambda"
  assume_role_policy = data.aws_iam_policy_document.audit_lambda_trust.json
}

# Least privilege. No cloudwatch:PutMetricData: the metric is published through the log line
# (Embedded Metric Format), which only needs the log permissions below.
data "aws_iam_policy_document" "audit_lambda" {
  statement {
    sid = "VpcNetworkInterfaces"
    # Lambda manages the ENIs in the VPC. These actions don't support resource-level scoping.
    actions = [
      "ec2:CreateNetworkInterface",
      "ec2:DescribeNetworkInterfaces",
      "ec2:DeleteNetworkInterface",
      "ec2:AssignPrivateIpAddresses",
      "ec2:UnassignPrivateIpAddresses",
    ]
    resources = ["*"]
  }

  statement {
    sid       = "OwnLogGroupOnly"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.audit_lambda.arn}:*"]
  }

  statement {
    sid       = "DbPasswordOnly"
    actions   = ["ssm:GetParameter"]
    resources = [aws_ssm_parameter.db_password.arn]
    # Decrypting with the AWS-managed aws/ssm key needs no kms:Decrypt here: that key's policy
    # already allows it for callers in this account, via SSM.
  }

  statement {
    sid       = "WriteReportsOnly"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.audit.arn}/audits/*"]
  }
}

resource "aws_iam_role_policy" "audit_lambda" {
  name   = "ledger-audit"
  role   = aws_iam_role.audit_lambda.id
  policy = data.aws_iam_policy_document.audit_lambda.json
}

resource "aws_lambda_function" "audit" {
  function_name = local.audit_function_name
  description   = "Nightly ledger audit: balanced entries, cached balances, global net, stale UNKNOWN payments"
  role          = aws_iam_role.audit_lambda.arn

  runtime       = "java21"
  architectures = ["arm64"] # Graviton: cheaper per GB-second; the jar is architecture-neutral
  handler       = "com.ledgerline.audit.LedgerAuditHandler::handleRequest"
  filename      = local.audit_jar
  memory_size   = 512
  timeout       = 60

  vpc_config {
    subnet_ids         = module.vpc.private_subnets
    security_group_ids = [aws_security_group.audit_lambda.id]
  }

  environment {
    variables = {
      DB_HOST                 = aws_db_instance.ledgerline.address
      DB_PORT                 = tostring(aws_db_instance.ledgerline.port)
      DB_NAME                 = aws_db_instance.ledgerline.db_name
      DB_USER                 = aws_db_instance.ledgerline.username
      DB_PASSWORD_PARAM       = aws_ssm_parameter.db_password.name
      AUDIT_BUCKET            = aws_s3_bucket.audit.bucket
      AUDIT_TIMEZONE          = var.audit_timezone
      UNKNOWN_MAX_AGE_MINUTES = "60"
    }
  }

  lifecycle {
    ignore_changes = [filename, source_code_hash]
  }

  depends_on = [
    aws_cloudwatch_log_group.audit_lambda,
    aws_iam_role_policy.audit_lambda,
  ]
}

# --- Nightly trigger: EventBridge Scheduler --------------------------------------------------------

data "aws_iam_policy_document" "audit_scheduler_trust" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }
    # Only schedules in this account may use the role (confused-deputy protection).
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }
  }
}

resource "aws_iam_role" "audit_scheduler" {
  name               = "${local.name}-ledger-audit-scheduler"
  assume_role_policy = data.aws_iam_policy_document.audit_scheduler_trust.json
}

resource "aws_iam_role_policy" "audit_scheduler" {
  name = "invoke-ledger-audit"
  role = aws_iam_role.audit_scheduler.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "lambda:InvokeFunction"
      Resource = aws_lambda_function.audit.arn
    }]
  })
}

resource "aws_scheduler_schedule" "audit" {
  name        = "${local.name}-ledger-audit-nightly"
  description = "Runs the ledger audit Lambda every night"

  schedule_expression          = var.audit_schedule
  schedule_expression_timezone = var.audit_timezone

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = aws_lambda_function.audit.arn
    role_arn = aws_iam_role.audit_scheduler.arn
    input    = jsonencode({ source = "eventbridge-scheduler" })
  }
}
