#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_dir"
python_bin=${1:-python3}
"$python_bin" -c 'import sys; assert sys.version_info >= (3,10), "code-review-graph needs Python 3.10 or newer"'
"$python_bin" -m venv .tools/venv
.tools/venv/bin/python -m pip install 'code-review-graph==2.3.9'
sh tools/crg build
