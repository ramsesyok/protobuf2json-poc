#!/usr/bin/env bash
# ../proto/simlog.proto から Go コードを ./proto/ に生成する(protoc / protoc-gen-go / protoc-gen-go-grpc が必要)
set -euo pipefail
cd "$(dirname "$0")"
export PATH="$PATH:$(go env GOPATH)/bin"
protoc -I ../proto \
  --go_out=. --go_opt=module=demo \
  --go-grpc_out=. --go-grpc_opt=module=demo \
  ../proto/simlog.proto
