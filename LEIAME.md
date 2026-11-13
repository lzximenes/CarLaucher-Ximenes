# Car Launcher v0.1

Launcher Android para central multimídia: relógio, dock personalizável, temas, foto do carro e controle de mídia.

## Como gerar o APK (sem Android Studio)
1. Crie um repositório vazio no GitHub (ex.: car-launcher).
2. No repositório: Add file > Upload files, e arraste TODO o conteúdo desta pasta (app, gradle.properties, settings.gradle.kts, build.gradle.kts, .gitignore).
3. Pasta .github/workflows: se ela não subir no upload, use Add file > Create new file, nome `.github/workflows/build.yml`, e cole o conteúdo do build.yml.
4. Aba Actions > "Build APK" > aguarde ficar verde (~5 min) > baixe o artefato `car-launcher-apk`.
5. Descompacte, copie app-debug.apk para um pendrive e instale na central.
6. Ao apertar Home na central, escolha "Car Launcher" > Sempre.

Se o build falhar, copie o erro da aba Actions e mande ao Claude.
