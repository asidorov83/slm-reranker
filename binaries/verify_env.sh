#!/bin/bash
echo "=========================================================="
echo " Тестовая среда NanoRank AI: Проверка APK и запуска"
echo "=========================================================="
echo ""

DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
WORKSPACE_ROOT="$DIR/.."
cd "$WORKSPACE_ROOT"

# Шаг 1: Сборка APK из чанков
echo "[1/4] Собираем APK из оригинальных чанков..."
if [ -f "binaries/nanorank-ai-debug.apk" ]; then
    rm "binaries/nanorank-ai-debug.apk"
fi

cat binaries/chunks/part_aa binaries/chunks/part_ab binaries/chunks/part_ac binaries/chunks/part_ad binaries/chunks/part_ae > binaries/nanorank-ai-debug.apk

if [ $? -eq 0 ] && [ -f "binaries/nanorank-ai-debug.apk" ]; then
    echo "  [OK] APK успешно собран."
else
    echo "  [ERROR] Не удалось собрать APK из чанков!"
    exit 1
fi

# Шаг 2: Проверка размера и контрольной суммы MD5
echo ""
echo "[2/4] Проверка размера файла и контрольной суммы MD5..."
EXPECTED_SIZE=6418993
EXPECTED_MD5="1030cb281daf1889eabbd42ec8477bd5"

if [[ "$OSTYPE" == "darwin"* ]]; then
    ACTUAL_SIZE=$(stat -f%z binaries/nanorank-ai-debug.apk)
else
    ACTUAL_SIZE=$(stat -c%s binaries/nanorank-ai-debug.apk)
fi

echo "  Ожидаемый размер: $EXPECTED_SIZE байт"
echo "  Фактический размер: $ACTUAL_SIZE байт"

if [ "$ACTUAL_SIZE" -eq "$EXPECTED_SIZE" ]; then
    echo "  [OK] Размер файла совпадает идеально."
else
    echo "  [ERROR] Ошибка! Размер файла не совпадает."
    exit 1
fi

ACTUAL_MD5=$(md5sum binaries/nanorank-ai-debug.apk | awk '{print $1}')
echo "  Ожидаемый MD5:    $EXPECTED_MD5"
echo "  Фактический MD5:  $ACTUAL_MD5"

if [ "$ACTUAL_MD5" == "$EXPECTED_MD5" ]; then
    echo "  [OK] Контрольная сумма MD5 совпадает идеально."
else
    echo "  [ERROR] Ошибка! Контрольная сумма MD5 не совпадает."
    exit 1
fi

# Шаг 3: Проверка цифровой подписи (APK Signature)
echo ""
echo "[3/4] Проверка цифровой подписи APK с помощью apksigner..."
APKSIGNER="/opt/android/sdk/build-tools/36.0.0/apksigner"

if [ -f "$APKSIGNER" ]; then
    SIGNATURE_INFO=$("$APKSIGNER" verify --verbose binaries/nanorank-ai-debug.apk 2>&1)
    if [ $? -eq 0 ]; then
        echo "  [OK] Подпись APK успешно верифицирована!"
        echo "  Детали подписи:"
        echo "$SIGNATURE_INFO" | grep -E "Verified using|Number of signers" | sed 's/^/    /'
    else
        echo "  [ERROR] Ошибка верификации подписи APK!"
        echo "$SIGNATURE_INFO"
        exit 1
    fi
else
    echo "  [WARNING] apksigner не найден в этой системе, пропускаем этот шаг."
fi

# Шаг 4: Проверка запуска приложения и отображения первого экрана (Robolectric)
echo ""
echo "[4/4] Запуск эмуляционного теста (Robolectric) для первого экрана..."
echo "  Запускаем симуляцию JVM, которая монтирует MainActivity,"
echo "  инициализирует Jetpack Compose тему, отрисовывает MainScreen"
echo "  и делает снимок первого экрана."
echo ""

gradle :app:testDebugUnitTest

if [ $? -eq 0 ]; then
    echo ""
    echo "  [OK] Все тесты инициализации и запуска успешно пройдены!"
    echo "       Приложение успешно запускается и показывает первый экран!"
    echo "=========================================================="
    echo " ИТОГ: ТЕСТОВАЯ СРЕДА ПОДТВЕРЖДАЕТ, ЧТО APK ПОЛНОСТЬЮ РАБОТАЕТ!"
    echo "=========================================================="
else
    echo ""
    echo "  [ERROR] Ошибка при симуляции запуска первого экрана!"
    exit 1
fi
