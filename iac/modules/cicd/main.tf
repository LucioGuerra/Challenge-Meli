locals {
  module_tags = merge(var.tags, { Module = "cicd" })
  name_prefix = "meli-devops-${var.environment}"

  buildspec_path = "../../../cicd"
}

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

resource "aws_kms_key" "pipeline" {
  description             = "CMK for CodePipeline artifacts"
  deletion_window_in_days = 30
  enable_key_rotation     = true
  tags                    = local.module_tags
}

resource "aws_kms_alias" "pipeline" {
  name          = "alias/${local.name_prefix}-pipeline"
  target_key_id = aws_kms_key.pipeline.key_id
}

resource "aws_s3_bucket" "artifacts" {
  bucket_prefix = "${local.name_prefix}-pipeline-"
  force_destroy = false
  tags          = local.module_tags
}

resource "aws_s3_bucket_versioning" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.pipeline.arn
    }
  }
}

resource "aws_s3_bucket_public_access_block" "artifacts" {
  bucket                  = aws_s3_bucket.artifacts.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id

  rule {
    id     = "expire-old-artifacts"
    status = "Enabled"
    filter {}
    expiration {
      days = 30
    }
    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }
}

resource "aws_sns_topic" "approvals_test" {
  name              = "${local.name_prefix}-approvals-test"
  kms_master_key_id = "alias/aws/sns"
  tags              = local.module_tags
}

resource "aws_sns_topic_subscription" "approvals_test_email" {
  topic_arn = aws_sns_topic.approvals_test.arn
  protocol  = "email"
  endpoint  = var.approval_test_email
}

resource "aws_sns_topic" "approvals_prod" {
  name              = "${local.name_prefix}-approvals-prod"
  kms_master_key_id = "alias/aws/sns"
  tags              = local.module_tags
}

resource "aws_sns_topic_subscription" "approvals_prod_email" {
  topic_arn = aws_sns_topic.approvals_prod.arn
  protocol  = "email"
  endpoint  = var.approval_prod_email
}

resource "aws_codebuild_project" "test" {
  name         = "${local.name_prefix}-test"
  description  = "Stage 2: unit tests + coverage gate"
  service_role = var.codebuild_test_role_arn

  artifacts {
    type = "CODEPIPELINE"
  }

  cache {
    type     = "S3"
    location = "${aws_s3_bucket.artifacts.bucket}/cache/maven"
  }

  environment {
    type            = "LINUX_CONTAINER"
    compute_type    = "BUILD_GENERAL1_SMALL"
    image           = "aws/codebuild/standard:7.0"
    privileged_mode = false
  }

  source {
    type      = "CODEPIPELINE"
    buildspec = file("${path.module}/${local.buildspec_path}/01-test.yml")
  }

  vpc_config {
    vpc_id             = var.vpc_id
    subnets            = var.private_subnet_ids
    security_group_ids = [aws_security_group.codebuild.id]
  }

  logs_config {
    cloudwatch_logs {
      group_name = var.codebuild_log_group_name
      status     = "ENABLED"
    }
  }

  tags = local.module_tags
}

resource "aws_codebuild_project" "quality" {
  name         = "${local.name_prefix}-code-quality"
  description  = "Stage 3: SonarQube quality gate"
  service_role = var.codebuild_quality_role_arn

  artifacts {
    type = "CODEPIPELINE"
  }

  environment {
    type            = "LINUX_CONTAINER"
    compute_type    = "BUILD_GENERAL1_SMALL"
    image           = "aws/codebuild/standard:7.0"
    privileged_mode = false
  }

  source {
    type      = "CODEPIPELINE"
    buildspec = file("${path.module}/${local.buildspec_path}/02-code-quality.yml")
  }

  logs_config {
    cloudwatch_logs {
      group_name = var.codebuild_log_group_name
      status     = "ENABLED"
    }
  }

  tags = local.module_tags
}

resource "aws_codebuild_project" "build" {
  name         = "${local.name_prefix}-build-and-scan"
  description  = "Stage 4: docker build + Trivy scan + ECR push"
  service_role = var.codebuild_build_role_arn

  artifacts {
    type = "CODEPIPELINE"
  }

  environment {
    type            = "LINUX_CONTAINER"
    compute_type    = "BUILD_GENERAL1_SMALL"
    image           = "aws/codebuild/standard:7.0"
    privileged_mode = true
  }

  source {
    type      = "CODEPIPELINE"
    buildspec = file("${path.module}/${local.buildspec_path}/03-build-and-scan.yml")
  }

  logs_config {
    cloudwatch_logs {
      group_name = var.codebuild_log_group_name
      status     = "ENABLED"
    }
  }

  tags = local.module_tags
}

resource "aws_codebuild_project" "manifests" {
  name         = "${local.name_prefix}-update-manifests"
  description  = "Stages 5/7/9: bump manifest repo for dev/test/prod"
  service_role = var.codebuild_manifests_role_arn

  artifacts {
    type = "CODEPIPELINE"
  }

  environment {
    type            = "LINUX_CONTAINER"
    compute_type    = "BUILD_GENERAL1_SMALL"
    image           = "aws/codebuild/standard:7.0"
    privileged_mode = false

    environment_variable {
      name  = "TARGET_ENV"
      value = "UNSET"
    }
  }

  source {
    type      = "CODEPIPELINE"
    buildspec = file("${path.module}/${local.buildspec_path}/04-update-manifests.yml")
  }

  logs_config {
    cloudwatch_logs {
      group_name = var.codebuild_log_group_name
      status     = "ENABLED"
    }
  }

  tags = local.module_tags
}

resource "aws_codepipeline" "this" {
  name     = "${local.name_prefix}-pipeline"
  role_arn = var.codepipeline_role_arn

  artifact_store {
    location = aws_s3_bucket.artifacts.bucket
    type     = "S3"

    encryption_key {
      id   = aws_kms_key.pipeline.arn
      type = "KMS"
    }
  }

  stage {
    name = "Source"
    action {
      name             = "Source"
      category         = "Source"
      owner            = "AWS"
      provider         = "CodeStarSourceConnection"
      version          = "1"
      output_artifacts = ["source_out"]

      configuration = {
        ConnectionArn        = var.codestar_connection_arn
        FullRepositoryId     = var.github_repo
        BranchName           = var.github_branch
        DetectChanges        = "true"
        OutputArtifactFormat = "CODE_ZIP"
      }
    }
  }

  stage {
    name = "UnitTests"
    action {
      name             = "UnitTests"
      category         = "Build"
      owner            = "AWS"
      provider         = "CodeBuild"
      version          = "1"
      input_artifacts  = ["source_out"]
      output_artifacts = ["test_out"]
      configuration = {
        ProjectName = aws_codebuild_project.test.name
      }
    }
  }

  stage {
    name = "CodeQuality"
    action {
      name             = "CodeQuality"
      category         = "Build"
      owner            = "AWS"
      provider         = "CodeBuild"
      version          = "1"
      input_artifacts  = ["test_out"]
      output_artifacts = ["quality_out"]
      configuration = {
        ProjectName = aws_codebuild_project.quality.name
      }
    }
  }

  stage {
    name = "BuildAndScan"
    action {
      name             = "BuildAndScan"
      category         = "Build"
      owner            = "AWS"
      provider         = "CodeBuild"
      version          = "1"
      input_artifacts  = ["quality_out"]
      output_artifacts = ["build_out"]
      configuration = {
        ProjectName = aws_codebuild_project.build.name
      }
    }
  }

  stage {
    name = "DeployDev"
    action {
      name            = "DeployDev"
      category        = "Build"
      owner           = "AWS"
      provider        = "CodeBuild"
      version         = "1"
      input_artifacts = ["build_out"]
      configuration = {
        ProjectName = aws_codebuild_project.manifests.name
        EnvironmentVariables = jsonencode([
          { name = "TARGET_ENV", value = "dev", type = "PLAINTEXT" },
        ])
      }
    }
  }

  stage {
    name = "ApproveTest"
    action {
      name     = "ApproveTest"
      category = "Approval"
      owner    = "AWS"
      provider = "Manual"
      version  = "1"
      configuration = {
        NotificationArn = aws_sns_topic.approvals_test.arn
        CustomData      = "Approve promotion to TEST environment (manifest repo bump → ArgoCD sync)."
      }
    }
  }

  stage {
    name = "DeployTest"
    action {
      name            = "DeployTest"
      category        = "Build"
      owner           = "AWS"
      provider        = "CodeBuild"
      version         = "1"
      input_artifacts = ["build_out"]
      configuration = {
        ProjectName = aws_codebuild_project.manifests.name
        EnvironmentVariables = jsonencode([
          { name = "TARGET_ENV", value = "test", type = "PLAINTEXT" },
        ])
      }
    }
  }

  stage {
    name = "ApproveProd"
    action {
      name     = "ApproveProd"
      category = "Approval"
      owner    = "AWS"
      provider = "Manual"
      version  = "1"
      configuration = {
        NotificationArn = aws_sns_topic.approvals_prod.arn
        CustomData      = "Approve promotion to PROD environment (manifest repo bump → ArgoCD sync)."
      }
    }
  }

  stage {
    name = "DeployProd"
    action {
      name            = "DeployProd"
      category        = "Build"
      owner           = "AWS"
      provider        = "CodeBuild"
      version         = "1"
      input_artifacts = ["build_out"]
      configuration = {
        ProjectName = aws_codebuild_project.manifests.name
        EnvironmentVariables = jsonencode([
          { name = "TARGET_ENV", value = "prod", type = "PLAINTEXT" },
        ])
      }
    }
  }

  tags = local.module_tags
}

resource "aws_sns_topic" "pipeline_events" {
  name              = "${local.name_prefix}-pipeline-events"
  kms_master_key_id = "alias/aws/sns"
  tags              = local.module_tags
}

resource "aws_sns_topic_subscription" "pipeline_events_email" {
  topic_arn = aws_sns_topic.pipeline_events.arn
  protocol  = "email"
  endpoint  = var.approval_prod_email
}

resource "aws_cloudwatch_event_rule" "pipeline_state" {
  name        = "${local.name_prefix}-pipeline-state"
  description = "Surface every CodePipeline state change to SNS"
  event_pattern = jsonencode({
    source      = ["aws.codepipeline"]
    "detail-type" = ["CodePipeline Pipeline Execution State Change"]
    detail = {
      pipeline = [aws_codepipeline.this.name]
    }
  })
}

resource "aws_cloudwatch_event_target" "pipeline_state" {
  rule      = aws_cloudwatch_event_rule.pipeline_state.name
  target_id = "sns"
  arn       = aws_sns_topic.pipeline_events.arn
}

data "aws_iam_policy_document" "events_to_sns" {
  statement {
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.pipeline_events.arn]
    principals {
      type        = "Service"
      identifiers = ["events.amazonaws.com"]
    }
  }
}

resource "aws_sns_topic_policy" "pipeline_events" {
  arn    = aws_sns_topic.pipeline_events.arn
  policy = data.aws_iam_policy_document.events_to_sns.json
}

resource "aws_security_group" "codebuild" {
  name        = "${local.name_prefix}-codebuild-sg"
  description = "Security group for CodeBuild test project (runs in VPC)"
  vpc_id      = var.vpc_id

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = local.module_tags
}
