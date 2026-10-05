#!/usr/bin/env bash
# ينزّل ملفي الأذان مرة واحدة ويضعهما في مكانهما داخل المشروع (يحتاج إنترنت الآن فقط).
set -e; cd "$(dirname "$0")"
RAW=app/src/main/res/raw; WEB=app/src/main/assets/www/audio; mkdir -p "$RAW" "$WEB"
get(){ curl -L --fail --retry 3 -o "$RAW/$1" "$2"; [ "$(wc -c < "$RAW/$1")" -gt 20000 ] || { echo "الملف $1 فاسد"; exit 1; }; cp "$RAW/$1" "$WEB/$1"; }
get adhan_alafasy_fajr.mp3 "https://www.image2url.com/r2/default/audio/1790852554286-6fe18847-eabc-40c5-9904-fd811501df42.mp3"
get adhan_qatami.mp3       "https://www.image2url.com/r2/default/audio/1790852315392-b5ff32ee-0734-436f-a0f9-c042161950b4.mp3"
echo "تم تنزيل الملفين."
