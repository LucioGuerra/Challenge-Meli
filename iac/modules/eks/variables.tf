variable "environment" {
  description = "Environment name used in resource name prefixes."
  type        = string
}

variable "tags" {
  description = "Base tag set merged into every resource."
  type        = map(string)
}

variable "cluster_name" {
  description = "Name of the EKS cluster."
  type        = string
}

variable "kubernetes_version" {
  description = "Kubernetes version (e.g. 1.31)."
  type        = string
}

variable "vpc_id" {
  description = "VPC ID where the cluster is deployed."
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnet IDs used by the control plane and node group."
  type        = list(string)
}

variable "cluster_role_arn" {
  description = "ARN of the IAM role assumed by the EKS control plane."
  type        = string
}

variable "node_role_arn" {
  description = "ARN of the IAM role assumed by the managed node group."
  type        = string
}

