terraform {
  backend "s3" {
    bucket         = "PLACEHOLDER-tfstate-bucket"
    key            = "meli-devops-api/terraform.tfstate"
    region         = "us-east-1"
    dynamodb_table = "PLACEHOLDER-tfstate-lock"
    encrypt        = true
    kms_key_id     = "PLACEHOLDER-tfstate-kms-key-arn"
  }
}
