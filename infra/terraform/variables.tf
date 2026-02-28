variable "project_name" {
  description = "Logical project name used in tags and names."
  type        = string
  default     = "finops-gateway"
}

variable "environment" {
  description = "Environment label used in tags and names."
  type        = string
  default     = "benchmark-v2"
}

variable "region" {
  description = "AWS region for the benchmark campaign."
  type        = string
  default     = "us-east-1"
}

variable "availability_zone" {
  description = "Optional fixed AZ (for reproducibility). Leave empty to use the first available AZ in region."
  type        = string
  default     = ""
}

variable "vpc_cidr" {
  description = "VPC CIDR block."
  type        = string
  default     = "10.42.0.0/16"
}

variable "public_subnet_cidr" {
  description = "Public subnet CIDR block used by all benchmark nodes."
  type        = string
  default     = "10.42.1.0/24"
}

variable "operator_ingress_cidrs" {
  description = "CIDR blocks allowed to SSH and scrape metrics from outside VPC (e.g. your office IP)."
  type        = list(string)
  default     = []
}

variable "key_name" {
  description = "Optional EC2 key pair name for SSH access."
  type        = string
  default     = null
}

variable "ssh_user" {
  description = "SSH username for Amazon Linux images."
  type        = string
  default     = "ec2-user"
}

variable "associate_public_ip" {
  description = "Whether to associate a public IPv4 with each benchmark node."
  type        = bool
  default     = true
}

variable "instance_architecture" {
  description = "CPU architecture for all instances in the track."
  type        = string
  default     = "x86_64"

  validation {
    condition     = contains(["x86_64", "arm64"], var.instance_architecture)
    error_message = "instance_architecture must be either x86_64 or arm64."
  }
}

variable "telemetrygen_instance_type" {
  description = "Instance type for telemetrygen load generator node."
  type        = string
  default     = "c7i.2xlarge"
}

variable "gateway_instance_type" {
  description = "Instance type for gateway node."
  type        = string
  default     = "c7i.2xlarge"
}

variable "upstream_instance_type" {
  description = "Instance type for OTEL upstream collector node."
  type        = string
  default     = "c7i.xlarge"
}

variable "root_volume_size_gb" {
  description = "Root EBS size for each instance."
  type        = number
  default     = 80
}

variable "root_volume_type" {
  description = "Root EBS type."
  type        = string
  default     = "gp3"
}

variable "root_volume_iops" {
  description = "Root EBS IOPS (used by gp3)."
  type        = number
  default     = 3000
}

variable "root_volume_throughput" {
  description = "Root EBS throughput in MiB/s (used by gp3)."
  type        = number
  default     = 125
}

variable "enable_detailed_monitoring" {
  description = "Enable EC2 detailed monitoring (1-minute CloudWatch granularity)."
  type        = bool
  default     = true
}

variable "cloudwatch_log_retention_days" {
  description = "Retention for benchmark log group in CloudWatch Logs."
  type        = number
  default     = 14
}

variable "user_data_extra" {
  description = "Optional shell snippet appended to user_data for custom bootstrap actions."
  type        = string
  default     = ""
}

variable "extra_tags" {
  description = "Additional tags merged into all resources."
  type        = map(string)
  default     = {}
}
