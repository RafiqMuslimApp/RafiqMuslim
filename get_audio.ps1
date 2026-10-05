# Windows: powershell -ExecutionPolicy Bypass -File get_audio.ps1
$ErrorActionPreference="Stop"; Set-Location $PSScriptRoot
$raw="app\src\main\res\raw"; $web="app\src\main\assets\www\audio"
New-Item -ItemType Directory -Force $raw,$web | Out-Null
$f=@{ "adhan_alafasy_fajr.mp3"="https://www.image2url.com/r2/default/audio/1790852554286-6fe18847-eabc-40c5-9904-fd811501df42.mp3";
      "adhan_qatami.mp3"="https://www.image2url.com/r2/default/audio/1790852315392-b5ff32ee-0734-436f-a0f9-c042161950b4.mp3" }
foreach($k in $f.Keys){ Invoke-WebRequest $f[$k] -OutFile "$raw\$k"; if((Get-Item "$raw\$k").Length -lt 20000){throw "$k فاسد"}; Copy-Item "$raw\$k" "$web\$k" -Force }
"تم تنزيل الملفين."
