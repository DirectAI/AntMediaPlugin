# Compile Plugin
mvn clean install -Dmaven.javadoc.skip=true -Dmaven.test.skip=true -Dgpg.skip=true
OUT=$?

if [ $OUT -ne 0 ]; then
    exit $OUT
fi

# Delete original inference plugin
AMS_DIR="/usr/local/antmedia"
file_to_delete="$AMS_DIR/plugins/ant-media-inference-plugin.jar"
if [ -f "$file_to_delete" ]; then
    sudo rm -r "$file_to_delete"
else
    echo "File not found: $file_to_delete"
fi
# Copy over compiled plugin
sudo cp target/ant-media-inference-plugin.jar $AMS_DIR/plugins/ant-media-inference-plugin.jar
sudo cp target/lib/opencv-4.7.0-0.jar $AMS_DIR/plugins/opencv-4.7.0-0.jar

OUT=$?

if [ $OUT -ne 0 ]; then
    exit $OUT
fi

# Restart AntMedia server to apply changes
sudo service antmedia restart