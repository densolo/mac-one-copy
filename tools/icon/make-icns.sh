#!/bin/sh
# Renders the icon at every size macOS wants and writes:
#   app/src/main/packaging/mac-one-copy.icns  (jpackage, the .app bundle)
#   app/src/main/resources/icon.png           (Dock and window icon when run from IntelliJ or the jar)
set -e
cd "$(dirname "$0")/../.."
set_dir=$(mktemp -d)/mac-one-copy.iconset
mkdir -p "$set_dir" app/src/main/packaging
for s in 16 32 128 256 512; do
  java tools/icon/Icon.java "$set_dir/icon_${s}x${s}.png" $s
  java tools/icon/Icon.java "$set_dir/icon_${s}x${s}@2x.png" $((s * 2))
done
iconutil -c icns "$set_dir" -o app/src/main/packaging/mac-one-copy.icns
java tools/icon/Icon.java app/src/main/resources/icon.png 512
echo "wrote app/src/main/packaging/mac-one-copy.icns and app/src/main/resources/icon.png"
