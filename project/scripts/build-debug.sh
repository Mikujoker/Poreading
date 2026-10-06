#!/bin/bash
source ~/.android-env.sh
unset http_proxy https_proxy HTTP_PROXY HTTPS_PROXY
cd ~/work/legado-md3
echo "START $(date '+%F %T')"
./gradlew assembleAppDebug -PenableAbiSplits=false --max-workers=8 --console=plain
echo "EXIT=$? at $(date '+%F %T')"
