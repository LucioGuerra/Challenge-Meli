variable "environment" {
  description = "Environment used in KMS alias."
  type        = string
}

variable "tags" {
  description = "Base tag set merged into every resource."
  type        = map(string)
}

variable "repository_name" {
  description = "Name of the ECR repository."
  type        = string
  default     = "meli-devops-api"
}

variable "eks_node_role_arn" {
  description = "ARN of the EKS node role authorized to pull images from this repo."
  type        = string
}
