#!/usr/bin/env bash

set -euo pipefail

bash build.sh --no-sbt --qemu-extra-args="-s -S"
