output "pipeline_name" {
  description = "Name of the CodePipeline."
  value       = aws_codepipeline.this.name
}

output "pipeline_arn" {
  description = "ARN of the CodePipeline."
  value       = aws_codepipeline.this.arn
}

output "artifact_bucket_name" {
  description = "S3 bucket holding pipeline artifacts."
  value       = aws_s3_bucket.artifacts.bucket
}

output "artifact_bucket_arn" {
  description = "ARN of the artifact bucket."
  value       = aws_s3_bucket.artifacts.arn
}

output "pipeline_kms_key_arn" {
  description = "ARN of the KMS CMK encrypting artifact bucket and pipeline artifacts."
  value       = aws_kms_key.pipeline.arn
}

output "codebuild_project_arns" {
  description = "ARNs of the four CodeBuild projects (used by the CodePipeline IAM policy)."
  value = [
    aws_codebuild_project.test.arn,
    aws_codebuild_project.quality.arn,
    aws_codebuild_project.build.arn,
    aws_codebuild_project.manifests.arn,
  ]
}

output "approval_topic_arns" {
  description = "ARNs of the two manual approval SNS topics."
  value = [
    aws_sns_topic.approvals_test.arn,
    aws_sns_topic.approvals_prod.arn,
  ]
}
