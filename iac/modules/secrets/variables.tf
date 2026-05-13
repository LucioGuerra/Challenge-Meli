variable "environment" {
  description = "Environment used in the KMS alias."
  type        = string
}

variable "tags" {
  description = "Base tag set."
  type        = map(string)
}
