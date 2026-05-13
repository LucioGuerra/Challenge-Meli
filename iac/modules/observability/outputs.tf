output "alerts_topic_arn" {
  description = "ARN of the SNS topic receiving infra alarms."
  value       = aws_sns_topic.alerts.arn
}

output "codebuild_log_group_name" {
  description = "Name of the CodeBuild log group used by all four projects."
  value       = aws_cloudwatch_log_group.codebuild.name
}

output "log_group_arns" {
  description = "List of CloudWatch log group ARNs created by this module."
  value       = [aws_cloudwatch_log_group.codebuild.arn]
}

output "dashboard_arn" {
  description = "ARN of the overview dashboard."
  value       = aws_cloudwatch_dashboard.overview.dashboard_arn
}
