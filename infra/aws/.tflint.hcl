config {
  call_module_type = "none"
}

plugin "aws" {
  enabled = true
  version = "0.50.0"
  source  = "github.com/terraform-linters/tflint-ruleset-aws"
}

# This is a root stack, not a reusable module, so it has no main.tf.
rule "terraform_standard_module_structure" {
  enabled = false
}
