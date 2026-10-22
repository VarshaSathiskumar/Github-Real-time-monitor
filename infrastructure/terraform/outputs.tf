output "public_ip" {
  description = "Elastic IP of the instance. Point your DNS A record here when using a domain."
  value       = aws_eip.app.public_ip
}

output "url" {
  description = "Where the dashboard is served once the instance has finished bootstrapping (a few minutes)."
  value       = var.domain != "" ? "https://${var.domain}" : "http://${aws_eip.app.public_ip}"
}

output "instance_id" {
  description = "Use with: aws ssm start-session --target <instance_id>"
  value       = aws_instance.app.id
}
