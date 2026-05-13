variable "environment" {
  description = "Environment used in user pool, app client, and domain names."
  type        = string
}

variable "region" {
  description = "AWS region (used to compose the token URL)."
  type        = string
}

variable "tags" {
  description = "Base tag set."
  type        = map(string)
}
