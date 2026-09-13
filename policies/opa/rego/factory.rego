# Policy: allow factory run and tool calls.
# Load data: opa run -d policies/opa/data.json policies/opa/rego/factory.rego
# Input: { "goal": "...", "tool_calls": [{"name": "..."}], "token_usage": N }

package factory

import future.keywords.if
import future.keywords.in
import future.keywords.every

default allow = false
default require_human_approval = false

# Allow initial request (goal present, no tool calls yet)
allow if {
    input.goal != ""
    not input.tool_calls
}

# Allow when tool_calls exist and each is in allowlist
allow if {
    count(input.tool_calls) > 0
    every call in input.tool_calls {
        call.name in data.tools
    }
}

# Deny if any tool call not in allowlist (expressed without "some")
allow = false if {
    count(input.tool_calls) > 0
    count({call | call := input.tool_calls[_]; not call.name in data.tools}) > 0
}

# Deny if over token budget
allow = false if {
    input.token_usage != null
    input.token_usage >= data.budgets.defaults.max_tokens_per_run
}

# Deny if any tool call has forbidden argument keys (no secrets in arguments)
allow = false if {
    forbidden_arg_keys := {"token", "password", "secret", "api_key"}
    some call in input.tool_calls
    some key in call.argument_keys
    key in forbidden_arg_keys
}

# Require human approval for privileged tools (expressed without "some")
require_human_approval if {
    count({call | call := input.tool_calls[_]; data.tool_risk[call.name] == "privileged"}) > 0
}

# Explicit manual approval for high-risk tools (release/publish side effects).
require_human_approval if {
    count({call | call := input.tool_calls[_]; call.name in data.high_risk_tools}) > 0
}
