output "alb_arn" {
  description = "ARN of the ALB."
  value       = aws_lb.this.arn
}

output "alb_dns_name" {
  description = "Public DNS name of the ALB."
  value       = aws_lb.this.dns_name
}

output "alb_zone_id" {
  description = "Hosted zone ID of the ALB (for Route53 alias records)."
  value       = aws_lb.this.zone_id
}

output "alb_security_group_id" {
  description = "Security group ID attached to the ALB."
  value       = aws_security_group.alb.id
}

output "target_group_arn" {
  description = "ARN of the IP-type target group (referenced by the Kubernetes Ingress)."
  value       = aws_lb_target_group.this.arn
}

output "waf_acl_arn" {
  description = "ARN of the WAFv2 Web ACL associated with the ALB."
  value       = aws_wafv2_web_acl.this.arn
}

output "acm_certificate_arn" {
  description = "ARN of the ACM certificate used by the HTTPS listener."
  value       = aws_acm_certificate.this.arn
}

output "acm_validation_records" {
  description = "DNS CNAMEs the external Route53 team must create to validate the ACM certificate."
  value = [
    for d in aws_acm_certificate.this.domain_validation_options : {
      name  = d.resource_record_name
      type  = d.resource_record_type
      value = d.resource_record_value
    }
  ]
}

output "alb_logs_bucket" {
  description = "Bucket where the ALB writes access logs."
  value       = aws_s3_bucket.alb_logs.id
}
