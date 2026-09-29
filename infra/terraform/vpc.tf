# 2 AZs, public + private subnets, exactly ONE NAT gateway (in the first AZ).
# One NAT is a deliberate cost trade-off (~$0.045/h each in us-east-1): if that AZ fails, the other AZ's private
# subnet loses outbound internet too. Production would use one NAT per AZ.
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "6.7.3"

  name = local.name
  cidr = "10.0.0.0/16"
  azs  = local.azs

  # Private /19s: the VPC CNI gives every pod a VPC IP, so pods need room. Public /24s hold only the
  # NAT gateway and the ALB.
  private_subnets = ["10.0.0.0/19", "10.0.32.0/19"]
  public_subnets  = ["10.0.64.0/24", "10.0.65.0/24"]

  enable_nat_gateway     = true
  single_nat_gateway     = true
  one_nat_gateway_per_az = false

  enable_dns_hostnames = true
  enable_dns_support   = true

  # How the AWS Load Balancer Controller finds subnets: internet-facing ALBs go in "elb" subnets.
  public_subnet_tags = {
    "kubernetes.io/role/elb" = "1"
  }
  private_subnet_tags = {
    "kubernetes.io/role/internal-elb" = "1"
  }

  tags = local.tags
}

# Free. ECR image layers are served from S3, and the audit Lambda writes to S3: this keeps that
# traffic off the NAT gateway (which charges per GB).
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = module.vpc.vpc_id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = module.vpc.private_route_table_ids

  tags = { Name = "${local.name}-s3" }
}
