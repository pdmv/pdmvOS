#!/usr/bin/env bash
set -euo pipefail
adb shell dumpsys meminfo dev.pdmv.os
adb shell dumpsys gfxinfo dev.pdmv.os framestats
