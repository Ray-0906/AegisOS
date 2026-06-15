#!/bin/bash

# Baseline number of allowed Thread.sleep usages
BASELINE=60

echo "Checking for new Thread.sleep() usages..."

# Find all occurrences of Thread.sleep in test code
# Exclude aegis-cli, aegis-demo-job, and any benchmarks
# Note: Since the command might fail if grep finds nothing, we ensure the pipeline returns a count
COUNT=$(git grep "Thread\.sleep" -- '*/src/test/java/*.java' ':!aegis-cli/*' ':!aegis-demo-job/*' ':!benchmarks/*' | wc -l)

echo "Found $COUNT usages of Thread.sleep in production test suites (baseline: $BASELINE)"

if [ "$COUNT" -gt "$BASELINE" ]; then
    echo "ERROR: Thread.sleep usages ($COUNT) exceeded baseline ($BASELINE). Do not add new Thread.sleep() calls to tests!"
    echo "Use ClusterAwaiter or EventAwaiter instead."
    exit 1
else
    echo "SUCCESS: Thread.sleep usages ($COUNT) is within or below baseline ($BASELINE)."
    exit 0
fi
