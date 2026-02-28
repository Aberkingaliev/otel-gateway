# Benchmark track: c7g (arm64 / Graviton)
region                    = "us-east-1"
instance_architecture     = "arm64"
telemetrygen_instance_type = "c7g.2xlarge"
gateway_instance_type     = "c7g.2xlarge"
upstream_instance_type    = "c7g.xlarge"

# Set before apply
# availability_zone       = "us-east-1a"
# operator_ingress_cidrs  = ["<YOUR_IP>/32"]
# key_name                = "<YOUR_EC2_KEYPAIR_NAME>"
