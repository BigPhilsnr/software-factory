# Local entry points and checks

Run commands from the repository root.

| File | Purpose |
| --- | --- |
| `factory_web.py` | Load local environment configuration, compile and launch ADK chat plus the operator UI. |
| `factory_cli.py` | Load local environment configuration and invoke the factory CLI. |
| `checks/clean_checkout.py` | Test committed HEAD in a fresh temporary clone; excludes ignored/uncommitted source files. |
| `checks/verify_evidence.py` | Verify all curated submission evidence against its checksum manifest. |
| `checks/evaluate.py` | Run the full local evaluation and retain machine-readable results/logs. |
| `checks/agent_smoke.py` | Replay all recorded engineering scenarios with sandbox validation. |
| `checks/web_smoke.py` | Check operator API controls against a running factory server. |
| `checks/live_tools_smoke.py --live` | Exercise all seven agent tools through native ADK chat; makes paid provider calls. |
| `checks/acceptance.py` | Check HTTP behavior against the running shortener. |

The checks have moved under `checks/`; the two factory launch commands retain their names. See the [root README](../README.md) for prerequisites and exact commands. Live generation requires `.env`; fixture checks do not use provider calls.
