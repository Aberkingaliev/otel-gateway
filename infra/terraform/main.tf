data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_ami" "al2023_x86" {
  most_recent = true
  owners      = ["137112412989"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }

  filter {
    name   = "architecture"
    values = ["x86_64"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }

  filter {
    name   = "root-device-type"
    values = ["ebs"]
  }
}

data "aws_ami" "al2023_arm" {
  most_recent = true
  owners      = ["137112412989"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-arm64"]
  }

  filter {
    name   = "architecture"
    values = ["arm64"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }

  filter {
    name   = "root-device-type"
    values = ["ebs"]
  }
}

locals {
  selected_az = var.availability_zone != "" ? var.availability_zone : data.aws_availability_zones.available.names[0]

  name_prefix = "${var.project_name}-${var.environment}"

  common_tags = merge(
    {
      Project           = var.project_name
      Environment       = var.environment
      ManagedBy         = "terraform"
      BenchmarkProtocol = "AWS_BENCHMARK_V2"
      BenchmarkRegion   = var.region
    },
    var.extra_tags
  )

  selected_ami_id = var.instance_architecture == "arm64" ? data.aws_ami.al2023_arm.id : data.aws_ami.al2023_x86.id

  node_specs = {
    telemetrygen = {
      instance_type = var.telemetrygen_instance_type
    }
    gateway = {
      instance_type = var.gateway_instance_type
    }
    upstream = {
      instance_type = var.upstream_instance_type
    }
  }

  service_ports = {
    otlp_grpc_gateway = 4317
    otlp_http_gateway = 4318
    gateway_metrics   = 9464
    upstream_otlp     = 14328
  }

  bootstrap_user_data = templatefile("${path.module}/templates/user_data.sh.tmpl", {
    region             = var.region
    log_group_name     = aws_cloudwatch_log_group.benchmark.name
    node_set_name      = local.name_prefix
    user_data_extra    = var.user_data_extra
  })
}

resource "aws_vpc" "benchmark" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-vpc"
  })
}

resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.benchmark.id
  cidr_block              = var.public_subnet_cidr
  availability_zone       = local.selected_az
  map_public_ip_on_launch = var.associate_public_ip

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-public-subnet"
  })
}

resource "aws_internet_gateway" "benchmark" {
  vpc_id = aws_vpc.benchmark.id

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-igw"
  })
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.benchmark.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.benchmark.id
  }

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-public-rt"
  })
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

resource "aws_security_group" "benchmark" {
  name        = "${local.name_prefix}-sg"
  description = "Benchmark security group for telemetrygen/gateway/upstream"
  vpc_id      = aws_vpc.benchmark.id

  tags = merge(local.common_tags, {
    Name = "${local.name_prefix}-sg"
  })
}

resource "aws_vpc_security_group_ingress_rule" "intra_all" {
  security_group_id            = aws_security_group.benchmark.id
  referenced_security_group_id = aws_security_group.benchmark.id
  ip_protocol                  = "-1"
  description                  = "Allow all traffic inside benchmark SG"
}

resource "aws_vpc_security_group_ingress_rule" "ssh_operator" {
  for_each = toset(var.operator_ingress_cidrs)

  security_group_id = aws_security_group.benchmark.id
  description       = "SSH operator access"
  cidr_ipv4         = each.value
  from_port         = 22
  to_port           = 22
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "metrics_operator" {
  for_each = toset(var.operator_ingress_cidrs)

  security_group_id = aws_security_group.benchmark.id
  description       = "Gateway metrics scrape access"
  cidr_ipv4         = each.value
  from_port         = local.service_ports.gateway_metrics
  to_port           = local.service_ports.gateway_metrics
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "service_from_vpc" {
  for_each = local.service_ports

  security_group_id = aws_security_group.benchmark.id
  description       = "${each.key} from VPC"
  cidr_ipv4         = var.vpc_cidr
  from_port         = each.value
  to_port           = each.value
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "all_egress" {
  security_group_id = aws_security_group.benchmark.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  description       = "Allow all egress"
}

resource "aws_cloudwatch_log_group" "benchmark" {
  name              = "/finops-gateway/${var.environment}/${var.project_name}"
  retention_in_days = var.cloudwatch_log_retention_days

  tags = local.common_tags
}

resource "aws_iam_role" "benchmark_ec2" {
  name = "${local.name_prefix}-ec2-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Service = "ec2.amazonaws.com"
        }
        Action = "sts:AssumeRole"
      }
    ]
  })

  tags = local.common_tags
}

resource "aws_iam_role_policy_attachment" "ssm_core" {
  role       = aws_iam_role.benchmark_ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy_attachment" "cw_agent" {
  role       = aws_iam_role.benchmark_ec2.name
  policy_arn = "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
}

resource "aws_iam_instance_profile" "benchmark" {
  name = "${local.name_prefix}-instance-profile"
  role = aws_iam_role.benchmark_ec2.name

  tags = local.common_tags
}

resource "aws_instance" "nodes" {
  for_each = local.node_specs

  ami                         = local.selected_ami_id
  instance_type               = each.value.instance_type
  subnet_id                   = aws_subnet.public.id
  vpc_security_group_ids      = [aws_security_group.benchmark.id]
  iam_instance_profile        = aws_iam_instance_profile.benchmark.name
  key_name                    = var.key_name
  associate_public_ip_address = var.associate_public_ip
  monitoring                  = var.enable_detailed_monitoring
  user_data                   = local.bootstrap_user_data

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_type           = var.root_volume_type
    volume_size           = var.root_volume_size_gb
    iops                  = contains(["gp3", "io1", "io2"], var.root_volume_type) ? var.root_volume_iops : null
    throughput            = var.root_volume_type == "gp3" ? var.root_volume_throughput : null
    delete_on_termination = true
    encrypted             = true
  }

  tags = merge(local.common_tags, {
    Name         = "${local.name_prefix}-${each.key}"
    Role         = each.key
    TrackArch    = var.instance_architecture
    CampaignTrack = var.instance_architecture == "arm64" ? "c7g" : "c7i"
  })
}
