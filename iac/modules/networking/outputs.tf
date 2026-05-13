output "vpc_id" {
  description = "ID of the created VPC."
  value       = aws_vpc.this.id
}

output "vpc_cidr" {
  description = "CIDR block of the VPC."
  value       = aws_vpc.this.cidr_block
}

output "public_subnet_ids" {
  description = "IDs of the public subnets (one per AZ)."
  value       = [for s in aws_subnet.public : s.id]
}

output "private_subnet_ids" {
  description = "IDs of the private subnets (one per AZ)."
  value       = [for s in aws_subnet.private : s.id]
}

output "vpc_flow_log_group_arn" {
  description = "ARN of the CloudWatch log group receiving VPC flow logs."
  value       = aws_cloudwatch_log_group.vpc_flow.arn
}
