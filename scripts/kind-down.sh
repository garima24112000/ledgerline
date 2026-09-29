#!/usr/bin/env bash
# Deletes the local kind cluster, including the Postgres and Kafka volumes.
set -euo pipefail
kind delete cluster --name ledgerline
