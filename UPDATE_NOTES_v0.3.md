# v0.3 — DualSense precise adjustment and center zoom

Based on the original MIT-licensed DualShock Calibration GUI by dualshock-tools.

- Joystick Info offers **Normal** and **10x zoom** rendering modes.
- 10x zoom uses the original piecewise radial transformation: the first **5%** of stick travel fills **50%** of the on-screen dial. It does not change the 8-bit report resolution.
- New **Precise adjustment / Fine-tune** opens a dialog that reads 12 live 16-bit calibration parameters from the attached DualSense using the original 0x80 / 0x81 feature-report protocol.
- Choose left/right stick, **Center** (LX,LY / RX,RY) or **Circularity** (LL,LT,LR,LB / RL,RT,RR,RB), adjustment step size of 1,5,10,15, on-screen +/− buttons, and a normal/10x plot.
- Edits are **local only** until you explicitly choose **Apply to RAM** and confirm. The native write uses 0x80 (0C 01 + 12 little-endian u16), then reads back to verify. Permanent memory save remains a separate guarded operation requiring a new stable drift test.
- Per-field adjustment capped at ±500 from the value loaded when opening the dialog; invalid values cannot be written.
- Unit tests added for the zoom geometry and staged-value validation.

**Unverified:** Android USB feature-report semantics, device-specific responses, physical DualSense hardware behavior, and persistence are not tested. Do not save permanently until the RAM-only result is confirmed on the actual controller. A failed RAM write requires a controller power-cycle.
