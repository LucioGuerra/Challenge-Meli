variable "environment" {
  description = "Environment used in resource name prefixes."
  type        = string
}

variable "tags" {
  description = "Base tag set."
  type        = map(string)
}

variable "region" {
  description = "AWS region (used to scope the KMS policy)."
  type        = string
}

variable "account_id" {
  description = "AWS account ID (used to scope the KMS policy)."
  type        = string
}

variable "alb_arn" {
  description = "ARN of the ALB; the module derives the metric dimension from the ARN suffix."
  type        = string
}

variable "alerts_email" {
  description = "Email subscribed to the infra alerts SNS topic."
  type        = string
}
