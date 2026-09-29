# One repository per app: ledgerline/<app>, matching the chart's <image.repository>/<app>:<tag>
# with image.repository = <registry>/ledgerline.
resource "aws_ecr_repository" "app" {
  for_each = toset(local.apps)

  name = "${local.name}/${each.key}"

  # Tags are git SHAs and are never re-pushed (deploy.yml skips images that already exist), so a tag
  # always means exactly one image.
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true # basic scanning, free
  }

  # Backstop only: scripts/aws-down.sh empties the repositories explicitly before destroy.
  force_delete = true
}

resource "aws_ecr_lifecycle_policy" "app" {
  for_each = aws_ecr_repository.app

  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the 10 most recent images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}
