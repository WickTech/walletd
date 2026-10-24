output "app_url" {
  description = "Where walletd answers. HTTP only unless acm_certificate_arn is set."
  value       = "${local.https_enabled ? "https" : "http"}://${aws_lb.main.dns_name}"
}

output "ecr_repository_url" {
  description = "Push the image here (see infra/scripts/push-image.sh)."
  value       = aws_ecr_repository.app.repository_url
}

output "ecs_cluster" {
  value = aws_ecs_cluster.main.name
}

output "ecs_service" {
  value = aws_ecs_service.app.name
}

output "log_group" {
  description = "Follow the logs with: aws logs tail <log_group> --follow"
  value       = aws_cloudwatch_log_group.app.name
}

output "github_deploy_role_arn" {
  description = "Put this in the repository variable AWS_DEPLOY_ROLE_ARN for the deploy workflow."
  value       = aws_iam_role.deploy.arn
}

output "webhook_secret_command" {
  description = "Read the webhook secret to give to the payment provider."
  value       = "aws secretsmanager get-secret-value --secret-id ${aws_secretsmanager_secret.webhook.name} --query SecretString --output text --region ${var.aws_region}"
}

output "jwt_secret_name" {
  description = "Secret holding the token signing key; your identity provider must sign tokens with it (HS256)."
  value       = aws_secretsmanager_secret.jwt.name
}

output "github_actions_variables" {
  description = "Run these once (GitHub CLI) so the deploy workflow knows where to deploy."
  value       = <<-EOT
    gh variable set AWS_REGION          --body "${var.aws_region}"
    gh variable set AWS_DEPLOY_ROLE_ARN --body "${aws_iam_role.deploy.arn}"
    gh variable set ECR_REPOSITORY      --body "${aws_ecr_repository.app.name}"
    gh variable set ECS_CLUSTER         --body "${aws_ecs_cluster.main.name}"
    gh variable set ECS_SERVICE         --body "${aws_ecs_service.app.name}"
    gh variable set APP_URL             --body "${local.https_enabled ? "https" : "http"}://${aws_lb.main.dns_name}"
  EOT
}
