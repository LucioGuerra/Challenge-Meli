variable "region" {
  description = "AWS region the VPC is deployed into. Used to derive AZ names (region+a, region+b)."
  type        = string
}

variable "environment" {
  description = "Environment suffix used in resource Name tags."
  type        = string
}

variable "vpc_cidr" {
  description = "Primary IPv4 CIDR block for the VPC."
  type        = string
  default     = "10.0.0.0/16"
}

variable "cluster_name" {
  description = "EKS cluster name referenced by the discovery tags applied to every subnet."
  type        = string
}

variable "tags" {
  description = "Base tag set merged into every resource created by this module."
  type        = map(string)
}

variable "flow_logs_kms_key_arn" {
  description = "KMS CMK ARN used to encrypt the VPC flow logs CloudWatch group. Null leaves it AWS-managed."
  type        = string
  default     = null
}
