# trivy:ignore:AVD-AWS-0053 -- the service is an internet-facing API on purpose.
resource "aws_lb" "main" {
  name               = local.name
  load_balancer_type = "application"
  internal           = false
  subnets            = aws_subnet.public[*].id
  security_groups    = [aws_security_group.alb.id]

  drop_invalid_header_fields = true
  enable_deletion_protection = var.protect_data
}

resource "aws_lb_target_group" "app" {
  name                 = local.name
  vpc_id               = aws_vpc.main.id
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip" # Fargate tasks register by IP
  deregistration_delay = 30

  health_check {
    path                = "/actuator/health"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

locals {
  https_enabled = var.acm_certificate_arn != ""
}

# With a certificate, port 80 only redirects. Without one, port 80 serves the app (demo only).
# trivy:ignore:AVD-AWS-0054 -- with a certificate this listener only redirects to HTTPS; without one it serves the
# demo over HTTP (documented, and the README says to set acm_certificate_arn before real use).
resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  dynamic "default_action" {
    for_each = local.https_enabled ? [1] : []
    content {
      type = "redirect"
      redirect {
        port        = "443"
        protocol    = "HTTPS"
        status_code = "HTTP_301"
      }
    }
  }

  dynamic "default_action" {
    for_each = local.https_enabled ? [] : [1]
    content {
      type             = "forward"
      target_group_arn = aws_lb_target_group.app.arn
    }
  }
}

resource "aws_lb_listener" "https" {
  count = local.https_enabled ? 1 : 0

  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.acm_certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }
}
