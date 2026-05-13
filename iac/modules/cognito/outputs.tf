output "user_pool_id" {
  description = "ID of the Cognito user pool."
  value       = aws_cognito_user_pool.this.id
}

output "user_pool_arn" {
  description = "ARN of the Cognito user pool."
  value       = aws_cognito_user_pool.this.arn
}

output "user_pool_endpoint" {
  description = "JWKS issuer endpoint of the user pool."
  value       = aws_cognito_user_pool.this.endpoint
}

output "app_client_id" {
  description = "Client ID of the M2M app client."
  value       = aws_cognito_user_pool_client.m2m.id
}

output "token_url" {
  description = "OAuth2 token endpoint for client_credentials flow."
  value       = "https://${aws_cognito_user_pool_domain.this.domain}.auth.${var.region}.amazoncognito.com/oauth2/token"
}

output "domain" {
  description = "Cognito-managed domain prefix."
  value       = aws_cognito_user_pool_domain.this.domain
}
