locals {
  module_tags = merge(var.tags, { Module = "networking" })

  azs = ["${var.region}a", "${var.region}b"]

  public_subnets = {
    "${var.region}a" = "10.0.1.0/24"
    "${var.region}b" = "10.0.3.0/24"
  }

  private_subnets = {
    "${var.region}a" = "10.0.2.0/24"
    "${var.region}b" = "10.0.4.0/24"
  }

  cluster_discovery_tag_key = "kubernetes.io/cluster/${var.cluster_name}"
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = merge(local.module_tags, {
    Name                          = "meli-devops-${var.environment}-vpc"
    (local.cluster_discovery_tag_key) = "owned"
  })
}

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id

  tags = merge(local.module_tags, {
    Name = "meli-devops-${var.environment}-igw"
  })
}

resource "aws_subnet" "public" {
  for_each = local.public_subnets

  vpc_id                  = aws_vpc.this.id
  cidr_block              = each.value
  availability_zone       = each.key
  map_public_ip_on_launch = true

  tags = merge(local.module_tags, {
    Name                              = "meli-devops-${var.environment}-public-${each.key}"
    Tier                              = "public"
    "kubernetes.io/role/elb"          = "1"
    (local.cluster_discovery_tag_key) = "owned"
  })
}

resource "aws_subnet" "private" {
  for_each = local.private_subnets

  vpc_id            = aws_vpc.this.id
  cidr_block        = each.value
  availability_zone = each.key

  tags = merge(local.module_tags, {
    Name                              = "meli-devops-${var.environment}-private-${each.key}"
    Tier                              = "private"
    "kubernetes.io/role/internal-elb" = "1"
    (local.cluster_discovery_tag_key) = "owned"
  })
}

resource "aws_eip" "nat" {
  for_each = local.public_subnets
  domain   = "vpc"

  tags = merge(local.module_tags, {
    Name = "meli-devops-${var.environment}-nat-eip-${each.key}"
  })
}

resource "aws_nat_gateway" "this" {
  for_each      = local.public_subnets
  allocation_id = aws_eip.nat[each.key].id
  subnet_id     = aws_subnet.public[each.key].id

  tags = merge(local.module_tags, {
    Name = "meli-devops-${var.environment}-nat-${each.key}"
  })

  depends_on = [aws_internet_gateway.this]
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.this.id
  }

  tags = merge(local.module_tags, {
    Name = "meli-devops-${var.environment}-rt-public"
  })
}

resource "aws_route_table" "private" {
  for_each = local.private_subnets
  vpc_id   = aws_vpc.this.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.this[each.key].id
  }

  tags = merge(local.module_tags, {
    Name = "meli-devops-${var.environment}-rt-private-${each.key}"
  })
}

resource "aws_route_table_association" "public" {
  for_each       = aws_subnet.public
  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "private" {
  for_each       = aws_subnet.private
  subnet_id      = each.value.id
  route_table_id = aws_route_table.private[each.key].id
}

resource "aws_cloudwatch_log_group" "vpc_flow" {
  name              = "/vpc/flow-logs"
  retention_in_days = 7
  kms_key_id        = var.flow_logs_kms_key_arn

  tags = local.module_tags
}

data "aws_iam_policy_document" "flow_logs_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["vpc-flow-logs.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "flow_logs" {
  name               = "meli-devops-${var.environment}-vpc-flow-logs"
  assume_role_policy = data.aws_iam_policy_document.flow_logs_assume.json
  tags               = local.module_tags
}

data "aws_iam_policy_document" "flow_logs" {
  statement {
    actions = [
      "logs:CreateLogStream",
      "logs:PutLogEvents",
      "logs:DescribeLogStreams",
    ]
    resources = [
      aws_cloudwatch_log_group.vpc_flow.arn,
      "${aws_cloudwatch_log_group.vpc_flow.arn}:*",
    ]
  }
}

resource "aws_iam_role_policy" "flow_logs" {
  name   = "flow-logs-write"
  role   = aws_iam_role.flow_logs.id
  policy = data.aws_iam_policy_document.flow_logs.json
}

resource "aws_flow_log" "this" {
  iam_role_arn    = aws_iam_role.flow_logs.arn
  log_destination = aws_cloudwatch_log_group.vpc_flow.arn
  traffic_type    = "ALL"
  vpc_id          = aws_vpc.this.id

  tags = local.module_tags
}
