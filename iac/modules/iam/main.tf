locals {
  module_tags = merge(var.tags, { Module = "iam" })
  name_prefix = "meli-devops-${var.environment}"

  artifact_bucket_arn_pattern  = "arn:aws:s3:::${local.name_prefix}-pipeline-*"
  pipeline_kms_arn_pattern     = "arn:aws:kms:${var.region}:${var.account_id}:key/*"
  codebuild_project_arn_pattern = "arn:aws:codebuild:${var.region}:${var.account_id}:project/${local.name_prefix}-*"
  approval_topic_arn_pattern   = "arn:aws:sns:${var.region}:${var.account_id}:${local.name_prefix}-approvals-*"
  codebuild_log_arn_pattern    = "arn:aws:logs:${var.region}:${var.account_id}:log-group:/aws/codebuild/${local.name_prefix}*"
}

data "aws_iam_policy_document" "eks_cluster_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "eks_cluster" {
  name               = "${local.name_prefix}-eks-cluster"
  assume_role_policy = data.aws_iam_policy_document.eks_cluster_assume.json
  tags               = local.module_tags
}

resource "aws_iam_role_policy_attachment" "eks_cluster_policy" {
  role       = aws_iam_role.eks_cluster.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}

data "aws_iam_policy_document" "eks_node_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "eks_node" {
  name               = "${local.name_prefix}-eks-node"
  assume_role_policy = data.aws_iam_policy_document.eks_node_assume.json
  tags               = local.module_tags
}

resource "aws_iam_role_policy_attachment" "eks_node_worker" {
  role       = aws_iam_role.eks_node.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy"
}

resource "aws_iam_role_policy_attachment" "eks_node_cni" {
  role       = aws_iam_role.eks_node.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy"
}

resource "aws_iam_role_policy_attachment" "eks_node_ecr" {
  role       = aws_iam_role.eks_node.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
}

resource "aws_iam_role_policy_attachment" "eks_node_cwagent" {
  role       = aws_iam_role.eks_node.name
  policy_arn = "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
}

data "aws_iam_policy_document" "codebuild_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["codebuild.amazonaws.com"]
    }
  }
}

data "aws_iam_policy_document" "codebuild_base" {
  statement {
    sid = "Logs"
    actions = [
      "logs:CreateLogGroup",
      "logs:CreateLogStream",
      "logs:PutLogEvents",
    ]
    resources = [
      local.codebuild_log_arn_pattern,
      "${local.codebuild_log_arn_pattern}:*",
    ]
  }

  statement {
    sid = "Artifacts"
    actions = [
      "s3:PutObject",
      "s3:GetObject",
      "s3:GetObjectVersion",
      "s3:GetBucketAcl",
      "s3:GetBucketLocation",
      "s3:ListBucket",
    ]
    resources = [
      local.artifact_bucket_arn_pattern,
      "${local.artifact_bucket_arn_pattern}/*",
    ]
  }

  statement {
    sid = "ArtifactKms"
    actions = [
      "kms:Decrypt",
      "kms:Encrypt",
      "kms:ReEncrypt*",
      "kms:GenerateDataKey*",
      "kms:DescribeKey",
    ]
    resources = [local.pipeline_kms_arn_pattern]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values = [
        "s3.${var.region}.amazonaws.com",
      ]
    }
  }

  statement {
    sid = "SsmConfig"
    actions = [
      "ssm:GetParameters",
      "ssm:GetParameter",
    ]
    resources = [
      "arn:aws:ssm:${var.region}:${var.account_id}:parameter/meli/config/*",
    ]
  }
}

resource "aws_iam_role" "codebuild_test" {
  name               = "${local.name_prefix}-codebuild-test"
  assume_role_policy = data.aws_iam_policy_document.codebuild_assume.json
  tags               = local.module_tags
}

resource "aws_iam_role_policy" "codebuild_test" {
  name   = "base"
  role   = aws_iam_role.codebuild_test.id
  policy = data.aws_iam_policy_document.codebuild_base.json
}

data "aws_iam_policy_document" "codebuild_test_vpc" {
  statement {
    sid = "VpcEnis"
    actions = [
      "ec2:CreateNetworkInterface",
      "ec2:DescribeDhcpOptions",
      "ec2:DescribeNetworkInterfaces",
      "ec2:DeleteNetworkInterface",
      "ec2:DescribeSubnets",
      "ec2:DescribeSecurityGroups",
      "ec2:DescribeVpcs",
      "ec2:CreateNetworkInterfacePermission",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "codebuild_test_vpc" {
  name   = "vpc-eni"
  role   = aws_iam_role.codebuild_test.id
  policy = data.aws_iam_policy_document.codebuild_test_vpc.json
}

data "aws_iam_policy_document" "codebuild_quality" {
  source_policy_documents = [data.aws_iam_policy_document.codebuild_base.json]

  statement {
    sid       = "SonarSecret"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [var.sonarqube_token_secret_arn]
  }
}

resource "aws_iam_role" "codebuild_quality" {
  name               = "${local.name_prefix}-codebuild-quality"
  assume_role_policy = data.aws_iam_policy_document.codebuild_assume.json
  tags               = local.module_tags
}

resource "aws_iam_role_policy" "codebuild_quality" {
  name   = "base-plus-sonar"
  role   = aws_iam_role.codebuild_quality.id
  policy = data.aws_iam_policy_document.codebuild_quality.json
}

data "aws_iam_policy_document" "codebuild_build" {
  source_policy_documents = [data.aws_iam_policy_document.codebuild_base.json]

  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid = "EcrPush"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:CompleteLayerUpload",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
      "ecr:BatchGetImage",
      "ecr:DescribeImages",
    ]
    resources = [var.ecr_repository_arn]
  }
}

resource "aws_iam_role" "codebuild_build" {
  name               = "${local.name_prefix}-codebuild-build"
  assume_role_policy = data.aws_iam_policy_document.codebuild_assume.json
  tags               = local.module_tags
}

resource "aws_iam_role_policy" "codebuild_build" {
  name   = "base-plus-ecr"
  role   = aws_iam_role.codebuild_build.id
  policy = data.aws_iam_policy_document.codebuild_build.json
}

data "aws_iam_policy_document" "codebuild_manifests" {
  source_policy_documents = [data.aws_iam_policy_document.codebuild_base.json]

  statement {
    sid       = "GithubManifestSecret"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [var.github_manifest_token_secret_arn]
  }
}

resource "aws_iam_role" "codebuild_manifests" {
  name               = "${local.name_prefix}-codebuild-manifests"
  assume_role_policy = data.aws_iam_policy_document.codebuild_assume.json
  tags               = local.module_tags
}

resource "aws_iam_role_policy" "codebuild_manifests" {
  name   = "base-plus-github"
  role   = aws_iam_role.codebuild_manifests.id
  policy = data.aws_iam_policy_document.codebuild_manifests.json
}

data "aws_iam_policy_document" "codepipeline_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["codepipeline.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "codepipeline" {
  name               = "${local.name_prefix}-codepipeline"
  assume_role_policy = data.aws_iam_policy_document.codepipeline_assume.json
  tags               = local.module_tags
}

data "aws_iam_policy_document" "codepipeline" {
  statement {
    sid = "Artifacts"
    actions = [
      "s3:PutObject",
      "s3:GetObject",
      "s3:GetObjectVersion",
      "s3:GetBucketLocation",
      "s3:ListBucket",
    ]
    resources = [
      local.artifact_bucket_arn_pattern,
      "${local.artifact_bucket_arn_pattern}/*",
    ]
  }

  statement {
    sid = "PipelineKms"
    actions = [
      "kms:Decrypt",
      "kms:Encrypt",
      "kms:ReEncrypt*",
      "kms:GenerateDataKey*",
      "kms:DescribeKey",
    ]
    resources = [local.pipeline_kms_arn_pattern]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values = [
        "s3.${var.region}.amazonaws.com",
      ]
    }
  }

  statement {
    sid = "CodeBuild"
    actions = [
      "codebuild:StartBuild",
      "codebuild:BatchGetBuilds",
      "codebuild:StopBuild",
    ]
    resources = [local.codebuild_project_arn_pattern]
  }

  statement {
    sid       = "CodeStar"
    actions   = ["codestar-connections:UseConnection"]
    resources = [var.codestar_connection_arn]
  }

  statement {
    sid       = "ApprovalSns"
    actions   = ["sns:Publish"]
    resources = [local.approval_topic_arn_pattern]
  }
}

resource "aws_iam_role_policy" "codepipeline" {
  name   = "orchestrate"
  role   = aws_iam_role.codepipeline.id
  policy = data.aws_iam_policy_document.codepipeline.json
}
