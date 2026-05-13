variable "environment" {
  description = "Environment name used in role name prefixes."
  type        = string
}

variable "tags" {
  description = "Base tag set merged into every role."
  type        = map(string)
}

variable "region" {
  description = "AWS region (used in ARN patterns)."
  type        = string
}

variable "account_id" {
  description = "AWS account ID (used in ARN patterns)."
  type        = string
}

variable "ecr_repository_arn" {
  description = "ARN of the ECR repository the build stage is allowed to push to."
  type        = string
}

variable "sonarqube_token_secret_arn" {
  description = "ARN of the SonarQube token secret in Secrets Manager."
  type        = string
}

variable "github_manifest_token_secret_arn" {
  description = "ARN of the GitHub manifest repo token secret in Secrets Manager."
  type        = string
}

variable "codestar_connection_arn" {
  description = "ARN of the CodeStar Connection used by the source stage."
  type        = string
}
