# Two alarms, because "the ledger is wrong" and "the audit didn't run" are different failures:
#   LedgerAuditFailures >= 1  the audit ran and at least one check failed (EMF metric from the Lambda)
#   Lambda Errors >= 1        the audit itself crashed (RDS unreachable, S3 denied, timeout)

resource "aws_sns_topic" "alerts" {
  name = "${local.name}-alerts"
}

resource "aws_sns_topic_subscription" "alerts_email" {
  count = var.alert_email == "" ? 0 : 1

  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alert_email # AWS emails a confirmation link; nothing is delivered until it's clicked
}

resource "aws_cloudwatch_metric_alarm" "ledger_audit_failures" {
  alarm_name        = "${local.name}-ledger-audit-failures"
  alarm_description = "The nightly ledger audit found at least one failed check. Read s3://${aws_s3_bucket.audit.bucket}/audits/<date>.json"

  namespace   = "Ledgerline"
  metric_name = "LedgerAuditFailures"
  dimensions = {
    FunctionName = aws_lambda_function.audit.function_name
  }
  statistic           = "Maximum"
  period              = 3600
  evaluation_periods  = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 1
  treat_missing_data  = "notBreaching" # no audit in the last hour is normal: it runs once a night

  alarm_actions = [aws_sns_topic.alerts.arn]
  ok_actions    = [aws_sns_topic.alerts.arn]
}

resource "aws_cloudwatch_metric_alarm" "ledger_audit_errors" {
  alarm_name        = "${local.name}-ledger-audit-errors"
  alarm_description = "The ledger audit Lambda failed to run. Check /aws/lambda/${aws_lambda_function.audit.function_name}"

  namespace   = "AWS/Lambda"
  metric_name = "Errors"
  dimensions = {
    FunctionName = aws_lambda_function.audit.function_name
  }
  statistic           = "Sum"
  period              = 3600
  evaluation_periods  = 1
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 1
  treat_missing_data  = "notBreaching"

  alarm_actions = [aws_sns_topic.alerts.arn]
  ok_actions    = [aws_sns_topic.alerts.arn]
}
