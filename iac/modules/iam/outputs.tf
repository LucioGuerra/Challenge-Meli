output "eks_cluster_role_arn" {
  description = "ARN of the EKS control plane role."
  value       = aws_iam_role.eks_cluster.arn
}

output "eks_node_role_arn" {
  description = "ARN of the EKS managed node group EC2 role."
  value       = aws_iam_role.eks_node.arn
}

output "eks_node_role_name" {
  description = "Name of the EKS node role (used by ECR repository policy)."
  value       = aws_iam_role.eks_node.name
}

output "codebuild_test_role_arn" {
  description = "ARN of the CodeBuild role for the unit tests stage."
  value       = aws_iam_role.codebuild_test.arn
}

output "codebuild_quality_role_arn" {
  description = "ARN of the CodeBuild role for the code-quality stage."
  value       = aws_iam_role.codebuild_quality.arn
}

output "codebuild_build_role_arn" {
  description = "ARN of the CodeBuild role for the build-and-scan stage."
  value       = aws_iam_role.codebuild_build.arn
}

output "codebuild_manifests_role_arn" {
  description = "ARN of the CodeBuild role for the manifest update stage."
  value       = aws_iam_role.codebuild_manifests.arn
}

output "codepipeline_role_arn" {
  description = "ARN of the CodePipeline orchestration role."
  value       = aws_iam_role.codepipeline.arn
}
