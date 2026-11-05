#!/usr/bin/env bash
adb logcat -v time | grep -E 'pdmvOS|dev.pdmv.os|FATAL EXCEPTION|AndroidRuntime'
