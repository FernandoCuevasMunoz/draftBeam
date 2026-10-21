# ADF Project Instructions

This project adopts the AI Development Framework (ADF). Follow the project's `CONTEXT.md` before engineering work and keep `SESSION.md` current for active work.

## Working principles

- Human authority is final; obtain approval before significant decisions or implementation plans.
- Understand the objective and existing context before changing files.
- Prefer small, reviewable changes and validate continuously.
- Preserve existing project knowledge and explain meaningful trade-offs, risks, and assumptions.
- Ask for clarification rather than inventing requirements.
- Keep permanent project knowledge in `CONTEXT.md`; keep current objectives and progress in `SESSION.md`.

## Project-specific constraints

- The product and documentation are in Spanish unless a technical convention requires otherwise.
- The product is an Android application for BLE measurement capture and basic floor-plan/DXF workflows. See `CONTEXT.md` and the linked project notes for established scope and observations.
- Treat the BLE protocol as only partially understood. Do not send unknown commands or write arbitrary values to proprietary characteristics.
- Preserve raw BLE measurement bytes when implementing decoding or storage.
- Do not redistribute proprietary ZHouse/JoyPlan APKs or reconstructed code. Do not publish device identifiers or logs containing personal data.
- The repository may initially lack source code and build tooling. Inspect the actual state before assuming a stack or running build commands.

## Engineering workflow

For meaningful work, follow ADF's lifecycle: research/context → analysis and plan → human approval → incremental implementation → validation → review → documentation. Report validation limitations honestly.
