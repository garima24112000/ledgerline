variable "region" {
  description = "AWS region. Everything (scripts, deploy.yml, docs) assumes us-east-1 (N. Virginia)."
  type        = string
  default     = "us-east-1"
}

variable "availability_zones" {
  description = "Exactly two AZs of var.region for the subnets, EKS and the RDS subnet group."
  type        = list(string)
  # Pinned rather than "the first two available": us-east-1e doesn't support EKS control planes or
  # many current instance types, so it must never be picked by accident.
  default = ["us-east-1a", "us-east-1b"]

  validation {
    condition     = length(var.availability_zones) == 2
    error_message = "availability_zones must list exactly two AZs."
  }
  validation {
    condition     = alltrue([for az in var.availability_zones : startswith(az, var.region)])
    error_message = "Every availability zone must belong to var.region (e.g. us-east-1a for us-east-1)."
  }
}

variable "kubernetes_version" {
  description = "EKS version. Keep it in STANDARD support: extended support costs 6x ($0.60/h instead of $0.10/h)."
  type        = string
  default     = "1.36" # standard support until 2027-08-02
}

variable "github_repository" {
  description = "owner/name of the GitHub repository whose main branch may deploy (OIDC trust)."
  type        = string
  default     = "garima24112000/ledgerline"
}

variable "github_branch" {
  description = "The only branch whose workflows may assume the deploy role."
  type        = string
  default     = "main"
}

variable "create_github_oidc_provider" {
  description = "false if the account already has the token.actions.githubusercontent.com OIDC provider (only one may exist per account)."
  type        = bool
  default     = true
}

variable "ingress_allowed_cidrs" {
  description = "Source CIDRs allowed to reach the HTTP-only ALB, normally [\"<your public IP>/32\"]. Required: there is no TLS in Phase 9."
  type        = list(string)
  # No default on purpose: plan/apply stop with "No value for required variable".

  validation {
    condition     = length(var.ingress_allowed_cidrs) > 0
    error_message = "ingress_allowed_cidrs must list at least one CIDR, e.g. [\"203.0.113.7/32\"] (curl -s https://checkip.amazonaws.com)."
  }
  validation {
    condition     = alltrue([for cidr in var.ingress_allowed_cidrs : can(cidrhost(cidr, 0))])
    error_message = "Every entry of ingress_allowed_cidrs must be a CIDR block such as 203.0.113.7/32."
  }
  validation {
    condition     = !anytrue([for cidr in var.ingress_allowed_cidrs : contains(["0.0.0.0/0", "::/0"], cidr)])
    error_message = "ingress_allowed_cidrs must not contain 0.0.0.0/0 or ::/0: the ALB is plain HTTP and carries API keys and admin credentials."
  }
}

variable "alert_email" {
  description = "Optional email subscribed to the ledger audit alarms (AWS sends a confirmation email first). Empty = no subscription."
  type        = string
  default     = ""
}

variable "secrets_version" {
  description = "Bump to regenerate the DB password, admin password and internal service token (then rerun scripts/aws-up.sh and restart the pods)."
  type        = number
  default     = 1
}

variable "audit_schedule" {
  description = "EventBridge Scheduler expression for the nightly ledger audit, in audit_timezone."
  type        = string
  default     = "cron(0 2 * * ? *)" # 02:00 every day
}

variable "audit_timezone" {
  description = "Time zone of the audit schedule and of the audits/YYYY-MM-DD.json date."
  type        = string
  default     = "America/New_York"
}
