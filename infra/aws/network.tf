data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name = "${var.project}-${var.environment}"
  azs  = slice(data.aws_availability_zones.available.names, 0, var.az_count)

  # Where the containers run, see var.use_nat_gateway.
  ecs_subnet_ids = var.use_nat_gateway ? aws_subnet.private[*].id : aws_subnet.public[*].id
}

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = local.name }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = { Name = local.name }
}

# Public subnets: load balancer (and the containers when there is no NAT gateway).
resource "aws_subnet" "public" {
  count = var.az_count

  vpc_id                  = aws_vpc.main.id
  availability_zone       = local.azs[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, count.index)
  map_public_ip_on_launch = false # public IPs are requested explicitly where needed

  tags = { Name = "${local.name}-public-${local.azs[count.index]}", Tier = "public" }
}

# Private subnets: the database (always), and the containers when a NAT gateway is used.
resource "aws_subnet" "private" {
  count = var.az_count

  vpc_id            = aws_vpc.main.id
  availability_zone = local.azs[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index + 10)

  tags = { Name = "${local.name}-private-${local.azs[count.index]}", Tier = "private" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${local.name}-public" }
}

resource "aws_route_table_association" "public" {
  count = var.az_count

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_eip" "nat" {
  count  = var.use_nat_gateway ? 1 : 0
  domain = "vpc"

  tags = { Name = "${local.name}-nat" }
}

resource "aws_nat_gateway" "main" {
  count = var.use_nat_gateway ? 1 : 0

  allocation_id = aws_eip.nat[0].id
  subnet_id     = aws_subnet.public[0].id

  tags = { Name = local.name }

  depends_on = [aws_internet_gateway.main]
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id

  dynamic "route" {
    for_each = var.use_nat_gateway ? [1] : []
    content {
      cidr_block     = "0.0.0.0/0"
      nat_gateway_id = aws_nat_gateway.main[0].id
    }
  }

  tags = { Name = "${local.name}-private" }
}

resource "aws_route_table_association" "private" {
  count = var.az_count

  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private.id
}

# --- Security groups: internet -> load balancer -> containers -> database, nothing else -----------------

resource "aws_security_group" "alb" {
  name_prefix = "${local.name}-alb-"
  description = "Public load balancer"
  vpc_id      = aws_vpc.main.id

  lifecycle { create_before_destroy = true }
  tags = { Name = "${local.name}-alb" }
}

resource "aws_security_group" "ecs" {
  name_prefix = "${local.name}-ecs-"
  description = "walletd containers"
  vpc_id      = aws_vpc.main.id

  lifecycle { create_before_destroy = true }
  tags = { Name = "${local.name}-ecs" }
}

resource "aws_security_group" "db" {
  name_prefix = "${local.name}-db-"
  description = "walletd database"
  vpc_id      = aws_vpc.main.id

  lifecycle { create_before_destroy = true }
  tags = { Name = "${local.name}-db" }
}

# Load balancer: web traffic in from anywhere, only to the containers out.
# Accepted: this is the public entry point; everything behind it is locked to security groups.
#trivy:ignore:AVD-AWS-0107
resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTP from the internet (redirects to HTTPS when a certificate is set)"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
}

# Accepted: public HTTPS entry point (see above).
#trivy:ignore:AVD-AWS-0107
resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTPS from the internet"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

resource "aws_vpc_security_group_egress_rule" "alb_to_ecs" {
  security_group_id            = aws_security_group.alb.id
  description                  = "To the containers"
  referenced_security_group_id = aws_security_group.ecs.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

# Containers: only the load balancer may connect; out to the database and HTTPS (ECR, Secrets Manager, logs).
resource "aws_vpc_security_group_ingress_rule" "ecs_from_alb" {
  security_group_id            = aws_security_group.ecs.id
  description                  = "From the load balancer"
  referenced_security_group_id = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
}

resource "aws_vpc_security_group_egress_rule" "ecs_to_db" {
  security_group_id            = aws_security_group.ecs.id
  description                  = "To the database"
  referenced_security_group_id = aws_security_group.db.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}

# Accepted: the containers must reach AWS APIs (ECR, Secrets Manager, CloudWatch) over HTTPS.
#trivy:ignore:AVD-AWS-0104
resource "aws_vpc_security_group_egress_rule" "ecs_https" {
  security_group_id = aws_security_group.ecs.id
  description       = "HTTPS to AWS APIs (image pull, secrets, logs)"
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
}

# Database: only the containers may connect, and it initiates nothing.
resource "aws_vpc_security_group_ingress_rule" "db_from_ecs" {
  security_group_id            = aws_security_group.db.id
  description                  = "PostgreSQL from the containers"
  referenced_security_group_id = aws_security_group.ecs.id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
}
