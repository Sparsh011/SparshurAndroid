#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
core_dir="$project_dir/../Sparshur/Sparshur/Core"
export DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
mkdir -p "$project_dir/.tools"
machine_arch=$(/usr/bin/uname -m)
/usr/bin/xcrun --sdk macosx swiftc -O -target "$machine_arch-apple-macosx14.0" \
  -module-cache-path "$project_dir/.tools/swift-module-cache" \
  "$core_dir/PairedProtocol.swift" "$core_dir/StrictJSON.swift" \
  "$project_dir/tools/mac_endpoint/main.swift" -o "$project_dir/.tools/mac-endpoint"
