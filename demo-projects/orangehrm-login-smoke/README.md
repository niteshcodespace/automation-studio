# OrangeHRM Login Smoke

This repository-owned package defines the first declarative business automation for Automation
Studio. `scenario.json` uses Playwright manifest schema `2.0`; the suite reference is
`demo-projects/orangehrm-login-smoke/scenario.json`.

The scenario reads the non-secret `${baseUrl}` execution variable, opens
`/web/index.php/auth/login`, verifies `form.oxd-form`, fills `input[name='username']` from
`orangehrm.username`, fills `input[name='password']` from `orangehrm.password`, clicks
`button[type='submit']`, verifies `/web/index.php/dashboard/index`, and confirms the dashboard
heading with `header h6`.

Both logical names are secret references. This package contains no account identifiers,
credentials, provider locations, scripts, executable code, browser setup, or target-access
instructions. Runtime environment and operator configuration remain outside this source package.
