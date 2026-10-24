variable "project" {
  description = "Name prefix for every resource."
  type        = string
  default     = "walletd"
}

variable "environment" {
  description = "Environment name, used in names and tags (for example demo, staging, prod)."
  type        = string
  default     = "demo"
}

variable "aws_region" {
  description = "AWS region."
  type        = string
  default     = "ap-south-1" # Mumbai
}

variable "github_repository" {
  description = "GitHub repository (owner/name) allowed to deploy through OIDC, also used as a tag."
  type        = string
  default     = "WickTech/walletd"
}

# --- Network -----------------------------------------------------------------------------------------

variable "vpc_cidr" {
  description = "CIDR block of the VPC."
  type        = string
  default     = "10.20.0.0/16"
}

variable "az_count" {
  description = "Number of availability zones to use (the load balancer and database need at least 2)."
  type        = number
  default     = 2

  validation {
    condition     = var.az_count >= 2 && var.az_count <= 3
    error_message = "Use 2 or 3 availability zones."
  }
}

variable "use_nat_gateway" {
  description = <<-EOT
    false (default): containers run in public subnets with a public IP, but only the load balancer can reach
    them (security group), which saves about $33/month.
    true: containers run in private subnets and reach the internet through a NAT gateway (the usual
    production shape).
  EOT
  type        = bool
  default     = false
}

# --- Application -------------------------------------------------------------------------------------

variable "image_tag" {
  description = "Tag of the image in ECR that the first deployment runs. Later deployments are done by the deploy workflow."
  type        = string
  default     = "bootstrap"
}

variable "container_cpu" {
  description = "Fargate CPU units (256, 512, 1024, ...)."
  type        = number
  default     = 512
}

variable "container_memory" {
  description = "Fargate memory in MiB (must be a valid pair with container_cpu)."
  type        = number
  default     = 1024
}

variable "min_tasks" {
  description = "Minimum number of running tasks. Use 2 or more in production so one task can fail or be replaced without downtime."
  type        = number
  default     = 1
}

variable "max_tasks" {
  description = "Maximum number of tasks autoscaling may start."
  type        = number
  default     = 3
}

variable "log_retention_days" {
  description = "How long CloudWatch keeps container logs."
  type        = number
  default     = 30
}

variable "acm_certificate_arn" {
  description = <<-EOT
    ARN of an ACM certificate in this region. When set, the load balancer serves HTTPS and redirects HTTP to
    it. When empty (default) it serves plain HTTP: fine to look at a demo, NOT fine for real bearer tokens.
  EOT
  type        = string
  default     = ""
}

# --- Database ----------------------------------------------------------------------------------------

variable "db_instance_class" {
  description = "RDS instance class."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_allocated_storage" {
  description = "Initial database storage in GiB (grows automatically up to 5x)."
  type        = number
  default     = 20
}

variable "db_multi_az" {
  description = "Keep a standby database in a second zone (doubles the database cost)."
  type        = bool
  default     = false
}

variable "db_backup_retention_days" {
  description = "Days of automated backups (point-in-time recovery). 0 disables backups."
  type        = number
  default     = 7
}

variable "protect_data" {
  description = <<-EOT
    true: deletion protection on the database and load balancer, a final database snapshot, and a 7-day
    recovery window for secrets. false (default): everything can be destroyed in one go, which suits a demo.
    Set it to true for anything that holds real money.
  EOT
  type        = bool
  default     = false
}

# --- Monitoring and CI/CD ----------------------------------------------------------------------------

variable "alarm_email" {
  description = "Email address that receives alarm notifications (it must confirm the subscription). Empty = alarms only show in the console."
  type        = string
  default     = ""
}

variable "create_github_oidc_provider" {
  description = "Create the GitHub OIDC identity provider. Set to false if your AWS account already has one (only one per account is allowed)."
  type        = bool
  default     = true
}
