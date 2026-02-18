# output "instance_id" {
#   description = "EC2 Instance ID (for SSM)"
#   value       = aws_instance.k8s_node.id
# }

# output "instance_public_ip" {
#   description = "Public IP of the K8s node"
#   value       = aws_instance.k8s_node.public_ip
# }


output "vpc_id" {
  description = "VPC ID"
  value       = aws_vpc.main.id
}

output "subnet_id" {
  description = "Subnet ID"
  value       = aws_subnet.public.id
}
