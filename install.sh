#!/usr/bin/env bash

set -e

pushd dist
sudo dpkg -r kdeps
sudo dpkg -i kdeps_1.0_amd64.deb
popd

echo
echo "Done!"
echo
