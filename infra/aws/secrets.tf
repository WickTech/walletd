# Two application secrets, generated here and stored in Secrets Manager. The containers read them at start.
# The values are also in Terraform state, which is why the state must live in an encrypted backend.

resource "random_password" "jwt" {
  length  = 48
  special = false
}

resource "random_password" "webhook" {
  length  = 48
  special = false
}

resource "aws_secretsmanager_secret" "jwt" {
  name                    = "${local.name}/jwt-secret"
  description             = "Signing key for walletd bearer tokens (WALLETD_JWT_SECRET)"
  recovery_window_in_days = var.protect_data ? 7 : 0
}

resource "aws_secretsmanager_secret_version" "jwt" {
  secret_id     = aws_secretsmanager_secret.jwt.id
  secret_string = random_password.jwt.result
}

resource "aws_secretsmanager_secret" "webhook" {
  name                    = "${local.name}/webhook-secret"
  description             = "HMAC secret shared with the payment provider (WALLETD_WEBHOOK_SECRET)"
  recovery_window_in_days = var.protect_data ? 7 : 0
}

resource "aws_secretsmanager_secret_version" "webhook" {
  secret_id     = aws_secretsmanager_secret.webhook.id
  secret_string = random_password.webhook.result
}
