#!/bin/sh
set -eu

# The app scheme has no test target; run the Swift core package tests for every cloud action.
swift test --package-path "$CI_PRIMARY_REPOSITORY_PATH"
