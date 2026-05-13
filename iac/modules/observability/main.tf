locals {
  module_tags = merge(var.tags, { Module = "observability" })
  name_prefix = "meli-devops-${var.environment}"

  alb_arn_suffix = element(split("loadbalancer/", var.alb_arn), 1)
}

resource "aws_kms_key" "logs" {
  description             = "CMK for CloudWatch log groups"
  deletion_window_in_days = 30
  enable_key_rotation     = true

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "EnableRoot"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${var.account_id}:root" }
        Action    = "kms:*"
        Resource  = "*"
      },
      {
        Sid       = "AllowLogsService"
        Effect    = "Allow"
        Principal = { Service = "logs.${var.region}.amazonaws.com" }
        Action = [
          "kms:Encrypt",
          "kms:Decrypt",
          "kms:ReEncrypt*",
          "kms:GenerateDataKey*",
          "kms:DescribeKey",
        ]
        Resource = "*"
        Condition = {
          ArnLike = {
            "kms:EncryptionContext:aws:logs:arn" = "arn:aws:logs:${var.region}:${var.account_id}:log-group:*"
          }
        }
      },
    ]
  })

  tags = local.module_tags
}

resource "aws_kms_alias" "logs" {
  name          = "alias/${local.name_prefix}-cloudwatch"
  target_key_id = aws_kms_key.logs.key_id
}

resource "aws_cloudwatch_log_group" "codebuild" {
  name              = "/aws/codebuild/${local.name_prefix}"
  retention_in_days = 14
  kms_key_id        = aws_kms_key.logs.arn
  tags              = local.module_tags
}

resource "aws_sns_topic" "alerts" {
  name              = "${local.name_prefix}-alerts"
  kms_master_key_id = "alias/aws/sns"
  tags              = local.module_tags
}

resource "aws_sns_topic_subscription" "alerts_email" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = var.alerts_email
}

resource "aws_cloudwatch_metric_alarm" "alb_5xx" {
  alarm_name          = "${local.name_prefix}-alb-5xx-rate"
  alarm_description   = "ALB 5xx error rate above 1% over 5 minutes"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  threshold           = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  metric_query {
    id          = "errors_pct"
    expression  = "100 * (m5xx / IF(req == 0, 1, req))"
    label       = "5xx rate %"
    return_data = true
  }

  metric_query {
    id = "m5xx"
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_Target_5XX_Count"
      stat        = "Sum"
      period      = 300
      dimensions = {
        LoadBalancer = local.alb_arn_suffix
      }
    }
  }

  metric_query {
    id = "req"
    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "RequestCount"
      stat        = "Sum"
      period      = 300
      dimensions = {
        LoadBalancer = local.alb_arn_suffix
      }
    }
  }

  tags = local.module_tags
}

resource "aws_cloudwatch_metric_alarm" "alb_p99_latency" {
  alarm_name          = "${local.name_prefix}-alb-p99-latency"
  alarm_description   = "ALB p99 latency above 500ms over 5 minutes"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  threshold           = 0.5
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  metric_name = "TargetResponseTime"
  namespace   = "AWS/ApplicationELB"
  statistic   = "p99"
  period      = 300

  dimensions = {
    LoadBalancer = local.alb_arn_suffix
  }

  tags = local.module_tags
}

resource "aws_cloudwatch_dashboard" "overview" {
  dashboard_name = "${local.name_prefix}-overview"

  dashboard_body = jsonencode({
    widgets = [
      {
        type = "metric", x = 0, y = 0, width = 12, height = 6,
        properties = {
          title  = "ALB request count + 5xx"
          region = var.region
          view   = "timeSeries"
          metrics = [
            ["AWS/ApplicationELB", "RequestCount", "LoadBalancer", local.alb_arn_suffix, { stat = "Sum" }],
            [".", "HTTPCode_Target_5XX_Count", ".", ".", { stat = "Sum" }],
          ]
          period = 60
        }
      },
      {
        type = "metric", x = 12, y = 0, width = 12, height = 6,
        properties = {
          title  = "ALB target latency p50/p95/p99"
          region = var.region
          view   = "timeSeries"
          metrics = [
            ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", local.alb_arn_suffix, { stat = "p50" }],
            [".", ".", ".", ".", { stat = "p95" }],
            [".", ".", ".", ".", { stat = "p99" }],
          ]
          period = 60
        }
      },
    ]
  })
}
