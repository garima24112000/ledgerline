provider "aws" {
  region = var.region

  # Every resource this provider creates gets project=ledgerline. Things created later by Kubernetes
  # controllers (ALB, target groups, EBS volumes) are tagged by those controllers instead: see
  # infra/k8s/eks/*.yaml (LBC defaultTags, StorageClass tagSpecification).
  default_tags {
    tags = local.tags
  }
}
