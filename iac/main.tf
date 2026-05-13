data "aws_caller_identity" "current" {}

locals {
  account_id = data.aws_caller_identity.current.account_id
}

module "secrets" {
  source = "./modules/secrets"

  environment = var.environment
  tags        = var.tags
}

module "iam" {
  source = "./modules/iam"

  environment = var.environment
  tags        = var.tags
  region      = var.region
  account_id  = local.account_id

  ecr_repository_arn               = module.ecr.repository_arn
  sonarqube_token_secret_arn       = module.secrets.sonarqube_token_arn
  github_manifest_token_secret_arn = module.secrets.github_manifest_token_arn
  codestar_connection_arn          = var.codestar_connection_arn
}

module "ecr" {
  source = "./modules/ecr"

  environment       = var.environment
  tags              = var.tags
  repository_name   = var.cluster_name
  eks_node_role_arn = module.iam.eks_node_role_arn
}

module "networking" {
  source = "./modules/networking"

  region       = var.region
  environment  = var.environment
  cluster_name = var.cluster_name
  tags         = var.tags
}

module "eks" {
  source = "./modules/eks"

  environment        = var.environment
  tags               = var.tags
  cluster_name       = var.cluster_name
  kubernetes_version = var.kubernetes_version

  vpc_id             = module.networking.vpc_id
  private_subnet_ids = module.networking.private_subnet_ids

  cluster_role_arn = module.iam.eks_cluster_role_arn
  node_role_arn    = module.iam.eks_node_role_arn
}

module "cognito" {
  source = "./modules/cognito"

  environment = var.environment
  region      = var.region
  tags        = var.tags
}

module "alb" {
  source = "./modules/alb"

  environment            = var.environment
  tags                   = var.tags
  vpc_id                 = module.networking.vpc_id
  public_subnet_ids      = module.networking.public_subnet_ids
  node_security_group_id = module.eks.node_security_group_id
  base_domain            = var.base_domain
}

resource "aws_security_group_rule" "nodes_from_alb" {
  type                     = "ingress"
  from_port                = 8080
  to_port                  = 8080
  protocol                 = "tcp"
  source_security_group_id = module.alb.alb_security_group_id
  security_group_id        = module.eks.node_security_group_id
  description              = "Allow ALB to reach pod port via IP targets"
}

module "observability" {
  source = "./modules/observability"

  environment  = var.environment
  tags         = var.tags
  region       = var.region
  account_id   = local.account_id
  alb_arn      = module.alb.alb_arn
  alerts_email = var.alerts_email
}

module "cicd" {
  source = "./modules/cicd"

  environment             = var.environment
  tags                    = var.tags
  vpc_id                  = module.networking.vpc_id
  private_subnet_ids      = module.networking.private_subnet_ids
  github_repo             = var.github_repo
  github_branch           = var.github_branch
  codestar_connection_arn = var.codestar_connection_arn

  codebuild_test_role_arn      = module.iam.codebuild_test_role_arn
  codebuild_quality_role_arn   = module.iam.codebuild_quality_role_arn
  codebuild_build_role_arn     = module.iam.codebuild_build_role_arn
  codebuild_manifests_role_arn = module.iam.codebuild_manifests_role_arn
  codepipeline_role_arn        = module.iam.codepipeline_role_arn
  codebuild_log_group_name     = module.observability.codebuild_log_group_name

  approval_test_email = var.approval_test_email
  approval_prod_email = var.approval_prod_email
}
