# Screenshots

Unmodified device screenshots:

- `phone-host.png`: Galaxy Z Fold6 (SM-F956B), 968 x 2376 pixels, captured on
  2026-10-07 after installing the published `build-2` release in place. Shows
  the retained Gemma 4 E2B model and GPU setting, the running API with the model
  asleep, and the enabled MCP-search switch. No inference result is shown.
  The displayed app API address and configured weather city are visible.

Samsung Galaxy Watch4 Classic (SM-R895F), 450 x 450 pixels, from an earlier
installed LLM Wear version:

- `watch-home.png`: the main screen with a nearby phone connected and no model
  loaded. The microphone, keyboard, timer, weather, and settings actions are visible.
- `watch-timer.png`: the duration picker set to five minutes, before starting
  the system timer.

These are not mockups. The watch images do not show the latest speech-provider
selection or MCP web-search update. The phone image is not proof of a successful
search: the provider returned HTTP 403 from the phone's network during validation.
Lock screens, personal photos, unrelated apps, and debug connection details
are deliberately excluded from the public repository.
