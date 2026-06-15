#!/bin/bash

echo "Checking for newly introduced Thread.sleep() usages..."

# Find all lines containing "Thread.sleep" that were added in the current changes compared to origin/v1.1
# Exclude aegis-cli, aegis-demo-job, and benchmarks.
ADDED_SLEEPS=$(git diff origin/v1.1..HEAD -U0 -- '*/src/test/java/*.java' ':!aegis-cli/*' ':!aegis-demo-job/*' ':!benchmarks/*' | grep '^\+.*Thread\.sleep' || true)

if [ -n "$ADDED_SLEEPS" ]; then
    echo "ERROR: Found new usages of Thread.sleep() in tests:"
    echo "$ADDED_SLEEPS"
    echo "Do not add new Thread.sleep() calls to tests! Use ClusterAwaiter or EventAwaiter instead."
    exit 1
else
    echo "SUCCESS: No new Thread.sleep() usages detected."
    exit 0
fi
