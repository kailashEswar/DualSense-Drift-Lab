# v0.4 — Controller-operated fine-tuning (experimental)

## Original DualShock Tools control scheme
- Use **L1** to switch to Center mode and **R1** for Circularity mode.
- Move a stick strongly to select left or right; radio controls also work.
- Use **D-pad** or **Square (left), Circle (right), Triangle (up), Cross (down)** to adjust.
- Holding one direction starts repeating after 400 ms, then repeats every 150 ms.
- Choose step sizes of 1, 5, 10 or 15 controller calibration units.
- The existing Normal / 10x zoom switch is retained, and **both** left/right joystick plots remain visible during fine tuning with the selected stick marked ACTIVE.

## Actual hardware behavior
- Inputs are decoded from byte 8 (d-pad hat and face buttons) and byte 9 (L1/R1) of standard DualSense USB report 0x01.
- For every accepted adjustment the complete 12-value calibration record is sent to **working RAM only** and read back for verification. Writes are queued/coalesced on the existing serial command executor: no blocking Android UI thread and no speculative joystick movement.
- Visual joystick movement is taken from *real subsequent USB input reports*. There is no artificial movement; very small calibration changes might not be visible due to 8-bit input resolution even with 10x visual zoom.
- Live tuning is gated behind an additional explicit warning and confirmation. Before enabling, controls are read-only.
- Controller center edits require release near the center; circularity requires holding a cardinal extreme.
- Values are limited to ±500 from the 12 original values captured on dialog entry.
- Cancel attempts to restore those original RAM values and verify before dismissing the dialog; Keep retains temporary RAM values for an after-test. Permanent NVS save is entirely separate.
- On unexpected write/readback failure, further tuning is disabled and the controller must be power-cycled.

## Limitations
- This is a USB-only, original-standard-DualSense (054C:0CE6) experimental alpha.
- Not compiled or tested on physical hardware in this environment; test read-only controls and minuscule steps before relying on the hardware path.
- Do not attempt permanent saving without confirming the before/after drift test and firmware compatibility.
- Updates do not repair physically worn joystick potentiometers.

References: https://dualshock-tools.github.io/ ; https://github.com/dualshock-tools/dualshock-tools.github.io ; https://kernel.googlesource.com/pub/scm/linux/kernel/git/stable/linux-stable/+/master/drivers/hid/hid-playstation.c
