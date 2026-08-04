# OrangeHRM Login Smoke

This repository-owned package defines the first declarative business automation for Automation
Studio. `scenario.json` uses Playwright manifest schema `2.0`; the suite reference is
`demo-projects/orangehrm-login-smoke/scenario.json`.

The manifest uses same-origin relative paths and does not require a `${baseUrl}` execution
variable. The authoritative origin comes from `ExecutionEnvironmentSnapshot.baseUrl()`;
`SameOriginNavigationPolicy` resolves and validates `/web/index.php/auth/login` and the expected
`/web/index.php/dashboard/index` location against that origin. The scenario verifies
`form.oxd-form`, fills `input[name='username']` from `orangehrm.username`, fills
`input[name='password']` from `orangehrm.password`, clicks `button[type='submit']`, and confirms the
dashboard heading with `header h6`.

Both logical names are secret references. This package contains no account identifiers,
credentials, provider locations, scripts, executable code, browser setup, or target-access
instructions. Runtime environment and operator configuration remain outside this source package.
Ordinary Maven verification is browser-, target-, and operator-secret-inert. Real-target
qualification is manual, bounded, explicitly opted in, and governed outside this source package.
