# Curated evaluation evidence

This is a small, committed snapshot of real local results. It contains no provider key or `.env` file. Absolute project/home paths are replaced with placeholders; original and exported SHA-256 digests are recorded where files were copied.

- `live-bugfix/`: provider-generated diagnosis, regression patch, actual red assertion, production repair, actual green report, runbook, final candidate diff, and selected run/audit metadata. The historical candidate uses the old Java package names.
- `live-failures/outcomes.json`: failed live greenfield and brownfield outcomes; no claim that the three required scenarios all succeeded live.
- `historical-evaluation/`: the earlier full local suite, explicitly tied to commit `101270c`. It tested the working directory, where an ignored source file masked a clean-clone compilation error. This snapshot is not fresh-clone proof or current-HEAD evidence.

Run `python3 scripts/checks/verify_evidence.py` from the repository root. The manifest covers exported file integrity, not authorship or independently verified execution. The live bug-fix's red/green reports and exact patches are the execution evidence; its model-written runbook is commentary and should be checked against those reports. A run was recorded as operator-approved, but this local prototype does not authenticate the operator.

Current-source verification is reproducible with `python3 scripts/checks/clean_checkout.py` (JDK 21), and the full local evaluation is `python3 scripts/checks/evaluate.py` after starting the documented databases and applications.
