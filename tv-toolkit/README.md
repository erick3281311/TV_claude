# MinimalTV Toolkit
toolkit/tvkit.py      -> orquestador ADB (backup, debloat reversible, launcher, verificacion, restore)
toolkit/packages.json -> listas de paquetes por grupos (editable)
android-launcher/     -> codigo fuente Kotlin del launcher (dock, camaras Hikvision RTSP, fondos)
Camaras: DVR Hikvision -> Config > Red > Puerto RTSP 554 habilitado; URL usada:
  rtsp://USER:PASS@IP:554/Streaming/Channels/{canal}01 (principal) / {canal}02 (sub-stream)
Acceso remoto (fuera de casa): DDNS + port-forward del puerto RTSP del DVR, o VPN (recomendado).

## Compilar el launcher propio sin instalar nada (GitHub Actions)
1. Crea un repo en github.com y sube el contenido de esta carpeta (incluida .github/workflows/build-apk.yml).
2. Pestana Actions > "Build MinimalTV APK" > Run workflow.
3. Descarga el artifact "minimaltv-apk", descomprime y copia minimaltv.apk a toolkit/apks/.
4. python3 toolkit/tvkit.py --ip IP --launcher custom all
