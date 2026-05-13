output "repository_url" {
  description = "Registry-qualified URL of the ECR repository."
  value       = aws_ecr_repository.this.repository_url
}

output "repository_arn" {
  description = "ARN of the ECR repository (used by the CodeBuild build-and-scan role)."
  value       = aws_ecr_repository.this.arn
}

output "repository_name" {
  description = "Name of the ECR repository."
  value       = aws_ecr_repository.this.name
}

output "kms_key_arn" {
  description = "ARN of the KMS CMK encrypting this repository."
  value       = aws_kms_key.ecr.arn
}
