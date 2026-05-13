variable "environment" {
  description = "Environment name used in resource name prefixes."
  type        = string
}

variable "tags" {
  description = "Base tag set."
  type        = map(string)
}

variable "vpc_id" {
  description = "VPC ID for CodeBuild VPC config (test project)."
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnets where CodeBuild test project runs."
  type        = list(string)
}

variable "github_repo" {
  description = "Source GitHub repo (owner/repo) consumed via CodeStar Connection."
  type        = string
}

variable "github_branch" {
  description = "Branch tracked by the source stage."
  type        = string
}

variable "codestar_connection_arn" {
  description = "ARN of the manually-created CodeStar Connection that grants OAuth access to GitHub."
  type        = string
}

variable "codebuild_test_role_arn" {
  description = "Role ARN consumed by the test CodeBuild project."
  type        = string
}

variable "codebuild_quality_role_arn" {
  description = "Role ARN consumed by the code-quality CodeBuild project."
  type        = string
}

variable "codebuild_build_role_arn" {
  description = "Role ARN consumed by the build-and-scan CodeBuild project."
  type        = string
}

variable "codebuild_manifests_role_arn" {
  description = "Role ARN consumed by the manifest-update CodeBuild project."
  type        = string
}

variable "codepipeline_role_arn" {
  description = "Role ARN of the CodePipeline orchestration role."
  type        = string
}

variable "codebuild_log_group_name" {
  description = "Name of the CloudWatch log group receiving CodeBuild stdout."
  type        = string
}

variable "approval_test_email" {
  description = "Email subscribed to the test approval SNS topic."
  type        = string
}

variable "approval_prod_email" {
  description = "Email subscribed to the prod approval SNS topic."
  type        = string
}
