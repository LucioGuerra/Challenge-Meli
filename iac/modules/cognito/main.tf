locals {
  module_tags = merge(var.tags, { Module = "cognito" })
}

resource "aws_cognito_user_pool" "this" {
  name = "meli-devops-${var.environment}-api"

  password_policy {
    minimum_length    = 16
    require_lowercase = true
    require_numbers   = true
    require_symbols   = true
    require_uppercase = true
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }

  admin_create_user_config {
    allow_admin_create_user_only = true
  }

  tags = local.module_tags
}

resource "aws_cognito_resource_server" "api" {
  identifier   = "api"
  name         = "meli-devops-api"
  user_pool_id = aws_cognito_user_pool.this.id

  scope {
    scope_name        = "read"
    scope_description = "Read-only access to the meli-devops-api"
  }
}

resource "aws_cognito_user_pool_client" "m2m" {
  name         = "meli-devops-${var.environment}-api-m2m"
  user_pool_id = aws_cognito_user_pool.this.id

  generate_secret                      = true
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["client_credentials"]
  allowed_oauth_scopes                 = ["${aws_cognito_resource_server.api.identifier}/read"]

  explicit_auth_flows = [
    "ALLOW_REFRESH_TOKEN_AUTH",
  ]

  access_token_validity  = 60
  id_token_validity      = 60
  refresh_token_validity = 1

  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }

  supported_identity_providers = ["COGNITO"]

  prevent_user_existence_errors = "ENABLED"

  depends_on = [aws_cognito_resource_server.api]
}

resource "aws_cognito_user_pool_domain" "this" {
  domain       = "meli-devops-${var.environment}-api-auth"
  user_pool_id = aws_cognito_user_pool.this.id
}
