output "region" {
  description = "Active AWS region."
  value       = var.region
}

output "availability_zone" {
  description = "Selected AZ for benchmark nodes."
  value       = local.selected_az
}

output "ami_id" {
  description = "AMI ID used for all benchmark instances."
  value       = local.selected_ami_id
}

output "vpc_id" {
  description = "Benchmark VPC ID."
  value       = aws_vpc.benchmark.id
}

output "subnet_id" {
  description = "Benchmark subnet ID."
  value       = aws_subnet.public.id
}

output "security_group_id" {
  description = "Benchmark security group ID."
  value       = aws_security_group.benchmark.id
}

output "instance_ids" {
  description = "EC2 instance IDs by role."
  value       = { for role, inst in aws_instance.nodes : role => inst.id }
}

output "private_ips" {
  description = "Private IPs by role."
  value       = { for role, inst in aws_instance.nodes : role => inst.private_ip }
}

output "public_ips" {
  description = "Public IPs by role."
  value       = { for role, inst in aws_instance.nodes : role => inst.public_ip }
}

output "ssh_commands" {
  description = "SSH helper commands by role (replace <path-to-key.pem> if key_name is configured)."
  value = {
    for role, inst in aws_instance.nodes : role => (
      inst.public_ip != "" ? "ssh -i <path-to-key.pem> ${var.ssh_user}@${inst.public_ip}" : "No public IP configured"
    )
  }
}

output "instance_types" {
  description = "Instance types by role."
  value       = { for role, inst in aws_instance.nodes : role => inst.instance_type }
}

output "benchmark_track_metadata" {
  description = "Track metadata for run-meta.json bootstrap."
  value = {
    track              = var.instance_architecture == "arm64" ? "c7g" : "c7i"
    architecture       = var.instance_architecture
    telemetrygen_type  = var.telemetrygen_instance_type
    gateway_type       = var.gateway_instance_type
    upstream_type      = var.upstream_instance_type
    cloudwatch_log_grp = aws_cloudwatch_log_group.benchmark.name
  }
}

output "run_meta_template_json" {
  description = "JSON template with core infra metadata for benchmark artifacts."
  value = jsonencode({
    protocol_version = "AWS_BENCHMARK_V2"
    region           = var.region
    availabilityZone = local.selected_az
    track            = var.instance_architecture == "arm64" ? "c7g" : "c7i"
    architecture     = var.instance_architecture
    instances = {
      telemetrygen = {
        id         = aws_instance.nodes["telemetrygen"].id
        type       = aws_instance.nodes["telemetrygen"].instance_type
        private_ip = aws_instance.nodes["telemetrygen"].private_ip
        public_ip  = aws_instance.nodes["telemetrygen"].public_ip
      }
      gateway = {
        id         = aws_instance.nodes["gateway"].id
        type       = aws_instance.nodes["gateway"].instance_type
        private_ip = aws_instance.nodes["gateway"].private_ip
        public_ip  = aws_instance.nodes["gateway"].public_ip
      }
      upstream = {
        id         = aws_instance.nodes["upstream"].id
        type       = aws_instance.nodes["upstream"].instance_type
        private_ip = aws_instance.nodes["upstream"].private_ip
        public_ip  = aws_instance.nodes["upstream"].public_ip
      }
    }
    network = {
      vpc_id            = aws_vpc.benchmark.id
      subnet_id         = aws_subnet.public.id
      security_group_id = aws_security_group.benchmark.id
    }
  })
}
