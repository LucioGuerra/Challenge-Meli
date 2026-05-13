locals {
  module_tags = merge(var.tags, { Module = "secrets" })

  secret_names = [
    "/meli/api/cognito-client-secret",
    "/meli/pipeline/github-manifest-token",
    "/meli/pipeline/sonarqube-token",
  ]
}

resource "aws_kms_key" "secrets" {
  description             = "CMK for Secrets Manager values"
  deletion_window_in_days = 30
  enable_key_rotation     = true
  tags                    = local.module_tags
}

resource "aws_kms_alias" "secrets" {
  name          = "alias/meli-devops-${var.environment}-secrets"
  target_key_id = aws_kms_key.secrets.key_id
}

resource "aws_secretsmanager_secret" "this" {
  for_each = toset(local.secret_names)

  name        = each.value
  description = "Managed by Terraform. Placeholder value — set manually before first apply."
  kms_key_id  = aws_kms_key.secrets.arn

  tags = local.module_tags
}

resource "aws_secretsmanager_secret_version" "placeholder" {
  for_each = aws_secretsmanager_secret.this

  secret_id     = each.value.id
  secret_string = "PLACEHOLDER - set manually before apply"

  lifecycle {
    ignore_changes = [secret_string]
  }
}

data "aws_iam_policy_document" "deny_insecure_transport" {
  for_each = aws_secretsmanager_secret.this

  statement {
    sid     = "DenyNonTls"
    effect  = "Deny"
    actions = ["secretsmanager:GetSecretValue"]
    resources = [each.value.arn]
    principals {
      type        = "AWS"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_secretsmanager_secret_policy" "this" {
  for_each   = aws_secretsmanager_secret.this
  secret_arn = each.value.arn
  policy     = data.aws_iam_policy_document.deny_insecure_transport[each.key].json
}
