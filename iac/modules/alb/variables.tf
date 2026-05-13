variable "environment" {
  description = "Environment used in resource name prefixes."
  type        = string
}

variable "tags" {
  description = "Base tag set."
  type        = map(string)
}

variable "vpc_id" {
  description = "VPC ID where the ALB lives."
  type        = string
}

variable "public_subnet_ids" {
  description = "Public subnets across two AZs for the ALB ENIs."
  type        = list(string)
}

variable "node_security_group_id" {
  description = "Security group ID of the EKS worker nodes, used as ALB egress target."
  type        = string
}

variable "base_domain" {
  description = "Domain registered with ACM (DNS validation). External team adds the CNAMEs published as output."
  type        = string
}
