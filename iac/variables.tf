variable "region" {
  description = "AWS region where all resources are deployed."
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Deployment environment name (dev/test/prod)."
  type        = string
  default     = "prod"
}

variable "tags" {
  description = "Base tag set applied to every resource through provider default_tags and module-level merges."
  type        = map(string)
  default = {
    Project     = "meli-devops-api"
    Environment = "prod"
    ManagedBy   = "terraform"
    Owner       = "devops-team"
    CostCenter  = "engineering"
  }
}

variable "cluster_name" {
  description = "Name of the EKS cluster. Used as suffix in subnet discovery tags."
  type        = string
  default     = "meli-devops-api"
}

variable "kubernetes_version" {
  description = "Kubernetes version for the EKS control plane and node group AMIs."
  type        = string
  default     = "1.31"
}

variable "base_domain" {
  description = "Base DNS domain managed externally. Used for ACM certificate request. Validation CNAMEs are exported as outputs for manual creation."
  type        = string
}

variable "github_repo" {
  description = "GitHub source repository for the application code in the form owner/repo."
  type        = string
}

variable "github_branch" {
  description = "Branch tracked by CodePipeline source stage."
  type        = string
  default     = "main"
}

variable "codestar_connection_arn" {
  description = "ARN of the existing CodeStar Connection (must be created manually in the AWS console once)."
  type        = string
}

variable "manifest_repo" {
  description = "GitHub manifest repo for GitOps (owner/repo)."
  type        = string
}

variable "alerts_email" {
  description = "Email subscribed to the infrastructure alerts SNS topic."
  type        = string
}

variable "approval_test_email" {
  description = "Email subscribed to the manual approval topic before DeployTest."
  type        = string
}

variable "approval_prod_email" {
  description = "Email subscribed to the manual approval topic before DeployProd."
  type        = string
}
