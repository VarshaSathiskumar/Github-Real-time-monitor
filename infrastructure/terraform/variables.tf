variable "region" {
  description = "AWS region to deploy into."
  type        = string
  default     = "us-east-1"
}

variable "name_prefix" {
  description = "Prefix for resource names."
  type        = string
  default     = "githubpulse"
}

variable "instance_type" {
  description = "EC2 instance type. Kafka, two JVMs, PostgreSQL and Redis need about 6 GB of RAM."
  type        = string
  default     = "t3.large"
}

variable "root_volume_gb" {
  description = "Size of the root volume in GB."
  type        = number
  default     = 30
}

variable "data_volume_gb" {
  description = "Size of the persistent data volume (Docker volumes: Kafka, PostgreSQL, stream state) in GB."
  type        = number
  default     = 40
}

variable "repo_url" {
  description = "Git repository the instance clones and runs with Docker Compose."
  type        = string
  default     = "https://github.com/VarshaSathiskumar/Github-Real-time-monitor.git"
}

variable "repo_ref" {
  description = "Branch or tag to deploy."
  type        = string
  default     = "main"
}

variable "domain" {
  description = "Public DNS name pointing at the Elastic IP. When set, Caddy serves HTTPS with an automatic certificate. When empty, the site is served over plain HTTP on the IP."
  type        = string
  default     = ""
}

variable "github_token" {
  description = "Optional GitHub token (no scopes needed) for a higher API rate limit. Stored in instance user data, so use a token you can revoke."
  type        = string
  default     = ""
  sensitive   = true
}

variable "ssh_allowed_cidrs" {
  description = "CIDR blocks allowed to SSH. Empty (default) disables SSH; use AWS Systems Manager Session Manager instead."
  type        = list(string)
  default     = []
}

variable "key_name" {
  description = "Existing EC2 key pair name. Only needed when ssh_allowed_cidrs is set."
  type        = string
  default     = null
}

variable "vpc_cidr" {
  description = "CIDR of the dedicated VPC."
  type        = string
  default     = "10.42.0.0/16"
}
