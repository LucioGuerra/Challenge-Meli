output "cluster_name" {
  description = "Name of the EKS cluster."
  value       = aws_eks_cluster.this.name
}

output "cluster_endpoint" {
  description = "Private API server endpoint."
  value       = aws_eks_cluster.this.endpoint
}

output "cluster_ca" {
  description = "Base64-encoded cluster CA certificate."
  value       = aws_eks_cluster.this.certificate_authority[0].data
}

output "cluster_security_group_id" {
  description = "Security group ID attached to the control plane ENIs."
  value       = aws_security_group.cluster.id
}

output "node_security_group_id" {
  description = "Security group ID attached to the worker nodes."
  value       = aws_security_group.nodes.id
}

output "oidc_provider_arn" {
  description = "ARN of the IAM OIDC provider trusted by IRSA roles."
  value       = aws_iam_openid_connect_provider.this.arn
}

output "oidc_provider_url" {
  description = "Issuer URL of the cluster OIDC provider."
  value       = aws_iam_openid_connect_provider.this.url
}

output "node_group_arn" {
  description = "ARN of the managed node group."
  value       = aws_eks_node_group.this.arn
}

output "cluster_autoscaler_role_arn" {
  description = "ARN of the IRSA role assumed by the cluster-autoscaler service account."
  value       = aws_iam_role.cluster_autoscaler.arn
}

output "external_secrets_role_arn" {
  description = "ARN of the IRSA role assumed by the external-secrets-system/external-secrets service account."
  value       = aws_iam_role.external_secrets.arn
}

output "aws_load_balancer_controller_role_arn" {
  description = "ARN of the IRSA role assumed by the kube-system/aws-load-balancer-controller service account."
  value       = aws_iam_role.aws_load_balancer_controller.arn
}

output "ebs_csi_role_arn" {
  description = "ARN of the IRSA role for the EBS CSI controller."
  value       = aws_iam_role.ebs_csi.arn
}

output "secrets_kms_key_arn" {
  description = "ARN of the CMK used for envelope encryption of Kubernetes secrets."
  value       = aws_kms_key.eks_secrets.arn
}
