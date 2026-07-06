@echo off
setlocal enabledelayedexpansion
echo ===================================================
echo NanoRank AI APK Reconstructor for Windows
echo ===================================================
echo.
echo Reconstructing nanorank-ai-debug.apk from chunks...

:: Change directory to where this script is located
cd /d "%~dp0"

:: Clean up any existing reconstructed APK first to avoid overwrite prompts or appending issues
if exist nanorank-ai-debug.apk del /f /q nanorank-ai-debug.apk

:: Perform binary concatenation in explicit alphabetical order
copy /b /y chunks\part_aa + chunks\part_ab + chunks\part_ac + chunks\part_ad + chunks\part_ae nanorank-ai-debug.apk > nul

if %ERRORLEVEL% EQU 0 (
    echo.
    echo SUCCESS: nanorank-ai-debug.apk was reconstructed successfully.
    echo.
    echo Expected File Size: 6418993 bytes
    echo.
    echo Checking your file size...
    for %%F in (nanorank-ai-debug.apk) do set "size=%%~zF"
    echo Your File Size:     !size! bytes
    echo.
    if "!size!"=="6418993" (
        echo OK: File size matches perfectly. You can now install it on your Android device.
    ) else (
        echo WARNING: File size does not match the expected size of 6418993 bytes.
        echo Please ensure all chunk files are fully downloaded in chunks directory.
    )
) else (
    echo.
    echo ERROR: Something went wrong during concatenation.
)
echo.
pause
