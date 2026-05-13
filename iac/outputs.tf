output "alb_dns_name" {
  description = "Public DNS of the ALB. Configure the company's Route53 hosted zone with a CNAME / Alias pointing the base domain here."
  value       = module.alb.alb_dns_name
}

output "acm_validation_records" {
  description = "DNS CNAMEs that the external DNS team must create to validate the ACM certificate."
  value       = module.alb.acm_validation_records
}

output "ecr_repository_url" {
  description = "URL for `docker push` from the CI pipeline."
  value       = module.ecr.repository_url
}

output "cluster_name" {
  description = "EKS cluster name (used by kubectl / argocd / observability stack)."
  value       = module.eks.cluster_name
}

output "cluster_endpoint" {
  description = "Private API server endpoint."
  value       = module.eks.cluster_endpoint
}

output "oidc_provider_arn" {
  description = "ARN of the IAM OIDC provider trusted by all IRSA roles."
  value       = module.eks.oidc_provider_arn
}

output "cluster_autoscaler_role_arn" {
  description = "ARN of the IRSA role for the Cluster Autoscaler. Patch the manifest in `manifests/platform/cluster-autoscaler/install.yaml` with this ARN."
  value       = module.eks.cluster_autoscaler_role_arn
}

output "external_secrets_role_arn" {
  description = "ARN of the IRSA role for the external-secrets-system/external-secrets ServiceAccount. Used by ESO to read Secrets Manager."
  value       = module.eks.external_secrets_role_arn
}

output "aws_load_balancer_controller_role_arn" {
  description = "ARN of the IRSA role for the kube-system/aws-load-balancer-controller ServiceAccount."
  value       = module.eks.aws_load_balancer_controller_role_arn
}

output "ebs_csi_role_arn" {
  description = "ARN of the IRSA role for the EBS CSI controller."
  value       = module.eks.ebs_csi_role_arn
}

output "cognito_token_url" {
  description = "OAuth2 token endpoint (client_credentials flow)."
  value       = module.cognito.token_url
}

output "cognito_user_pool_endpoint" {
  description = "JWKS issuer endpoint of the Cognito user pool."
  value       = module.cognito.user_pool_endpoint
}

output "cognito_app_client_id" {
  description = "M2M App Client ID. The client secret is retrieved manually post-apply (see secrets/README.md)."
  value       = module.cognito.app_client_id
}

output "pipeline_name" {
  description = "CodePipeline name."
  value       = module.cicd.pipeline_name
}

output "alerts_topic_arn" {
  description = "SNS topic that receives infrastructure alarms."
  value       = module.observability.alerts_topic_arn
}
