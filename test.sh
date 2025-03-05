#!/bin/sh

if [ $# -ge 1 ]; then
    SUFFIX="--test-only *$1"
else
    SUFFIX=''
fi
if scala test . $SUFFIX --suppress-experimental-feature-warning --suppress-directives-in-multiple-files-warning --suppress-outdated-dependency-warning; then
    echo "Done."
else
    echo "Tests failed, check the log for the details."
fi
