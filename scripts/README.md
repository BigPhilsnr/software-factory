# Local entry points and checks

Run commands from the repository root.

| File | Purpose |
| --- | --- |
| `factory_web.py` | Load local environment configuration, compile and launch ADK chat plus the operator UI. |
| `factory_cli.py` | Load local environment configuration and invoke the factory CLI. |
| `checks/agent_smoke.py` | Replay all recorded engineering scenarios with sandbox validation. |
| `checks/web_smoke.py` | Check operator API controls against a running factory server. |
| `checks/acceptance.py` | Check HTTP behavior against the running shortener. |

The checks have moved under `checks/`; the two factory launch commands retain their names. See the [root README](../README.md) for prerequisites and exact commands. Live generation requires `.env`; fixture checks do not use provider calls.
