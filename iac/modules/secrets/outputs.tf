output "kms_key_arn" {
  description = "ARN of the KMS CMK that encrypts every Secrets Manager value."
  value       = aws_kms_key.secrets.arn
}

output "secret_arns" {
  description = "Map of secret name → ARN."
  value       = { for k, v in aws_secretsmanager_secret.this : k => v.arn }
}

output "cognito_client_secret_arn" {
  description = "ARN of the Cognito M2M client secret."
  value       = aws_secretsmanager_secret.this["/meli/api/cognito-client-secret"].arn
}

output "github_manifest_token_arn" {
  description = "ARN of the GitHub manifest repo token used by CodeBuild manifests stage."
  value       = aws_secretsmanager_secret.this["/meli/pipeline/github-manifest-token"].arn
}

output "sonarqube_token_arn" {
  description = "ARN of the SonarQube token used by CodeBuild code-quality stage."
  value       = aws_secretsmanager_secret.this["/meli/pipeline/sonarqube-token"].arn
}
