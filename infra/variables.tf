variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "ap-northeast-2"
}

variable "project_name" {
  description = "프로젝트 이름 (기존 리소스 이름 접두어로 사용)"
  type        = string
  default     = "team3"
}

variable "vpc_cidr" {
  description = "CIDR block for VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "public_subnet_cidr" {
  description = "CIDR block for public subnet"
  type        = string
  default     = "10.0.0.0/20" # 기존 서브넷 (10.0.0.0/20)에 맞춤
}

variable "instance_type" {
  description = "EC2 instance type"
  type        = string
  default     = "t3.large" # 2 vCPU, 8GB RAM (K8S 최소 사양)
}

variable "vpc_name" {
  description = "team3-vpc"
  type        = string
  default     = "team3-vpc"
}

variable "subnet_name" {
  description = "team3-subnet-public1-ap-northeast-2a"
  type        = string
  default     = "team3-subnet-public1-ap-northeast-2a" # AWS 콘솔에서 보시는 이름
}

variable "instance_name" {
  description = "team3-server"
  type        = string
  default     = "team3-server"
}
