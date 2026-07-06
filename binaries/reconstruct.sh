#!/bin/bash
echo "==================================================="
echo "NanoRank AI APK Reconstructor for macOS / Linux"
echo "==================================================="
echo ""
echo "Reconstructing nanorank-ai-debug.apk from chunks..."

# Get the script directory
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$DIR"

# Perform concatenation using explicit files to avoid sorting issues
cat chunks/part_aa chunks/part_ab chunks/part_ac chunks/part_ad chunks/part_ae > nanorank-ai-debug.apk

if [ $? -eq 0 ]; then
    echo ""
    echo "[SUCCESS] nanorank-ai-debug.apk was reconstructed successfully!"
    echo ""
    echo "Expected File Size: 6418993 bytes"
    
    # Get file size
    if [[ "$OSTYPE" == "darwin"* ]]; then
        ACTUAL_SIZE=$(stat -f%z nanorank-ai-debug.apk)
    else
        ACTUAL_SIZE=$(stat -c%s nanorank-ai-debug.apk)
    fi
    
    echo "Your File Size:     $ACTUAL_SIZE bytes"
    echo ""
    if [ "$ACTUAL_SIZE" -eq 6418993 ]; then
        echo "[OK] File size matches perfectly! You can now transfer and install it."
    else
        echo "[WARNING] File size ($ACTUAL_SIZE) does not match the expected size (6418993)."
        echo "Please make sure all parts inside the 'chunks' directory are fully downloaded."
    fi
else
    echo ""
    echo "[ERROR] Something went wrong during concatenation."
fi
