# Local state (the default backend): terraform.tfstate stays on this laptop and is gitignored.
# Fine for a one-person demo that is torn down after each session; a team would use S3 + locking.
terraform {
  # >= 1.11 for ephemeral resources and write-only (*_wo) arguments: generated passwords never
  # reach terraform.tfstate.
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.66"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }
  }
}
