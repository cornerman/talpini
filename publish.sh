#!/bin/sh

set -e

cd "$(dirname $0)"

rm -rf ./npm/bin/
mkdir ./npm/bin/

sbt cli/fullOptJS::webpack

cp ./cli/target/scala-2.13/scalajs-bundler/main/talpini.js ./npm/bin/
cp ./README.md ./npm/
